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

-- Upgrade delta for dlap_common (single source: init-scripts/02-create-common-schema.sql).
--
-- AUD-01: the tamper-evident audit log. Adds dlap_common.audit_streams (one writer role per
-- stream, seeded with the planned streams) and dlap_common.audit_log (one SHA-256 hash chain per
-- stream), the chain and append-only triggers, and SELECT plus INSERT for the service roles.
--
-- No backfill: nothing was audited into dlap_common before this delta.
--
-- Idempotent: safe to re-run. Applied by run-upgrades.sh (ADR-0056); fresh databases get the
-- same result from 02 directly. It must run as a superuser, so that the audit tables are owned by
-- the bootstrap superuser and by no service role (an owner could disable the triggers).

DO $guard$
BEGIN
    IF NOT (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user) THEN
        RAISE EXCEPTION '2026-10-21_audit_log.sql must run as a superuser, not as %', current_user;
    END IF;
END
$guard$;

-- =============================================================================
-- Tamper-evident audit log (AUD-01; normative spec docs/specs/audit-log.md).
-- One SHA-256 hash chain per stream. A row is accepted only when it extends the head of its
-- stream: seq is the previous seq plus one, prev_hash is the previous entry_hash (64 zeros for
-- seq 1) and entry_hash is the SHA-256 of the canonical hash input, which the insert trigger
-- recomputes. Rows are never updated, deleted or truncated: the triggers reject it for every
-- role, including the owner, and fire even under session_replication_role = replica (ENABLE
-- ALWAYS). Each stream has one writer role; the insert trigger refuses any other role.
-- Service roles get SELECT and INSERT only and own nothing here. A superuser can still disable
-- a trigger; the signed checkpoints that da-admin-service exports to object-lock storage are
-- what detect such a rewrite (spec section "Threat model").
-- Existing databases get this block from upgrades/2026-10-21_audit_log.sql; keep the two
-- identical (AuditLogSchemaContainerTest compares them).
-- =============================================================================
CREATE TABLE IF NOT EXISTS dlap_common.audit_streams (
    stream_id   VARCHAR(64) NOT NULL,
    writer_role VARCHAR(63) NOT NULL,
    description TEXT        NOT NULL,
    CONSTRAINT pk_audit_streams PRIMARY KEY (stream_id),
    CONSTRAINT ck_audit_streams_id CHECK (stream_id ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    CONSTRAINT ck_audit_streams_writer_role CHECK (writer_role ~ '^[a-z_][a-z0-9_]*$')
);

CREATE TABLE IF NOT EXISTS dlap_common.audit_log (
    stream_id       VARCHAR(64) NOT NULL,
    seq             BIGINT      NOT NULL,
    prev_hash       CHAR(64)    NOT NULL,
    entry_hash      CHAR(64)    NOT NULL,
    record          TEXT        NOT NULL,
    event_type      TEXT GENERATED ALWAYS AS (record::jsonb ->> 'eventType') STORED,
    target_id       TEXT GENERATED ALWAYS AS (record::jsonb -> 'target' ->> 'id') STORED,
    correlation_id  TEXT GENERATED ALWAYS AS (record::jsonb ->> 'correlationId') STORED,
    idempotency_key TEXT GENERATED ALWAYS AS (record::jsonb ->> 'idempotencyKey') STORED,
    inserted_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_audit_log PRIMARY KEY (stream_id, seq),
    CONSTRAINT fk_audit_log_stream FOREIGN KEY (stream_id) REFERENCES dlap_common.audit_streams (stream_id),
    CONSTRAINT ck_audit_log_seq_positive CHECK (seq >= 1),
    CONSTRAINT ck_audit_log_prev_hash_hex CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_log_entry_hash_hex CHECK (entry_hash ~ '^[0-9a-f]{64}$'),
    -- Canonical records are printable ASCII (every other character is a \u escape), so the
    -- hash input has one byte form in every client encoding.
    CONSTRAINT ck_audit_log_record_ascii CHECK (record ~ '^[ -~]+$'),
    CONSTRAINT ck_audit_log_record_object CHECK (jsonb_typeof(record::jsonb) = 'object')
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_log_idempotency
    ON dlap_common.audit_log (stream_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_log_target ON dlap_common.audit_log (target_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation ON dlap_common.audit_log (correlation_id);

-- The canonical hash input (spec section "Hash chain"): the ASCII text
-- 'DLAP-AUDIT-1' LF stream_id LF seq LF prev_hash LF record, hashed with SHA-256, lower-case hex.
-- Verifiers must not call this function: scripts/audit-verify.sh spells the same expression out
-- with pg_catalog functions, so a replaced function cannot hide a broken row.
CREATE OR REPLACE FUNCTION dlap_common.audit_entry_hash(p_stream_id TEXT, p_seq BIGINT,
        p_prev_hash TEXT, p_record TEXT)
    RETURNS TEXT
    LANGUAGE sql
    STABLE
    STRICT
AS $fn$
    SELECT pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
        'DLAP-AUDIT-1' || pg_catalog.chr(10) || p_stream_id || pg_catalog.chr(10)
        || p_seq::text || pg_catalog.chr(10) || p_prev_hash || pg_catalog.chr(10) || p_record,
        'UTF8')), 'hex')
$fn$;

CREATE OR REPLACE FUNCTION dlap_common.audit_log_before_insert()
    RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_writer    TEXT;
    v_last_seq  BIGINT;
    v_last_hash TEXT;
BEGIN
    SELECT s.writer_role INTO v_writer FROM dlap_common.audit_streams s WHERE s.stream_id = NEW.stream_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'audit stream "%" is not registered in dlap_common.audit_streams', NEW.stream_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.rolname = v_writer)
            OR NOT pg_catalog.pg_has_role(current_user, v_writer, 'MEMBER') THEN
        RAISE EXCEPTION 'role "%" may not write audit stream "%"', current_user, NEW.stream_id
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- One writer per stream at a time; writers take the same lock before they read the head.
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('dlap_common.audit_log:' || NEW.stream_id, 0));
    SELECT l.seq, l.entry_hash INTO v_last_seq, v_last_hash
        FROM dlap_common.audit_log l WHERE l.stream_id = NEW.stream_id ORDER BY l.seq DESC LIMIT 1;
    IF NOT FOUND THEN
        v_last_seq := 0;
        v_last_hash := pg_catalog.repeat('0', 64);
    END IF;
    IF NEW.seq <> v_last_seq + 1 OR NEW.prev_hash <> v_last_hash THEN
        RAISE EXCEPTION 'audit entry % of stream "%" does not extend the head (seq %)',
            NEW.seq, NEW.stream_id, v_last_seq
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.entry_hash <> dlap_common.audit_entry_hash(NEW.stream_id, NEW.seq, NEW.prev_hash, NEW.record) THEN
        RAISE EXCEPTION 'audit entry % of stream "%" has a wrong entry_hash', NEW.seq, NEW.stream_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    NEW.inserted_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION dlap_common.audit_reject_change()
    RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: audit rows are append-only (docs/specs/audit-log.md)',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END
