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

-- Upgrade delta for dlap_common (single source: init-scripts/02-create-common-schema.sql).
--
-- ADR-0046 / CAT-01: asset time, size and retention.
--   1. Widens ck_asset_cloud_provider_valid with DATABRICKS and SNOWFLAKE, and
--      ck_asset_service_type_valid with the kinds frozen in wave 1 (S3_OBJECT,
--      GLUE_DATABASE, GLUE_TABLE, GCS_OBJECT, BLOB_CONTAINER, BLOB_OBJECT,
--      UNITY_VOLUME, SNOWFLAKE_TABLE).
--   2. Adds the creation, size, activity, lifecycle and retention columns, their
--      value checks, sort and filter indexes, and column comments.
--
-- No backfill. New time columns start NULL (unknown) and are filled from event-time
-- evidence by CAT-02, DISC-02 and CAT-03. dlap_usage_metrics.lastAccessedAt is not
-- copied: it holds DLAP processing time, not event time. retention_status starts
-- UNKNOWN and legal_hold false on every existing row.
--
-- Idempotent: safe to re-run. Apply once to any dlap_common database provisioned
-- before this delta; fresh databases get the same result from 02 directly.
--
--   psql "$DLAP_COMMON_DB_URL" -v ON_ERROR_STOP=1 \
--        -f deployment/platform/database/upgrades/2026-10-18_asset_time_size_retention.sql
--
-- Run it as the table owner (da_catalog_user) or a superuser.

SET search_path TO dlap_common;

-- 1. Provider and service-type lists (same order as 02-create-common-schema.sql).
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_cloud_provider_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_cloud_provider_valid CHECK (cloud_provider IN (
    'AWS', 'GCP', 'AZURE', 'OCI', 'UNITY', 'DATABRICKS', 'SNOWFLAKE'
));

ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_service_type_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_service_type_valid CHECK (service_type IN (
    'S3_BUCKET', 'RDS_INSTANCE', 'REDSHIFT_CLUSTER', 'DYNAMODB_TABLE',
    'GCS_BUCKET', 'BIGQUERY_DATASET', 'BIGQUERY_TABLE', 'CLOUD_SQL_INSTANCE',
    'BLOB_STORAGE', 'SQL_DATABASE', 'COSMOS_DB', 'DATA_LAKE',
    'UNITY_CATALOG', 'UNITY_SCHEMA', 'UNITY_TABLE',
    'SPARK_JOB', 'DATAFLOW_JOB', 'BIGQUERY_JOB', 'DBT_MODEL',
    'REDSHIFT_QUERY', 'DATABRICKS_JOB', 'PYTHON_JOB',
    'S3_OBJECT', 'GLUE_DATABASE', 'GLUE_TABLE', 'GCS_OBJECT',
    'BLOB_CONTAINER', 'BLOB_OBJECT', 'UNITY_VOLUME', 'SNOWFLAKE_TABLE'
));

-- 2. Time, size and retention: identical to the block in 02-create-common-schema.sql.
-- Creation and size
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS cloud_created_at          TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS cloud_created_source      VARCHAR(64);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS size_bytes                BIGINT;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS size_observed_at          TIMESTAMP WITH TIME ZONE;
-- Activity
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS last_accessed_at          TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS last_access_source        VARCHAR(64);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS last_modified_at          TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS last_permission_change_at TIMESTAMP WITH TIME ZONE;
-- Lifecycle
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS lifecycle_event_time      TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS recreated_at              TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS discovery_source          VARCHAR(64);
-- Retention. Evaluation output only: no rule input is stored on the asset row. Rules
-- stay at their source (spec "Retention > Rule"). retention_days, _basis, _source and
-- _policy_id name the rule that decided the last evaluation, and the evaluator rewrites
-- them on every run. legal_hold is not output: only an audited hold action changes it.
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_days            INTEGER;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_basis           VARCHAR(20);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_source          VARCHAR(64);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_policy_id       UUID;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_expires_at      TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_status          VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS retention_evaluated_at    TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS legal_hold                BOOLEAN NOT NULL DEFAULT FALSE;

