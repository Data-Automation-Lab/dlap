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

-- SEC-04 (ADR-0057): retire the MVEL rule conditions.
--
-- policy_rules.condition and event_rules.condition held MVEL expressions, which are
-- executable code. MVEL is no longer on the engine's classpath, so these conditions can
-- never run again. This migration:
--   1. copies every stored rule, with its owner's name, status and creator, to the archive
--      table retired_mvel_rules (for operator review; nothing reads it to evaluate);
--   2. disables every policy and every subscription that had a rule, because its behaviour
--      depended on a condition that can no longer run;
--   3. empties policy_rules and event_rules, deleting only rows already archived, and makes
--      both tables refuse new rows;
--   4. adds the typed subscription filters for asset prefixes and labels.
-- Every statement is idempotent.

-- 1. Archive.
CREATE TABLE IF NOT EXISTS retired_mvel_rules (
    id UUID NOT NULL,
    source_table varchar(32) NOT NULL CHECK (source_table IN ('policy_rules','event_rules')),
    owner_id UUID NOT NULL,
    owner_name varchar(255),
    owner_status_before varchar(255),
    owner_condition_logic TEXT,
    owner_created_by_user_id UUID,
    rule_name varchar(255) NOT NULL,
    rule_description varchar(1000),
    condition TEXT NOT NULL,
    priority integer,
    enabled boolean,
    rule_payload jsonb,
    rule_created_at timestamp(6) with time zone,
    rule_created_by_user_id UUID,
    retired_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    retired_by varchar(64) NOT NULL DEFAULT 'flyway:V5__retire_mvel_rules',
    PRIMARY KEY (source_table, id)
);

COMMENT ON TABLE retired_mvel_rules IS
    'SEC-04 / ADR-0057: MVEL rule conditions archived by V5. Review only; never evaluated.';

INSERT INTO retired_mvel_rules (id, source_table, owner_id, owner_name, owner_status_before,
        owner_condition_logic, owner_created_by_user_id, rule_name, rule_description, condition,
        priority, enabled, rule_payload, rule_created_at, rule_created_by_user_id)
SELECT r.id, 'policy_rules', r.policy_id, p.name, p.status,
       p.condition_logic, p.created_by_user_id, r.name, r.description, r.condition,
       r.priority, NULL, r.actions, r.created_at, NULL
FROM policy_rules r
LEFT JOIN policies p ON p.id = r.policy_id
ON CONFLICT (source_table, id) DO NOTHING;

INSERT INTO retired_mvel_rules (id, source_table, owner_id, owner_name, owner_status_before,
        owner_condition_logic, owner_created_by_user_id, rule_name, rule_description, condition,
        priority, enabled, rule_payload, rule_created_at, rule_created_by_user_id)
SELECT r.id, 'event_rules', r.subscription_id, s.name, s.status,
       NULL, s.created_by_user_id, r.name, r.description, r.condition,
       r.priority, r.enabled, r.parameters, r.created_at, r.created_by_user_id
FROM event_rules r
LEFT JOIN event_subscriptions s ON s.id = r.subscription_id
ON CONFLICT (source_table, id) DO NOTHING;

-- 2. Disable the owners. A policy or subscription without a rule is unchanged.
UPDATE policies
SET status = 'DISABLED', updated_at = now()
WHERE status <> 'DISABLED'
  AND id IN (SELECT policy_id FROM policy_rules);

UPDATE event_subscriptions
SET status = 'DISABLED', updated_at = now()
WHERE status IN ('ACTIVE', 'PAUSED')
  AND id IN (SELECT subscription_id FROM event_rules);

-- 3. Empty the rule tables (archived rows only) and refuse new rows. A CHECK constraint
--    added NOT VALID skips existing rows but applies to every INSERT and UPDATE, so no code
--    path can store a condition again. Deletes stay allowed.
DELETE FROM policy_rules r
WHERE EXISTS (SELECT 1 FROM retired_mvel_rules a
              WHERE a.source_table = 'policy_rules' AND a.id = r.id);

DELETE FROM event_rules r
WHERE EXISTS (SELECT 1 FROM retired_mvel_rules a
              WHERE a.source_table = 'event_rules' AND a.id = r.id);

DO $$ BEGIN
    ALTER TABLE policy_rules
        ADD CONSTRAINT ck_policy_rules_retired CHECK (false) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE event_rules
        ADD CONSTRAINT ck_event_rules_retired CHECK (false) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 4. Typed subscription filters (event types, severities and source services already have
--    their own tables since V2).
CREATE TABLE IF NOT EXISTS event_subscription_asset_prefixes (
    subscription_id UUID NOT NULL,
    asset_prefix varchar(2048) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_subscription_asset_prefixes_subscription
    ON event_subscription_asset_prefixes (subscription_id);

DO $$ BEGIN
    ALTER TABLE event_subscription_asset_prefixes
        ADD CONSTRAINT fk_event_subscription_asset_prefixes_subscription
        FOREIGN KEY (subscription_id) REFERENCES event_subscriptions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS event_subscription_labels (
    subscription_id UUID NOT NULL,
    label varchar(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_subscription_labels_subscription
    ON event_subscription_labels (subscription_id);

DO $$ BEGIN
    ALTER TABLE event_subscription_labels
        ADD CONSTRAINT fk_event_subscription_labels_subscription
        FOREIGN KEY (subscription_id) REFERENCES event_subscriptions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