$fn$;

DROP TRIGGER IF EXISTS tr_audit_log_chain ON dlap_common.audit_log;
CREATE TRIGGER tr_audit_log_chain
    BEFORE INSERT ON dlap_common.audit_log
    FOR EACH ROW EXECUTE FUNCTION dlap_common.audit_log_before_insert();
DROP TRIGGER IF EXISTS tr_audit_log_append_only ON dlap_common.audit_log;
CREATE TRIGGER tr_audit_log_append_only
    BEFORE UPDATE OR DELETE ON dlap_common.audit_log
    FOR EACH ROW EXECUTE FUNCTION dlap_common.audit_reject_change();
DROP TRIGGER IF EXISTS tr_audit_log_no_truncate ON dlap_common.audit_log;
CREATE TRIGGER tr_audit_log_no_truncate
    BEFORE TRUNCATE ON dlap_common.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION dlap_common.audit_reject_change();
DROP TRIGGER IF EXISTS tr_audit_streams_append_only ON dlap_common.audit_streams;
CREATE TRIGGER tr_audit_streams_append_only
    BEFORE UPDATE OR DELETE ON dlap_common.audit_streams
    FOR EACH ROW EXECUTE FUNCTION dlap_common.audit_reject_change();
DROP TRIGGER IF EXISTS tr_audit_streams_no_truncate ON dlap_common.audit_streams;
CREATE TRIGGER tr_audit_streams_no_truncate
    BEFORE TRUNCATE ON dlap_common.audit_streams
    FOR EACH STATEMENT EXECUTE FUNCTION dlap_common.audit_reject_change();
ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_chain;
ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only;
ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_no_truncate;
ALTER TABLE dlap_common.audit_streams ENABLE ALWAYS TRIGGER tr_audit_streams_append_only;
ALTER TABLE dlap_common.audit_streams ENABLE ALWAYS TRIGGER tr_audit_streams_no_truncate;

