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


-- V8__rule_end_states.sql
--
-- RET-06 (orchestrator decision D37), the end of a native delete rule.
--
-- 1. soft_delete_records.status gains RULE_ENDED (Q-677): a bucket, prefix or container rule that was
--    RULE_IN_FORCE for longer than dlap.autodelete.rule-in-force.max-age since it was applied is removed
--    by the executor itself. Removing a rule is not destructive, so it needs no approval; it is audited
--    (retire.delete.rule_ended).
-- 2. soft_delete_records.status gains RESTORE_FAILED (Q-682): DLAP tried to remove its own rule (a restore
--    or the end of the maximum age) and found no matching rule on the platform. A person has to look.
--
-- Idempotent: the CHECK is dropped and re-added by name.

SET search_path TO dlap_autodelete, public;

ALTER TABLE soft_delete_records
    DROP CONSTRAINT IF EXISTS chk_sdr_status;
ALTER TABLE soft_delete_records
    ADD CONSTRAINT chk_sdr_status
    CHECK (status IN ('SOFT_DELETED', 'HARD_DELETE_PENDING_APPROVAL', 'HARD_DELETE_REFUSED',
                      'DRY_RUN_COMPLETE', 'AWAITING_PROOF', 'HARD_DELETED', 'HARD_DELETE_FAILED',
                      'RULE_IN_FORCE', 'RULE_ENDED', 'RESTORE_FAILED', 'RESTORED'));
