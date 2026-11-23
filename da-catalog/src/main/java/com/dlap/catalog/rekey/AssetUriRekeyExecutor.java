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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.rekey.RekeyPlan.Change;
import com.dlap.catalog.rekey.RekeyPlan.MergeGroup;
import com.dlap.catalog.rekey.RekeyPlan.StoredAsset;
import com.dlap.catalog.rekey.RekeyPlan.Target;

/**
 * Executes a {@link RekeyPlan} inside the caller's transaction, recording every change in the undo
 * log and the map. For each collision group, and for each merged row oldest first:
 * <ol>
 *   <li>the survivor absorbs the merged row's facts (blank descriptive fields filled, JSON
 *       metadata merged with the survivor's keys winning, first-seen times the least, last-seen
 *       times the greatest, the size and the read coverage of the newer observation, the status
 *       of the later lifecycle event, a legal hold kept);</li>
 *   <li>label assignments, cloud resources, lineage edges, daily usage, processed events and
 *       retention rules move to the survivor; a duplicate (the survivor already has that label,
 *       that edge, that event) is dropped, same-day usage counts are added, and of two rules of
 *       one policy the later-issued one stays;</li>
 *   <li>the merged row is deleted.</li>
 * </ol>
 * Then the survivor, and every row alone under its URI (in batches), takes the canonical URI,
 * service type and provider; {@code native_asset_id} is set to the first native form seen when it was empty.
 * Merged rows are deleted before any survivor takes its new URI, so the unique index on
 * {@code asset_uri} never sees two rows with one URI. {@code last_modified_ts} is not merged: the
 * asset table's trigger sets it to the time of every update.
 *
 * <p>Every statement is a constant; asset ids travel as bind parameters.
 */
@Component
public class AssetUriRekeyExecutor {

    /** {@code dlap_assets.native_asset_id} is {@code VARCHAR(500)}; a longer old URI stays only in the map. */
    static final int NATIVE_ID_MAX = 500;

    /** Rows alone under their URI re-keyed per batch of statements, by default. */
    static final int BATCH_SIZE = 500;

    private static final String MERGE_ASSET_SQL = "UPDATE dlap_common.dlap_assets s SET "
            + "asset_name = COALESCE(s.asset_name, l.asset_name), "
            + "description = COALESCE(s.description, l.description), "
            + "owner_info = COALESCE(s.owner_info, l.owner_info), "
            + "region = COALESCE(s.region, l.region), "
            + "asset_type = COALESCE(s.asset_type, l.asset_type), "
            + "cloud_connection_id = COALESCE(s.cloud_connection_id, l.cloud_connection_id), "
            + "created_by_user_id = COALESCE(s.created_by_user_id, l.created_by_user_id), "
            + "discovery_source = COALESCE(s.discovery_source, l.discovery_source), "
            + jsonMerge("technical_metadata") + ", " + jsonMerge("business_metadata") + ", "
            + jsonMerge("details_json") + ", " + jsonMerge("dlap_usage_metrics") + ", "
            + jsonMerge("dlap_policy_status") + ", " + jsonMerge("dlap_lineage_info") + ", "
            + "created_ts = LEAST(s.created_ts, l.created_ts), "
            + "dlap_discovered_ts = LEAST(s.dlap_discovered_ts, l.dlap_discovered_ts), "
            + "discovered_at = LEAST(s.discovered_at, l.discovered_at), "
            + "dlap_last_scanned_ts = GREATEST(s.dlap_last_scanned_ts, l.dlap_last_scanned_ts), "
            + "last_scanned_at = GREATEST(s.last_scanned_at, l.last_scanned_at), "
            + "cloud_created_source = CASE WHEN " + earlier("cloud_created_at")
            + " THEN l.cloud_created_source ELSE s.cloud_created_source END, "
            + "cloud_created_at = LEAST(s.cloud_created_at, l.cloud_created_at), "
            + "size_bytes = CASE WHEN " + newerSize() + " THEN l.size_bytes ELSE s.size_bytes END, "
            + "size_observed_at = CASE WHEN " + newerSize() + " THEN l.size_observed_at ELSE s.size_observed_at END, "
            + "last_access_source = CASE WHEN " + later("last_accessed_at")
            + " THEN l.last_access_source ELSE s.last_access_source END, "
            + "last_accessed_at = GREATEST(s.last_accessed_at, l.last_accessed_at), "
            + "last_modified_at = GREATEST(s.last_modified_at, l.last_modified_at), "
            + "last_permission_change_at = GREATEST(s.last_permission_change_at, l.last_permission_change_at), "
            + "recreated_at = GREATEST(s.recreated_at, l.recreated_at), "
            + "status = CASE WHEN " + later("lifecycle_event_time") + " THEN l.status ELSE s.status END, "
            + "lifecycle_event_time = GREATEST(s.lifecycle_event_time, l.lifecycle_event_time), "
            + "legal_hold = (s.legal_hold OR l.legal_hold), "
            + "retention_status = CASE WHEN s.legal_hold OR l.legal_hold THEN 'ON_HOLD' ELSE s.retention_status END, "
            + coverage("read_coverage") + ", " + coverage("read_coverage_reasons") + ", "
            + coverage("read_coverage_since") + ", " + coverage("read_coverage_observed_at")
            + " FROM dlap_common.dlap_assets l WHERE s.asset_id = ? AND l.asset_id = ?";

