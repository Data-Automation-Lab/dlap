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

-- V3__soft_delete_records.sql
--
-- M5 / R-P5. Soft-delete window state. One row per asset that has
-- entered the soft-delete state via a policy DELETE action. The
-- scheduled sweeper hard-deletes rows whose
-- scheduled_hard_delete_at < now() AND status = 'SOFT_DELETED'.

SET search_path TO dlap_autodelete, public;

CREATE TABLE IF NOT EXISTS soft_delete_records (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                    UUID         NOT NULL,
    policy_id                   VARCHAR(128),
    policy_fire_id              UUID,
    queued_at                   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scheduled_hard_delete_at    TIMESTAMPTZ  NOT NULL,
    status                      VARCHAR(32)  NOT NULL DEFAULT 'SOFT_DELETED',
    audit_note                  TEXT,
    cloud_provider              VARCHAR(20)  NOT NULL,
    asset_uri                   VARCHAR(2048),
    restored_at                 TIMESTAMPTZ,
    restored_by_user_id         UUID,
    hard_deleted_at             TIMESTAMPTZ,
    last_error                  TEXT
);

CREATE INDEX IF NOT EXISTS idx_sdr_asset_id
    ON soft_delete_records (asset_id);
CREATE INDEX IF NOT EXISTS idx_sdr_status
    ON soft_delete_records (status);
CREATE INDEX IF NOT EXISTS idx_sdr_scheduled_hard_delete_at
    ON soft_delete_records (scheduled_hard_delete_at);

ALTER TABLE soft_delete_records
    DROP CONSTRAINT IF EXISTS chk_sdr_status;
ALTER TABLE soft_delete_records
    ADD CONSTRAINT chk_sdr_status
    CHECK (status IN ('SOFT_DELETED', 'RESTORED', 'HARD_DELETED', 'HARD_DELETE_FAILED'));
