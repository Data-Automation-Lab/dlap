-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- ID-03 (ADR-0047, docs/specs/asset-id.md section 13, docs/guides/upgrade.md "Re-key asset
-- URIs"): the bookkeeping of the one-shot asset_uri re-key. The runner (da-catalog package
-- com.dlap.catalog.rekey) moves every dlap_assets row whose stored asset_uri is not canonical to
-- normalize(asset_uri), merges rows that normalize to the same URI into the oldest one, and
-- writes here what it changed, in the same transaction:
--
--   asset_uri_rekey_run    one row per applied run, with the run report.
--   asset_uri_rekey_map    the reversible map: per changed asset, its old and new identity and,
--                          for a merged row, the asset_id that absorbed it. Readers outside the
--                          catalog (other services' rows that still name an old URI or a merged
--                          asset_id) resolve through it.
--   asset_uri_rekey_undo   the ordered before-images of every row the run updated or deleted, in
--                          dlap_assets and in the tables that reference an asset. A revert replays
--                          it backwards, so the database returns to its state before the run.
--
-- Written through JDBC only (not mapped by Hibernate). Idempotent (CREATE ... IF NOT EXISTS) per
-- CLAUDE.md section 7. dlap_assets is in dlap_common and is referenced by asset_id without a
-- foreign key, like processed_event (V3): a merged asset_id no longer exists.

-- 1. asset_uri_rekey_run. status APPLIED until a revert sets REVERTED. applied_by is the database
--    role that ran it, operator the optional name passed with dlap.catalog.rekey.operator.
CREATE TABLE IF NOT EXISTS dlap_catalog.asset_uri_rekey_run (
    run_id        UUID          NOT NULL,
    spec_version  VARCHAR(16)   NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    applied_at    TIMESTAMPTZ   NOT NULL,
    applied_by    VARCHAR(128)  NOT NULL,
    operator      VARCHAR(255),
    rows_rekeyed  INTEGER       NOT NULL,
    merge_groups  INTEGER       NOT NULL,
    rows_merged   INTEGER       NOT NULL,
    undo_entries  INTEGER       NOT NULL,
    reverted_at   TIMESTAMPTZ,
    reverted_by   VARCHAR(128),
    report        JSONB         NOT NULL,
    CONSTRAINT pk_asset_uri_rekey_run PRIMARY KEY (run_id),
    CONSTRAINT ck_asset_uri_rekey_run_status CHECK (status IN ('APPLIED', 'REVERTED')),
    CONSTRAINT ck_asset_uri_rekey_run_reverted CHECK ((status = 'REVERTED') = (reverted_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_asset_uri_rekey_run_applied_at
    ON dlap_catalog.asset_uri_rekey_run (applied_at);

COMMENT ON TABLE dlap_catalog.asset_uri_rekey_run IS
    'ID-03: one row per applied asset_uri re-key run; report holds the dry-run shaped report of what it changed.';

-- 2. asset_uri_rekey_map. action:
--      REKEYED   a row that was alone under its canonical URI: its identity changed in place.
--      SURVIVOR  the oldest row of a collision group (created_ts, then asset_id: the row the
--                catalog event matcher already picks); it keeps its asset_id and absorbs the rest.
--      MERGED    a younger row of a collision group; deleted, its labels, lineage, usage, cloud
--                resources, processed events and retention rules moved to survivor_asset_id.
--    survivor_asset_id equals asset_id for REKEYED and SURVIVOR rows. The old URI can be 2048
--    characters, more than a btree entry may hold, so its lookup index is a hash index.
CREATE TABLE IF NOT EXISTS dlap_catalog.asset_uri_rekey_map (
    run_id             UUID          NOT NULL,
    asset_id           UUID          NOT NULL,
    survivor_asset_id  UUID          NOT NULL,
    action             VARCHAR(16)   NOT NULL,
    old_asset_uri      VARCHAR(2048) NOT NULL,
    new_asset_uri      VARCHAR(2048) NOT NULL,
    old_service_type   VARCHAR(50)   NOT NULL,
    new_service_type   VARCHAR(50)   NOT NULL,
    old_cloud_provider VARCHAR(20)   NOT NULL,
    new_cloud_provider VARCHAR(20)   NOT NULL,
    CONSTRAINT pk_asset_uri_rekey_map PRIMARY KEY (run_id, asset_id),
    CONSTRAINT fk_asset_uri_rekey_map_run FOREIGN KEY (run_id)
        REFERENCES dlap_catalog.asset_uri_rekey_run (run_id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_uri_rekey_map_action CHECK (action IN ('REKEYED', 'SURVIVOR', 'MERGED')),
    CONSTRAINT ck_asset_uri_rekey_map_survivor CHECK ((action = 'MERGED') = (survivor_asset_id <> asset_id))
);

CREATE INDEX IF NOT EXISTS idx_asset_uri_rekey_map_asset
    ON dlap_catalog.asset_uri_rekey_map (asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_uri_rekey_map_old_uri
    ON dlap_catalog.asset_uri_rekey_map USING hash (old_asset_uri);

COMMENT ON TABLE dlap_catalog.asset_uri_rekey_map IS
    'ID-03: reversible asset_uri re-key map: old identity, new identity and the absorbing asset_id per changed asset.';

-- 3. asset_uri_rekey_undo. One entry per row the run changed, in the order it changed them:
--    before_row is the full row (to_jsonb) before the change, NULL when the run created the row;
--    after_key holds the primary-key columns of the row after the change, NULL when the run
--    deleted it. The revert applies the inverse of each entry from the highest seq down.
CREATE TABLE IF NOT EXISTS dlap_catalog.asset_uri_rekey_undo (
    run_id      UUID         NOT NULL,
    seq         INTEGER      NOT NULL,
    table_name  VARCHAR(64)  NOT NULL,
    before_row  JSONB,
    after_key   JSONB,
    CONSTRAINT pk_asset_uri_rekey_undo PRIMARY KEY (run_id, seq),
    CONSTRAINT fk_asset_uri_rekey_undo_run FOREIGN KEY (run_id)
        REFERENCES dlap_catalog.asset_uri_rekey_run (run_id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_uri_rekey_undo_table CHECK (table_name IN (
        'dlap_assets', 'dlap_asset_label_assignments', 'dlap_cloud_resources', 'dlap_asset_lineage',
        'asset_usage_daily', 'processed_event', 'retention_rule')),
    CONSTRAINT ck_asset_uri_rekey_undo_images CHECK (before_row IS NOT NULL OR after_key IS NOT NULL)
);

COMMENT ON TABLE dlap_catalog.asset_uri_rekey_undo IS
    'ID-03: ordered before-images of the rows an asset_uri re-key run changed; a revert replays them backwards.';