-- Value checks. Drop-then-add keeps a re-run convergent.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_size_bytes_nonneg;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_size_bytes_nonneg CHECK (size_bytes >= 0);
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_retention_days_nonneg;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_retention_days_nonneg CHECK (retention_days >= 0);
-- Values of com.dlap.common.model.RetentionBasis
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_retention_basis_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_retention_basis_valid CHECK (retention_basis IN (
    'CREATED', 'LAST_MODIFIED', 'LAST_ACCESSED', 'FIXED_DATE'
));
-- Values of com.dlap.common.model.RetentionStatus
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_retention_status_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_retention_status_valid CHECK (retention_status IN (
    'UNKNOWN', 'NO_POLICY', 'RETAINED', 'DUE', 'ON_HOLD'
));
-- A held asset always reads ON_HOLD, so no reader can see it as DUE.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_legal_hold_status;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_legal_hold_status CHECK (NOT legal_hold OR retention_status = 'ON_HOLD');

-- Sort and filter indexes for the activity and expiry reports and the retention sweep.
CREATE INDEX IF NOT EXISTS idx_asset_cloud_created_at         ON dlap_assets (cloud_created_at);
CREATE INDEX IF NOT EXISTS idx_asset_size_bytes               ON dlap_assets (size_bytes);
CREATE INDEX IF NOT EXISTS idx_asset_last_accessed_at         ON dlap_assets (last_accessed_at);
CREATE INDEX IF NOT EXISTS idx_asset_last_modified_at         ON dlap_assets (last_modified_at);
CREATE INDEX IF NOT EXISTS idx_asset_retention_expires_at     ON dlap_assets (retention_expires_at);
CREATE INDEX IF NOT EXISTS idx_asset_retention_status_expires ON dlap_assets (retention_status, retention_expires_at);
CREATE INDEX IF NOT EXISTS idx_asset_retention_evaluated_at   ON dlap_assets (retention_evaluated_at);
CREATE INDEX IF NOT EXISTS idx_asset_legal_hold               ON dlap_assets (asset_id) WHERE legal_hold;

COMMENT ON COLUMN dlap_assets.cloud_created_at IS 'Provider creation time of the current incarnation; least known value (ADR-0046)';
COMMENT ON COLUMN dlap_assets.cloud_created_source IS 'Evidence channel for cloud_created_at, e.g. CRAWLER or AUDIT_LOG:GCP_CLOUD_AUDIT';
COMMENT ON COLUMN dlap_assets.size_bytes IS 'Latest observed size in bytes; NULL unknown, 0 is a real size';
COMMENT ON COLUMN dlap_assets.size_observed_at IS 'Business time at which size_bytes was true';
COMMENT ON COLUMN dlap_assets.last_accessed_at IS 'Max event time of a successful data read (ADR-0045 categories DATA_READ, DATA_READ_OBSERVED)';
COMMENT ON COLUMN dlap_assets.last_access_source IS 'Evidence channel for last_accessed_at';
COMMENT ON COLUMN dlap_assets.last_modified_at IS 'Max event time of a successful content write; not the DLAP row time (last_modified_ts)';
COMMENT ON COLUMN dlap_assets.last_permission_change_at IS 'Max event time of a successful permission change';
COMMENT ON COLUMN dlap_assets.lifecycle_event_time IS 'Event time of the last applied status transition; older transitions are ignored';
COMMENT ON COLUMN dlap_assets.recreated_at IS 'Event time of a CREATE applied after DELETED; the incarnation boundary';
COMMENT ON COLUMN dlap_assets.discovery_source IS 'Channel through which DLAP first learned of the asset';
COMMENT ON COLUMN dlap_assets.retention_days IS 'Deciding rule of the last evaluation: period in days; NULL for FIXED_DATE. Output only (ADR-0046)';
COMMENT ON COLUMN dlap_assets.retention_basis IS 'Deciding rule of the last evaluation: what the period counts from (com.dlap.common.model.RetentionBasis). Output only';
COMMENT ON COLUMN dlap_assets.retention_source IS 'Deciding rule of the last evaluation: POLICY, LABEL_DEFAULT, PROVIDER or MANUAL. Output only';
COMMENT ON COLUMN dlap_assets.retention_policy_id IS 'Deciding rule of the last evaluation: the policy-engine policy, when retention_source is POLICY. Output only';
COMMENT ON COLUMN dlap_assets.retention_expires_at IS 'Expiry the last evaluation found: exact (for FIXED_DATE, the date of the rule) or the proving bound; NULL for UNKNOWN and NO_POLICY. Output only';
COMMENT ON COLUMN dlap_assets.retention_status IS 'Retention state as of retention_evaluated_at (com.dlap.common.model.RetentionStatus)';
COMMENT ON COLUMN dlap_assets.retention_evaluated_at IS 'As-of instant of the last retention evaluation (evaluator clock)';
COMMENT ON COLUMN dlap_assets.legal_hold IS 'DLAP legal hold; when true nothing may retire the asset';

RESET search_path;
