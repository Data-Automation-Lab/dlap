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

-- ===========================================================================
-- Delta upgrade for the shared dlap_common database (matches the additions in
-- 02-create-common-schema.sql). Apply to any pre-existing dlap_common DB.
--
-- Why: da-catalog (the asset manager) must connect to the shared dlap_common
-- DATABASE because the canonical Asset/Label/User entities (da-protos,
-- @Table schema="dlap_common") live there — not in da-catalog's per-service DB.
-- Two gaps blocked that:
--   1. Service users had table grants on dlap_common but no USAGE on the SCHEMA,
--      so every access failed with "permission denied for schema dlap_common".
--   2. da-catalog had no home schema in this DB for its own dlap_asset_lineage
--      table (created by Hibernate ddl-auto=update on boot).
--
-- Idempotent: safe to re-run.
-- Run as the database owner/superuser:
--   psql -U dlap -d dlap_common -f 2026-06-28_dlap_common_schema_grants.sql
-- ===========================================================================
\c dlap_common

-- 1. Schema USAGE (the missing grant that made the table grants unusable).
GRANT USAGE ON SCHEMA dlap_common TO
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;

-- 2. da-catalog's home schema for service-private tables (dlap_asset_lineage).
--    CREATE on the database is required: da-catalog's Flyway V1 issues
--    `CREATE SCHEMA IF NOT EXISTS dlap_catalog`, and Postgres checks the
--    CREATE-on-database privilege before the IF-NOT-EXISTS short-circuits.
GRANT CREATE ON DATABASE dlap_common TO da_catalog_user;
CREATE SCHEMA IF NOT EXISTS dlap_catalog AUTHORIZATION da_catalog_user;

-- 3. da-catalog owns the shared asset-domain tables so ddl-auto=update can
--    reconcile them on boot. Other services keep their DML grants.
ALTER TABLE dlap_common.dlap_assets                  OWNER TO da_catalog_user;
ALTER TABLE dlap_common.dlap_labels                  OWNER TO da_catalog_user;
ALTER TABLE dlap_common.dlap_users                   OWNER TO da_catalog_user;
ALTER TABLE dlap_common.dlap_asset_label_assignments OWNER TO da_catalog_user;
ALTER TABLE dlap_common.dlap_cloud_resources         OWNER TO da_catalog_user;
