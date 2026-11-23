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

-- DLAP PostgreSQL Extensions Initialization
-- This script sets up required extensions for all DLAP databases

-- UUID extension for generating UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- JSONB support (already included in PostgreSQL 15+)
-- CREATE EXTENSION IF NOT EXISTS "jsonb";

-- Full text search extensions
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Additional useful extensions for data processing
CREATE EXTENSION IF NOT EXISTS "btree_gin";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- Statistics extension for query optimization
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";

-- Administrative functions
CREATE EXTENSION IF NOT EXISTS "adminpack";

-- Time series data support (optional; only if the image ships timescaledb).
-- The stock postgres image does not bundle it, so this is best-effort: a
-- missing extension must not abort database initialization.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS "timescaledb" CASCADE;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'timescaledb extension not available; skipping (%).', SQLERRM;
END $$;

-- Comment extensions installed
COMMENT ON EXTENSION "uuid-ossp" IS 'DLAP: UUID generation functions';
COMMENT ON EXTENSION "pg_trgm" IS 'DLAP: Full text search trigram matching';
COMMENT ON EXTENSION "btree_gin" IS 'DLAP: GIN index support for btree-equivalent operators';
COMMENT ON EXTENSION "btree_gist" IS 'DLAP: GiST index support for btree-equivalent operators';
COMMENT ON EXTENSION "pg_stat_statements" IS 'DLAP: Query statistics tracking';
COMMENT ON EXTENSION "adminpack" IS 'DLAP: Administrative functions';

-- Grant usage permissions to all users
GRANT USAGE ON SCHEMA public TO PUBLIC; 