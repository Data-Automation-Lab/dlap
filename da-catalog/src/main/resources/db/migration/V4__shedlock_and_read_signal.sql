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

-- CAT-03 (ADR-0046 "Retention", docs/specs/asset-descriptor.md): the catalog retention
-- machinery. The file name is the one the work-package plan reserved for catalog V4; the
-- migration holds the five tables the retention sweep needs, all in da-catalog's own schema:
--
--   shedlock             lock table of ShedLock's JDBC provider (plan rule R7): the sweep and the
--                        retention-due publisher are cluster-wide singletons.
--   retention_rule       the rule store: POLICY rules (setRetention actions, one per asset and
--                        policy) and MANUAL rules (written through the API by a named role).
--                        LABEL_DEFAULT rules are configuration and PROVIDER rules live in
--                        dlap_assets.technical_metadata, so neither is stored here.
--   log_binding_status   the read signal: the latest dlap.log.v1.LogBindingStatus per binding,
--                        read from dlap.logingest.binding.status.
--   retention_outbox     transactional outbox of retention-due events: a row is written in the
--                        transaction that changes retention_status and published afterwards.
--   retention_audit      append-only record of the actions a named role takes (legal hold,
--                        MANUAL rules).
--
-- None of these tables is mapped by Hibernate; they are written through JDBC. Idempotent
-- (CREATE ... IF NOT EXISTS) per CLAUDE.md section 7. dlap_assets is in dlap_common and is
-- referenced by asset_id without a foreign key, like processed_event (V3).

