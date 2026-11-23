-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file. Apache-2.0.
--
-- Upgrade delta for dlap_common (single source: init-scripts/02-create-common-schema.sql).
--
-- Widens the dlap_assets provider/service-type CHECK constraints to admit Unity
-- Catalog assets. Unity Catalog is a crawler-only source (no cloud audit-log
-- stream); with the new crawler->log-spine bridge (CrawlerAssetSpineBridge,
-- da-discovery) UC tables now reach dlp_assets and must pass these checks.
--
-- Idempotent: safe to re-run. Apply against an existing dlap_common database;
-- fresh databases pick the values up from 02-create-common-schema.sql directly.

SET search_path TO dlap_common;

-- cloud_provider: add UNITY.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_cloud_provider_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_cloud_provider_valid
    CHECK (cloud_provider IN ('AWS', 'GCP', 'AZURE', 'OCI', 'UNITY'));

-- service_type: add the Unity Catalog hierarchy leaf types.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_service_type_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_service_type_valid
    CHECK (service_type IN (
        'S3_BUCKET', 'RDS_INSTANCE', 'REDSHIFT_CLUSTER', 'DYNAMODB_TABLE',
        'GCS_BUCKET', 'BIGQUERY_DATASET', 'BIGQUERY_TABLE', 'CLOUD_SQL_INSTANCE',
        'BLOB_STORAGE', 'SQL_DATABASE', 'COSMOS_DB', 'DATA_LAKE',
        'UNITY_CATALOG', 'UNITY_SCHEMA', 'UNITY_TABLE'
    ));
