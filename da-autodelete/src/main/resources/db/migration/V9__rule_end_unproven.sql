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



-- V9__rule_end_unproven.sql
--
-- RET-08 (orchestrator decision D38), the maximum age of a rule that is never proven (Q-695).
--
-- 1. soft_delete_records.status gains RULE_ENDED_UNPROVEN: a bucket, prefix or container rule that DLAP
--    applied and that no provider event ever proved (the record still AWAITING_PROOF, the disposition
--    AWAITING_PROOF or PROOF_OVERDUE) reached dlap.autodelete.rule-in-force.max-age since it was applied.
--    The executor removed it, the removal is audited (retire.delete.rule_ended_unproven) and the record
--    ends here. An end state: whatever the rule expired is gone, nothing deletes any more, and nothing was
--    ever proven.
-- 2. soft_delete_records.rule_end_retry_at: when the rule-end job may try a removal again after the
--    platform refused it or the attempt was rolled back. It used next_check_at before; an AWAITING_PROOF
--    row's next_check_at belongs to the sweeper (the proof-overdue check), so the job keeps its own time.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS, and the CHECK is dropped and re-added by name.

SET search_path TO dlap_autodelete, public;

ALTER TABLE soft_delete_records ADD COLUMN IF NOT EXISTS rule_end_retry_at TIMESTAMPTZ;

ALTER TABLE soft_delete_records
    DROP CONSTRAINT IF EXISTS chk_sdr_status;
ALTER TABLE soft_delete_records
    ADD CONSTRAINT chk_sdr_status
    CHECK (status IN ('SOFT_DELETED', 'HARD_DELETE_PENDING_APPROVAL', 'HARD_DELETE_REFUSED',
                      'DRY_RUN_COMPLETE', 'AWAITING_PROOF', 'HARD_DELETED', 'HARD_DELETE_FAILED',
                      'RULE_IN_FORCE', 'RULE_ENDED', 'RULE_ENDED_UNPROVEN', 'RESTORE_FAILED', 'RESTORED'));
