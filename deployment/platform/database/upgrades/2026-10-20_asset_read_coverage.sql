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

-- Upgrade delta for dlap_common (single source: init-scripts/02-create-common-schema.sql).
--
-- COV-01: per-asset read coverage. Adds read_coverage (FULL, PARTIAL, NONE; NULL unknown),
-- read_coverage_reasons (a JSON array of strings), read_coverage_observed_at and
-- read_coverage_since to dlap_assets, their value checks, a filter index and column comments.
--
-- No backfill. Every existing row starts unknown (NULL) until the da-discovery coverage
-- collectors observe the provider configuration. The technical_metadata key "readCoverage"
-- that the activity report read before COV-01 was never written, so nothing is copied.
--
-- Idempotent: safe to re-run. Applied by run-upgrades.sh (ADR-0056); fresh databases get the
-- same result from 02 directly. Run it as the table owner (da_catalog_user) or a superuser.

SET search_path TO dlap_common;

-- =============================================================================
-- Per-asset read coverage (COV-01; spec docs/specs/asset-descriptor.md "Read coverage").
-- Whether the provider configuration and DLAP's log bindings prove that data reads of the
-- asset are recorded: FULL, PARTIAL or NONE, with reasons. NULL is unknown (no observation
-- yet, or the collector lacked a permission, which the reasons name). Written only by the
-- da-catalog coverage applier; the JPA entity maps these columns read-only.
-- The block below is identical to the one in init-scripts/02-create-common-schema.sql.
-- =============================================================================
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage             VARCHAR(10);
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage_reasons     JSONB;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage_observed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE dlap_assets ADD COLUMN IF NOT EXISTS read_coverage_since       TIMESTAMP WITH TIME ZONE;

ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_read_coverage_valid;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_read_coverage_valid CHECK (read_coverage IN (
    'FULL', 'PARTIAL', 'NONE'
));
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_read_coverage_reasons_array;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_read_coverage_reasons_array CHECK (
    read_coverage_reasons IS NULL OR jsonb_typeof(read_coverage_reasons) = 'array'
);
-- A coverage start exists only while the coverage is FULL.
ALTER TABLE dlap_assets DROP CONSTRAINT IF EXISTS ck_asset_read_coverage_since_full;
ALTER TABLE dlap_assets ADD CONSTRAINT ck_asset_read_coverage_since_full CHECK (
    read_coverage_since IS NULL OR read_coverage = 'FULL'
);

CREATE INDEX IF NOT EXISTS idx_asset_read_coverage ON dlap_assets (read_coverage);

COMMENT ON COLUMN dlap_assets.read_coverage IS 'Read-logging coverage of the asset: FULL, PARTIAL or NONE; NULL unknown (COV-01). Only FULL lets a report call the asset not read';
COMMENT ON COLUMN dlap_assets.read_coverage_reasons IS 'JSON array of strings: why the coverage is PARTIAL, NONE or unknown (a missing permission is named), or the evidence for FULL';
COMMENT ON COLUMN dlap_assets.read_coverage_observed_at IS 'When the provider configuration behind read_coverage was read (collector clock); an older observation never replaces a newer one';
COMMENT ON COLUMN dlap_assets.read_coverage_since IS 'First observation of the current unbroken run of FULL observations; NULL unless read_coverage is FULL';
