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

-- APPR-03 (Q-676): a service account withdraws a pending approval request on behalf of a person.
--
-- A new terminal state, WITHDRAWN, reached only from PENDING. The request keeps who withdrew it
-- (the service principal), for whom, why and when (the injected clock); resolved_at carries the
-- same instant, so ck_approval_requests_resolved holds. The four columns are set together with the
-- state, in the one update that leaves PENDING, and never change afterwards (guard trigger below).
-- Every statement is idempotent.

ALTER TABLE approval_requests ADD COLUMN IF NOT EXISTS withdrawn_by    VARCHAR(255);
ALTER TABLE approval_requests ADD COLUMN IF NOT EXISTS on_behalf_of    VARCHAR(255);
ALTER TABLE approval_requests ADD COLUMN IF NOT EXISTS withdraw_reason TEXT;
ALTER TABLE approval_requests ADD COLUMN IF NOT EXISTS withdrawn_at    TIMESTAMP WITH TIME ZONE;

ALTER TABLE approval_requests DROP CONSTRAINT IF EXISTS ck_approval_requests_status;
ALTER TABLE approval_requests ADD CONSTRAINT ck_approval_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'APPROVED_BREAK_GLASS', 'REJECTED', 'EXPIRED', 'WITHDRAWN'));

-- A withdrawn request names all four facts (a reason of at least 10 characters); every other
-- request names none of them.
ALTER TABLE approval_requests DROP CONSTRAINT IF EXISTS ck_approval_requests_withdrawal;
ALTER TABLE approval_requests ADD CONSTRAINT ck_approval_requests_withdrawal CHECK (
    (status = 'WITHDRAWN'
        AND length(btrim(withdrawn_by)) > 0
        AND length(btrim(on_behalf_of)) > 0
        AND length(btrim(withdraw_reason)) >= 10
        AND withdrawn_at IS NOT NULL
        AND withdrawn_at = resolved_at)
    OR (status <> 'WITHDRAWN'
        AND withdrawn_by IS NULL AND on_behalf_of IS NULL AND withdraw_reason IS NULL AND withdrawn_at IS NULL));

-- The guard of V7, plus the withdrawal columns: they are part of the immutable result once the
-- request has left PENDING, and a withdrawn request is as final as any other terminal one.
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
            OR NEW.approvals IS DISTINCT FROM OLD.approvals
            OR NEW.withdrawn_by IS DISTINCT FROM OLD.withdrawn_by
            OR NEW.on_behalf_of IS DISTINCT FROM OLD.on_behalf_of
            OR NEW.withdraw_reason IS DISTINCT FROM OLD.withdraw_reason
            OR NEW.withdrawn_at IS DISTINCT FROM OLD.withdrawn_at) THEN
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
