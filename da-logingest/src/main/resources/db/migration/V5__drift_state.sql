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

-- V5__drift_state.sql
--
-- LOG-07 (Q-364). The drift report is read weekly, but the unknown JSON paths and the fill-ratio
-- baselines it lists were counted in memory and a restart emptied them. Two small tables keep them:
--
--   unknown_path_observation  JSON paths the parsers do not declare, per (source kind, envelope,
--                             version label): how often they were seen and when. Paths only, never
--                             values (the telemetry sanitizes and bounds every path to 200 chars).
--   fill_baseline             the highest fill ratio of a watched field per (source kind, version
--                             label) seen with enough samples; the drop alert and the drift report
--                             compare the live ratio to it.
--
-- Each instance adds the counts it saw since its last flush (observed_count = observed_count +
-- delta) and raises a baseline with GREATEST, so replicas converge on one view. Rows whose
-- last_seen (paths) or updated_at (baselines) is older than the retention window are pruned by the
-- service. Not entities in the JPA sense: ddl-auto=validate is unaffected.
--
-- Idempotent (CREATE TABLE IF NOT EXISTS) per CLAUDE.md section 7.

SET search_path TO dlap_logingest, public;

CREATE TABLE IF NOT EXISTS unknown_path_observation (
    source_kind     VARCHAR(128) NOT NULL,
    envelope        VARCHAR(128) NOT NULL,
    schema_version  VARCHAR(128) NOT NULL,
    path            VARCHAR(200) NOT NULL,
    observed_count  BIGINT       NOT NULL DEFAULT 0,
    first_seen      TIMESTAMPTZ  NOT NULL,
    last_seen       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_unknown_path_observation PRIMARY KEY (source_kind, envelope, schema_version, path)
);

CREATE INDEX IF NOT EXISTS idx_unknown_path_observation_last_seen
    ON unknown_path_observation (last_seen);

CREATE TABLE IF NOT EXISTS fill_baseline (
    source_kind     VARCHAR(128)     NOT NULL,
    schema_version  VARCHAR(128)     NOT NULL,
    field           VARCHAR(64)      NOT NULL,
    baseline        DOUBLE PRECISION NOT NULL,
    updated_at      TIMESTAMPTZ      NOT NULL,
    CONSTRAINT pk_fill_baseline PRIMARY KEY (source_kind, schema_version, field)
);
