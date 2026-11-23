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

-- C7 / V4-AWS / V4-AZ / V4-DBX (ADR-0041): the processing-job asset entities landed
-- without their JOINED-inheritance child tables, so a fresh database could not boot
-- da-discovery at all: ddl-auto=validate aborted with
--   Schema-validation: missing table [aws_processing_job_resource]
-- Each entity is a JOINED subclass whose table holds only the subtype columns plus an
-- id that is simultaneously the primary key and the foreign key to its parent table,
-- exactly as s3_resource/glue_resource (-> aws_resource) and unity_catalog_resource
-- (-> cloud_resource) are shaped. The crawlers are profile-gated, but the @Entity
-- classes are always registered, so the tables must exist for boot regardless.
--
-- Columns trace 1:1 to the mapped @Column fields; the entities' inputTables lists are
-- @Transient (carried on the discovery event for lineage attribution, not persisted)
-- and therefore deliberately have no column here. The *_job_query columns mirror the
-- entities' length = 8192.
--
-- No CHECK-constraint widening is required: DATABRICKS has been an accepted
-- cloud_provider value on cloud_resource, discovery_job and resource_change since the
-- V1 baseline, and V7/V8 carried it forward when they widened those constraints for
-- UNITY. The other three subtypes reuse the existing AWS/AZURE/GCP values.

-- AwsProcessingJobResource extends AwsResource (Glue job / Athena query / EMR step).
CREATE TABLE IF NOT EXISTS aws_processing_job_resource (
    id             UUID NOT NULL,
    aws_job_source VARCHAR(255),
    aws_job_type   VARCHAR(255),
    aws_job_state  VARCHAR(255),
    aws_job_query  VARCHAR(8192),
    aws_job_output VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE aws_processing_job_resource
        ADD CONSTRAINT fk_aws_processing_job_resource_aws_resource
        FOREIGN KEY (id) REFERENCES aws_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- AzureProcessingJobResource extends AzureResource (Data Factory pipeline / Synapse Spark job).
CREATE TABLE IF NOT EXISTS azure_processing_job_resource (
    id               UUID NOT NULL,
    azure_job_source VARCHAR(255),
    azure_job_type   VARCHAR(255),
    azure_job_state  VARCHAR(255),
    azure_job_query  VARCHAR(8192),
    azure_job_output VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE azure_processing_job_resource
        ADD CONSTRAINT fk_azure_processing_job_resource_azure_resource
        FOREIGN KEY (id) REFERENCES azure_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- DatabricksProcessingJobResource extends CloudResource directly (job run / SQL query).
-- Databricks is a standalone provider, like UnityCatalogResource, so the parent is
-- cloud_resource rather than an intermediate per-cloud table.
CREATE TABLE IF NOT EXISTS databricks_processing_job_resource (
    id                    UUID NOT NULL,
    databricks_job_source VARCHAR(255),
    databricks_job_type   VARCHAR(255),
    databricks_job_state  VARCHAR(255),
    databricks_job_query  VARCHAR(8192),
    databricks_job_output VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE databricks_processing_job_resource
        ADD CONSTRAINT fk_databricks_processing_job_resource_cloud_resource
        FOREIGN KEY (id) REFERENCES cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- GcpBigQueryJobResource extends GcpResource (BigQuery query / load / extract / copy).
CREATE TABLE IF NOT EXISTS gcp_bigquery_job_resource (
    id            UUID NOT NULL,
    bq_job_engine VARCHAR(255),
    bq_job_type   VARCHAR(255),
    bq_job_state  VARCHAR(255),
    bq_job_query  VARCHAR(8192),
    bq_job_output VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE gcp_bigquery_job_resource
        ADD CONSTRAINT fk_gcp_bigquery_job_resource_gcp_resource
        FOREIGN KEY (id) REFERENCES gcp_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
