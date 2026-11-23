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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Undoes one applied run inside the caller's transaction: replays the run's undo log from the
 * last entry to the first, applying the inverse of each entry.
 * <ul>
 *   <li>no before-image: the run created the row, so it is deleted;</li>
 *   <li>no after-key: the run deleted the row, so the before-image is inserted;</li>
 *   <li>an asset row with both: it was updated in place, so it gets its before-image back by an
 *       update (deleting and re-inserting it would cascade to its label assignments);</li>
 *   <li>any other row with both: it was moved to the survivor or updated in place, so the row at
 *       the after-key is deleted and the before-image inserted.</li>
 * </ul>
 * Restored merged rows get their normalized-URI index entry back; the entries of the other rows
 * now record a URI the row no longer holds, so the catalog recomputes them before use.
 *
 * <p>Every inverse must touch exactly one row. When one does not (the row was changed or removed
 * since the run), the revert is refused and the transaction rolls back, leaving the run applied.
 */
@Component
public class AssetUriRekeyReverter {

    /**
     * Every column of {@code dlap_common.dlap_assets} but the key, restored by the in-place
     * update. A revert refuses when the live table has a column this list lacks, so a column
     * added later is never silently left at its re-keyed value.
     */
    static final List<String> ASSET_COLUMNS = List.of("asset_uri", "cloud_provider", "service_type", "asset_name",
            "description", "technical_metadata", "dlap_discovered_ts", "dlap_last_scanned_ts", "dlap_usage_metrics",
            "dlap_policy_status", "dlap_lineage_info", "owner_info", "status", "created_ts", "last_modified_ts",
            "cloud_connection_id", "created_by_user_id", "asset_type", "native_asset_id", "region", "business_metadata",
            "details_json", "discovered_at", "last_scanned_at", "cloud_created_at", "cloud_created_source",
            "size_bytes", "size_observed_at", "last_accessed_at", "last_access_source", "last_modified_at",
            "last_permission_change_at", "lifecycle_event_time", "recreated_at", "discovery_source", "retention_days",
            "retention_basis", "retention_source", "retention_policy_id", "retention_expires_at", "retention_status",
            "retention_evaluated_at", "legal_hold", "read_coverage", "read_coverage_reasons",
            "read_coverage_observed_at", "read_coverage_since");

    private static final String RESTORE_ASSET_SQL = "UPDATE dlap_common.dlap_assets t SET ("
            + String.join(", ", ASSET_COLUMNS) + ") = (SELECT "
            + ASSET_COLUMNS.stream().map(c -> "r." + c).collect(Collectors.joining(", "))
            + " FROM jsonb_populate_record(NULL::dlap_common.dlap_assets, ?::jsonb) r) WHERE t.asset_id = ?";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String UNDO_SQL = "SELECT seq, table_name, before_row::text AS before_row, "
            + "after_key::text AS after_key FROM dlap_catalog.asset_uri_rekey_undo WHERE run_id = ? ORDER BY seq DESC";

    private static final String ASSET_COLUMNS_SQL = "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = 'dlap_common' AND table_name = 'dlap_assets'";

    private static final String DRIFT_SQL = "SELECT m.asset_id FROM dlap_catalog.asset_uri_rekey_map m "
            + "LEFT JOIN dlap_common.dlap_assets a ON a.asset_id = m.asset_id WHERE m.run_id = ? AND ("
            + "(m.action = 'MERGED' AND a.asset_id IS NOT NULL) OR (m.action <> 'MERGED' AND (a.asset_id IS NULL "
            + "OR a.asset_uri <> m.new_asset_uri OR a.service_type <> m.new_service_type "
            + "OR a.cloud_provider <> m.new_cloud_provider))) ORDER BY m.asset_id";

    private static final String TAKEN_SQL = "SELECT a.asset_id FROM dlap_catalog.asset_uri_rekey_map m "
            + "JOIN dlap_common.dlap_assets a ON a.asset_uri = m.old_asset_uri WHERE m.run_id = ? "
            + "AND NOT EXISTS (SELECT 1 FROM dlap_catalog.asset_uri_rekey_map o WHERE o.run_id = m.run_id "
            + "AND o.asset_id = a.asset_id) ORDER BY a.asset_id";

    private static final String RESTORED_SQL = "SELECT a.asset_id, a.asset_uri, a.service_type, a.cloud_provider, "
            + "a.region FROM dlap_catalog.asset_uri_rekey_map m JOIN dlap_common.dlap_assets a "
            + "ON a.asset_id = m.asset_id WHERE m.run_id = ? AND m.action = 'MERGED'";

    private final JdbcTemplate jdbcTemplate;
    private final CanonicalUriIndex index;