-- 1. shedlock: the column set ShedLock's JdbcTemplateLockProvider requires, unchanged (same as
--    da-discovery V10 and da-logingest V3). Timestamps are UTC, hence TIMESTAMP without zone.
CREATE TABLE IF NOT EXISTS dlap_catalog.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- 2. retention_rule: the rule store.
--    source POLICY:  one row per (asset, policy); a newer action of the same policy replaces it
--                    (issued_at is the policy engine's firedAt and orders the actions).
--    source MANUAL:  any number of rows per asset; created_by names the operator.
--    basis FIXED_DATE carries expires_at and no days; every other basis carries days and no
--    expires_at (RetentionBasis.requiresDays). mode is the setRetention mode (soft or hard).
CREATE TABLE IF NOT EXISTS dlap_catalog.retention_rule (
    rule_id     UUID          NOT NULL,
    asset_id    UUID          NOT NULL,
    source      VARCHAR(16)   NOT NULL,
    basis       VARCHAR(20)   NOT NULL,
    days        INTEGER,
    expires_at  TIMESTAMPTZ,
    policy_id   UUID,
    mode        VARCHAR(8),
    reason      VARCHAR(1000),
    issued_at   TIMESTAMPTZ   NOT NULL,
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_retention_rule PRIMARY KEY (rule_id),
    CONSTRAINT ck_retention_rule_source CHECK (source IN ('POLICY', 'MANUAL')),
    CONSTRAINT ck_retention_rule_basis CHECK (basis IN ('CREATED', 'LAST_MODIFIED', 'LAST_ACCESSED', 'FIXED_DATE')),
    CONSTRAINT ck_retention_rule_shape CHECK (
        (basis = 'FIXED_DATE' AND expires_at IS NOT NULL AND days IS NULL)
        OR (basis <> 'FIXED_DATE' AND days IS NOT NULL AND days >= 0 AND expires_at IS NULL)),
    CONSTRAINT ck_retention_rule_policy CHECK (
        (source = 'POLICY' AND policy_id IS NOT NULL) OR (source = 'MANUAL' AND policy_id IS NULL)),
    CONSTRAINT ck_retention_rule_mode CHECK (mode IS NULL OR mode IN ('soft', 'hard'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_retention_rule_policy
    ON dlap_catalog.retention_rule (asset_id, policy_id) WHERE source = 'POLICY';
CREATE INDEX IF NOT EXISTS idx_retention_rule_asset
    ON dlap_catalog.retention_rule (asset_id);

COMMENT ON TABLE dlap_catalog.retention_rule IS
    'CAT-03: retention rules kept at their source (POLICY setRetention actions, MANUAL operator rules). The evaluator reads them on every run; dlap_assets.retention_* hold only the result.';
COMMENT ON COLUMN dlap_catalog.retention_rule.issued_at IS
    'POLICY: firedAt of the setRetention action (orders actions of one policy). MANUAL: when the operator created the rule.';

-- 3. log_binding_status: the latest binding status per binding_id (the topic is compacted, the
--    latest message per key is the current state). source_provider is the SourceProvider enum
--    name, which equals dlap_assets.cloud_provider for the same cloud. read_coverage is the
--    ReadCoverage enum name. low_watermark is the event-time cursor: everything before it is
--    acknowledged, so reads are known to be captured only up to it.
CREATE TABLE IF NOT EXISTS dlap_catalog.log_binding_status (
    binding_id          VARCHAR(255) NOT NULL,
    source_provider     VARCHAR(32)  NOT NULL,
    source_kind         VARCHAR(64),
    scope_type          VARCHAR(64),
    scope_id            VARCHAR(255),
    state               VARCHAR(40)  NOT NULL,
    read_coverage       VARCHAR(40)  NOT NULL,
    read_coverage_since TIMESTAMPTZ,
    low_watermark       TIMESTAMPTZ,
    high_watermark      TIMESTAMPTZ,
    reported_time       TIMESTAMPTZ  NOT NULL,
    config_hash         VARCHAR(64),
    received_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_log_binding_status PRIMARY KEY (binding_id)
);

CREATE INDEX IF NOT EXISTS idx_log_binding_status_scope
    ON dlap_catalog.log_binding_status (source_provider, scope_id);

COMMENT ON TABLE dlap_catalog.log_binding_status IS
    'CAT-03: latest LogBindingStatus per binding. The read signal of the retention evaluator: an asset may be called not read only when its binding captures reads (log-event.md section 10).';

-- 4. retention_outbox: retention-due events written in the transaction that changes the status
--    and published by the publisher job (at least once; consumers dedupe on event_id). The
--    unique event_id makes a re-run of the same transition a no-op.
CREATE TABLE IF NOT EXISTS dlap_catalog.retention_outbox (
    outbox_id    BIGSERIAL     NOT NULL,
    event_id     VARCHAR(64)   NOT NULL,
    asset_uri    VARCHAR(2048) NOT NULL,
    payload      TEXT          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT pk_retention_outbox PRIMARY KEY (outbox_id),
    CONSTRAINT uq_retention_outbox_event UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_retention_outbox_unpublished
    ON dlap_catalog.retention_outbox (outbox_id) WHERE published_at IS NULL;

COMMENT ON TABLE dlap_catalog.retention_outbox IS
    'CAT-03: dlap.catalog.asset.retention-due events awaiting publication; published rows are pruned after dlap.catalog.retention.publish.outbox-retention.';

-- 5. retention_audit: append-only record of the actions a named role takes on retention.
--    occurred_at is DLAP's clock at the time of the action.
CREATE TABLE IF NOT EXISTS dlap_catalog.retention_audit (
    audit_id    BIGSERIAL     NOT NULL,
    asset_id    UUID          NOT NULL,
    action      VARCHAR(32)   NOT NULL,
    actor       VARCHAR(255)  NOT NULL,
    actor_id    UUID,
    reason      VARCHAR(1000),
    detail      TEXT,
    occurred_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_retention_audit PRIMARY KEY (audit_id)
);

CREATE INDEX IF NOT EXISTS idx_retention_audit_asset
    ON dlap_catalog.retention_audit (asset_id, occurred_at);

COMMENT ON TABLE dlap_catalog.retention_audit IS
    'CAT-03: who placed or released a legal hold and who added or removed a MANUAL retention rule, and when.';