    private static final String IDENTITY_SQL = "UPDATE dlap_common.dlap_assets SET asset_uri = ?, service_type = ?, "
            + "cloud_provider = ?, native_asset_id = COALESCE(native_asset_id, ?) WHERE asset_id = ?";

    private static final String HOLD_SQL = "SELECT legal_hold FROM dlap_common.dlap_assets WHERE asset_id = ?";

    private static final String MAP_SQL = "INSERT INTO dlap_catalog.asset_uri_rekey_map (run_id, asset_id, "
            + "survivor_asset_id, action, old_asset_uri, new_asset_uri, old_service_type, new_service_type, "
            + "old_cloud_provider, new_cloud_provider) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String LINEAGE_SQL = "SELECT lineage_id, upstream_asset_id, downstream_asset_id, "
            + "relationship_type FROM dlap_catalog.dlap_asset_lineage WHERE upstream_asset_id = ? "
            + "OR downstream_asset_id = ? ORDER BY lineage_id";

    private static final String LINEAGE_DUPLICATE_SQL = "SELECT count(*) FROM dlap_catalog.dlap_asset_lineage "
            + "WHERE upstream_asset_id = ? AND downstream_asset_id = ? "
            + "AND relationship_type IS NOT DISTINCT FROM ?::varchar AND lineage_id <> ?";

    // Label assignments. Parameters of the undo statement: survivor (duplicate test), survivor
    // (after-key), merged row.
    private static final String LABEL_ON_SURVIVOR = "EXISTS (SELECT 1 FROM dlap_common.dlap_asset_label_assignments y "
            + "WHERE y.asset_id = ? AND y.label_id = x.label_id)";
    private static final String UNDO_LABELS = UndoLog.HEAD + "x.asset_id, x.label_id), 'dlap_asset_label_assignments', "
            + "to_jsonb(x), CASE WHEN " + LABEL_ON_SURVIVOR + " THEN NULL ELSE jsonb_build_object('asset_id', ?::uuid, "
            + "'label_id', x.label_id) END FROM dlap_common.dlap_asset_label_assignments x WHERE x.asset_id = ?";
    private static final String DROP_LABELS = "DELETE FROM dlap_common.dlap_asset_label_assignments x "
            + "WHERE x.asset_id = ? AND " + LABEL_ON_SURVIVOR;
    private static final String MOVE_LABELS =
            "UPDATE dlap_common.dlap_asset_label_assignments SET asset_id = ? WHERE asset_id = ?";

    // Cloud resources.
    private static final String UNDO_CLOUD_RESOURCES = UndoLog.HEAD + "x.cloud_resource_id), 'dlap_cloud_resources', "
            + "to_jsonb(x), jsonb_build_object('cloud_resource_id', x.cloud_resource_id) "
            + "FROM dlap_common.dlap_cloud_resources x WHERE x.asset_id = ?";
    private static final String MOVE_CLOUD_RESOURCES =
            "UPDATE dlap_common.dlap_cloud_resources SET asset_id = ? WHERE asset_id = ?";

