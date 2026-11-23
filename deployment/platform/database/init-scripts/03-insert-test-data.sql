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

-- DLAP Test Data Insertion
-- This script inserts sample data for development and testing.
--
-- Reconciled with the dlap_common schema (02-create-common-schema.sql) and the
-- da-protos entities (com.dlap.common.model.entity.*) - the runtime source of
-- truth. Column names, role/category/source check-constraint values all align
-- (Q-011). Kept best-effort (ON_ERROR_STOP off): this is OPTIONAL dev/demo data,
-- so a future seed/schema drift must never abort the DB bootstrap.
\set ON_ERROR_STOP off

-- Connect to the dlap_common database
\c dlap_common;

-- Set search path
SET search_path TO dlap_common, public;

-- Insert test users
-- Columns + roles match da-protos User; roles must be a subset of
-- {ADMIN, DATA_ENGINEER, DATA_ANALYST, POLICY_MANAGER, AUDITOR, CDO, VP, VIEWER}.
INSERT INTO dlap_common.dlap_users (user_id, keycloak_subject_id, email, full_name, roles, job_title) VALUES
    ('550e8400-e29b-41d4-a716-446655440001', 'kc-admin-001', 'admin@dalab.com', 'Admin Administrator', ARRAY['ADMIN'], 'Administrator'),
    ('550e8400-e29b-41d4-a716-446655440002', 'kc-analyst-001', 'analyst@dalab.com', 'Data Analyst', ARRAY['DATA_ANALYST'], 'Data Analyst'),
    ('550e8400-e29b-41d4-a716-446655440003', 'kc-engineer-001', 'engineer@dalab.com', 'Data Engineer', ARRAY['DATA_ENGINEER'], 'Data Engineer'),
    ('550e8400-e29b-41d4-a716-446655440004', 'kc-scientist-001', 'scientist@dalab.com', 'Data Scientist', ARRAY['DATA_ANALYST'], 'Data Scientist'),
    ('550e8400-e29b-41d4-a716-446655440005', 'kc-manager-001', 'manager@dalab.com', 'Policy Manager', ARRAY['POLICY_MANAGER'], 'Policy Manager')
ON CONFLICT (keycloak_subject_id) DO NOTHING;

-- Insert system-defined labels
-- label_category must be IN ('Sensitivity','Compliance','Data Type',
-- 'Business Domain','Governance','Classification') per da-protos Label.
INSERT INTO dlap_common.dlap_labels (label_id, label_name, label_category, description, created_by_user_id) VALUES
    ('660e8400-e29b-41d4-a716-446655440001', 'PII', 'Sensitivity', 'Data that can identify a specific individual', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440002', 'PHI', 'Compliance', 'Health information protected under HIPAA', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440003', 'FINANCIAL', 'Compliance', 'Financial records and payment information', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440004', 'CONFIDENTIAL', 'Classification', 'Confidential business information', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440005', 'PUBLIC', 'Classification', 'Public information safe for general access', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440006', 'INTERNAL', 'Classification', 'For internal company use only', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440007', 'CUSTOMER_DATA', 'Business Domain', 'Customer information and records', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440008', 'ANALYTICS', 'Business Domain', 'Data used for analytics and reporting', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440009', 'ML_TRAINING', 'Data Type', 'Data used for ML model training', '550e8400-e29b-41d4-a716-446655440001'),
    ('660e8400-e29b-41d4-a716-446655440010', 'ARCHIVED', 'Governance', 'Data that has been archived', '550e8400-e29b-41d4-a716-446655440001')
ON CONFLICT (label_name) DO NOTHING;

-- Insert custom labels
INSERT INTO dlap_common.dlap_labels (label_id, label_name, label_category, description, created_by_user_id) VALUES
    ('660e8400-e29b-41d4-a716-446655440011', 'SALES_DATA', 'Business Domain', 'Sales transactions and related information', '550e8400-e29b-41d4-a716-446655440002'),
    ('660e8400-e29b-41d4-a716-446655440012', 'MARKETING_DATA', 'Business Domain', 'Marketing campaigns and customer insights', '550e8400-e29b-41d4-a716-446655440002'),
    ('660e8400-e29b-41d4-a716-446655440013', 'INVENTORY_DATA', 'Business Domain', 'Product inventory and warehouse data', '550e8400-e29b-41d4-a716-446655440003'),
    ('660e8400-e29b-41d4-a716-446655440014', 'LOG_DATA', 'Data Type', 'Application and system logs', '550e8400-e29b-41d4-a716-446655440003')
ON CONFLICT (label_name) DO NOTHING;

