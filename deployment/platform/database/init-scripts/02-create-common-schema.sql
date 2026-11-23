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

-- DLAP Common Schema Creation Script
-- Creates tables for entities shared across all microservices

-- Target the shared dlap_common database (services connect to it via
-- jdbc:.../dlap_common). The entrypoint runs this against the default
-- POSTGRES_DB, so connect explicitly and ensure the schema exists.
\c dlap_common
CREATE SCHEMA IF NOT EXISTS dlap_common;

-- Ensure we're using the dlap_common schema
SET search_path TO dlap_common;

-- Table: dlap_users
-- Stores DLAP-specific user information linked to Keycloak users
CREATE TABLE IF NOT EXISTS dlap_users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_subject_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    full_name VARCHAR(255),
    roles TEXT[] DEFAULT '{}',
    profile_photo_url VARCHAR(2048),
    job_title VARCHAR(255),
    department VARCHAR(255),
    phone_number VARCHAR(50),
    created_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT ck_user_email_format CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'),
    CONSTRAINT ck_user_roles_valid CHECK (roles <@ ARRAY['ADMIN', 'DATA_ENGINEER', 'DATA_ANALYST', 'POLICY_MANAGER', 'AUDITOR', 'CDO', 'VP', 'VIEWER'])
);

-- Indexes for dlap_users
CREATE INDEX IF NOT EXISTS idx_user_keycloak_subject_id ON dlap_users(keycloak_subject_id);
CREATE INDEX IF NOT EXISTS idx_user_email ON dlap_users(email);
CREATE INDEX IF NOT EXISTS idx_user_roles ON dlap_users USING GIN(roles);
CREATE INDEX IF NOT EXISTS idx_user_department ON dlap_users(department);
CREATE INDEX IF NOT EXISTS idx_user_job_title ON dlap_users(job_title);

-- Table: dlap_labels
-- Defines the taxonomy of labels that can be applied to assets
CREATE TABLE IF NOT EXISTS dlap_labels (
    label_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label_name VARCHAR(255) UNIQUE NOT NULL,
    label_category VARCHAR(100),
    description TEXT,
    created_by_user_id UUID,
    created_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Foreign key to users table
    CONSTRAINT fk_label_created_by_user FOREIGN KEY (created_by_user_id) REFERENCES dlap_users(user_id) ON DELETE SET NULL,
    
    -- Constraints
    CONSTRAINT ck_label_category_valid CHECK (label_category IN ('Sensitivity', 'Compliance', 'Data Type', 'Business Domain', 'Governance', 'Classification'))
);

-- Indexes for dlap_labels
CREATE UNIQUE INDEX IF NOT EXISTS idx_label_name ON dlap_labels(label_name);
CREATE INDEX IF NOT EXISTS idx_label_category ON dlap_labels(label_category);
CREATE INDEX IF NOT EXISTS idx_label_created_by ON dlap_labels(created_by_user_id);