    // Lineage, one edge at a time.
    private static final String UNDO_EDGE_DROPPED = UndoLog.HEAD + "x.lineage_id), 'dlap_asset_lineage', to_jsonb(x), "
            + "NULL::jsonb FROM dlap_catalog.dlap_asset_lineage x WHERE x.lineage_id = ?";
    private static final String UNDO_EDGE_MOVED = UndoLog.HEAD + "x.lineage_id), 'dlap_asset_lineage', to_jsonb(x), "
            + "jsonb_build_object('lineage_id', x.lineage_id) FROM dlap_catalog.dlap_asset_lineage x "
            + "WHERE x.lineage_id = ?";
    private static final String DROP_EDGE = "DELETE FROM dlap_catalog.dlap_asset_lineage WHERE lineage_id = ?";
    private static final String MOVE_EDGE = "UPDATE dlap_catalog.dlap_asset_lineage SET upstream_asset_id = ?, "
            + "downstream_asset_id = ? WHERE lineage_id = ?";

    // Daily usage. The survivor's days that the merged row also has are updated in place
    // (parameters: survivor, merged row); the merged row's days are deleted when the survivor has
    // the day and moved otherwise (parameters: survivor, survivor, merged row).
    private static final String SURVIVOR_HAS_DAY = "EXISTS (SELECT 1 FROM dlap_catalog.asset_usage_daily y "
            + "WHERE y.asset_id = ? AND y.day = x.day)";
    private static final String UNDO_USAGE_SURVIVOR = UndoLog.HEAD + "x.asset_id, x.day), 'asset_usage_daily', "
            + "to_jsonb(x), jsonb_build_object('asset_id', x.asset_id, 'day', x.day) "
            + "FROM dlap_catalog.asset_usage_daily x WHERE x.asset_id = ? AND " + SURVIVOR_HAS_DAY;
    private static final String UNDO_USAGE_MERGED = UndoLog.HEAD + "x.asset_id, x.day), 'asset_usage_daily', "
            + "to_jsonb(x), CASE WHEN " + SURVIVOR_HAS_DAY + " THEN NULL ELSE jsonb_build_object('asset_id', ?::uuid, "
            + "'day', x.day) END FROM dlap_catalog.asset_usage_daily x WHERE x.asset_id = ?";
    private static final String SUM_USAGE = "UPDATE dlap_catalog.asset_usage_daily s SET reads = s.reads + l.reads, "
            + "writes = s.writes + l.writes, first_event_at = LEAST(s.first_event_at, l.first_event_at), "
            + "last_event_at = GREATEST(s.last_event_at, l.last_event_at) FROM dlap_catalog.asset_usage_daily l "
            + "WHERE s.asset_id = ? AND l.asset_id = ? AND l.day = s.day";
    private static final String DROP_SUMMED_USAGE = "DELETE FROM dlap_catalog.asset_usage_daily x "
            + "WHERE x.asset_id = ? AND " + SURVIVOR_HAS_DAY;
    private static final String MOVE_USAGE =
            "UPDATE dlap_catalog.asset_usage_daily SET asset_id = ? WHERE asset_id = ?";

    // Processed events (parameters of the undo statement: survivor, survivor, merged row).
    private static final String EVENT_ON_SURVIVOR = "EXISTS (SELECT 1 FROM dlap_catalog.processed_event y "
            + "WHERE y.asset_id = ? AND y.consumer = x.consumer AND y.event_id = x.event_id)";
    private static final String UNDO_EVENTS = UndoLog.HEAD + "x.consumer, x.event_id, x.asset_id), 'processed_event', "
            + "to_jsonb(x), CASE WHEN " + EVENT_ON_SURVIVOR + " THEN NULL ELSE jsonb_build_object('consumer', "
            + "x.consumer, 'event_id', x.event_id, 'asset_id', ?::uuid) END FROM dlap_catalog.processed_event x "
            + "WHERE x.asset_id = ?";
    private static final String DROP_EVENTS = "DELETE FROM dlap_catalog.processed_event x WHERE x.asset_id = ? AND "
            + EVENT_ON_SURVIVOR;
    private static final String MOVE_EVENTS = "UPDATE dlap_catalog.processed_event SET asset_id = ? WHERE asset_id = ?";

