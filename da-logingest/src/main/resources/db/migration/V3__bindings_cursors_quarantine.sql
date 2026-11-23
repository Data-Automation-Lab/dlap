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

-- V3__bindings_cursors_quarantine.sql
--
-- LOG-02bc (ADR-0045 decision 8, ADR-0060). Runtime state of the configuration-driven log layer
-- (design B2, B3, B5, B6) and a partitioned replay store (hardening critic item 20):
--
--   source_binding       mirror of the effective bindings file, with a config hash
--   stream_cursor        per (binding, stream, partition) event-time cursor (replaces watermarks)
--   processed_object     per-object checkpoints of file transports
--   format_observation   formats, versions and shape fingerprints seen per binding
--   quarantine_record    quarantined records (written by LOG-03; table reserved here)
--   backfill_job         bounded, resumable history reads (API is a later package)
--   shedlock             ShedLock lock table for singleton scheduled jobs (plan rule R7)
--   log_events           re-created as a table partitioned by month of ingest_time
--
-- The bindings file stays authoritative; these tables hold state and a mirror. Idempotent where
-- PostgreSQL allows it (CREATE ... IF NOT EXISTS), per CLAUDE.md section 7. The log_events
-- conversion runs once: Flyway records V3 and never re-runs it.

SET search_path TO dlap_logingest, public;

-- ---------------------------------------------------------------------------------------------
-- 1. source_binding: one row per binding the service has seen.
--
-- config_origin: FILE (the bindings file), BUILTIN (the REST and fixture-replay channels of the
-- registry pipeline), MIGRATED (V4, from the legacy source_watermarks).
-- state: ACTIVE (runs), INACTIVE (valid but the legacy pipeline is selected), DISABLED
-- (enabled: false), INVALID (failed validation under a warning-only profile), REMOVED (no longer
-- in the configuration; kept because cursors and observations reference it), MIGRATED.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS source_binding (
    binding_id              VARCHAR(128) PRIMARY KEY,
    source_id               VARCHAR(128) NOT NULL,
    source_kind             VARCHAR(64)  NOT NULL,
    config_origin           VARCHAR(16)  NOT NULL,
    state                   VARCHAR(16)  NOT NULL,
    enabled                 BOOLEAN      NOT NULL,
    scope                   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    effective_config        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    config_hash             VARCHAR(64)  NOT NULL,
    deployment_config_hash  VARCHAR(64),
    first_synced_at         TIMESTAMPTZ  NOT NULL,
    last_synced_at          TIMESTAMPTZ  NOT NULL,
    removed_at              TIMESTAMPTZ,
    CONSTRAINT ck_source_binding_origin CHECK (config_origin IN ('FILE', 'BUILTIN', 'MIGRATED')),
    CONSTRAINT ck_source_binding_state
        CHECK (state IN ('ACTIVE', 'INACTIVE', 'DISABLED', 'INVALID', 'REMOVED', 'MIGRATED'))
);

CREATE INDEX IF NOT EXISTS idx_source_binding_kind ON source_binding (source_kind);

-- ---------------------------------------------------------------------------------------------
-- 2. stream_cursor (design B5 "Cursors replace watermarks").
--
-- Time transports poll [low_watermark - overlap, now - settle]; low_watermark moves to the
-- oldest event time not yet acknowledged, never to the newest seen. partition_key splits one
-- stream (an AWS region, a shard); '' when the stream has one cursor. cursor holds transport
-- state (page tokens, StartAfter keys) as an opaque object.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stream_cursor (
    binding_id      VARCHAR(128) NOT NULL REFERENCES source_binding (binding_id),
    stream_id       VARCHAR(128) NOT NULL,
    partition_key   VARCHAR(256) NOT NULL DEFAULT '',
    low_watermark   TIMESTAMPTZ,
    high_watermark  TIMESTAMPTZ,
    cursor          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_stream_cursor PRIMARY KEY (binding_id, stream_id, partition_key)
);

