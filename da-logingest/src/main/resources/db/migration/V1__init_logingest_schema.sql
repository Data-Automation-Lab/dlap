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

-- V1__init_logingest_schema.sql
--
-- M2 / R-P1-10. Schema for da-logingest, the cloud-neutral audit-log
-- normalization service. One table holds the local replay store of
-- normalized events (90-day default retention, configurable via
-- dlap.logingest.retention-days).
--
-- Schema name follows CLAUDE.md sec.6 (one schema per service).

CREATE SCHEMA IF NOT EXISTS dlap_logingest;
SET search_path TO dlap_logingest, public;

CREATE TABLE IF NOT EXISTS log_events (
    event_id              VARCHAR(64)  PRIMARY KEY,
    event_time            TIMESTAMPTZ  NOT NULL,
    ingest_time           TIMESTAMPTZ  NOT NULL,
    source_provider       VARCHAR(32)  NOT NULL,
    source_service        VARCHAR(128),
    change_type           VARCHAR(32)  NOT NULL,
    operation             VARCHAR(256) NOT NULL,
    result_status         VARCHAR(32),
    principal_id          VARCHAR(256),
    principal_type        VARCHAR(32),
    target_resource_id    VARCHAR(512),
    target_resource_kind  VARCHAR(128),
    target_project        VARCHAR(128),
    target_region         VARCHAR(64),
    schema_version        VARCHAR(8)   NOT NULL,
    raw_event             JSONB
);

-- Replay queries are typically time-bounded + filtered by provider /
-- change-type / target. Indexes match the JPA @Index declarations on
-- LogEventEntity.
CREATE INDEX IF NOT EXISTS idx_log_events_event_time
    ON log_events (event_time);
CREATE INDEX IF NOT EXISTS idx_log_events_source_provider
    ON log_events (source_provider);
CREATE INDEX IF NOT EXISTS idx_log_events_change_type
    ON log_events (change_type);
CREATE INDEX IF NOT EXISTS idx_log_events_target_resource_id
    ON log_events (target_resource_id);

-- Composite index for "show me everything that changed on this
-- resource between t1 and t2" — the most common consumer pattern.
CREATE INDEX IF NOT EXISTS idx_log_events_target_time
    ON log_events (target_resource_id, event_time);

-- Self-grant + default privileges so future migrations and
-- da_logingest_user automation can write here.
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA dlap_logingest TO da_logingest_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA dlap_logingest TO da_logingest_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA dlap_logingest
    GRANT ALL ON TABLES TO da_logingest_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA dlap_logingest
    GRANT ALL ON SEQUENCES TO da_logingest_user;
