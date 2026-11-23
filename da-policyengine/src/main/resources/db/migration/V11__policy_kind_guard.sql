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

-- POL-05 (decision D45, Q-881): closes the one bypass V10 left. A direct edit that set policy_kind to
-- LEGACY_RULE and blanked policy_document in one statement made an ENABLED deny invisible to the PDP,
-- which skips a LEGACY_RULE row that has no document (ADR-0136). The trigger refuses both halves:
--
--   1. a row of kind LEGACY_RULE with a non-blank policy_document, on insert and on update (a legacy
--      rule row never has a document; a row that gets one is a DSL row, as createPolicy and
--      updatePolicy set it);
--   2. a change of policy_kind from DSL to LEGACY_RULE (a row that ever was a DSL row stays one, so
--      blanking its document leaves an invalid deny for the PDP to fail closed on).
--
-- Rows written before this migration are untouched: V10 made every row with a non-blank document DSL,
-- so no stored row breaks the rule. Statements are idempotent. A soft-deleted row stays frozen by
-- trg_policies_soft_delete_guard (V9); this trigger adds nothing for it.

CREATE OR REPLACE FUNCTION policies_kind_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.policy_kind = 'LEGACY_RULE' AND NEW.policy_document IS NOT NULL AND NEW.policy_document ~ '\S' THEN
        RAISE EXCEPTION 'policy % is a LEGACY_RULE row and cannot hold a policy_document', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.policy_kind = 'DSL' AND NEW.policy_kind = 'LEGACY_RULE' THEN
        RAISE EXCEPTION 'policy % is a DSL row and cannot become a LEGACY_RULE row', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_policies_kind_guard ON policies;
CREATE TRIGGER trg_policies_kind_guard
    BEFORE INSERT OR UPDATE ON policies
    FOR EACH ROW EXECUTE FUNCTION policies_kind_guard();
