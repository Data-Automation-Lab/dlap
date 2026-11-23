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

-- APPR-01: approvals and dual control (docs/specs/approvals.md).
--
-- Three tables in this service's own schema:
--   approval_requests   one row per destructive or approval-gated action that waits for people.
--                       Identity columns never change; the state moves once, from PENDING to a
--                       terminal state (trigger trg_approval_requests_guard).
--   approval_decisions  one row per person per request: APPROVE, REJECT or BREAK_GLASS, with the
--                       approver's JWT sub and a comment. Rows are immutable: UPDATE, DELETE and
--                       TRUNCATE raise an error (triggers trg_approval_decisions_*).
--   approval_outbox     the Kafka messages a state change produces, written in the same
--                       transaction and relayed by ApprovalMaintenance (at least once).
-- No foreign key points at policies: deleting a policy never deletes its approval history.
-- Every statement is idempotent.

CREATE TABLE IF NOT EXISTS approval_requests (
    id                  UUID                     NOT NULL,
    request_key         VARCHAR(200)             NOT NULL,
    origin              VARCHAR(16)              NOT NULL,
    action_class        VARCHAR(16)              NOT NULL,
    action_type         VARCHAR(64)              NOT NULL,
    action_parameters   JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    asset_id            UUID                     NOT NULL,
    asset_uri           TEXT,
    provider            VARCHAR(32),
    service_type        VARCHAR(64),
    policy_db_id        UUID,
    policy_id           VARCHAR(255),
    policy_name         VARCHAR(255),
    fire_id             VARCHAR(64),
    requested_by        VARCHAR(255)             NOT NULL,
    requester_kind      VARCHAR(16)              NOT NULL,
    submitted_by        VARCHAR(255)             NOT NULL,
    excluded_subjects   TEXT[]                   NOT NULL,
    reason              TEXT                     NOT NULL,
    required_approvals  INTEGER                  NOT NULL,
    approvals           INTEGER                  NOT NULL DEFAULT 0,
    status              VARCHAR(24)              NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at         TIMESTAMP WITH TIME ZONE,
    action_payload      JSONB,
    dispatch_status     VARCHAR(16)              NOT NULL DEFAULT 'NONE',
    dispatched_at       TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uq_approval_requests_key UNIQUE (request_key),
    CONSTRAINT ck_approval_requests_origin CHECK (origin IN ('POLICY', 'API')),
    CONSTRAINT ck_approval_requests_class
        CHECK (action_class IN ('ARCHIVE', 'SOFT_DELETE', 'HARD_DELETE', 'OTHER')),
    CONSTRAINT ck_approval_requests_requester CHECK (requester_kind IN ('POLICY', 'USER', 'SERVICE')),
    CONSTRAINT ck_approval_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'APPROVED_BREAK_GLASS', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_approval_requests_dispatch CHECK (dispatch_status IN ('NONE', 'QUEUED', 'SENT', 'SHADOWED')),
    -- The dual-control floor lives in the database too: a hard delete never needs fewer than two.
    CONSTRAINT ck_approval_requests_required CHECK (required_approvals BETWEEN 1 AND 10
        AND (action_class <> 'HARD_DELETE' OR required_approvals >= 2)),
    CONSTRAINT ck_approval_requests_approvals CHECK (approvals >= 0),
    CONSTRAINT ck_approval_requests_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_approval_requests_resolved CHECK ((status = 'PENDING') = (resolved_at IS NULL)),
    CONSTRAINT ck_approval_requests_requester_excluded CHECK (requested_by = ANY (excluded_subjects)
        AND submitted_by = ANY (excluded_subjects))
);

-- One open request per (policy, asset, action): a schedule that fires every day for the same asset
-- adds no second request while the first one waits.
CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_requests_open_policy_fire
    ON approval_requests (policy_db_id, asset_id, action_type)
    WHERE status = 'PENDING' AND origin = 'POLICY';

CREATE INDEX IF NOT EXISTS ix_approval_requests_pending_expiry
    ON approval_requests (expires_at) WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS ix_approval_requests_created
    ON approval_requests (created_at DESC, id);

CREATE INDEX IF NOT EXISTS ix_approval_requests_asset
    ON approval_requests (asset_id);

CREATE TABLE IF NOT EXISTS approval_decisions (
    seq             BIGINT                   GENERATED ALWAYS AS IDENTITY,
    id              UUID                     NOT NULL,
    request_id      UUID                     NOT NULL,
    approver_sub    VARCHAR(255)             NOT NULL,
    approver_name   VARCHAR(255),
    approver_roles  TEXT[]                   NOT NULL,
    decision        VARCHAR(16)              NOT NULL,
    comment         TEXT                     NOT NULL,
    decided_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_approval_decisions_request FOREIGN KEY (request_id) REFERENCES approval_requests (id),
    -- Distinct approvers: one decision per person per request.
    CONSTRAINT uq_approval_decisions_person UNIQUE (request_id, approver_sub),
    CONSTRAINT ck_approval_decisions_kind CHECK (decision IN ('APPROVE', 'REJECT', 'BREAK_GLASS')),
    CONSTRAINT ck_approval_decisions_comment CHECK (length(btrim(comment)) > 0),
    CONSTRAINT ck_approval_decisions_sub CHECK (length(btrim(approver_sub)) > 0)
);