-- Insert test assets
INSERT INTO dlap_common.dlap_assets (
    asset_id, asset_uri, cloud_provider, service_type, asset_type, native_asset_id, region, 
    cloud_connection_id, asset_name, description, 
    business_metadata, technical_metadata, details_json,
    created_by_user_id, dlap_discovered_ts, discovered_at, 
    dlap_last_scanned_ts, last_scanned_at, owner_info, status
) VALUES
    (
        '770e8400-e29b-41d4-a716-446655440001',
        'gs://dlap-demo-bucket/customer-data/',
        'GCP',
        'GCS_BUCKET',
        'BUCKET',
        'dlap-demo-bucket',
        'us-central1',
        '880e8400-e29b-41d4-a716-446655440001',
        'Customer Data Bucket',
        'Primary bucket containing customer information and transaction data',
        '{"department": "Sales", "owner_team": "Customer Analytics", "business_criticality": "HIGH", "data_classification": "CONFIDENTIAL"}',
        '{"size_gb": 156.7, "object_count": 12547, "storage_class": "STANDARD", "created_date": "2023-01-15", "encryption": "GOOGLE_MANAGED"}',
        '{"retention_policy": "7_YEARS", "backup_enabled": true, "versioning": true}',
        '550e8400-e29b-41d4-a716-446655440002',
        '2024-01-15 10:30:00+00',
        '2024-01-15 10:30:00+00',
        '2024-12-20 08:15:00+00',
        '2024-12-20 08:15:00+00',
        'team-customer-analytics@company.com',
        'ACTIVE'
    ),
    (
        '770e8400-e29b-41d4-a716-446655440002',
        's3://dlap-analytics-bucket/sales-reports/',
        'AWS',
        'S3_BUCKET',
        'BUCKET',
        'dlap-analytics-bucket',
        'us-east-1',
        '880e8400-e29b-41d4-a716-446655440002',
        'Sales Analytics Bucket',
        'Bucket containing monthly and quarterly sales reports and dashboards',
        '{"department": "Finance", "owner_team": "Business Intelligence", "business_criticality": "MEDIUM", "refresh_frequency": "DAILY"}',
        '{"size_gb": 45.2, "object_count": 3421, "storage_class": "STANDARD_IA", "created_date": "2023-03-10", "encryption": "AES256"}',
        '{"lifecycle_policy": "90_DAYS_IA", "public_access": false, "cross_region_replication": true}',
        '550e8400-e29b-41d4-a716-446655440003',
        '2024-02-01 14:20:00+00',
        '2024-02-01 14:20:00+00',
        '2024-12-20 09:45:00+00',
        '2024-12-20 09:45:00+00',
        'team-bi@company.com',
        'ACTIVE'
    ),
    (
        '770e8400-e29b-41d4-a716-446655440003',
        'projects/dlap-demo/datasets/customer_analytics/tables/customer_profiles',
        'GCP',
        'BIGQUERY_TABLE',
        'TABLE',
        'customer_profiles',
        'us-central1',
        '880e8400-e29b-41d4-a716-446655440001',
        'Customer Profiles Table',
        'BigQuery table containing detailed customer profiles and segmentation data',
        '{"department": "Marketing", "owner_team": "Customer Insights", "business_criticality": "HIGH", "update_frequency": "HOURLY"}',
        '{"row_count": 2547891, "size_gb": 12.3, "columns": 45, "partitioned": true, "clustered": true, "schema_version": "2.1"}',
        '{"partition_field": "created_date", "cluster_fields": ["customer_id", "region"], "table_type": "PARTITIONED"}',
        '550e8400-e29b-41d4-a716-446655440004',
        '2024-01-20 11:15:00+00',
        '2024-01-20 11:15:00+00',
        '2024-12-20 10:30:00+00',
        '2024-12-20 10:30:00+00',
        'team-customer-insights@company.com',
        'ACTIVE'
    ),
    (
        '770e8400-e29b-41d4-a716-446655440004',
        'https://dlapdemoaccount.blob.core.windows.net/ml-models/',
        'AZURE',
        'BLOB_STORAGE',
        'CONTAINER',
        'ml-models',
        'eastus',
        '880e8400-e29b-41d4-a716-446655440003',
        'ML Models Container',
        'Azure Blob container storing trained machine learning models and artifacts',
        '{"department": "Data Science", "owner_team": "ML Engineering", "business_criticality": "MEDIUM", "model_type": "PRODUCTION"}',
        '{"size_gb": 23.8, "blob_count": 156, "access_tier": "Hot", "created_date": "2023-05-12", "redundancy": "LRS"}',
        '{"versioning_enabled": true, "soft_delete_days": 30, "immutable_storage": false}',
        '550e8400-e29b-41d4-a716-446655440004',
        '2024-03-10 16:45:00+00',
        '2024-03-10 16:45:00+00',
        '2024-12-20 07:20:00+00',
        '2024-12-20 07:20:00+00',
        'team-ml-engineering@company.com',
        'ACTIVE'
    ),
    (
        '770e8400-e29b-41d4-a716-446655440005',
        's3://dlap-archive-bucket/historical-data/',
        'AWS',
        'S3_BUCKET',
        'BUCKET',
        'dlap-archive-bucket',
        'us-west-2',
        '880e8400-e29b-41d4-a716-446655440002',
        'Historical Data Archive',
        'Long-term storage for historical data older than 2 years',
        '{"department": "IT", "owner_team": "Data Management", "business_criticality": "LOW", "retention_period": "10_YEARS"}',
        '{"size_gb": 2340.7, "object_count": 45231, "storage_class": "GLACIER", "created_date": "2022-01-05", "encryption": "KMS"}',
        '{"lifecycle_policy": "IMMEDIATE_GLACIER", "public_access": false, "mfa_delete": true}',
        '550e8400-e29b-41d4-a716-446655440001',
        '2024-01-05 09:00:00+00',
        '2024-01-05 09:00:00+00',
        '2024-12-19 12:00:00+00',
        '2024-12-19 12:00:00+00',
        'team-data-management@company.com',
        'ARCHIVED'
    )
