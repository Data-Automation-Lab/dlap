/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.catalog.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;

/**
 * One pass over the catalog that evaluates the retention of every asset that is not
 * {@code DELETED} (docs/specs/asset-descriptor.md "Retention > Evaluation").
 *
 * <p><b>Paged.</b> Assets are read in keyset pages ({@code asset_id} order,
 * {@code dlap.catalog.retention.page-size} rows), with the labels and stored rules of a page
 * loaded in one query each. Memory is bounded by the page, and an asset created or changed during
 * the pass is neither skipped nor read twice.
 *
 * <p><b>Idempotent.</b> The pass is a function of the catalog state and the as-of instant taken
 * from the injected {@link Clock} when it starts. An asset whose result did not change, and whose
 * stored evaluation is younger than the refresh age, is not written, and no row lock is taken for
 * it; running the pass twice with the same clock writes nothing the second time and queues no
 * second event. An asset whose result changed is re-read and re-evaluated under its row lock in
 * its own short transaction ({@link RetentionEvaluator#reevaluate}), so the sweep holds one lock
 * at a time and cannot deadlock with the event consumers.
 *
 * <p>A failure on one asset is counted and logged and the pass goes on; after
 * {@value #ABORT_AFTER_CONSECUTIVE_ERRORS} failures in a row (a database that is down) the pass
 * stops and the next run starts again.
 */
@Component
public class RetentionSweep {

    /** Consecutive per-asset failures after which a pass gives up. */
    static final int ABORT_AFTER_CONSECUTIVE_ERRORS = 25;

    private static final UUID FIRST = new UUID(0L, 0L);
    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /**
     * What one pass did.
     *
     * @param asOf        the instant the pass evaluated at
     * @param scanned     assets read
     * @param changed     assets whose result columns were written
     * @param unchanged   assets that needed no write
     * @param transitions status changes (retention-due events queued)
     * @param errors      assets that failed
     * @param pages       pages read
     * @param aborted     whether the pass stopped early on repeated failures
     */
    public record Result(Instant asOf, int scanned, int changed, int unchanged, int transitions, int errors,
                         int pages, boolean aborted) {
    }

    private final RetentionAssetStore assets;
    private final RetentionRuleStore rules;
    private final RetentionEvaluator evaluator;
    private final ReadCoverageSource coverage;
    private final RetentionProperties properties;
    private final RetentionMetrics metrics;
    private final Clock clock;

    /**
     * Creates the sweep.
     *
     * @param assets     access to the asset columns
     * @param rules      the rule store
     * @param evaluator  the evaluator
     * @param coverage   the read signal
     * @param properties page size and refresh age
     * @param metrics    the retention metrics
     * @param clock      the time source; the pass evaluates at its instant when it starts
     */
    public RetentionSweep(RetentionAssetStore assets, RetentionRuleStore rules, RetentionEvaluator evaluator,
                          ReadCoverageSource coverage, RetentionProperties properties, RetentionMetrics metrics,
                          Clock clock) {
        this.assets = assets;
        this.rules = rules;
        this.evaluator = evaluator;
        this.coverage = coverage;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Runs one pass.
     *
     * @return what it did
     */
    public Result run() {
        Instant asOf = clock.instant();
        ReadCoverageSource.View view = coverage.snapshot();
        int pageSize = properties.getPageSize();
        UUID after = FIRST;
        int scanned = 0;
        int changed = 0;
        int unchanged = 0;
        int transitions = 0;
        int errors = 0;
        int pages = 0;
        int consecutiveErrors = 0;
        boolean aborted = false;
        while (!aborted) {
            List<AssetRow> page = assets.page(after, pageSize);
            if (page.isEmpty()) {
                break;
            }
            pages++;
            List<UUID> ids = page.stream().map(AssetRow::assetId).toList();
            Map<UUID, Set<String>> labels = assets.labelNames(ids);
            Map<UUID, List<RetentionRule>> stored = rules.findByAssets(ids);
            for (AssetRow row : page) {
                scanned++;
                try {
                    RetentionEvaluation eval = evaluator.evaluate(row, labels.getOrDefault(row.assetId(), Set.of()),
                            stored.getOrDefault(row.assetId(), List.of()), view, asOf);
                    boolean statusChanged = row.current().status() != eval.status();
                    if (!statusChanged && !evaluator.needsWrite(row, eval, asOf)) {
                        unchanged++;
                        metrics.sweepAsset("unchanged");
                        consecutiveErrors = 0;
                        continue;
                    }
                    RetentionEvaluator.Outcome outcome = evaluator.reevaluate(row.assetId(), asOf, view, null);
                    consecutiveErrors = 0;
                    if (outcome.result() == RetentionEvaluator.Result.CHANGED) {
                        changed++;
                        metrics.sweepAsset("changed");
                        if (outcome.transitioned()) {
                            transitions++;
                        }
                    } else {
                        unchanged++;
                        metrics.sweepAsset("unchanged");
                    }
                } catch (RuntimeException e) {
                    errors++;
                    consecutiveErrors++;
                    metrics.sweepAsset("error");
                    log.warn("Retention evaluation failed for asset {}: {}", row.assetId(), e.toString());
                    if (consecutiveErrors >= ABORT_AFTER_CONSECUTIVE_ERRORS) {
                        aborted = true;
                        break;
                    }
                }
            }
            after = page.get(page.size() - 1).assetId();
            if (page.size() < pageSize) {
                break;
            }
        }
        metrics.sweepRun(aborted ? "aborted" : "completed");
        Result result = new Result(asOf, scanned, changed, unchanged, transitions, errors, pages, aborted);
        log.info("Retention sweep {}: scanned={} changed={} unchanged={} transitions={} errors={} pages={}",
                aborted ? "aborted" : "finished", scanned, changed, unchanged, transitions, errors, pages);
        return result;
    }
}