-- Decision order is insertion order (seq), also for two decisions in the same microsecond.
CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_decisions_seq ON approval_decisions (request_id, seq);

CREATE TABLE IF NOT EXISTS approval_outbox (
    seq         BIGINT                   GENERATED ALWAYS AS IDENTITY,
    id          UUID                     NOT NULL,
    request_id  UUID                     NOT NULL,
    kind        VARCHAR(16)              NOT NULL,
    topic       VARCHAR(255)             NOT NULL,
    msg_key     VARCHAR(255)             NOT NULL,
    payload     TEXT                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at     TIMESTAMP WITH TIME ZONE,
    attempts    INTEGER                  NOT NULL DEFAULT 0,
    last_error  VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_approval_outbox_request FOREIGN KEY (request_id) REFERENCES approval_requests (id),
    CONSTRAINT ck_approval_outbox_kind CHECK (kind IN ('REQUESTED', 'DECIDED', 'BREAK_GLASS', 'ACTION'))
);

-- The relay sends in seq order: the order the messages were written, also within one transaction.
CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_outbox_seq ON approval_outbox (seq);

CREATE INDEX IF NOT EXISTS ix_approval_outbox_unsent
    ON approval_outbox (seq) WHERE sent_at IS NULL;

-- Decision rows are immutable: every UPDATE or DELETE fails, and so does TRUNCATE. The function
-- names the table it guards, so the TRUNCATE guard of approval_requests below reuses it.
CREATE OR REPLACE FUNCTION approval_decisions_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable (% refused)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_approval_decisions_immutable ON approval_decisions;
CREATE TRIGGER trg_approval_decisions_immutable
    BEFORE UPDATE OR DELETE ON approval_decisions
    FOR EACH ROW EXECUTE FUNCTION approval_decisions_immutable();

DROP TRIGGER IF EXISTS trg_approval_decisions_no_truncate ON approval_decisions;
CREATE TRIGGER trg_approval_decisions_no_truncate
    BEFORE TRUNCATE ON approval_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION approval_decisions_immutable();

-- A request keeps what was asked, by whom and under which rule. Only the state may move, once,
-- from PENDING; the approval count only grows; dispatch moves forward after the decision.
CREATE OR REPLACE FUNCTION approval_requests_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'approval_requests rows cannot be deleted'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.request_key IS DISTINCT FROM OLD.request_key
        OR NEW.origin IS DISTINCT FROM OLD.origin
        OR NEW.action_class IS DISTINCT FROM OLD.action_class
        OR NEW.action_type IS DISTINCT FROM OLD.action_type
        OR NEW.action_parameters IS DISTINCT FROM OLD.action_parameters
        OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
        OR NEW.asset_uri IS DISTINCT FROM OLD.asset_uri
        OR NEW.provider IS DISTINCT FROM OLD.provider
        OR NEW.service_type IS DISTINCT FROM OLD.service_type
        OR NEW.policy_db_id IS DISTINCT FROM OLD.policy_db_id
        OR NEW.policy_id IS DISTINCT FROM OLD.policy_id
        OR NEW.policy_name IS DISTINCT FROM OLD.policy_name
        OR NEW.fire_id IS DISTINCT FROM OLD.fire_id
        OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
        OR NEW.requester_kind IS DISTINCT FROM OLD.requester_kind
        OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
        OR NEW.excluded_subjects IS DISTINCT FROM OLD.excluded_subjects
        OR NEW.reason IS DISTINCT FROM OLD.reason
        OR NEW.required_approvals IS DISTINCT FROM OLD.required_approvals
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR NEW.action_payload IS DISTINCT FROM OLD.action_payload THEN
        RAISE EXCEPTION 'approval_requests identity columns are immutable'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'PENDING' AND (NEW.status IS DISTINCT FROM OLD.status
            OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
            OR NEW.approvals IS DISTINCT FROM OLD.approvals) THEN
        RAISE EXCEPTION 'approval request % is % and cannot change', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.approvals < OLD.approvals THEN
        RAISE EXCEPTION 'approval count of request % cannot go down', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.dispatch_status IN ('SENT', 'SHADOWED') AND NEW.dispatch_status IS DISTINCT FROM OLD.dispatch_status THEN
        RAISE EXCEPTION 'dispatch of approval request % is final', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_approval_requests_guard ON approval_requests;
CREATE TRIGGER trg_approval_requests_guard
    BEFORE UPDATE OR DELETE ON approval_requests
    FOR EACH ROW EXECUTE FUNCTION approval_requests_guard();

DROP TRIGGER IF EXISTS trg_approval_requests_no_truncate ON approval_requests;
CREATE TRIGGER trg_approval_requests_no_truncate
    BEFORE TRUNCATE ON approval_requests
    FOR EACH STATEMENT EXECUTE FUNCTION approval_decisions_immutable();