-- ---------------------------------------------------------------------------------------------
-- 3. processed_object: file transports checkpoint per object (S3 trail files, GCS sink shards,
-- blobs). The etag is part of the key, so a rewritten object is read again. The key holds the
-- SHA-256 of the object key, not the key itself: an object key of 1024 multi-byte characters
-- would exceed the btree row limit.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processed_object (
    binding_id          VARCHAR(128)  NOT NULL REFERENCES source_binding (binding_id),
    stream_id           VARCHAR(128)  NOT NULL,
    object_key_sha256   VARCHAR(64)   NOT NULL,
    object_key          VARCHAR(2048) NOT NULL,
    etag                VARCHAR(256)  NOT NULL DEFAULT '',
    status              VARCHAR(16)   NOT NULL,
    records             BIGINT        NOT NULL DEFAULT 0,
    quarantined         BIGINT        NOT NULL DEFAULT 0,
    attempts            INTEGER       NOT NULL DEFAULT 0,
    first_processed_at  TIMESTAMPTZ   NOT NULL,
    last_processed_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_processed_object PRIMARY KEY (binding_id, stream_id, object_key_sha256, etag),
    CONSTRAINT ck_processed_object_status CHECK (status IN ('DONE', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_processed_object_last
    ON processed_object (binding_id, stream_id, last_processed_at);

-- ---------------------------------------------------------------------------------------------
-- 4. format_observation (design B2): one row per (binding, envelope, detected version, shape
-- fingerprint). A new row is the "new version observed" signal LOG-03 alerts on.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS format_observation (
    binding_id      VARCHAR(128) NOT NULL REFERENCES source_binding (binding_id),
    envelope        VARCHAR(64)  NOT NULL,
    schema_version  VARCHAR(64)  NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL DEFAULT '',
    first_seen      TIMESTAMPTZ  NOT NULL,
    last_seen       TIMESTAMPTZ  NOT NULL,
    record_count    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_format_observation PRIMARY KEY (binding_id, envelope, schema_version, fingerprint)
);

-- ---------------------------------------------------------------------------------------------
-- 5. quarantine_record (design B3, log-event spec section 9). Mirrors
-- dlap.log.v1.LogQuarantineRecord. Written by the quarantine sink of LOG-03; default retention
-- 30 days by quarantined_at. reason_detail never quotes record values; raw_excerpt is bounded
-- and has emails and IP addresses masked.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quarantine_record (
    quarantine_id          VARCHAR(64)   PRIMARY KEY,
    binding_id             VARCHAR(128)  NOT NULL REFERENCES source_binding (binding_id),
    source_provider        VARCHAR(32)   NOT NULL,
    source_kind            VARCHAR(64)   NOT NULL,
    envelope               VARCHAR(64)   NOT NULL,
    detected_version       VARCHAR(64)   NOT NULL DEFAULT '',
    pinned_version         VARCHAR(64)   NOT NULL DEFAULT '',
    parser_version         VARCHAR(160)  NOT NULL DEFAULT '',
    reason_code            VARCHAR(64)   NOT NULL,
    reason_detail          VARCHAR(1024) NOT NULL DEFAULT '',
    raw_ref                JSONB         NOT NULL DEFAULT '{}'::jsonb,
    raw_sha256             VARCHAR(64)   NOT NULL,
    raw_size_bytes         BIGINT        NOT NULL,
    raw_excerpt            TEXT          NOT NULL DEFAULT '',
    raw_truncated          BOOLEAN       NOT NULL DEFAULT FALSE,
    event_time             TIMESTAMPTZ,
    native_event_id        TEXT          NOT NULL DEFAULT '',
    pass_through_event_id  VARCHAR(64)   NOT NULL DEFAULT '',
    quarantined_at         TIMESTAMPTZ   NOT NULL,
    replayed_at            TIMESTAMPTZ,
    replay_count           INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_quarantine_record_binding_reason
    ON quarantine_record (binding_id, reason_code, quarantined_at);
CREATE INDEX IF NOT EXISTS idx_quarantine_record_quarantined_at
    ON quarantine_record (quarantined_at);

-- ---------------------------------------------------------------------------------------------
-- 6. backfill_job (design B6): bounded, resumable, at most one active job per binding.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backfill_job (
    id             UUID          PRIMARY KEY,
    binding_id     VARCHAR(128)  NOT NULL REFERENCES source_binding (binding_id),
    stream_id      VARCHAR(128)  NOT NULL,
    from_ts        TIMESTAMPTZ   NOT NULL,
    to_ts          TIMESTAMPTZ   NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    progress       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    requested_by   VARCHAR(256)  NOT NULL,
    requested_at   TIMESTAMPTZ   NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    error_detail   VARCHAR(1024),
    CONSTRAINT ck_backfill_job_window CHECK (from_ts < to_ts),
    CONSTRAINT ck_backfill_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_backfill_job_active
    ON backfill_job (binding_id) WHERE status IN ('PENDING', 'RUNNING');

-- ---------------------------------------------------------------------------------------------
-- 7. shedlock: the column set ShedLock's JdbcTemplateLockProvider requires, unchanged (same as
-- da-discovery V10). Timestamps are UTC, hence TIMESTAMP without time zone. Not an entity.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- ---------------------------------------------------------------------------------------------
-- 8. log_events, partitioned by month of ingest_time (critic item 20, ADR-0060).
--
-- Retention drops whole partitions (LogEventRetentionSweeper) instead of deleting rows, and it
-- keys on ingest_time, so a backfill of old events is not pruned the night it lands
-- (log-event spec section 14). A partitioned table's primary key must contain the partition
-- key, so the key is (event_id, ingest_time); event_id stays unique because every writer takes a
-- transaction-scoped advisory lock on the id before it checks and inserts (LogEventIdLock).
--
-- Partitions are log_events_pYYYY_MM covering [first of month, first of next month) in UTC.
-- log_events_default catches rows of a month whose partition does not exist yet; the sweeper
-- moves them into the month's partition when it creates it.
--
-- The legacy columns keep their names and types (LogEventEntity maps them unchanged); the
-- registry columns are nullable, so legacy rows leave them empty.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE log_events RENAME TO log_events_v2;
ALTER TABLE log_events_v2 RENAME CONSTRAINT log_events_pkey TO log_events_v2_pkey;
ALTER INDEX IF EXISTS idx_log_events_event_time RENAME TO idx_log_events_v2_event_time;
ALTER INDEX IF EXISTS idx_log_events_source_provider RENAME TO idx_log_events_v2_source_provider;
ALTER INDEX IF EXISTS idx_log_events_change_type RENAME TO idx_log_events_v2_change_type;
ALTER INDEX IF EXISTS idx_log_events_target_resource_id RENAME TO idx_log_events_v2_target_resource_id;
ALTER INDEX IF EXISTS idx_log_events_target_time RENAME TO idx_log_events_v2_target_time;

CREATE TABLE log_events (
    event_id              VARCHAR(64)  NOT NULL,
    event_time            TIMESTAMPTZ  NOT NULL,
    ingest_time           TIMESTAMPTZ  NOT NULL,
    source_provider       VARCHAR(32)  NOT NULL,
    source_service        VARCHAR(128),
    change_type           VARCHAR(32)  NOT NULL,
    operation             VARCHAR(256) NOT NULL,
    result_status         VARCHAR(32),
    principal_id          VARCHAR(256),
    principal_type        VARCHAR(32),
    target_resource_id    VARCHAR(512),
    target_resource_kind  VARCHAR(128),
    target_project        VARCHAR(128),
    target_region         VARCHAR(64),
    schema_version        VARCHAR(8)   NOT NULL,
    raw_event             JSONB,
    -- Registry pipeline (log-event spec section 4.1 fields 14 to 26); empty on legacy rows.
    binding_id            VARCHAR(128),
    source_format         VARCHAR(160),
    source_schema_version VARCHAR(64),
    parser_version        VARCHAR(160),
    native_event_id       TEXT,
    observed_time         TIMESTAMPTZ,
    event_category        VARCHAR(48),
    origin                VARCHAR(32),
    parse_status          VARCHAR(32),
    parse_warnings        VARCHAR(512),
    target_canonical_uri  TEXT,
    shared_event_id       VARCHAR(256),
    supersedes_event_id   VARCHAR(64),
    CONSTRAINT log_events_pkey PRIMARY KEY (event_id, ingest_time)
) PARTITION BY RANGE (ingest_time);

CREATE TABLE IF NOT EXISTS log_events_default PARTITION OF log_events DEFAULT;

-- One partition per month from the oldest stored row (at most 240 months back) to two months
-- ahead of the database clock. Rows outside that span land in log_events_default.
DO $$
DECLARE
    oldest        TIMESTAMPTZ;
    first_month   DATE;
    last_month    DATE;
    m             DATE;
BEGIN
    SELECT min(ingest_time) INTO oldest FROM dlap_logingest.log_events_v2;
    last_month  := (date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '2 months')::date;
    first_month := date_trunc('month', COALESCE(LEAST(oldest, now()), now()) AT TIME ZONE 'UTC')::date;
    IF first_month < (last_month - INTERVAL '240 months')::date THEN
        first_month := (last_month - INTERVAL '240 months')::date;
    END IF;
    m := first_month;
    WHILE m <= last_month LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS dlap_logingest.%I PARTITION OF dlap_logingest.log_events '
                || 'FOR VALUES FROM (%L) TO (%L)',
            'log_events_p' || to_char(m, 'YYYY_MM'),
            to_char(m, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char((m + INTERVAL '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00');
        m := (m + INTERVAL '1 month')::date;
    END LOOP;
END
$$;

INSERT INTO log_events (
    event_id, event_time, ingest_time, source_provider, source_service, change_type, operation,
    result_status, principal_id, principal_type, target_resource_id, target_resource_kind,
    target_project, target_region, schema_version, raw_event)
SELECT
    event_id, event_time, ingest_time, source_provider, source_service, change_type, operation,
    result_status, principal_id, principal_type, target_resource_id, target_resource_kind,
    target_project, target_region, schema_version, raw_event
FROM log_events_v2;

DROP TABLE log_events_v2;

-- The V1 indexes, re-created on the partitioned parent (each partition gets its own copy), plus
-- the registry's per-binding lookup. The primary key (event_id, ingest_time) already serves the
-- dedup lookup by event_id. target_canonical_uri is not indexed: a canonical URI can exceed the
-- btree row limit (an Azure blob name is up to 1024 characters of up to 3 bytes each).
CREATE INDEX IF NOT EXISTS idx_log_events_event_time ON log_events (event_time);
CREATE INDEX IF NOT EXISTS idx_log_events_source_provider ON log_events (source_provider);
CREATE INDEX IF NOT EXISTS idx_log_events_change_type ON log_events (change_type);
CREATE INDEX IF NOT EXISTS idx_log_events_target_resource_id ON log_events (target_resource_id);
CREATE INDEX IF NOT EXISTS idx_log_events_target_time ON log_events (target_resource_id, event_time);
CREATE INDEX IF NOT EXISTS idx_log_events_binding_time ON log_events (binding_id, event_time);