-- Table: dlap_assets
-- Central repository for metadata about discovered data assets
CREATE TABLE IF NOT EXISTS dlap_assets (
    asset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_uri VARCHAR(2048) UNIQUE NOT NULL,
    cloud_provider VARCHAR(20) NOT NULL,
    service_type VARCHAR(50) NOT NULL,
    asset_name VARCHAR(255),
    description TEXT,
    technical_metadata JSONB DEFAULT '{}',
    dlap_discovered_ts TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    dlap_last_scanned_ts TIMESTAMP WITH TIME ZONE,
    dlap_usage_metrics JSONB DEFAULT '{}',
    dlap_policy_status JSONB DEFAULT '{}',
    dlap_lineage_info JSONB DEFAULT '{}',
    owner_info TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Constraints. Existing databases get the provider and service-type lists
    -- from upgrades/2026-10-18_asset_time_size_retention.sql; keep both in step.
    CONSTRAINT ck_asset_cloud_provider_valid CHECK (cloud_provider IN (
        'AWS', 'GCP', 'AZURE', 'OCI', 'UNITY', 'DATABRICKS', 'SNOWFLAKE'
    )),
    CONSTRAINT ck_asset_status_valid CHECK (status IN ('ACTIVE', 'ARCHIVED', 'PENDING_DELETION', 'DELETED')),
    CONSTRAINT ck_asset_service_type_valid CHECK (service_type IN (
        'S3_BUCKET', 'RDS_INSTANCE', 'REDSHIFT_CLUSTER', 'DYNAMODB_TABLE',
        'GCS_BUCKET', 'BIGQUERY_DATASET', 'BIGQUERY_TABLE', 'CLOUD_SQL_INSTANCE',
        'BLOB_STORAGE', 'SQL_DATABASE', 'COSMOS_DB', 'DATA_LAKE',
        'UNITY_CATALOG', 'UNITY_SCHEMA', 'UNITY_TABLE',
        -- Processing engines / jobs (C7): jobs are first-class assets so
        -- log-derived lineage can attach a real job endpoint (read -> job -> write).
        'SPARK_JOB', 'DATAFLOW_JOB', 'BIGQUERY_JOB', 'DBT_MODEL',
        'REDSHIFT_QUERY', 'DATABRICKS_JOB', 'PYTHON_JOB',
        -- Object, container and table kinds frozen in wave 1 (ADR-0046, ADR-0047).
        -- Object kinds get rows only under a governed prefix (ADR-0046).
        'S3_OBJECT', 'GLUE_DATABASE', 'GLUE_TABLE', 'GCS_OBJECT',
        'BLOB_CONTAINER', 'BLOB_OBJECT', 'UNITY_VOLUME', 'SNOWFLAKE_TABLE'
    ))
);

-- Indexes for dlap_assets
CREATE UNIQUE INDEX IF NOT EXISTS idx_asset_uri ON dlap_assets(asset_uri);
CREATE INDEX IF NOT EXISTS idx_asset_cloud_provider ON dlap_assets(cloud_provider);
CREATE INDEX IF NOT EXISTS idx_asset_service_type ON dlap_assets(service_type);
CREATE INDEX IF NOT EXISTS idx_asset_status ON dlap_assets(status);
CREATE INDEX IF NOT EXISTS idx_asset_name ON dlap_assets(asset_name);
CREATE INDEX IF NOT EXISTS idx_asset_discovered_ts ON dlap_assets(dlap_discovered_ts);
CREATE INDEX IF NOT EXISTS idx_asset_last_scanned_ts ON dlap_assets(dlap_last_scanned_ts);
CREATE INDEX IF NOT EXISTS idx_asset_owner_info ON dlap_assets USING GIN(to_tsvector('english', owner_info));

-- GIN indexes for JSONB fields
CREATE INDEX IF NOT EXISTS idx_asset_technical_metadata ON dlap_assets USING GIN(technical_metadata);
CREATE INDEX IF NOT EXISTS idx_asset_usage_metrics ON dlap_assets USING GIN(dlap_usage_metrics);
CREATE INDEX IF NOT EXISTS idx_asset_policy_status ON dlap_assets USING GIN(dlap_policy_status);
CREATE INDEX IF NOT EXISTS idx_asset_lineage_info ON dlap_assets USING GIN(dlap_lineage_info);

-- Nullable FK-related columns for Asset entity (da-protos); required before idx below (M1-19)
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS cloud_connection_id UUID;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS created_by_user_id UUID;

-- Remaining Asset entity columns (da-protos com.dlap.common.model.entity.Asset).
-- These must exist or da-catalog's Hibernate ddl-auto=validate fails on boot.
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS asset_type        VARCHAR(50);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS native_asset_id   VARCHAR(500);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS region            VARCHAR(50);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS business_metadata JSONB DEFAULT '{}';
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS details_json      JSONB DEFAULT '{}';
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS discovered_at     TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS last_scanned_at   TIMESTAMP WITH TIME ZONE;

-- FK indexes for dlap_assets (M1-19: names match @Index on Asset.java)
CREATE INDEX IF NOT EXISTS idx_asset_cloud_connection ON dlap_assets (cloud_connection_id);
CREATE INDEX IF NOT EXISTS idx_asset_created_by_user ON dlap_assets (created_by_user_id);