    // Retention rules. A survivor POLICY rule loses to a merged row's rule of the same policy issued
    // later (parameters: survivor, merged row). A merged row's POLICY rule loses to the survivor's
    // rule of the same policy issued at the same time or later (parameter: survivor).
    private static final String SURVIVOR_RULE_LOSES = "x.asset_id = ? AND x.source = 'POLICY' AND EXISTS (SELECT 1 "
            + "FROM dlap_catalog.retention_rule y WHERE y.asset_id = ? AND y.source = 'POLICY' "
            + "AND y.policy_id = x.policy_id AND y.issued_at > x.issued_at)";
    private static final String MERGED_RULE_LOSES = "EXISTS (SELECT 1 FROM dlap_catalog.retention_rule y "
            + "WHERE y.asset_id = ? AND y.source = 'POLICY' AND x.source = 'POLICY' AND y.policy_id = x.policy_id "
            + "AND NOT (x.issued_at > y.issued_at))";
    private static final String UNDO_SURVIVOR_RULES = UndoLog.HEAD + "x.rule_id), 'retention_rule', to_jsonb(x), "
            + "NULL::jsonb FROM dlap_catalog.retention_rule x WHERE " + SURVIVOR_RULE_LOSES;
    private static final String UNDO_MERGED_RULES = UndoLog.HEAD + "x.rule_id), 'retention_rule', to_jsonb(x), "
            + "CASE WHEN " + MERGED_RULE_LOSES + " THEN NULL ELSE jsonb_build_object('rule_id', x.rule_id) END "
            + "FROM dlap_catalog.retention_rule x WHERE x.asset_id = ?";
    private static final String DROP_SURVIVOR_RULES = "DELETE FROM dlap_catalog.retention_rule x WHERE "
            + SURVIVOR_RULE_LOSES;
    private static final String DROP_MERGED_RULES = "DELETE FROM dlap_catalog.retention_rule x WHERE x.asset_id = ? "
            + "AND " + MERGED_RULE_LOSES;
    private static final String MOVE_RULES = "UPDATE dlap_catalog.retention_rule SET asset_id = ? WHERE asset_id = ?";

    private static final String DROP_INDEX_ENTRY = "DELETE FROM dlap_catalog.asset_uri_canonical WHERE asset_id = ?";
    private static final String DELETE_ASSET = "DELETE FROM dlap_common.dlap_assets WHERE asset_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final CanonicalUriIndex index;
    private int batchSize = BATCH_SIZE;

    /**
     * Creates the executor.
     *
     * @param jdbcTemplate JDBC access
     * @param index        the normalized-URI index, rewritten for every survivor
     */
    public AssetUriRekeyExecutor(JdbcTemplate jdbcTemplate, CanonicalUriIndex index) {
        this.jdbcTemplate = jdbcTemplate;
        this.index = index;
    }

    /**
     * Executes the plan. The caller holds the table lock and the transaction, and has inserted the
     * run row {@code runId}.
     *
     * @param plan  the plan, computed in the same transaction
     * @param runId the run
     * @return what was done: per-group counts and totals
     */
    public Result execute(RekeyPlan plan, UUID runId) {
        UndoLog undo = new UndoLog(jdbcTemplate, runId);
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<UUID, Map<String, Integer>> perGroup = new LinkedHashMap<>();
        for (MergeGroup group : plan.groups()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            UUID s = group.survivor().assetId();
            for (StoredAsset loser : group.merged()) {
                mergeInto(undo, s, loser.assetId(), counts);
            }
            applyIdentity(undo, group.survivor(), group.target(), nativeCandidate(group), runId, "SURVIVOR", s);
            for (StoredAsset loser : group.merged()) {
                map(runId, loser, group.target(), "MERGED", s);
            }
            perGroup.put(s, counts);
            counts.forEach((k, v) -> totals.merge(k, v, Integer::sum));
        }
        List<Change> changes = plan.changes();
        for (int from = 0; from < changes.size(); from += batchSize) {
            rekeyInPlace(undo, changes.subList(from, Math.min(changes.size(), from + batchSize)), runId);
        }
        return new Result(perGroup, totals, undo.size());
    }

