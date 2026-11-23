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

CREATE SCHEMA IF NOT EXISTS dlap_catalog;

-- da-catalog's service-private table (AssetLineage). Generated from the JPA entity
-- so ddl-auto=validate passes on a fresh DB (was previously created by ddl-auto).
-- FKs reference the shared dlap_common.dlap_assets, which the platform init scripts
-- create before any service migration runs.
CREATE TABLE IF NOT EXISTS dlap_catalog.dlap_asset_lineage (
    lineage_id          uuid NOT NULL,
    created_at          timestamp(6) with time zone NOT NULL,
    created_by_user_id  uuid,
    details_json        jsonb,
    relationship_type   character varying(100),
    downstream_asset_id uuid NOT NULL,
    upstream_asset_id   uuid NOT NULL,
    CONSTRAINT dlap_asset_lineage_pkey PRIMARY KEY (lineage_id),
    CONSTRAINT fk_lineage_upstream_asset   FOREIGN KEY (upstream_asset_id)   REFERENCES dlap_common.dlap_assets(asset_id),
    CONSTRAINT fk_lineage_downstream_asset FOREIGN KEY (downstream_asset_id) REFERENCES dlap_common.dlap_assets(asset_id)
);

CREATE INDEX IF NOT EXISTS idx_lineage_upstream_asset_id   ON dlap_catalog.dlap_asset_lineage USING btree (upstream_asset_id);
CREATE INDEX IF NOT EXISTS idx_lineage_downstream_asset_id ON dlap_catalog.dlap_asset_lineage USING btree (downstream_asset_id);
