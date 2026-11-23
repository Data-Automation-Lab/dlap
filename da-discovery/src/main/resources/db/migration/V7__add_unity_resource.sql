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

-- UC-5: the Unity Catalog discovery source. UnityCatalogResource is a JOINED
-- subclass of cloud_resource (peer of bigquery_resource / glue_resource), so it
-- needs its own child table. The crawler is profile-gated off, but the @Entity is
-- always registered, and ddl-auto=validate requires the table to exist for boot.

CREATE TABLE IF NOT EXISTS unity_catalog_resource (
    id           UUID NOT NULL,
    catalog_name VARCHAR(255),
    schema_name  VARCHAR(255),
    table_name   VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE unity_catalog_resource
        ADD CONSTRAINT fk_unity_catalog_resource_cloud_resource
        FOREIGN KEY (id) REFERENCES cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- UNITY is a new CloudProvider value; widen the check constraint to accept it.
ALTER TABLE cloud_resource DROP CONSTRAINT IF EXISTS cloud_resource_cloud_provider_check;
ALTER TABLE cloud_resource
    ADD CONSTRAINT cloud_resource_cloud_provider_check
    CHECK (cloud_provider IN ('GCP','AWS','AZURE','OCI','DATABRICKS','UNITY','UNKNOWN'));