-- The planned writers (spec section "Streams"). A new stream is a new row, added by a delta;
-- a row is never changed, so a stream keeps its writer for life.
INSERT INTO dlap_common.audit_streams (stream_id, writer_role, description) VALUES
    ('retire.delete', 'da_autodelete_user', 'Delete path: requests, executions, restores (RET-02)'),
    ('retire.archive', 'da_autoarchival_user', 'Archive path: requests, executions, restores (RET-03)'),
    ('govern.approval', 'da_policyengine_user', 'Approval requests and decisions (APPR-01)'),
    ('govern.policy', 'da_policyengine_user', 'Policy versions and changes (AUD-02a)'),
    ('admin.access', 'da_admin_user', 'Role changes and identity-provider events (AUD-02b)'),
    ('report.export', 'da_reporting_user', 'Report and CSV exports (AUD-02b)')
ON CONFLICT (stream_id) DO NOTHING;

-- Privileges. Nobody but the owner (the bootstrap superuser) may change these tables; the
-- GRANT ... ON ALL TABLES blocks earlier in 02, and default privileges, may have granted more
-- on a re-run, so every such grant is taken away again before the narrow grants below.
REVOKE ALL ON TABLE dlap_common.audit_log, dlap_common.audit_streams FROM PUBLIC;
DO $do$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT DISTINCT a.grantee::regrole::text AS grantee
        FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) a
        WHERE c.oid IN ('dlap_common.audit_log'::regclass, 'dlap_common.audit_streams'::regclass)
          AND a.grantee <> 0
          AND a.grantee <> c.relowner
    LOOP
        EXECUTE pg_catalog.format('REVOKE ALL ON TABLE dlap_common.audit_log, dlap_common.audit_streams FROM %s',
            r.grantee);
    END LOOP;
END
$do$;
GRANT SELECT, INSERT ON TABLE dlap_common.audit_log TO
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;
GRANT SELECT ON TABLE dlap_common.audit_streams TO
    da_catalog_user, da_discovery_user, da_policyengine_user, da_reporting_user,
    da_autolabel_user, da_autoarchival_user, da_autodelete_user,
    da_autocompliance_user, da_admin_user, da_logingest_user;

COMMENT ON TABLE dlap_common.audit_streams IS
    'Audit streams and their single writer role (AUD-01, docs/specs/audit-log.md); append-only';
COMMENT ON TABLE dlap_common.audit_log IS
    'Tamper-evident audit log: one SHA-256 hash chain per stream (AUD-01, docs/specs/audit-log.md); append-only';
COMMENT ON COLUMN dlap_common.audit_log.record IS
    'Canonical JSON audit record (printable ASCII); hashed exactly as stored, never re-serialized';
COMMENT ON COLUMN dlap_common.audit_log.entry_hash IS
    'SHA-256 of DLAP-AUDIT-1 LF stream_id LF seq LF prev_hash LF record, lower-case hex';
COMMENT ON COLUMN dlap_common.audit_log.inserted_at IS
    'Database clock at insert; informational, not hashed (the record carries occurredAt and recordedAt)';
-- End of the audit log block (AUD-01).
