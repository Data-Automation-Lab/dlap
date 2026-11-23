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
-- RET-05 (Q-608, Q-609, Q-607), follow-ups to RET-03.
--   1. EXECUTING: the claim state. The executor moves a row from PLANNED to EXECUTING with one
--      conditional UPDATE before it writes the REQUESTED audit record, so the policy-event path,
--      the approval-decided path and POST /rules/{id}/retry can never run one row twice.
--   2. already_present: whether the provider already held the identical rule when DLAP applied
--      it (GCS lifecycle rules have no id, so an identical customer rule counts). A restore never
--      removes a rule that was already there.
--   3. shedlock: the lock table of the reconciliation job (plan rule R7).
--
-- Rows written before this migration read already_present = false. For a GCS row applied before
-- V3 that is a guess: nothing recorded whether the rule was the customer's.

ALTER TABLE dlap_autoarchival.dlap_archive_rules
    ADD COLUMN IF NOT EXISTS already_present boolean NOT NULL DEFAULT false;

ALTER TABLE dlap_autoarchival.dlap_archive_rules DROP CONSTRAINT IF EXISTS ck_archive_rules_status;
ALTER TABLE dlap_autoarchival.dlap_archive_rules ADD CONSTRAINT ck_archive_rules_status CHECK (status IN
    ('PLANNED', 'EXECUTING', 'APPLIED', 'RENDERED', 'EXECUTED', 'FAILED', 'REFUSED', 'REMOVED'));

-- The reconciliation job looks for rows by status and age.
CREATE INDEX IF NOT EXISTS idx_archive_rules_status_updated ON dlap_autoarchival.dlap_archive_rules
    (status, applied_at, updated_at);

-- ShedLock (net.javacrumbs.shedlock, JdbcTemplateLockProvider with usingDbTime()).
CREATE TABLE IF NOT EXISTS dlap_autoarchival.shedlock (
    name       character varying(64)  NOT NULL,
    lock_until timestamp              NOT NULL,
    locked_at  timestamp              NOT NULL,
    locked_by  character varying(255) NOT NULL,
    CONSTRAINT shedlock_pkey PRIMARY KEY (name)
);
