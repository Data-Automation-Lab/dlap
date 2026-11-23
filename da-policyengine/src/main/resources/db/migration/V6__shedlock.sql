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

-- POL-01: state of the scheduled policy sweep (plan rule R7).
--
-- Schedule triggers (spec.triggers[].type: schedule) fire through ScheduledPolicySweeper, a
-- cluster-wide singleton job. Two tables, both in this service's own schema:
--   shedlock               lock table of ShedLock's JDBC provider; the sweeper holds the lock
--                          "policy-schedule-sweeper" for one tick, on the database clock.
--   policy_schedule_state  one row per scheduled policy: the latest cron slot fired (last_slot)
--                          and the counts of that fire (shadowed: withheld by shadow mode). A
--                          slot is recorded only after its sweep finishes, so a crash mid-sweep
--                          re-runs the slot (at-least-once; the fire ids are deterministic, so a
--                          consumer can tell the repeat).
-- Both statements are idempotent.

-- 1. shedlock: the column set ShedLock's JdbcTemplateLockProvider requires, unchanged (same as
--    da-catalog V4, da-discovery V10 and da-logingest V3). Timestamps are UTC, hence TIMESTAMP
--    without zone.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- 2. policy_schedule_state: the sweep's per-policy cursor. Deleting a policy deletes its state.
--    last_slot is the cron slot (business time of the fire); first_seen_at is when the sweeper
--    first saw the policy, which fires from its next slot on, never retroactively.
CREATE TABLE IF NOT EXISTS policy_schedule_state (
    policy_id         UUID                     NOT NULL,
    last_slot         TIMESTAMP WITH TIME ZONE NOT NULL,
    first_seen_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_started_at   TIMESTAMP WITH TIME ZONE,
    last_finished_at  TIMESTAMP WITH TIME ZONE,
    assets_evaluated  BIGINT                   NOT NULL DEFAULT 0,
    matched           BIGINT                   NOT NULL DEFAULT 0,
    unknown           BIGINT                   NOT NULL DEFAULT 0,
    published         BIGINT                   NOT NULL DEFAULT 0,
    shadowed          BIGINT                   NOT NULL DEFAULT 0,
    PRIMARY KEY (policy_id),
    CONSTRAINT fk_policy_schedule_state_policy
        FOREIGN KEY (policy_id) REFERENCES policies (id) ON DELETE CASCADE,
    CONSTRAINT ck_policy_schedule_state_counts
        CHECK (assets_evaluated >= 0 AND matched >= 0 AND unknown >= 0
               AND published >= 0 AND shadowed >= 0)
);
