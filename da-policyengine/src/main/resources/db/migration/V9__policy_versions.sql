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

-- AUD-02a: policy history. Every change of a policy (create, update, status change, delete) adds one
-- immutable row to policy_versions, and the same change is chained in the audit log stream
-- govern.policy (docs/specs/audit-log.md). A policy is never deleted any more: a delete sets
-- deleted_at, disables it and freezes the row, so its history stays readable.
-- (V8 went to APPR-03, so this is V9: Q-689.) Every statement is idempotent.

-- 1. Soft delete and the current version number on the policy.
ALTER TABLE policies ADD COLUMN IF NOT EXISTS deleted_at         TIMESTAMP WITH TIME ZONE;
ALTER TABLE policies ADD COLUMN IF NOT EXISTS deleted_by_user_id UUID;
ALTER TABLE policies ADD COLUMN IF NOT EXISTS current_version    INTEGER NOT NULL DEFAULT 0;

DO $$ BEGIN
    ALTER TABLE policies ADD CONSTRAINT ck_policies_current_version CHECK (current_version >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- A deleted policy keeps its name in its history, and the name becomes free for a new policy: the
-- name stays unique among live policies only. V2 created the constraint as "name ... UNIQUE"; a
-- database once bootstrapped by Hibernate may carry another name for it, so drop every single-column
-- unique constraint on name, whatever it is called.
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT con.conname
          FROM pg_catalog.pg_constraint con
          JOIN pg_catalog.pg_attribute att
            ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
         WHERE con.conrelid = 'policies'::regclass
           AND con.contype = 'u'
           AND array_length(con.conkey, 1) = 1
           AND att.attname = 'name'
    LOOP
        EXECUTE format('ALTER TABLE policies DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_policies_live_name ON policies (name) WHERE deleted_at IS NULL;

-- 2. One immutable row per version. No foreign key to policies: the history never depends on the
--    policy row. snapshot is the exact JSON text whose SHA-256 is content_sha256, and that hash is
--    in the govern.policy audit entry at audit_seq, so a changed version row no longer matches the
--    chain.
CREATE TABLE IF NOT EXISTS policy_versions (
    id               UUID                     NOT NULL,
    policy_id        UUID                     NOT NULL,
    version          INTEGER                  NOT NULL,
    change_type      VARCHAR(16)              NOT NULL,
    name             VARCHAR(255)             NOT NULL,
    status           VARCHAR(16)              NOT NULL,
    deleted          BOOLEAN                  NOT NULL,
    snapshot         TEXT                     NOT NULL,
    content_sha256   VARCHAR(64)              NOT NULL,
    changed_by       VARCHAR(255)             NOT NULL,
    changed_by_kind  VARCHAR(16)              NOT NULL,
    changed_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    audit_seq        BIGINT                   NOT NULL,
    audit_entry_hash VARCHAR(64)              NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_policy_versions_policy_version UNIQUE (policy_id, version),
    CONSTRAINT ck_policy_versions_version CHECK (version >= 1),
    CONSTRAINT ck_policy_versions_change_type
        CHECK (change_type IN ('BASELINE', 'CREATED', 'UPDATED', 'STATUS_CHANGED', 'DELETED')),
    CONSTRAINT ck_policy_versions_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_policy_versions_deleted CHECK (deleted = (change_type = 'DELETED')),
    CONSTRAINT ck_policy_versions_hashes
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$' AND audit_entry_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_policy_versions_audit_seq CHECK (audit_seq >= 1),
    CONSTRAINT ck_policy_versions_changed_by_kind CHECK (changed_by_kind IN ('USER', 'SERVICE', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS ix_policy_versions_changed_at ON policy_versions (changed_at);

-- Versions are immutable: every UPDATE or DELETE fails, and so does TRUNCATE.
CREATE OR REPLACE FUNCTION policy_versions_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable (% refused)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_policy_versions_immutable ON policy_versions;
CREATE TRIGGER trg_policy_versions_immutable
    BEFORE UPDATE OR DELETE ON policy_versions
    FOR EACH ROW EXECUTE FUNCTION policy_versions_immutable();

DROP TRIGGER IF EXISTS trg_policy_versions_no_truncate ON policy_versions;
CREATE TRIGGER trg_policy_versions_no_truncate
    BEFORE TRUNCATE ON policy_versions
    FOR EACH STATEMENT EXECUTE FUNCTION policy_versions_immutable();

-- A new version follows the newest one of its policy without a gap, and nothing follows a delete.
-- The advisory lock serialises inserts per policy, so two writers cannot both pass the check. The table
-- is named through the trigger's own schema, so the check does not depend on the session's search_path.
CREATE OR REPLACE FUNCTION policy_versions_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    head_version INTEGER;
    head_deleted BOOLEAN;
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('policy_versions:' || NEW.policy_id::text, 0));
    EXECUTE format('SELECT version, deleted FROM %I.%I WHERE policy_id = $1 ORDER BY version DESC LIMIT 1',
                   TG_TABLE_SCHEMA, TG_TABLE_NAME)
       INTO head_version, head_deleted
      USING NEW.policy_id;
    IF head_deleted THEN
        RAISE EXCEPTION 'policy % is deleted; its history is closed', NEW.policy_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.version <> COALESCE(head_version, 0) + 1 THEN
        RAISE EXCEPTION 'policy % version % does not follow version %', NEW.policy_id, NEW.version,
            COALESCE(head_version, 0)
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_policy_versions_append_only ON policy_versions;
CREATE TRIGGER trg_policy_versions_append_only
    BEFORE INSERT ON policy_versions
    FOR EACH ROW EXECUTE FUNCTION policy_versions_append_only();

-- 3. A policy is soft deleted only. The row of a deleted policy is frozen, the delete cannot be
--    undone and the version number never goes back.
CREATE OR REPLACE FUNCTION policies_soft_delete_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'policies are soft deleted; % refused', TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'policy % is deleted and cannot change', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.current_version < OLD.current_version THEN
        RAISE EXCEPTION 'version of policy % cannot go down', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_policies_soft_delete_guard ON policies;
CREATE TRIGGER trg_policies_soft_delete_guard
    BEFORE UPDATE OR DELETE ON policies
    FOR EACH ROW EXECUTE FUNCTION policies_soft_delete_guard();

DROP TRIGGER IF EXISTS trg_policies_no_truncate ON policies;
CREATE TRIGGER trg_policies_no_truncate
    BEFORE TRUNCATE ON policies
    FOR EACH STATEMENT EXECUTE FUNCTION policies_soft_delete_guard();
