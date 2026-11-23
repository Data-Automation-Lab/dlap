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

-- 2026-04-10 deletion audit trail
-- Persists an append-only compliance record of every cloud deletion attempt.
-- See com.dlap.autodelete.domain.DeletionAuditEntity.
--
-- NOTE: The task brief specified a Liquibase changeset at
-- db/changelog/changes/2026-04-10-deletion-audit.xml. This service uses Flyway
-- (see application.properties: spring.flyway.enabled=true), not Liquibase.
-- Changing migrators mid-session would break clean startup for the existing
-- V1__init_schema.sql baseline, so the schema is delivered as a Flyway SQL
-- migration instead. Recorded as a deviation in the session report.

CREATE TABLE IF NOT EXISTS dlap_autodelete.dlap_deletion_audit (
    id                   BIGSERIAL     PRIMARY KEY,
    task_id              VARCHAR(64)   NOT NULL,
    asset_id             VARCHAR(512)  NOT NULL,
    cloud_provider       VARCHAR(32)   NOT NULL,
    action               VARCHAR(64)   NOT NULL,
    status               VARCHAR(32)   NOT NULL,
    performed_by         VARCHAR(256),
    performed_at         TIMESTAMP     NOT NULL,
    verification_status  VARCHAR(32),
    raw_response_summary VARCHAR(2048),
    bytes_deleted        BIGINT
);

CREATE INDEX IF NOT EXISTS idx_deletion_audit_task_id
    ON dlap_autodelete.dlap_deletion_audit (task_id);

CREATE INDEX IF NOT EXISTS idx_deletion_audit_asset_id
    ON dlap_autodelete.dlap_deletion_audit (asset_id);

CREATE INDEX IF NOT EXISTS idx_deletion_audit_performed_at
    ON dlap_autodelete.dlap_deletion_audit (performed_at);
