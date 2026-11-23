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

package com.dlap.catalog.rekey;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dlap.catalog.rekey.RekeyOutcome.Status;
import com.dlap.catalog.rekey.RekeyPlan.MergeGroup;
import com.dlap.catalog.rekey.RekeyPlan.StoredAsset;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one-shot asset_uri re-key (ID-03): moves every catalog row to the canonical URI of
 * {@code AssetIdNormalizer} and merges rows that are one asset. Three commands:
 * <ul>
 *   <li>{@link #dryRun()}: plans in a read-only snapshot and reports collisions, in-place
 *       re-keys, rows that do not normalize and blockers; changes nothing and takes no lock;</li>
 *   <li>{@link #apply()}: locks {@code dlap_assets} against writers, plans again in the same
 *       transaction, refuses on a blocker, then re-keys, merges and records the reversible map,
 *       all or nothing. When every row is already canonical it changes nothing and records no
 *       run, so a second apply is a no-op;</li>
 *   <li>{@link #revert(UUID)}: undoes the latest applied run from its undo log.</li>
 * </ul>
 * The runbook (docs/guides/upgrade.md, "Re-key asset URIs") stops the writers and drains the
 * topics first; the lock is the backstop for a writer that was missed.
 */
@Service
public class AssetUriRekeyService {

    private static final Logger log = LoggerFactory.getLogger(AssetUriRekeyService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Blocker code: retention-due events not yet published name an old URI. */
    static final String BLOCKER_OUTBOX = "UNPUBLISHED_RETENTION_DUE";

    private static final String LOCK_SQL = "LOCK TABLE dlap_common.dlap_assets IN SHARE ROW EXCLUSIVE MODE";
    private static final String OUTBOX_SQL =
            "SELECT DISTINCT asset_uri FROM dlap_catalog.retention_outbox WHERE published_at IS NULL";
    private static final String RUN_INSERT_SQL = "INSERT INTO dlap_catalog.asset_uri_rekey_run (run_id, spec_version, "
            + "status, applied_at, applied_by, operator, rows_rekeyed, merge_groups, rows_merged, undo_entries, report) "
            + "VALUES (?, ?, 'APPLIED', ?, current_user, ?, 0, 0, 0, 0, '{}'::jsonb)";
    private static final String RUN_FINISH_SQL = "UPDATE dlap_catalog.asset_uri_rekey_run SET rows_rekeyed = ?, "
            + "merge_groups = ?, rows_merged = ?, undo_entries = ?, report = ?::jsonb WHERE run_id = ?";
    private static final String LATEST_APPLIED_SQL = "SELECT run_id FROM dlap_catalog.asset_uri_rekey_run "
            + "WHERE status = 'APPLIED' ORDER BY applied_at DESC, run_id DESC LIMIT 1";
    private static final String RUN_STATUS_SQL = "SELECT status FROM dlap_catalog.asset_uri_rekey_run WHERE run_id = ?";
    private static final String RUN_REVERTED_SQL = "UPDATE dlap_catalog.asset_uri_rekey_run SET status = 'REVERTED', "
            + "reverted_at = ?, reverted_by = current_user WHERE run_id = ?";
    private static final String MAP_COUNT_SQL = "SELECT count(*) FROM dlap_catalog.asset_uri_rekey_map WHERE run_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final AssetUriRekeyPlanner planner;
    private final AssetUriRekeyExecutor executor;
    private final AssetUriRekeyReverter reverter;
    private final RekeyProperties properties;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    /**
     * Creates the service.
     *
     * @param jdbcTemplate       JDBC access
     * @param planner            the planner
     * @param executor           the executor
     * @param reverter           the reverter
     * @param properties         lock timeout and operator name
     * @param clock              DLAP's clock (report and run times; operational, not business time)
     * @param transactionManager the transaction manager
     */
    public AssetUriRekeyService(JdbcTemplate jdbcTemplate, AssetUriRekeyPlanner planner,
                                AssetUriRekeyExecutor executor, AssetUriRekeyReverter reverter,
                                RekeyProperties properties, Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.planner = planner;
        this.executor = executor;
        this.reverter = reverter;
        this.properties = properties;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTx.setReadOnly(true);
    }

    /**
     * Plans and reports without changing anything.
     *
     * @return {@link Status#DONE}, or {@link Status#REFUSED} when an apply would be refused now
     */
    public RekeyOutcome dryRun() {
        return readTx.execute(status -> {
            RekeyPlan plan = planner.plan();
            List<Map<String, Object>> blockers = blockers(plan);
            Map<String, Object> report = RekeyReport.of(RekeyCommand.DRY_RUN, clock.instant(), null, plan,
                    this::plannedReferences, blockers);
            String message = String.format("dry run: %d row(s) scanned, %d re-keyed in place, %d collision group(s) "
                            + "merging %d row(s), %d row(s) do not normalize, %d blocker(s)", plan.scanned(),
                    plan.changes().size(), plan.groups().size(), plan.mergedRows(), plan.rejected().size(),
                    blockers.size());
            return new RekeyOutcome(blockers.isEmpty() ? Status.DONE : Status.REFUSED, null, message, report);
        });
    }

    /**
     * Re-keys and merges in one transaction.
     *
     * @return {@link Status#DONE} with the run id, {@link Status#NOTHING_TO_DO}, or
     *         {@link Status#REFUSED} when a blocker exists
     */
    public RekeyOutcome apply() {
        return writeTx.execute(status -> {
            lock();
            RekeyPlan plan = planner.plan();
            List<Map<String, Object>> blockers = blockers(plan);
            Instant now = clock.instant();
            if (!blockers.isEmpty()) {
                Map<String, Object> report = RekeyReport.of(RekeyCommand.APPLY, now, null, plan,
                        this::plannedReferences, blockers);
                return new RekeyOutcome(Status.REFUSED, null, "apply refused: " + blockers.size()
                        + " blocker(s); see the report", report);
            }
            if (plan.isEmpty()) {
                Map<String, Object> report = RekeyReport.of(RekeyCommand.APPLY, now, null, plan, g -> Map.of(),
                        blockers);
                return new RekeyOutcome(Status.NOTHING_TO_DO, null, "apply: every row is canonical ("
                        + plan.scanned() + " scanned, " + plan.rejected().size() + " do not normalize); no run recorded",
                        report);
            }
            UUID runId = UUID.randomUUID();
            jdbcTemplate.update(RUN_INSERT_SQL, runId, AssetIdNormalizer.SPEC_VERSION, now.atOffset(ZoneOffset.UTC),
                    properties.getOperator());
            AssetUriRekeyExecutor.Result result = executor.execute(plan, runId);
            Map<String, Object> report = RekeyReport.of(RekeyCommand.APPLY, now, runId, plan,
                    g -> result.perGroup().getOrDefault(g.survivor().assetId(), Map.of()), blockers);
            Map<String, Object> applied = new LinkedHashMap<>(result.totals());
            applied.put("undoEntries", result.undoEntries());
            report.put("applied", applied);
            jdbcTemplate.update(RUN_FINISH_SQL, plan.changes().size(), plan.groups().size(), plan.mergedRows(),
                    result.undoEntries(), toJson(report), runId);
            String message = String.format("apply: run %s re-keyed %d row(s) in place and merged %d row(s) into %d "
                    + "survivor(s); %d undo entries", runId, plan.changes().size(), plan.mergedRows(),
                    plan.groups().size(), result.undoEntries());
            log.info(message);
            return new RekeyOutcome(Status.DONE, runId, message, report);
        });
    }

    /**
     * Undoes a run. Only the latest applied run can be reverted, and only while no row it changed
     * has changed identity since; otherwise restore the backup taken before the apply.
     *
     * @param runId the run, or {@code null} for the latest applied run
     * @return {@link Status#DONE}, or {@link Status#REFUSED} with the reason
     */
    public RekeyOutcome revert(UUID runId) {
        try {
            return writeTx.execute(status -> {
                lock();
                List<UUID> latest = jdbcTemplate.queryForList(LATEST_APPLIED_SQL, UUID.class);
                if (latest.isEmpty()) {
                    throw new RekeyRefusedException("no applied run to revert");
                }
                UUID target = runId == null ? latest.get(0) : runId;
                List<String> state = jdbcTemplate.queryForList(RUN_STATUS_SQL, String.class, target);
                if (state.isEmpty()) {
                    throw new RekeyRefusedException("run " + target + " does not exist");
                }
                if (!"APPLIED".equals(state.get(0))) {
                    throw new RekeyRefusedException("run " + target + " is " + state.get(0));
                }
                if (!target.equals(latest.get(0))) {
                    throw new RekeyRefusedException("run " + target + " is not the latest applied run ("
                            + latest.get(0) + "); revert that one first");
                }
                List<String> problems = reverter.preconditions(target);
                if (!problems.isEmpty()) {
                    throw new RekeyRefusedException(String.join("; ", problems)
                            + "; restore the backup taken before the apply instead");
                }
                int entries = reverter.revert(target);
                Integer assets = jdbcTemplate.queryForObject(MAP_COUNT_SQL, Integer.class, target);
                Instant now = clock.instant();
                jdbcTemplate.update(RUN_REVERTED_SQL, now.atOffset(ZoneOffset.UTC), target);
                String message = "revert: run " + target + " undone, " + entries + " undo entries replayed";
                log.info(message);
                return new RekeyOutcome(Status.DONE, target, message,
                        RekeyReport.revert(now, target, entries, assets == null ? 0 : assets));
            });
        } catch (RekeyRefusedException e) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("command", RekeyCommand.REVERT.text());
            report.put("refused", e.getMessage());
            return new RekeyOutcome(Status.REFUSED, runId, "revert refused: " + e.getMessage(), report);
        }
    }

    private void lock() {
        jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
                properties.getLockTimeout().toMillis() + "ms");
        jdbcTemplate.execute(LOCK_SQL);
    }

    private List<Map<String, Object>> blockers(RekeyPlan plan) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        Set<String> old = new HashSet<>(plan.oldUris());
        if (!old.isEmpty()) {
            int pending = 0;
            for (String uri : jdbcTemplate.queryForList(OUTBOX_SQL, String.class)) {
                if (old.contains(uri)) {
                    pending++;
                }
            }
            if (pending > 0) {
                blockers.add(RekeyReport.blocker(BLOCKER_OUTBOX, pending, "retention-due events for an old URI "
                        + "are not published yet: start da-catalog with the topics reachable until "
                        + "dlap_catalog.retention_outbox has no unpublished row, then stop it and run again"));
            }
        }
        return blockers;
    }

    /**
     * Dry-run reference counts of a group: the rows of the merged members that will move to the
     * survivor, and those that collide with a row the survivor already has.
     */
    private Map<String, Integer> plannedReferences(MergeGroup group) {
        UUID s = group.survivor().assetId();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StoredAsset m : group.merged()) {
            UUID l = m.assetId();
            add(counts, "labels", count("SELECT count(*) FROM dlap_common.dlap_asset_label_assignments WHERE asset_id = ?", l));
            add(counts, "labelsAlreadyOnSurvivor", count("SELECT count(*) FROM dlap_common.dlap_asset_label_assignments x "
                    + "WHERE x.asset_id = ? AND EXISTS (SELECT 1 FROM dlap_common.dlap_asset_label_assignments y "
                    + "WHERE y.asset_id = ? AND y.label_id = x.label_id)", l, s));
            add(counts, "cloudResources", count("SELECT count(*) FROM dlap_common.dlap_cloud_resources WHERE asset_id = ?", l));
            add(counts, "lineageEdges", count("SELECT count(*) FROM dlap_catalog.dlap_asset_lineage "
                    + "WHERE upstream_asset_id = ? OR downstream_asset_id = ?", l, l));
            add(counts, "usageDays", count("SELECT count(*) FROM dlap_catalog.asset_usage_daily WHERE asset_id = ?", l));
            add(counts, "usageDaysAlsoOnSurvivor", count("SELECT count(*) FROM dlap_catalog.asset_usage_daily x "
                    + "WHERE x.asset_id = ? AND EXISTS (SELECT 1 FROM dlap_catalog.asset_usage_daily y "
                    + "WHERE y.asset_id = ? AND y.day = x.day)", l, s));
            add(counts, "processedEvents", count("SELECT count(*) FROM dlap_catalog.processed_event WHERE asset_id = ?", l));
            add(counts, "retentionRules", count("SELECT count(*) FROM dlap_catalog.retention_rule WHERE asset_id = ?", l));
        }
        return counts;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static void add(Map<String, Integer> counts, String key, int n) {
        counts.merge(key, n, Integer::sum);
    }

    private static String toJson(Map<String, Object> report) {
        try {
            return JSON.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the re-key report", e);
        }
    }
}
