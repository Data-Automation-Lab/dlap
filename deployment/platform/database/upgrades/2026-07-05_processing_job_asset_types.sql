-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file. Apache-2.0.
--
-- Upgrade delta for dlap_common (single source: init-scripts/02-create-common-schema.sql).
--
-- C7: makes data-processing jobs/engines first-class assets. Widens the
-- dlap_assets.service_type CHECK constraint to admit the cloud-neutral job kinds
-- (SPARK_JOB, DATAFLOW_JOB, BIGQUERY_JOB, DBT_MODEL, REDSHIFT_QUERY,
-- DATABRICKS_JOB, PYTHON_JOB). A job asset can then be a lineage endpoint, so a
-- log-derived read -> job -> write edge has a real job node to attach to.
--
-- Idempotent: safe to re-run. Apply against an existing dlap_common database;
-- fresh databases pick the values up from 02-create-common-schema.sql directly.

SET search_path TO dlap_common;

-- service_type: add the processing-job / engine kinds alongside the existing set.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_service_type_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_service_type_valid
    CHECK (service_type IN (
        'S3_BUCKET', 'RDS_INSTANCE', 'REDSHIFT_CLUSTER', 'DYNAMODB_TABLE',
        'GCS_BUCKET', 'BIGQUERY_DATASET', 'BIGQUERY_TABLE', 'CLOUD_SQL_INSTANCE',
        'BLOB_STORAGE', 'SQL_DATABASE', 'COSMOS_DB', 'DATA_LAKE',
        'UNITY_CATALOG', 'UNITY_SCHEMA', 'UNITY_TABLE',
        'SPARK_JOB', 'DATAFLOW_JOB', 'BIGQUERY_JOB', 'DBT_MODEL',
        'REDSHIFT_QUERY', 'DATABRICKS_JOB', 'PYTHON_JOB'
    ));