ON CONFLICT (asset_uri) DO NOTHING;

-- Insert asset label assignments
-- Columns match da-protos AssetLabelAssignment; source must be IN
-- ('USER','SYSTEM','SMART_LABEL_ML','SMART_LABEL_RULE','POLICY_ENGINE');
-- confidence_score in [0.0, 1.0].
-- label_id is resolved by name so this works whether the label was seeded by
-- 02 (canonical taxonomy) or by 03's custom block above - decoupled from UUIDs.
INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, assigned_by_user_id, confidence_score, source)
SELECT v.asset_id, l.label_id, v.assigned_by_user_id, v.confidence_score, v.source
FROM (VALUES
    -- Customer Data Bucket labels
    ('770e8400-e29b-41d4-a716-446655440001'::uuid, 'PII',           NULL::uuid,                                     0.95, 'SMART_LABEL_ML'),
    ('770e8400-e29b-41d4-a716-446655440001'::uuid, 'CUSTOMER_DATA', '550e8400-e29b-41d4-a716-446655440002'::uuid,   1.0,  'USER'),
    ('770e8400-e29b-41d4-a716-446655440001'::uuid, 'CONFIDENTIAL',  '550e8400-e29b-41d4-a716-446655440002'::uuid,   1.0,  'USER'),
    -- Sales Analytics Bucket labels
    ('770e8400-e29b-41d4-a716-446655440002'::uuid, 'SALES_DATA',    '550e8400-e29b-41d4-a716-446655440003'::uuid,   1.0,  'USER'),
    ('770e8400-e29b-41d4-a716-446655440002'::uuid, 'ANALYTICS',     NULL::uuid,                                     0.88, 'SMART_LABEL_ML'),
    ('770e8400-e29b-41d4-a716-446655440002'::uuid, 'INTERNAL',      '550e8400-e29b-41d4-a716-446655440003'::uuid,   1.0,  'USER'),
    -- Customer Profiles Table labels
    ('770e8400-e29b-41d4-a716-446655440003'::uuid, 'PII',           NULL::uuid,                                     0.92, 'SMART_LABEL_ML'),
    ('770e8400-e29b-41d4-a716-446655440003'::uuid, 'CUSTOMER_DATA', '550e8400-e29b-41d4-a716-446655440004'::uuid,   1.0,  'USER'),
    ('770e8400-e29b-41d4-a716-446655440003'::uuid, 'ANALYTICS',     '550e8400-e29b-41d4-a716-446655440004'::uuid,   1.0,  'USER'),
    -- ML Models Container labels
    ('770e8400-e29b-41d4-a716-446655440004'::uuid, 'ML_TRAINING',   '550e8400-e29b-41d4-a716-446655440004'::uuid,   1.0,  'USER'),
    ('770e8400-e29b-41d4-a716-446655440004'::uuid, 'INTERNAL',      '550e8400-e29b-41d4-a716-446655440004'::uuid,   1.0,  'USER'),
    -- Historical Data Archive labels
    ('770e8400-e29b-41d4-a716-446655440005'::uuid, 'ARCHIVED',      NULL::uuid,                                     1.0,  'POLICY_ENGINE'),
    ('770e8400-e29b-41d4-a716-446655440005'::uuid, 'PII',           NULL::uuid,                                     0.75, 'SMART_LABEL_ML')
) AS v(asset_id, label_name, assigned_by_user_id, confidence_score, source)
JOIN dlap_common.dlap_labels l ON l.label_name = v.label_name
ON CONFLICT (asset_id, label_id) DO NOTHING;

-- Success message
DO $$
BEGIN
    RAISE NOTICE 'DLAP test data inserted successfully';
    RAISE NOTICE 'Created % users', (SELECT COUNT(*) FROM dlap_common.dlap_users);
    RAISE NOTICE 'Created % labels', (SELECT COUNT(*) FROM dlap_common.dlap_labels);
    RAISE NOTICE 'Created % assets', (SELECT COUNT(*) FROM dlap_common.dlap_assets);
    RAISE NOTICE 'Created % label assignments', (SELECT COUNT(*) FROM dlap_common.dlap_asset_label_assignments);
END $$; 