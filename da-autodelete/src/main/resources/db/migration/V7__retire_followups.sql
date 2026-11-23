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


-- V7__retire_followups.sql
--
-- RET-04 (decision D36), follow-ups of the RET-02 review.
--
-- 1. soft_delete_records.status gains RULE_IN_FORCE (Q-631). A native lifecycle rule on a whole
--    bucket, prefix or container deletes objects on the platform's schedule. The first proven
--    child delete shows the rule fires (the disposition is EXECUTED with that proof), but the
--    bucket or container itself stays and other objects may not have expired yet, so the record
--    must not say HARD_DELETED. RULE_IN_FORCE means "the provider rule is proven to fire"; it is
--    restorable (the restore removes the rule; objects it already expired are gone).
-- 2. soft_delete_records.size_bytes (Q-619): the asset's size as the catalog last said it, kept
--    for the storage-reclaimed KPI, which counts only records the provider's log proved deleted.
-- 3. retire_dispositions.success_audit_pending (Q-633): the provider call succeeded but the
--    retire.delete.apply SUCCEEDED append did not land; the sweeper retries the append and never
--    applies the rule or the engine delete a second time.
-- 4. retire_dispositions.rule_removed_at (Q-631): when a restore removed the native rule.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS, and the CHECK is dropped and re-added by name.

SET search_path TO dlap_autodelete, public;

ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS size_bytes BIGINT;

ALTER TABLE soft_delete_records
    DROP CONSTRAINT IF EXISTS chk_sdr_status;
ALTER TABLE soft_delete_records
    ADD CONSTRAINT chk_sdr_status
    CHECK (status IN ('SOFT_DELETED', 'HARD_DELETE_PENDING_APPROVAL', 'HARD_DELETE_REFUSED',
                      'DRY_RUN_COMPLETE', 'AWAITING_PROOF', 'HARD_DELETED', 'HARD_DELETE_FAILED',
                      'RULE_IN_FORCE', 'RESTORED'));

ALTER TABLE retire_dispositions ADD COLUMN IF NOT EXISTS success_audit_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE retire_dispositions ADD COLUMN IF NOT EXISTS rule_removed_at        TIMESTAMPTZ;
