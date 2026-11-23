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

-- DLAP Database Initialization Script - MULTI-DATABASE APPROACH
-- This script sets up permissions for the shared dlap_common database
-- Individual service databases are created by 00-create-multiple-databases.sh

-- Enable required extensions in dlap_common database
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- Set timezone to UTC for consistency
SET timezone = 'UTC';

-- Grant permissions to service users for dlap_common database
-- Each service user gets appropriate access to shared entities

-- Discovery Service User - Read access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_discovery_user;
GRANT USAGE ON SCHEMA public TO da_discovery_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_discovery_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_discovery_user;

-- Catalog Service User - Full access to common entities (manages assets)
GRANT CONNECT ON DATABASE dlap_common TO da_catalog_user;
GRANT USAGE ON SCHEMA public TO da_catalog_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO da_catalog_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO da_catalog_user;

-- Policy Engine User - Read/Write access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_policyengine_user;
GRANT USAGE ON SCHEMA public TO da_policyengine_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_policyengine_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_policyengine_user;

-- Reporting Service User - Read access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_reporting_user;
GRANT USAGE ON SCHEMA public TO da_reporting_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_reporting_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_reporting_user;

-- Auto-Label Service User - Read/Write access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_autolabel_user;
GRANT USAGE ON SCHEMA public TO da_autolabel_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_autolabel_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_autolabel_user;

-- Auto-Archival Service User - Read/Write access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_autoarchival_user;
GRANT USAGE ON SCHEMA public TO da_autoarchival_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_autoarchival_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_autoarchival_user;

-- Auto-Delete Service User - Read/Write access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_autodelete_user;
GRANT USAGE ON SCHEMA public TO da_autodelete_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_autodelete_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_autodelete_user;

-- Auto-Compliance Service User - Read/Write access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_autocompliance_user;
GRANT USAGE ON SCHEMA public TO da_autocompliance_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_autocompliance_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_autocompliance_user;

-- Log Ingest Service User (M2 / R-P1-10 / ADR-0010) - Read/Write access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_logingest_user;
GRANT USAGE ON SCHEMA public TO da_logingest_user;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO da_logingest_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO da_logingest_user;

-- Admin Service User - Full access to common entities
GRANT CONNECT ON DATABASE dlap_common TO da_admin_user;
GRANT USAGE ON SCHEMA public TO da_admin_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO da_admin_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO da_admin_user;

-- Set default privileges for future objects in dlap_common
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO da_catalog_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_discovery_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_policyengine_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_reporting_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_autolabel_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_autoarchival_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_autodelete_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_autocompliance_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO da_logingest_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO da_admin_user;

-- Set default privileges for sequences
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO da_catalog_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_discovery_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_policyengine_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_reporting_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_autolabel_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_autoarchival_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_autodelete_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_autocompliance_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO da_logingest_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO da_admin_user;