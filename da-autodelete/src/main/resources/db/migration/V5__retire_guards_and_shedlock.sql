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

-- V5__retire_guards_and_shedlock.sql
--
-- RET-02 (decision D20, docs/specs/approvals.md section 9, docs/specs/audit-log.md).
--
-- 1. soft_delete_records keeps what the executor checked when a delete entered its window
--    (the approval, its approvers, the requester, the window it asked for and the window it
--    got after the floor) and what it needs for the hard delete at the end of the window (the
--    HARD_DELETE approval it asked for, the next time the sweeper looks at the row). asset_uri
--    now always holds the canonical asset URI (ADR-0047) for new rows.
-- 2. The status CHECK gains the hard-delete states:
--      HARD_DELETE_PENDING_APPROVAL  the window ended; a HARD_DELETE approval was requested
--      HARD_DELETE_REFUSED           an executor rule refused the hard delete; nothing was touched
--      DRY_RUN_COMPLETE              everything passed but dry-run-cloud-delete is on; nothing was touched
--      AWAITING_PROOF                the native rule or the engine delete was applied; the row
--                                    becomes HARD_DELETED only when the provider's own log event is seen
-- 3. dlap_deletion_tasks records the JWT subject that submitted a task and when the sweeper looks
--    at it next (task approvals now go through the policy engine approval service).
-- 4. The ShedLock table for the retire sweeper (plan rule R7, Q-548).
--
-- Idempotent: ADD COLUMN IF NOT EXISTS, CREATE ... IF NOT EXISTS, and the CHECK is dropped and
-- re-added by name.

SET search_path TO dlap_autodelete, public;

ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS service_type             VARCHAR(64);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS origin                   VARCHAR(16);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS task_id                  VARCHAR(255);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS requested_by             VARCHAR(255);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS requested_window_days    INTEGER;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS window_days              INTEGER;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS approval_id              UUID;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS approval_class           VARCHAR(16);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS approvers                TEXT;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS read_coverage            VARCHAR(16);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS hard_delete_approval_id  UUID;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS hard_delete_requested_at TIMESTAMPTZ;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS next_check_at            TIMESTAMPTZ;
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS restored_by              VARCHAR(255);
ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS updated_at               TIMESTAMPTZ;

ALTER TABLE soft_delete_records
    DROP CONSTRAINT IF EXISTS chk_sdr_status;
ALTER TABLE soft_delete_records
    ADD CONSTRAINT chk_sdr_status
    CHECK (status IN ('SOFT_DELETED', 'HARD_DELETE_PENDING_APPROVAL', 'HARD_DELETE_REFUSED',
                      'DRY_RUN_COMPLETE', 'AWAITING_PROOF', 'HARD_DELETED', 'HARD_DELETE_FAILED',
                      'RESTORED'));

-- The sweeper's claim query (status, then due time) and the proof lookups by fire.
CREATE INDEX IF NOT EXISTS idx_sdr_status_next_check
    ON soft_delete_records (status, next_check_at);
CREATE INDEX IF NOT EXISTS idx_sdr_policy_fire_id
    ON soft_delete_records (policy_fire_id);

ALTER TABLE dlap_deletion_tasks ADD COLUMN IF NOT EXISTS requested_by  VARCHAR(255);
ALTER TABLE dlap_deletion_tasks ADD COLUMN IF NOT EXISTS next_check_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_deletion_tasks_status_next_check
    ON dlap_deletion_tasks (status, next_check_at);

-- ShedLock (net.javacrumbs.shedlock, JdbcTemplateLockProvider with usingDbTime()).
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
