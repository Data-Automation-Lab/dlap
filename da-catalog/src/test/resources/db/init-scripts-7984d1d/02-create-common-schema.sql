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
    
    -- Constraints
    CONSTRAINT ck_asset_cloud_provider_valid CHECK (cloud_provider IN ('AWS', 'GCP', 'AZURE', 'OCI', 'UNITY')),
    CONSTRAINT ck_asset_status_valid CHECK (status IN ('ACTIVE', 'ARCHIVED', 'PENDING_DELETION', 'DELETED')),
    CONSTRAINT ck_asset_service_type_valid CHECK (service_type IN (
        'S3_BUCKET', 'RDS_INSTANCE', 'REDSHIFT_CLUSTER', 'DYNAMODB_TABLE',
        'GCS_BUCKET', 'BIGQUERY_DATASET', 'BIGQUERY_TABLE', 'CLOUD_SQL_INSTANCE',
        'BLOB_STORAGE', 'SQL_DATABASE', 'COSMOS_DB', 'DATA_LAKE',
        'UNITY_CATALOG', 'UNITY_SCHEMA', 'UNITY_TABLE',
        -- Processing engines / jobs (C7): jobs are first-class assets so
        -- log-derived lineage can attach a real job endpoint (read -> job -> write).
        'SPARK_JOB', 'DATAFLOW_JOB', 'BIGQUERY_JOB', 'DBT_MODEL',
        'REDSHIFT_QUERY', 'DATABRICKS_JOB', 'PYTHON_JOB'
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

-- FK indexes for dlap_assets (M1-19 - names match @Index on Asset.java)
CREATE INDEX IF NOT EXISTS idx_asset_cloud_connection ON dlap_assets (cloud_connection_id);
CREATE INDEX IF NOT EXISTS idx_asset_created_by_user ON dlap_assets (created_by_user_id);

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

-- Apply triggers to tables with last_modified_ts
CREATE TRIGGER tr_user_update_timestamp
    BEFORE UPDATE ON dlap_users
    FOR EACH ROW
    EXECUTE FUNCTION update_last_modified_ts();

CREATE TRIGGER tr_label_update_timestamp
    BEFORE UPDATE ON dlap_labels
    FOR EACH ROW
    EXECUTE FUNCTION update_last_modified_ts();

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
-- provider-native detail - hierarchy (parent_resource_id/has_children),
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

-- Schema USAGE - REQUIRED for the table/sequence grants above to be usable.
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
-- short-circuits - so without this grant da-catalog crash-loops on boot.
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