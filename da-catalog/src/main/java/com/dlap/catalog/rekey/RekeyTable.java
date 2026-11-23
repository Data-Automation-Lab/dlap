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

import java.util.List;
import java.util.Optional;

/**
 * The tables a re-key run changes and records in {@code dlap_catalog.asset_uri_rekey_undo}: the
 * asset table and every catalog table that names an asset by {@code asset_id}. The undo log
 * stores {@link #undoName()}; a revert finds here the table, its primary key and the two
 * statements that undo an entry. Every statement is a constant: the row data travels only as a
 * bind parameter (a jsonb image read by {@code jsonb_populate_record}).
 *
 * <p>Not listed, on purpose: {@code dlap_catalog.asset_uri_canonical} (derived state; the run and
 * the revert rewrite it), {@code retention_audit} (append-only; its rows keep the asset_id they
 * were written for and resolve through the map) and {@code retention_outbox} (the runbook drains
 * it first; the run refuses while an unpublished row names an old URI).
 */
public enum RekeyTable {

    /** The assets. Restored in place, never deleted and re-inserted (that would cascade). */
    ASSETS("dlap_common", "dlap_assets", List.of("asset_id"),
            "DELETE FROM dlap_common.dlap_assets t WHERE t.asset_id = "
                    + "(SELECT k.asset_id FROM jsonb_populate_record(NULL::dlap_common.dlap_assets, ?::jsonb) k)",
            "INSERT INTO dlap_common.dlap_assets "
                    + "SELECT * FROM jsonb_populate_record(NULL::dlap_common.dlap_assets, ?::jsonb)"),

    /** Label assignments (foreign key to the asset, cascading delete). */
    LABELS("dlap_common", "dlap_asset_label_assignments", List.of("asset_id", "label_id"),
            "DELETE FROM dlap_common.dlap_asset_label_assignments t WHERE (t.asset_id, t.label_id) = "
                    + "(SELECT k.asset_id, k.label_id FROM "
                    + "jsonb_populate_record(NULL::dlap_common.dlap_asset_label_assignments, ?::jsonb) k)",
            "INSERT INTO dlap_common.dlap_asset_label_assignments SELECT * FROM "
                    + "jsonb_populate_record(NULL::dlap_common.dlap_asset_label_assignments, ?::jsonb)"),

    /** The provider-native resource model (foreign key to the asset, set null on delete). */
    CLOUD_RESOURCES("dlap_common", "dlap_cloud_resources", List.of("cloud_resource_id"),
            "DELETE FROM dlap_common.dlap_cloud_resources t WHERE t.cloud_resource_id = "
                    + "(SELECT k.cloud_resource_id FROM "
                    + "jsonb_populate_record(NULL::dlap_common.dlap_cloud_resources, ?::jsonb) k)",
            "INSERT INTO dlap_common.dlap_cloud_resources SELECT * FROM "
                    + "jsonb_populate_record(NULL::dlap_common.dlap_cloud_resources, ?::jsonb)"),

    /** Lineage edges (foreign keys on both endpoints, no cascade). */
    LINEAGE("dlap_catalog", "dlap_asset_lineage", List.of("lineage_id"),
            "DELETE FROM dlap_catalog.dlap_asset_lineage t WHERE t.lineage_id = "
                    + "(SELECT k.lineage_id FROM jsonb_populate_record(NULL::dlap_catalog.dlap_asset_lineage, ?::jsonb) k)",
            "INSERT INTO dlap_catalog.dlap_asset_lineage SELECT * FROM "
                    + "jsonb_populate_record(NULL::dlap_catalog.dlap_asset_lineage, ?::jsonb)"),

    /** Daily usage counters. */
    USAGE("dlap_catalog", "asset_usage_daily", List.of("asset_id", "day"),
            "DELETE FROM dlap_catalog.asset_usage_daily t WHERE (t.asset_id, t.day) = "
                    + "(SELECT k.asset_id, k.day FROM jsonb_populate_record(NULL::dlap_catalog.asset_usage_daily, ?::jsonb) k)",
            "INSERT INTO dlap_catalog.asset_usage_daily SELECT * FROM "
                    + "jsonb_populate_record(NULL::dlap_catalog.asset_usage_daily, ?::jsonb)"),

    /** Applied-event records (idempotency). */
    PROCESSED_EVENTS("dlap_catalog", "processed_event", List.of("consumer", "event_id", "asset_id"),
            "DELETE FROM dlap_catalog.processed_event t WHERE (t.consumer, t.event_id, t.asset_id) = "
                    + "(SELECT k.consumer, k.event_id, k.asset_id FROM "
                    + "jsonb_populate_record(NULL::dlap_catalog.processed_event, ?::jsonb) k)",
            "INSERT INTO dlap_catalog.processed_event SELECT * FROM "
                    + "jsonb_populate_record(NULL::dlap_catalog.processed_event, ?::jsonb)"),

    /** Retention rules. */
    RETENTION_RULES("dlap_catalog", "retention_rule", List.of("rule_id"),
            "DELETE FROM dlap_catalog.retention_rule t WHERE t.rule_id = "
                    + "(SELECT k.rule_id FROM jsonb_populate_record(NULL::dlap_catalog.retention_rule, ?::jsonb) k)",
            "INSERT INTO dlap_catalog.retention_rule SELECT * FROM "
                    + "jsonb_populate_record(NULL::dlap_catalog.retention_rule, ?::jsonb)");

    private final String schema;
    private final String table;
    private final List<String> keyColumns;
    private final String deleteAtKeySql;
    private final String insertSql;

    RekeyTable(String schema, String table, List<String> keyColumns, String deleteAtKeySql, String insertSql) {
        this.schema = schema;
        this.table = table;
        this.keyColumns = keyColumns;
        this.deleteAtKeySql = deleteAtKeySql;
        this.insertSql = insertSql;
    }

    /**
     * The schema.
     *
     * @return the schema name
     */
    public String schema() {
        return schema;
    }

    /**
     * The table name without schema; the value of {@code asset_uri_rekey_undo.table_name}.
     *
     * @return the name
     */
    public String undoName() {
        return table;
    }

    /**
     * The schema-qualified name.
     *
     * @return {@code schema.table}
     */
    public String qualified() {
        return schema + "." + table;
    }

    /**
     * The primary-key columns.
     *
     * @return the columns
     */
    public List<String> keyColumns() {
        return keyColumns;
    }

    /**
     * Deletes the row whose key the jsonb parameter holds.
     *
     * @return the statement, one parameter
     */
    public String deleteAtKeySql() {
        return deleteAtKeySql;
    }

    /**
     * Inserts the row the jsonb parameter holds (a {@code to_jsonb} image of a row of this table).
     *
     * @return the statement, one parameter
     */
    public String insertSql() {
        return insertSql;
    }

    /**
     * The table an undo entry names.
     *
     * @param undoName the stored {@code table_name}
     * @return the table, or empty for an unknown name
     */
    public static Optional<RekeyTable> fromUndoName(String undoName) {
        for (RekeyTable t : values()) {
            if (t.table.equals(undoName)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
