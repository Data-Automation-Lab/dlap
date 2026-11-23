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

-- N4 / ADR-0028 delta: adds dlap_cloud_resources to an existing dlap_common
-- schema. New deployments get this table from 02-create-common-schema.sql;
-- run this delta once against databases provisioned before 2026-06-13.
--
--   psql "$DLAP_COMMON_DB_URL" -v ON_ERROR_STOP=1 \
--        -f deployment/platform/database/upgrades/2026-06-13_add_cloud_resources.sql

SET search_path TO dlap_common;

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

COMMENT ON TABLE dlap_cloud_resources IS 'Richer optional provider-native resource model complementing dlap_assets (ADR-0028)';

DO $$ BEGIN
    GRANT SELECT, INSERT, UPDATE, DELETE ON dlap_cloud_resources TO dlap_discovery_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON dlap_cloud_resources TO dlap_catalog_user;
EXCEPTION WHEN undefined_object THEN NULL; END $$;

RESET search_path;
