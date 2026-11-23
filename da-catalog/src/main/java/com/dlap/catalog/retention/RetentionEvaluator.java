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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * Evaluates the retention of one asset against all four rule sources and writes the result.
 *
 * <p>{@link #evaluate} is read-only: it assembles the asset's rules (stored POLICY and MANUAL
 * rules, LABEL_DEFAULT rules from configuration, PROVIDER rules from
 * {@code technical_metadata}), its read coverage and its evidence, and calls
 * {@link RetentionCalculator}. {@link #reevaluate} is the write path: in one transaction it locks
 * the row (the lock the event consumers take), re-reads the evidence under the lock, evaluates,
 * writes the {@code retention_*} columns, and queues a retention-due event when the status
 * changed. The sweep and the API both use it, so both follow the same rules.
 *
 * <p>An evaluation is skipped when a newer one is already stored (a long sweep must not undo an
 * evaluation made after the sweep took its as-of instant), and a row whose result did not change
 * is rewritten only when its {@code retention_evaluated_at} is older than
 * {@code dlap.catalog.retention.evaluated-at-refresh}.
 */
@Component
public class RetentionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetentionEvaluator.class);

    /** What {@link #reevaluate} did. */
    public enum Result {
        /** The result columns were written. */
        CHANGED,
        /** Nothing needed writing. */
        UNCHANGED,
        /** The asset is DELETED or a newer evaluation is stored. */
        SKIPPED,
        /** The asset does not exist. */
        NOT_FOUND
    }

    /**
     * The outcome of {@link #reevaluate}.
     *
     * @param result     what happened
     * @param previous   the status before the change, or {@code null} when nothing was evaluated
     * @param evaluation the evaluation, or {@code null} when nothing was evaluated
     */
    public record Outcome(Result result, RetentionStatus previous, RetentionEvaluation evaluation) {

        /**
         * Whether the status changed and a retention-due event was queued.
         *
         * @return {@code true} for a transition
         */
        public boolean transitioned() {
            return result == Result.CHANGED && evaluation != null && previous != evaluation.status();
        }
    }

    private final RetentionAssetStore assets;
    private final RetentionRuleStore rules;
    private final RetentionOutbox outbox;
    private final RetentionProperties properties;
    private final RetentionMetrics metrics;

    /**
     * Creates the evaluator.
     *
     * @param assets     access to the asset columns
     * @param rules      the rule store
     * @param outbox     the retention-due outbox
     * @param properties label defaults, hold label and refresh age
     * @param metrics    the retention metrics
     */
    public RetentionEvaluator(RetentionAssetStore assets, RetentionRuleStore rules, RetentionOutbox outbox,
                              RetentionProperties properties, RetentionMetrics metrics) {
        this.assets = assets;
        this.rules = rules;
        this.outbox = outbox;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Evaluates an asset from data already loaded; touches no database.
     *
     * @param row      the asset
     * @param labels   the asset's label names, lower-cased
     * @param stored   the asset's stored rules (POLICY and MANUAL)
     * @param coverage the coverage snapshot
     * @param asOf     the evaluation instant
     * @return the result
     */
    public RetentionEvaluation evaluate(AssetRow row, Set<String> labels, List<RetentionRule> stored,
                                        ReadCoverageSource.View coverage, Instant asOf) {
        Collected collected = collect(row, labels, stored);
        List<RetentionRule> all = collected.rules();
        boolean unreadable = collected.providerRuleUnreadable();
        String holdLabel = properties.getLegalHoldLabel();
        boolean heldByLabel = !holdLabel.isBlank() && labels.contains(holdLabel.toLowerCase(Locale.ROOT));
        RetentionEvidence evidence = new RetentionEvidence(row.cloudCreatedAt(), row.discoveredAt(),
                row.lastAccessedAt(), row.lastModifiedAt(), row.legalHold(), heldByLabel, unreadable);
        ReadCoverage read = coverage.coverageFor(row).orElse(null);
        return RetentionCalculator.evaluate(asOf, evidence, all, read);
    }

    /**
     * Every rule that applies to an asset, for display.
     *
     * @param row    the asset
     * @param labels the asset's label names, lower-cased
     * @param stored the asset's stored rules
     * @return the effective rules, stored ones first
     */
    public List<RetentionRule> effectiveRules(AssetRow row, Set<String> labels, List<RetentionRule> stored) {
        return collect(row, labels, stored).rules();
    }

    private record Collected(List<RetentionRule> rules, boolean providerRuleUnreadable) {
    }

    private Collected collect(AssetRow row, Set<String> labels, List<RetentionRule> stored) {
        List<RetentionRule> all = new ArrayList<>(stored);
        for (RetentionProperties.LabelDefault d : properties.getLabelDefaults()) {
            if (labels.contains(d.getLabel().toLowerCase(Locale.ROOT))) {
                all.add(d.toRule());
            }
        }
        boolean unreadable = addProviderRules(row.metadata(), all);
        return new Collected(all, unreadable);
    }

    /**
     * Adds the rules a crawler recorded in {@code technical_metadata}: a provider retention
     * period ({@code retentionPeriodDays}, for example a GCS bucket retention policy) counts from
     * creation, and an expiration time ({@code expirationTime}, for example a BigQuery table
     * expiration) is a fixed date.
     *
     * @return {@code true} when a setting was present but could not be read
     */
    private boolean addProviderRules(TechnicalKeys keys, List<RetentionRule> into) {
        boolean unreadable = false;
        if (keys.retentionPeriodDays() != null && !keys.retentionPeriodDays().isBlank()) {
            Integer days = parseDays(keys.retentionPeriodDays());
            if (days == null) {
                unreadable = true;
                metrics.invalidProviderRule();
            } else {
                into.add(RetentionRule.ofDays(RuleSource.PROVIDER, RetentionBasis.CREATED, days));
            }
        }
        if (keys.expirationTime() != null && !keys.expirationTime().isBlank()) {
            Instant expiry = parseInstant(keys.expirationTime());
            if (expiry == null) {
                unreadable = true;
                metrics.invalidProviderRule();
            } else {
                into.add(RetentionRule.ofFixedDate(RuleSource.PROVIDER, expiry));
            }
        }
        return unreadable;
    }

    private static Integer parseDays(String text) {
        try {
            long value = Long.parseLong(text.trim());
            return value >= 0 && value <= RetentionProperties.MAX_DAYS ? (int) value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Whether writing is needed for an asset whose stored result and evaluation are given: the
     * result changed, nothing was ever evaluated, or the stored as-of instant is older than the
     * refresh age.
     *
     * @param row  the asset as loaded
     * @param eval the evaluation
     * @param asOf the evaluation instant
     * @return {@code true} when the sweep must take the row lock and write
     */
    public boolean needsWrite(AssetRow row, RetentionEvaluation eval, Instant asOf) {
        RetentionAssetStore.StoredOutcome current = row.current();
        if (current.evaluatedAt() == null || !current.sameResultAs(eval)) {
            return true;
        }
        if (current.evaluatedAt().isAfter(asOf)) {
            return false;
        }
        return properties.getEvaluatedAtRefresh().isZero()
                || !current.evaluatedAt().plus(properties.getEvaluatedAtRefresh()).isAfter(asOf);
    }

    /**
     * Evaluates one asset under its row lock and writes the result. Joins the caller's
     * transaction or starts one.
     *
     * @param assetId          the asset
     * @param asOf             the evaluation instant
     * @param coverage         the coverage snapshot
     * @param previousOverride the status to report as "previous" when the caller changed the
     *                         stored status in the same transaction before calling (a legal hold
     *                         placed or released), or {@code null} to use the stored one
     * @return what happened
     */
    @Transactional
    public Outcome reevaluate(UUID assetId, Instant asOf, ReadCoverageSource.View coverage,
                              RetentionStatus previousOverride) {
        Optional<AssetRow> locked = assets.lockById(assetId);
        if (locked.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null, null);
        }
        AssetRow row = locked.get();
        if ("DELETED".equals(row.status())) {
            return new Outcome(Result.SKIPPED, null, null);
        }
        RetentionAssetStore.StoredOutcome current = row.current();
        if (current.evaluatedAt() != null && current.evaluatedAt().isAfter(asOf) && previousOverride == null) {
            return new Outcome(Result.SKIPPED, null, null);
        }
        Set<String> labels = assets.labelNames(List.of(assetId)).getOrDefault(assetId, Set.of());
        List<RetentionRule> stored = rules.findByAssets(List.of(assetId)).getOrDefault(assetId, List.of());
        RetentionEvaluation eval = evaluate(row, labels, stored, coverage, asOf);
        RetentionStatus previous = previousOverride != null ? previousOverride : current.status();
        boolean statusChanged = previous != eval.status();
        if (!statusChanged && !needsWrite(row, eval, asOf)) {
            return new Outcome(Result.UNCHANGED, previous, eval);
        }
        assets.writeOutcome(assetId, eval, asOf);
        if (statusChanged) {
            outbox.add(RetentionDueEvent.of(assetId, row.assetUri(), row.cloudProvider(), row.serviceType(),
                    previous, eval, row.legalHold(), asOf));
            metrics.transition(previous, eval.status());
            log.info("Retention status of asset {} changed {} -> {} ({})", assetId, previous, eval.status(),
                    eval.reason());
        }
        return new Outcome(Result.CHANGED, previous, eval);
    }
}