-- =============================================================================
-- Asset time, size and retention (ADR-0046, CAT-01; spec docs/specs/asset-descriptor.md).
-- Every time here is a business time from provider evidence (ADR-0045 business-time
-- rule), never a DLAP clock; retention_evaluated_at is the one exception. NULL means
-- unknown, and unknown is never treated as stale or as fresh. dlap_usage_metrics
-- keeps its lastAccessedAt key until CAT-02 moves its readers to last_accessed_at.
-- Existing databases get this block from
-- upgrades/2026-10-18_asset_time_size_retention.sql; keep the two identical.
-- =============================================================================
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

-- =============================================================================
-- Per-asset read coverage (COV-01; spec docs/specs/asset-descriptor.md "Read coverage").
-- Whether the provider configuration and DLAP's log bindings prove that data reads of the
-- asset are recorded: FULL, PARTIAL or NONE, with reasons. NULL is unknown (no observation
-- yet, or the collector lacked a permission, which the reasons name). Written only by the
-- da-catalog coverage applier; the JPA entity maps these columns read-only.
-- Existing databases get this block from upgrades/2026-10-20_asset_read_coverage.sql; keep
-- the two identical.
-- =============================================================================
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage             VARCHAR(10);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage_reasons     JSONB;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage_observed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage_since       TIMESTAMP WITH TIME ZONE;

ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_read_coverage_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_read_coverage_valid CHECK (read_coverage IN (
    'FULL', 'PARTIAL', 'NONE'
));
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_read_coverage_reasons_array;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_read_coverage_reasons_array CHECK (
    read_coverage_reasons IS NULL OR jsonb_typeof(read_coverage_reasons) = 'array'
);
-- A coverage start exists only while the coverage is FULL.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_read_coverage_since_full;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_read_coverage_since_full CHECK (
    read_coverage_since IS NULL OR read_coverage = 'FULL'
);

CREATE INDEX IF NOT EXISTS idx_asset_read_coverage ON dlap_assets (read_coverage);

COMMENT ON COLUMN dlap_assets.read_coverage IS 'Read-logging coverage of the asset: FULL, PARTIAL or NONE; NULL unknown (COV-01). Only FULL lets a report call the asset not read';
COMMENT ON COLUMN dlap_assets.read_coverage_reasons IS 'JSON array of strings: why the coverage is PARTIAL, NONE or unknown (a missing permission is named), or the evidence for FULL';
COMMENT ON COLUMN dlap_assets.read_coverage_observed_at IS 'When the provider configuration behind read_coverage was read (collector clock); an older observation never replaces a newer one';
COMMENT ON COLUMN dlap_assets.read_coverage_since IS 'First observation of the current unbroken run of FULL observations; NULL unless read_coverage is FULL';