    /**
     * The rows alone under their URI, one batch at a time: one undo statement for the batch, then
     * batched identity updates and map rows. Their normalized-URI index entries are left as they
     * are: an entry whose recorded {@code asset_uri} differs from the row's is stale, and the
     * catalog recomputes it before use and in its start-up build ({@code CanonicalUriIndex});
     * events find these rows by exact URI anyway.
     */
    private void rekeyInPlace(UndoLog undo, List<Change> batch, UUID runId) {
        StringBuilder ids = new StringBuilder("{");
        List<Object[]> identities = new ArrayList<>(batch.size());
        List<Object[]> maps = new ArrayList<>(batch.size());
        for (Change change : batch) {
            StoredAsset row = change.row();
            Target target = change.target();
            if (ids.length() > 1) {
                ids.append(',');
            }
            ids.append(row.assetId());
            String nativeId = row.assetUri().equals(target.uri()) ? null : row.assetUri();
            identities.add(new Object[] {target.uri(), target.serviceType(), target.cloudProvider(),
                    nativeValue(nativeId), row.assetId()});
            maps.add(new Object[] {runId, row.assetId(), row.assetId(), "REKEYED", row.assetUri(), target.uri(),
                    row.serviceType(), target.serviceType(), row.cloudProvider(), target.cloudProvider()});
        }
        undo.record(UndoLog.ASSETS_UPDATED, ids.append('}').toString());
        jdbcTemplate.batchUpdate(IDENTITY_SQL, identities);
        jdbcTemplate.batchUpdate(MAP_SQL, maps);
    }

    private void mergeInto(UndoLog undo, UUID s, UUID l, Map<String, Integer> counts) {
        Boolean survivorHeld = jdbcTemplate.queryForObject(HOLD_SQL, Boolean.class, s);
        Boolean loserHeld = jdbcTemplate.queryForObject(HOLD_SQL, Boolean.class, l);
        if (Boolean.TRUE.equals(loserHeld) && !Boolean.TRUE.equals(survivorHeld)) {
            add(counts, "legalHoldsMerged", 1);
        }
        undo.assetUpdated(s);
        jdbcTemplate.update(MERGE_ASSET_SQL, s, l);

        undo.record(UNDO_LABELS, s, s, l);
        add(counts, "labelsDropped", jdbcTemplate.update(DROP_LABELS, l, s));
        add(counts, "labelsMoved", jdbcTemplate.update(MOVE_LABELS, s, l));

        undo.record(UNDO_CLOUD_RESOURCES, l);
        add(counts, "cloudResourcesMoved", jdbcTemplate.update(MOVE_CLOUD_RESOURCES, s, l));

        moveLineage(undo, s, l, counts);

        undo.record(UNDO_USAGE_SURVIVOR, s, l);
        undo.record(UNDO_USAGE_MERGED, s, s, l);
        add(counts, "usageDaysSummed", jdbcTemplate.update(SUM_USAGE, s, l));
        jdbcTemplate.update(DROP_SUMMED_USAGE, l, s);
        add(counts, "usageDaysMoved", jdbcTemplate.update(MOVE_USAGE, s, l));

        undo.record(UNDO_EVENTS, s, s, l);
        add(counts, "processedEventsDropped", jdbcTemplate.update(DROP_EVENTS, l, s));
        add(counts, "processedEventsMoved", jdbcTemplate.update(MOVE_EVENTS, s, l));

        undo.record(UNDO_SURVIVOR_RULES, s, l);
        undo.record(UNDO_MERGED_RULES, s, l);
        int dropped = jdbcTemplate.update(DROP_SURVIVOR_RULES, s, l);
        dropped += jdbcTemplate.update(DROP_MERGED_RULES, l, s);
        add(counts, "retentionRulesDropped", dropped);
        add(counts, "retentionRulesMoved", jdbcTemplate.update(MOVE_RULES, s, l));

        jdbcTemplate.update(DROP_INDEX_ENTRY, l);
        undo.assetDeleted(l);
        jdbcTemplate.update(DELETE_ASSET, l);
    }

