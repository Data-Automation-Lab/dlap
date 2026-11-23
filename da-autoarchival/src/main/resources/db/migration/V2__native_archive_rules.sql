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
--
-- RET-03 (decision D20): archive on native cloud primitives.
--   1. dlap_archive_rules: one row per approved archive action. The approval id is unique, so a
--      redelivered policy fire or a repeated approval is the same decision (approvals.md section 9,
--      rule 5). The row records what DLAP applied (status APPLIED or RENDERED) and, separately, the
--      provider event that proves the transition happened (status EXECUTED).
--   2. The task status check gains the states of that flow.
--   3. The default archival tier "STANDARD_ARCHIVE" is not a storage class on any cloud, so it is
--      replaced by the neutral tier COLD (S3 GLACIER_IR, GCS COLDLINE, Azure Cold).

CREATE TABLE IF NOT EXISTS dlap_autoarchival.dlap_archive_rules (
    rule_id             character varying(64)   NOT NULL,
    approval_id         character varying(64)   NOT NULL,
    fire_id             character varying(128),
    task_id             character varying(255)  NOT NULL,
    asset_id            character varying(255)  NOT NULL,
    asset_uri           character varying(2048) NOT NULL,
    provider            character varying(16)   NOT NULL,
    mode                character varying(16)   NOT NULL,
    scope_bucket        character varying(255)  NOT NULL,
    scope_prefix        character varying(1024) NOT NULL DEFAULT '',
    native_class        character varying(32)   NOT NULL,
    after_days          integer                 NOT NULL,
    status              character varying(16)   NOT NULL,
    mock                boolean                 NOT NULL DEFAULT false,
    rule_document       text,
    applied_at          timestamp with time zone,
    executed_at         timestamp with time zone,
    evidence_event_id   character varying(128),
    evidence_operation  character varying(128),
    evidence_event_time timestamp with time zone,
    evidence_kind       character varying(32),
    last_error          text,
    created_at          timestamp with time zone NOT NULL,
    updated_at          timestamp with time zone NOT NULL,
    CONSTRAINT dlap_archive_rules_pkey PRIMARY KEY (rule_id),
    CONSTRAINT ck_archive_rules_mode CHECK (mode IN ('NATIVE_RULE', 'DIRECT_OBJECT')),
    CONSTRAINT ck_archive_rules_status CHECK (status IN
        ('PLANNED', 'APPLIED', 'RENDERED', 'EXECUTED', 'FAILED', 'REFUSED', 'REMOVED')),
    CONSTRAINT ck_archive_rules_after_days CHECK (after_days >= 0),
    -- Executed means a provider event proved it: the evidence columns are all set or the row is not executed.
    CONSTRAINT ck_archive_rules_executed_has_evidence CHECK (status <> 'EXECUTED'
        OR (evidence_event_id IS NOT NULL AND evidence_event_time IS NOT NULL AND executed_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_archive_rules_approval ON dlap_autoarchival.dlap_archive_rules (approval_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_archive_rules_fire ON dlap_autoarchival.dlap_archive_rules (fire_id)
    WHERE fire_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_archive_rules_task ON dlap_autoarchival.dlap_archive_rules (task_id);
CREATE INDEX IF NOT EXISTS idx_archive_rules_proof ON dlap_autoarchival.dlap_archive_rules
    (provider, scope_bucket, status);

ALTER TABLE dlap_autoarchival.dlap_archival_tasks DROP CONSTRAINT IF EXISTS dlap_archival_tasks_status_check;
ALTER TABLE dlap_autoarchival.dlap_archival_tasks ADD CONSTRAINT dlap_archival_tasks_status_check CHECK (status IN
    ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUBMITTED', 'IN_PROGRESS', 'COMPLETED', 'PARTIALLY_COMPLETED',
     'FAILED', 'CANCELLED', 'RESTORATION_REQUESTED', 'RESTORATION_IN_PROGRESS', 'RESTORED', 'RESTORATION_FAILED',
     'RULE_APPLIED', 'RULE_RENDERED', 'EXECUTED', 'REFUSED', 'EXPIRED'));

UPDATE dlap_autoarchival.dlap_archival_config
   SET default_archival_tier = 'COLD'
 WHERE default_archival_tier IS NULL OR default_archival_tier IN ('STANDARD_ARCHIVE', 'STANDARD', 'HOT');
