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

-- AUD-01: signed checkpoints of the dlap_common audit streams (docs/specs/audit-log.md,
-- section "Checkpoints"). One row per stream and covered position. The signed part (stream_id,
-- seq, entry_hash, key_id, signed_at, payload, signature) never changes once written: the trigger
-- below rejects such an update and every delete. Only the export bookkeeping columns change, as
-- the exporter retries until the write-once copy exists.

CREATE TABLE IF NOT EXISTS dlap_admin.audit_checkpoints (
    stream_id         VARCHAR(64)              NOT NULL,
    seq               BIGINT                   NOT NULL,
    entry_hash        CHAR(64)                 NOT NULL,
    key_id            CHAR(16)                 NOT NULL,
    signed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    payload           TEXT                     NOT NULL,
    signature         TEXT                     NOT NULL,
    export_sink       VARCHAR(16),
    export_location   TEXT,
    exported_at       TIMESTAMP WITH TIME ZONE,
    export_attempts   INTEGER                  NOT NULL DEFAULT 0,
    last_export_error TEXT,
    CONSTRAINT pk_audit_checkpoints PRIMARY KEY (stream_id, seq),
    CONSTRAINT ck_audit_checkpoints_seq CHECK (seq >= 1),
    CONSTRAINT ck_audit_checkpoints_entry_hash CHECK (entry_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_checkpoints_key_id CHECK (key_id ~ '^[0-9a-f]{16}$'),
    CONSTRAINT ck_audit_checkpoints_exported CHECK ((exported_at IS NULL) = (export_location IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_audit_checkpoints_unexported
    ON dlap_admin.audit_checkpoints (signed_at) WHERE exported_at IS NULL;

CREATE OR REPLACE FUNCTION dlap_admin.audit_checkpoints_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit checkpoints are never deleted (docs/specs/audit-log.md)'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.stream_id IS DISTINCT FROM OLD.stream_id OR NEW.seq IS DISTINCT FROM OLD.seq
            OR NEW.entry_hash IS DISTINCT FROM OLD.entry_hash OR NEW.key_id IS DISTINCT FROM OLD.key_id
            OR NEW.signed_at IS DISTINCT FROM OLD.signed_at OR NEW.payload IS DISTINCT FROM OLD.payload
            OR NEW.signature IS DISTINCT FROM OLD.signature THEN
        RAISE EXCEPTION 'the signed part of an audit checkpoint never changes (docs/specs/audit-log.md)'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$fn$;

DROP TRIGGER IF EXISTS tr_audit_checkpoints_guard ON dlap_admin.audit_checkpoints;
CREATE TRIGGER tr_audit_checkpoints_guard
    BEFORE UPDATE OR DELETE ON dlap_admin.audit_checkpoints
    FOR EACH ROW EXECUTE FUNCTION dlap_admin.audit_checkpoints_guard();

COMMENT ON TABLE dlap_admin.audit_checkpoints IS
    'Ed25519-signed heads of the dlap_common audit streams and their write-once export (AUD-01)';
COMMENT ON COLUMN dlap_admin.audit_checkpoints.payload IS
    'Canonical checkpoint payload, the exact bytes that were signed (dlap-audit-checkpoint/1)';
