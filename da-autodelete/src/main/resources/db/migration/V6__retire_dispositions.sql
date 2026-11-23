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

-- V6__retire_dispositions.sql
--
-- RET-02 (decision D20). One disposition per hard delete the executor carried out, or would have
-- carried out under dry-run-cloud-delete:
--
--   mode NATIVE_RULE  the platform's own lifecycle primitive (S3 lifecycle expiration, GCS lifecycle
--                     delete, Azure Blob lifecycle management, BigQuery table expiration); rule_document
--                     holds the rule in the provider's API shape
--   mode DIRECT       the engine's own delete call, for kinds with no exact native rule
--
-- A disposition is EXECUTED only when the provider's own log event proves the deletion
-- (proof_event_id, proof_event_time). Until then an applied disposition is AWAITING_PROOF, and
-- PROOF_OVERDUE once the expected delay has passed without a proof.

SET search_path TO dlap_autodelete, public;

CREATE TABLE IF NOT EXISTS retire_dispositions (
    id                     UUID          PRIMARY KEY,
    soft_delete_record_id  UUID          NOT NULL,
    asset_id               UUID          NOT NULL,
    target_uri             VARCHAR(2048) NOT NULL,
    cloud_provider         VARCHAR(20)   NOT NULL,
    service_type           VARCHAR(64),
    mode                   VARCHAR(16)   NOT NULL,
    platform               VARCHAR(40)   NOT NULL,
    rule_id                VARCHAR(128),
    rule_document          TEXT,
    status                 VARCHAR(20)   NOT NULL,
    dry_run                BOOLEAN       NOT NULL,
    approval_id            UUID          NOT NULL,
    approvers              TEXT,
    planned_at             TIMESTAMPTZ   NOT NULL,
    effective_at           TIMESTAMPTZ,
    applied_at             TIMESTAMPTZ,
    proof_due_at           TIMESTAMPTZ,
    proof_event_id         VARCHAR(512),
    proof_event_time       TIMESTAMPTZ,
    proof_source           VARCHAR(128),
    executed_at            TIMESTAMPTZ,
    last_error             TEXT,
    updated_at             TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_retire_dispositions_record UNIQUE (soft_delete_record_id),
    CONSTRAINT fk_retire_dispositions_record FOREIGN KEY (soft_delete_record_id)
        REFERENCES soft_delete_records (id),
    CONSTRAINT chk_retire_dispositions_mode CHECK (mode IN ('NATIVE_RULE', 'DIRECT')),
    CONSTRAINT chk_retire_dispositions_status CHECK (status IN (
        'DRY_RUN', 'AWAITING_PROOF', 'PROOF_OVERDUE', 'EXECUTED', 'FAILED')),
    CONSTRAINT chk_retire_dispositions_executed_has_proof CHECK (
        status <> 'EXECUTED' OR (proof_event_id IS NOT NULL AND proof_event_time IS NOT NULL)),
    CONSTRAINT chk_retire_dispositions_dry_run_never_applied CHECK (
        NOT dry_run OR (applied_at IS NULL AND status = 'DRY_RUN'))
);

CREATE INDEX IF NOT EXISTS idx_retire_dispositions_target
    ON retire_dispositions (target_uri, status);
CREATE INDEX IF NOT EXISTS idx_retire_dispositions_status_due
    ON retire_dispositions (status, proof_due_at);
