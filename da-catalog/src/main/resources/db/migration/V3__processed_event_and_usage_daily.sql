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

-- CAT-02a (ADR-0055): catalog event idempotency, idempotent daily usage, and the
-- normalized-URI index that lets events match rows written before canonical IDs.
-- All three tables are written through JDBC by the catalog event consumers, in the
-- same transaction as the asset change they guard (docs/specs/asset-descriptor.md,
-- "Catalog event ingest"). None is mapped by Hibernate, so ddl-auto=validate does not
-- cover them; CatalogEventIngestContainerTest applies this migration and uses them.

-- 1. processed_event: one row per (consumer, event key, asset) the catalog applied.
--    An insert that conflicts means the pair was applied before, so the consumer skips
--    it (exactly-once effect under at-least-once delivery, log-event.md section 5.4).
--    event_id holds the DlapLogEvent event_id, a usage dedup key, or "sha256:<hex>" of a
--    longer key. Rows are pruned by processed_at after dlap.catalog.ingest.processed-event
--    .retention, which must be longer than the log store's replay window.
CREATE TABLE IF NOT EXISTS dlap_catalog.processed_event (
    consumer     VARCHAR(64)  NOT NULL,
    event_id     VARCHAR(128) NOT NULL,
    asset_id     UUID         NOT NULL,
    event_time   TIMESTAMPTZ,
    processed_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (consumer, event_id, asset_id)
);

CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at
    ON dlap_catalog.processed_event (processed_at);

COMMENT ON TABLE dlap_catalog.processed_event IS
    'CAT-02a: (consumer, event key, asset) pairs already applied; insert-or-skip in the state-change transaction.';
COMMENT ON COLUMN dlap_catalog.processed_event.processed_at IS
    'DLAP processing time (operational, used only for pruning).';

-- 2. asset_usage_daily: successful recorded reads and writes per asset per UTC day of
--    event_time (business time). Each increment is guarded by a processed_event row
--    keyed on the usage dedup key, so a redelivery never counts twice. Observed reads
--    (DATA_READ_OBSERVED) never count. Object events count on the container they roll
--    up to (ADR-0046).
CREATE TABLE IF NOT EXISTS dlap_catalog.asset_usage_daily (
    asset_id       UUID        NOT NULL,
    day            DATE        NOT NULL,
    reads          BIGINT      NOT NULL DEFAULT 0,
    writes         BIGINT      NOT NULL DEFAULT 0,
    first_event_at TIMESTAMPTZ,
    last_event_at  TIMESTAMPTZ,
    CONSTRAINT pk_asset_usage_daily PRIMARY KEY (asset_id, day),
    CONSTRAINT ck_asset_usage_daily_reads_nonneg CHECK (reads >= 0),
    CONSTRAINT ck_asset_usage_daily_writes_nonneg CHECK (writes >= 0)
);

CREATE INDEX IF NOT EXISTS idx_asset_usage_daily_day
    ON dlap_catalog.asset_usage_daily (day);

COMMENT ON TABLE dlap_catalog.asset_usage_daily IS
    'CAT-02a: idempotent daily read and write counts by event_time (UTC day).';

-- 3. asset_uri_canonical: AssetIdNormalizer applied to each row's stored asset_uri,
--    with hints from the row (service_type, cloud_provider, region). Rows written before
--    canonical IDs (for example a GCS bucket stored as "b", a BigQuery table stored as
--    "p:d.t") are found by the canonical URI an event carries, so they are updated
--    instead of duplicated until ID-03 re-keys them. asset_uri records the value the
--    entry was computed from; an entry whose asset_uri no longer matches the row is
--    stale and is recomputed. canonical_uri is null when the stored form does not
--    normalize (reject_reason says why), so the row is not retried on every sweep.
--    The lookup index is on the SHA-256 of the canonical URI because a 2048-character
--    URI can exceed the btree row limit.
CREATE TABLE IF NOT EXISTS dlap_catalog.asset_uri_canonical (
    asset_id         UUID          NOT NULL,
    asset_uri        VARCHAR(2048) NOT NULL,
    canonical_uri    VARCHAR(2048),
    canonical_sha256 CHAR(64),
    reject_reason    VARCHAR(32),
    indexed_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_asset_uri_canonical PRIMARY KEY (asset_id),
    CONSTRAINT ck_asset_uri_canonical_outcome CHECK (
        (canonical_uri IS NOT NULL AND canonical_sha256 IS NOT NULL AND reject_reason IS NULL)
        OR (canonical_uri IS NULL AND canonical_sha256 IS NULL AND reject_reason IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_asset_uri_canonical_sha256
    ON dlap_catalog.asset_uri_canonical (canonical_sha256);

COMMENT ON TABLE dlap_catalog.asset_uri_canonical IS
    'CAT-02a: normalize(stored asset_uri) per asset, so pre-canonical rows match canonical event targets.';