-- Table: dlap_asset_label_assignments
-- Join table for the many-to-many relationship between assets and labels
CREATE TABLE IF NOT EXISTS dlap_asset_label_assignments (
    asset_id UUID NOT NULL,
    label_id UUID NOT NULL,
    assigned_by_user_id UUID,
    assigned_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    confidence_score DOUBLE PRECISION CHECK (confidence_score >= 0.0 AND confidence_score <= 1.0),
    source VARCHAR(50) DEFAULT 'SYSTEM',
    
    -- Primary key
    PRIMARY KEY (asset_id, label_id),
    
    -- Foreign keys
    CONSTRAINT fk_assignment_asset FOREIGN KEY (asset_id) REFERENCES dlap_assets(asset_id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_label FOREIGN KEY (label_id) REFERENCES dlap_labels(label_id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_user FOREIGN KEY (assigned_by_user_id) REFERENCES dlap_users(user_id) ON DELETE SET NULL,
    
    -- Constraints
    CONSTRAINT ck_assignment_source_valid CHECK (source IN ('USER', 'SYSTEM', 'SMART_LABEL_ML', 'SMART_LABEL_RULE', 'POLICY_ENGINE'))
);

-- Indexes for dlap_asset_label_assignments
CREATE INDEX IF NOT EXISTS idx_assignment_asset_id ON dlap_asset_label_assignments(asset_id);
CREATE INDEX IF NOT EXISTS idx_assignment_label_id ON dlap_asset_label_assignments(label_id);
CREATE INDEX IF NOT EXISTS idx_assignment_assigned_by ON dlap_asset_label_assignments(assigned_by_user_id);
CREATE INDEX IF NOT EXISTS idx_assignment_source ON dlap_asset_label_assignments(source);
CREATE INDEX IF NOT EXISTS idx_assignment_assigned_ts ON dlap_asset_label_assignments(assigned_ts);
CREATE INDEX IF NOT EXISTS idx_assignment_confidence_score ON dlap_asset_label_assignments(confidence_score);

-- Create triggers to automatically update last_modified_ts
CREATE OR REPLACE FUNCTION update_last_modified_ts()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_modified_ts = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply triggers to tables with last_modified_ts. DROP ... IF EXISTS first so a
-- re-run of this script does not fail on an existing trigger.
DROP TRIGGER IF EXISTS tr_user_update_timestamp ON dlap_users;
CREATE TRIGGER tr_user_update_timestamp
    BEFORE UPDATE ON dlap_users
    FOR EACH ROW
    EXECUTE FUNCTION update_last_modified_ts();

DROP TRIGGER IF EXISTS tr_label_update_timestamp ON dlap_labels;
CREATE TRIGGER tr_label_update_timestamp
    BEFORE UPDATE ON dlap_labels
    FOR EACH ROW
    EXECUTE FUNCTION update_last_modified_ts();

DROP TRIGGER IF EXISTS tr_asset_update_timestamp ON dlap_assets;
CREATE TRIGGER tr_asset_update_timestamp
    BEFORE UPDATE ON dlap_assets
    FOR EACH ROW
    EXECUTE FUNCTION update_last_modified_ts();

-- Insert some default system labels
INSERT INTO dlap_labels (label_name, label_category, description, created_by_user_id) VALUES
    ('PII', 'Sensitivity', 'Personally Identifiable Information', NULL),
    ('PHI', 'Sensitivity', 'Protected Health Information', NULL),
    ('Public', 'Sensitivity', 'Public information', NULL),
    ('Internal', 'Sensitivity', 'Internal use only', NULL),
    ('Confidential', 'Sensitivity', 'Confidential information', NULL),
    ('Restricted', 'Sensitivity', 'Restricted access information', NULL),
    
    ('GDPR', 'Compliance', 'General Data Protection Regulation', NULL),
    ('HIPAA', 'Compliance', 'Health Insurance Portability and Accountability Act', NULL),
    ('SOX', 'Compliance', 'Sarbanes-Oxley Act', NULL),
    ('PCI-DSS', 'Compliance', 'Payment Card Industry Data Security Standard', NULL),
    
    ('Customer Data', 'Data Type', 'Customer related data', NULL),
    ('Financial Data', 'Data Type', 'Financial information', NULL),
    ('Employee Data', 'Data Type', 'Employee information', NULL),
    ('Operational Data', 'Data Type', 'Operational metrics and logs', NULL),
    ('Analytics Data', 'Data Type', 'Data used for analytics and reporting', NULL),
    
    ('Sales', 'Business Domain', 'Sales department data', NULL),
    ('Marketing', 'Business Domain', 'Marketing department data', NULL),
    ('Finance', 'Business Domain', 'Finance department data', NULL),
    ('HR', 'Business Domain', 'Human Resources data', NULL),
    ('IT', 'Business Domain', 'Information Technology data', NULL)
ON CONFLICT (label_name) DO NOTHING;

-- =============================================================================
-- CloudResource (ADR-0028): richer, optional model that complements dlap_assets.
-- Asset stays the canonical lightweight asset; CloudResource carries
-- provider-native detail: hierarchy (parent_resource_id/has_children),
-- cross-region replication (replica_regions), and the raw provider payload
-- (provider_native_json). asset_id links a resource to its canonical Asset.
-- =============================================================================
CREATE TABLE IF NOT EXISTS dlap_cloud_resources (
    cloud_resource_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id             UUID REFERENCES dlap_assets(asset_id) ON DELETE SET NULL,
    cloud_provider       VARCHAR(20) NOT NULL,
    service_id           VARCHAR(64),
    resource_type        VARCHAR(100),
    native_resource_id   VARCHAR(1024),
    resource_uri         VARCHAR(2048),
    name                 VARCHAR(512),
    description          TEXT,
    account_id           VARCHAR(255),
    project_id           VARCHAR(255),
    region               VARCHAR(64),
    zone                 VARCHAR(64),
    location             VARCHAR(255),
    parent_resource_id   UUID,
    has_children         BOOLEAN,
    replica_regions      JSONB,
    tags                 JSONB,
    provider_native_json JSONB,
    first_discovered_ts  TIMESTAMP WITH TIME ZONE,
    last_discovered_ts   TIMESTAMP WITH TIME ZONE,
    created_ts           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_modified_ts     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cloud_resource_native_id      ON dlap_cloud_resources (native_resource_id);
CREATE INDEX IF NOT EXISTS idx_cloud_resource_cloud_provider ON dlap_cloud_resources (cloud_provider);
CREATE INDEX IF NOT EXISTS idx_cloud_resource_asset          ON dlap_cloud_resources (asset_id);
CREATE INDEX IF NOT EXISTS idx_cloud_resource_parent         ON dlap_cloud_resources (parent_resource_id);
CREATE INDEX IF NOT EXISTS idx_cloud_resource_account        ON dlap_cloud_resources (account_id);

-- Grant permissions to service users (these users should be created in the previous script)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_catalog_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_discovery_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_policyengine_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_reporting_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_autolabel_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_autoarchival_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_autodelete_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_autocompliance_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dlap_common TO da_admin_user;

-- Grant sequence permissions
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_catalog_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_discovery_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_policyengine_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_reporting_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_autolabel_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_autoarchival_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_autodelete_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_autocompliance_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA dlap_common TO da_admin_user;

-- Schema USAGE is REQUIRED for the table/sequence grants above to be usable.
-- Without USAGE on the schema itself, every access fails with
-- "permission denied for schema dlap_common" regardless of table grants.
GRANT USAGE ON SCHEMA dlap_common TO
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;

-- da-catalog connects to THIS shared database (jdbc:.../dlap_common) because the
-- canonical Asset/Label/User entities live in the dlap_common schema here. It also
-- needs a home for its own service-private tables (e.g. dlap_asset_lineage), which
-- Hibernate ddl-auto=update creates on boot. Give it the dlap_catalog schema, owned
-- by da_catalog_user.
--
-- CREATE on the database is REQUIRED even though we pre-create the schema below:
-- da-catalog's Flyway migration V1 issues `CREATE SCHEMA IF NOT EXISTS dlap_catalog`,
-- and Postgres checks the CREATE-on-database privilege BEFORE the IF-NOT-EXISTS
-- short-circuits, so without this grant da-catalog crash-loops on boot.
GRANT CREATE ON DATABASE dlap_common TO da_catalog_user;
CREATE SCHEMA IF NOT EXISTS dlap_catalog AUTHORIZATION da_catalog_user;

-- da-catalog is the asset manager (see 01-create-schemas-and-users.sql). Make it the
-- owner of the shared asset-domain tables so Hibernate ddl-auto=update can reconcile
-- them on boot (add columns / indexes) without "must be owner of table" errors.
-- Other services keep their explicit SELECT/INSERT/UPDATE/DELETE grants above.
ALTER TABLE dlap_assets                  OWNER TO da_catalog_user;
ALTER TABLE dlap_labels                  OWNER TO da_catalog_user;
ALTER TABLE dlap_users                   OWNER TO da_catalog_user;
ALTER TABLE dlap_asset_label_assignments OWNER TO da_catalog_user;
ALTER TABLE dlap_cloud_resources         OWNER TO da_catalog_user;

-- Upgrade ledger (ADR-0056). upgrades/run-upgrades.sh applies the dated deltas to a database
-- that already exists and records each file here with the SHA-256 of its content. A fresh
-- database starts with an empty ledger, so the first runner pass re-applies every delta (each
-- is idempotent) and records it. The runner creates this table itself on a database that
-- predates it; keep both definitions identical (CommonSchemaUpgradeTest compares them).
-- Service roles get no access to it. The GRANT ... ON ALL TABLES blocks above would include
-- the ledger when this script is run a second time, so the REVOKE below takes it away again.
CREATE TABLE IF NOT EXISTS dlap_common.schema_upgrades (
    file_name       VARCHAR(255)  PRIMARY KEY,
    checksum_sha256 CHAR(64)      NOT NULL,
    applied_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    applied_by      VARCHAR(128)  NOT NULL DEFAULT CURRENT_USER,
    CONSTRAINT ck_schema_upgrades_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);
COMMENT ON TABLE dlap_common.schema_upgrades IS
    'Ledger of applied upgrades/*.sql deltas written by upgrades/run-upgrades.sh (ADR-0056)';
REVOKE ALL ON TABLE dlap_common.schema_upgrades FROM
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;

-- =============================================================================
-- Tamper-evident audit log (AUD-01; normative spec docs/specs/audit-log.md).
-- One SHA-256 hash chain per stream. A row is accepted only when it extends the head of its
-- stream: seq is the previous seq plus one, prev_hash is the previous entry_hash (64 zeros for
-- seq 1) and entry_hash is the SHA-256 of the canonical hash input, which the insert trigger
-- recomputes. Rows are never updated, deleted or truncated: the triggers reject it for every
-- role, including the owner, and fire even under session_replication_role = replica (ENABLE
-- ALWAYS). Each stream has one writer role; the insert trigger refuses any other role.
-- Service roles get SELECT and INSERT only and own nothing here. A superuser can still disable
-- a trigger; the signed checkpoints that da-admin-service exports to object-lock storage are
-- what detect such a rewrite (spec section "Threat model").
-- Existing databases get this block from upgrades/2026-10-21_audit_log.sql; keep the two
-- identical (AuditLogSchemaContainerTest compares them).
-- =============================================================================
CREATE TABLE IF NOT EXISTS dlap_common.audit_streams (
    stream_id   VARCHAR(64) NOT NULL,
    writer_role VARCHAR(63) NOT NULL,
    description TEXT        NOT NULL,
    CONSTRAINT pk_audit_streams PRIMARY KEY (stream_id),
    CONSTRAINT ck_audit_streams_id CHECK (stream_id ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    CONSTRAINT ck_audit_streams_writer_role CHECK (writer_role ~ '^[a-z_][a-z0-9_]*$')
);

CREATE TABLE IF NOT EXISTS dlap_common.audit_log (
    stream_id       VARCHAR(64) NOT NULL,
    seq             BIGINT      NOT NULL,
    prev_hash       CHAR(64)    NOT NULL,
    entry_hash      CHAR(64)    NOT NULL,
    record          TEXT        NOT NULL,
    event_type      TEXT GENERATED ALWAYS AS (record::jsonb ->> 'eventType') STORED,
    target_id       TEXT GENERATED ALWAYS AS (record::jsonb -> 'target' ->> 'id') STORED,
    correlation_id  TEXT GENERATED ALWAYS AS (record::jsonb ->> 'correlationId') STORED,
    idempotency_key TEXT GENERATED ALWAYS AS (record::jsonb ->> 'idempotencyKey') STORED,
    inserted_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_audit_log PRIMARY KEY (stream_id, seq),
    CONSTRAINT fk_audit_log_stream FOREIGN KEY (stream_id) REFERENCES dlap_common.audit_streams (stream_id),
    CONSTRAINT ck_audit_log_seq_positive CHECK (seq >= 1),
    CONSTRAINT ck_audit_log_prev_hash_hex CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_log_entry_hash_hex CHECK (entry_hash ~ '^[0-9a-f]{64}$'),
    -- Canonical records are printable ASCII (every other character is a \u escape), so the
    -- hash input has one byte form in every client encoding.
    CONSTRAINT ck_audit_log_record_ascii CHECK (record ~ '^[ -~]+$'),
    CONSTRAINT ck_audit_log_record_object CHECK (jsonb_typeof(record::jsonb) = 'object')
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_log_idempotency
    ON dlap_common.audit_log (stream_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_log_target ON dlap_common.audit_log (target_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation ON dlap_common.audit_log (correlation_id);

-- The canonical hash input (spec section "Hash chain"): the ASCII text
-- 'DLAP-AUDIT-1' LF stream_id LF seq LF prev_hash LF record, hashed with SHA-256, lower-case hex.
-- Verifiers must not call this function: scripts/audit-verify.sh spells the same expression out
-- with pg_catalog functions, so a replaced function cannot hide a broken row.
CREATE OR REPLACE FUNCTION dlap_common.audit_entry_hash(p_stream_id TEXT, p_seq BIGINT,
        p_prev_hash TEXT, p_record TEXT)
    RETURNS TEXT
    LANGUAGE sql
    STABLE
    STRICT
AS $fn$
    SELECT pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
        'DLAP-AUDIT-1' || pg_catalog.chr(10) || p_stream_id || pg_catalog.chr(10)
        || p_seq::text || pg_catalog.chr(10) || p_prev_hash || pg_catalog.chr(10) || p_record,
        'UTF8')), 'hex')
$fn$;

CREATE OR REPLACE FUNCTION dlap_common.audit_log_before_insert()
    RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_writer    TEXT;
    v_last_seq  BIGINT;
    v_last_hash TEXT;
BEGIN
    SELECT s.writer_role INTO v_writer FROM dlap_common.audit_streams s WHERE s.stream_id = NEW.stream_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'audit stream "%" is not registered in dlap_common.audit_streams', NEW.stream_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.rolname = v_writer)
            OR NOT pg_catalog.pg_has_role(current_user, v_writer, 'MEMBER') THEN
        RAISE EXCEPTION 'role "%" may not write audit stream "%"', current_user, NEW.stream_id
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- One writer per stream at a time; writers take the same lock before they read the head.
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('dlap_common.audit_log:' || NEW.stream_id, 0));
    SELECT l.seq, l.entry_hash INTO v_last_seq, v_last_hash
        FROM dlap_common.audit_log l WHERE l.stream_id = NEW.stream_id ORDER BY l.seq DESC LIMIT 1;
    IF NOT FOUND THEN
        v_last_seq := 0;
        v_last_hash := pg_catalog.repeat('0', 64);
    END IF;
    IF NEW.seq <> v_last_seq + 1 OR NEW.prev_hash <> v_last_hash THEN
        RAISE EXCEPTION 'audit entry % of stream "%" does not extend the head (seq %)',
            NEW.seq, NEW.stream_id, v_last_seq
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.entry_hash <> dlap_common.audit_entry_hash(NEW.stream_id, NEW.seq, NEW.prev_hash, NEW.record) THEN
        RAISE EXCEPTION 'audit entry % of stream "%" has a wrong entry_hash', NEW.seq, NEW.stream_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    NEW.inserted_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION dlap_common.audit_reject_change()
    RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: audit rows are append-only (docs/specs/audit-log.md)',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END
$fn$;

DROP TRIGGER IF EXISTS tr_audit_log_chain ON dlap_common.audit_log;
CREATE TRIGGER tr_audit_log_chain
    BEFORE INSERT ON dlap_common.audit_log
    FOR EACH ROW EXECUTE FUNCTION dlap_common.audit_log_before_insert();
DROP TRIGGER IF EXISTS tr_audit_log_append_only ON dlap_common.audit_log;
CREATE TRIGGER tr_audit_log_append_only
    BEFORE UPDATE OR DELETE ON dlap_common.audit_log
    FOR EACH ROW EXECUTE FUNCTION dlap_common.audit_reject_change();
DROP TRIGGER IF EXISTS tr_audit_log_no_truncate ON dlap_common.audit_log;
CREATE TRIGGER tr_audit_log_no_truncate
    BEFORE TRUNCATE ON dlap_common.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION dlap_common.audit_reject_change();
DROP TRIGGER IF EXISTS tr_audit_streams_append_only ON dlap_common.audit_streams;
CREATE TRIGGER tr_audit_streams_append_only
    BEFORE UPDATE OR DELETE ON dlap_common.audit_streams
    FOR EACH ROW EXECUTE FUNCTION dlap_common.audit_reject_change();
DROP TRIGGER IF EXISTS tr_audit_streams_no_truncate ON dlap_common.audit_streams;
CREATE TRIGGER tr_audit_streams_no_truncate
    BEFORE TRUNCATE ON dlap_common.audit_streams
    FOR EACH STATEMENT EXECUTE FUNCTION dlap_common.audit_reject_change();
ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_chain;
ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only;
ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_no_truncate;
ALTER TABLE dlap_common.audit_streams ENABLE ALWAYS TRIGGER tr_audit_streams_append_only;
ALTER TABLE dlap_common.audit_streams ENABLE ALWAYS TRIGGER tr_audit_streams_no_truncate;

-- The planned writers (spec section "Streams"). A new stream is a new row, added by a delta;
-- a row is never changed, so a stream keeps its writer for life.
INSERT INTO dlap_common.audit_streams (stream_id, writer_role, description) VALUES
    ('retire.delete', 'da_autodelete_user', 'Delete path: requests, executions, restores (RET-02)'),
    ('retire.archive', 'da_autoarchival_user', 'Archive path: requests, executions, restores (RET-03)'),
    ('govern.approval', 'da_policyengine_user', 'Approval requests and decisions (APPR-01)'),
    ('govern.policy', 'da_policyengine_user', 'Policy versions and changes (AUD-02a)'),
    ('admin.access', 'da_admin_user', 'Role changes and identity-provider events (AUD-02b)'),
    ('report.export', 'da_reporting_user', 'Report and CSV exports (AUD-02b)')
ON CONFLICT (stream_id) DO NOTHING;

-- Privileges. Nobody but the owner (the bootstrap superuser) may change these tables; the
-- GRANT ... ON ALL TABLES blocks earlier in 02, and default privileges, may have granted more
-- on a re-run, so every such grant is taken away again before the narrow grants below.
REVOKE ALL ON TABLE dlap_common.audit_log, dlap_common.audit_streams FROM PUBLIC;
DO $do$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT DISTINCT a.grantee::regrole::text AS grantee
        FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) a
        WHERE c.oid IN ('dlap_common.audit_log'::regclass, 'dlap_common.audit_streams'::regclass)
          AND a.grantee <> 0
          AND a.grantee <> c.relowner
    LOOP
        EXECUTE pg_catalog.format('REVOKE ALL ON TABLE dlap_common.audit_log, dlap_common.audit_streams FROM %s',
            r.grantee);
    END LOOP;
END
$do$;
GRANT SELECT, INSERT ON TABLE dlap_common.audit_log TO
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;
GRANT SELECT ON TABLE dlap_common.audit_streams TO
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;

COMMENT ON TABLE dlap_common.audit_streams IS
    'Audit streams and their single writer role (AUD-01, docs/specs/audit-log.md); append-only';
COMMENT ON TABLE dlap_common.audit_log IS
    'Tamper-evident audit log: one SHA-256 hash chain per stream (AUD-01, docs/specs/audit-log.md); append-only';
COMMENT ON COLUMN dlap_common.audit_log.record IS
    'Canonical JSON audit record (printable ASCII); hashed exactly as stored, never re-serialized';
COMMENT ON COLUMN dlap_common.audit_log.entry_hash IS
    'SHA-256 of DLAP-AUDIT-1 LF stream_id LF seq LF prev_hash LF record, lower-case hex';
COMMENT ON COLUMN dlap_common.audit_log.inserted_at IS
    'Database clock at insert; informational, not hashed (the record carries occurredAt and recordedAt)';
-- End of the audit log block (AUD-01).

-- Comments for documentation
COMMENT ON TABLE dlap_users IS 'DLAP users with Keycloak integration';
COMMENT ON TABLE dlap_labels IS 'Label taxonomy for asset classification';
COMMENT ON TABLE dlap_assets IS 'Central catalog of discovered data assets';
COMMENT ON TABLE dlap_asset_label_assignments IS 'Many-to-many relationship between assets and labels';
COMMENT ON TABLE dlap_cloud_resources IS 'Richer optional provider-native resource model complementing dlap_assets (ADR-0028)';

COMMENT ON COLUMN dlap_users.keycloak_subject_id IS 'Link to Keycloak user subject ID';
COMMENT ON COLUMN dlap_assets.asset_uri IS 'Unique identifier in source cloud system';
COMMENT ON COLUMN dlap_asset_label_assignments.confidence_score IS 'ML model confidence score (0.0-1.0)';
COMMENT ON COLUMN dlap_asset_label_assignments.source IS 'Source of the label assignment';

-- Reset search path
RESET search_path; 