    /**
     * Creates the reverter.
     *
     * @param jdbcTemplate JDBC access
     * @param index        the normalized-URI index, refreshed for every restored row
     */
    public AssetUriRekeyReverter(JdbcTemplate jdbcTemplate, CanonicalUriIndex index) {
        this.jdbcTemplate = jdbcTemplate;
        this.index = index;
    }

    /**
     * What stops a revert of the run: rows that changed identity since, old URIs another row now
     * holds, asset columns this build does not restore.
     *
     * @param runId the run
     * @return human-readable problems, empty when the revert can proceed
     */
    public List<String> preconditions(UUID runId) {
        List<String> problems = new ArrayList<>();
        Set<String> unknown = unknownAssetColumns(new HashSet<>(jdbcTemplate.queryForList(ASSET_COLUMNS_SQL,
                String.class)));
        if (!unknown.isEmpty()) {
            problems.add("dlap_assets has column(s) this build does not restore: " + String.join(", ", unknown));
        }
        List<UUID> drifted = jdbcTemplate.queryForList(DRIFT_SQL, UUID.class, runId);
        if (!drifted.isEmpty()) {
            problems.add(drifted.size() + " asset row(s) changed identity since the run, for example " + sample(drifted));
        }
        List<UUID> taken = jdbcTemplate.queryForList(TAKEN_SQL, UUID.class, runId);
        if (!taken.isEmpty()) {
            problems.add(taken.size() + " row(s) created since the run hold an old URI, for example " + sample(taken));
        }
        return problems;
    }

    /**
     * Replays the undo log backwards. The caller holds the table lock and has checked
     * {@link #preconditions}.
     *
     * @param runId the run
     * @return the number of entries replayed
     * @throws RekeyRefusedException when an inverse does not touch exactly one row
     */
    public int revert(UUID runId) {
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(UNDO_SQL, runId);
        for (Map<String, Object> entry : entries) {
            String name = (String) entry.get("table_name");
            RekeyTable table = RekeyTable.fromUndoName(name)
                    .orElseThrow(() -> new IllegalStateException("unknown table in the undo log: " + name));
            String before = (String) entry.get("before_row");
            String after = (String) entry.get("after_key");
            int seq = ((Number) entry.get("seq")).intValue();
            if (before == null) {
                expectOne(jdbcTemplate.update(table.deleteAtKeySql(), after), table, seq);
            } else if (after == null) {
                expectOne(jdbcTemplate.update(table.insertSql(), before), table, seq);
            } else if (table == RekeyTable.ASSETS) {
                if (!sameKey(table, before, after)) {
                    throw new IllegalStateException("undo entry " + seq + " moves an asset row");
                }
                expectOne(jdbcTemplate.update(RESTORE_ASSET_SQL, before, UUID.fromString(key(before, "asset_id"))),
                        table, seq);
            } else {
                expectOne(jdbcTemplate.update(table.deleteAtKeySql(), after), table, seq);
                expectOne(jdbcTemplate.update(table.insertSql(), before), table, seq);
            }
        }
        jdbcTemplate.query(RESTORED_SQL, rs -> {
            index.writeThrough(rs.getObject("asset_id", UUID.class), rs.getString("asset_uri"),
                    rs.getString("service_type"), rs.getString("cloud_provider"), rs.getString("region"));
        }, runId);
        return entries.size();
    }

    /**
     * The live asset columns that {@link #ASSET_COLUMNS} does not cover.
     *
     * @param live the column names of the live table
     * @return the uncovered columns, sorted; empty when the revert restores every column
     */
    static Set<String> unknownAssetColumns(Set<String> live) {
        Set<String> unknown = new TreeSet<>(live);
        unknown.remove("asset_id");
        ASSET_COLUMNS.forEach(unknown::remove);
        return unknown;
    }

    /**
     * Whether a before-image and an after-key name the same row.
     *
     * @param t          the table
     * @param beforeJson the before-image
     * @param afterJson  the after-key
     * @return {@code true} when every key column is equal
     */
    static boolean sameKey(RekeyTable t, String beforeJson, String afterJson) {
        for (String k : t.keyColumns()) {
            if (!key(beforeJson, k).equals(key(afterJson, k))) {
                return false;
            }
        }
        return true;
    }

    private static String key(String json, String column) {
        try {
            JsonNode node = JSON.readTree(json);
            return node.path(column).asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable undo entry", e);
        }
    }

    private static void expectOne(int n, RekeyTable t, int seq) {
        if (n != 1) {
            throw new RekeyRefusedException("undo entry " + seq + " on " + t.qualified() + " touched " + n
                    + " row(s) instead of 1: the data changed since the run; restore the backup instead");
        }
    }

    private static String sample(List<UUID> ids) {
        return ids.stream().limit(5).map(UUID::toString).collect(Collectors.joining(", "));
    }
}