    private void moveLineage(UndoLog undo, UUID s, UUID l, Map<String, Integer> counts) {
        List<Map<String, Object>> edges = jdbcTemplate.queryForList(LINEAGE_SQL, l, l);
        for (Map<String, Object> edge : edges) {
            UUID id = (UUID) edge.get("lineage_id");
            UUID up = (UUID) edge.get("upstream_asset_id");
            UUID down = (UUID) edge.get("downstream_asset_id");
            String rel = (String) edge.get("relationship_type");
            UUID newUp = l.equals(up) ? s : up;
            UUID newDown = l.equals(down) ? s : down;
            Integer duplicates = jdbcTemplate.queryForObject(LINEAGE_DUPLICATE_SQL, Integer.class, newUp, newDown, rel,
                    id);
            if (newUp.equals(newDown) || (duplicates != null && duplicates > 0)) {
                undo.record(UNDO_EDGE_DROPPED, id);
                jdbcTemplate.update(DROP_EDGE, id);
                add(counts, "lineageDropped", 1);
            } else {
                undo.record(UNDO_EDGE_MOVED, id);
                jdbcTemplate.update(MOVE_EDGE, newUp, newDown, id);
                add(counts, "lineageMoved", 1);
            }
        }
    }

    private void applyIdentity(UndoLog undo, StoredAsset row, Target target, String nativeId, UUID runId,
                               String action, UUID survivor) {
        undo.assetUpdated(row.assetId());
        jdbcTemplate.update(IDENTITY_SQL, target.uri(), target.serviceType(), target.cloudProvider(),
                nativeValue(nativeId), row.assetId());
        index.writeThrough(row.assetId(), target.uri(), target.serviceType(), target.cloudProvider(), row.region());
        map(runId, row, target, action, survivor);
    }

    private void map(UUID runId, StoredAsset row, Target target, String action, UUID survivor) {
        jdbcTemplate.update(MAP_SQL, runId, row.assetId(), survivor, action, row.assetUri(), target.uri(),
                row.serviceType(), target.serviceType(), row.cloudProvider(), target.cloudProvider());
    }

    /**
     * The native form a survivor records when it has none: the first form seen, oldest row first,
     * where a row's form is its stored native id, else its stored URI when that was not canonical.
     *
     * @param group the collision group
     * @return the native form, or {@code null} when every member was canonical and had none
     */
    static String nativeCandidate(MergeGroup group) {
        for (StoredAsset m : group.members()) {
            if (m.nativeAssetId() != null && !m.nativeAssetId().isBlank()) {
                return m.nativeAssetId();
            }
            if (!m.assetUri().equals(group.target().uri())) {
                return m.assetUri();
            }
        }
        return null;
    }

    /**
     * Test seam: the batch size of in-place re-keys.
     *
     * @param batchSize a positive size
     */
    void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    private static String nativeValue(String nativeId) {
        return nativeId != null && nativeId.length() <= NATIVE_ID_MAX ? nativeId : null;
    }

    private static void add(Map<String, Integer> counts, String key, int n) {
        counts.merge(key, n, Integer::sum);
    }

    private static String jsonMerge(String col) {
        return col + " = CASE WHEN jsonb_typeof(s." + col + ") = 'object' AND jsonb_typeof(l." + col + ") = 'object' "
                + "THEN l." + col + " || s." + col + " ELSE COALESCE(s." + col + ", l." + col + ") END";
    }

    private static String earlier(String col) {
        return "l." + col + " IS NOT NULL AND (s." + col + " IS NULL OR l." + col + " < s." + col + ")";
    }

    private static String later(String col) {
        return "l." + col + " IS NOT NULL AND (s." + col + " IS NULL OR l." + col + " > s." + col + ")";
    }

    private static String newerSize() {
        return "l.size_bytes IS NOT NULL AND (s.size_bytes IS NULL OR COALESCE(l.size_observed_at, '-infinity') "
                + "> COALESCE(s.size_observed_at, '-infinity'))";
    }

    private static String coverage(String col) {
        return col + " = CASE WHEN " + later("read_coverage_observed_at") + " THEN l." + col + " ELSE s." + col + " END";
    }

    /**
     * What an execution did.
     *
     * @param perGroup    counts per collision group, keyed by the survivor
     * @param totals      the counts summed over all groups
     * @param undoEntries entries written to the undo log
     */
    public record Result(Map<UUID, Map<String, Integer>> perGroup, Map<String, Integer> totals, int undoEntries) {
    }
}
