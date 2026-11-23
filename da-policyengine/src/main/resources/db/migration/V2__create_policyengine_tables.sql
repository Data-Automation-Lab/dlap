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

-- Q-010: V1 created only the schema; with hibernate.ddl-auto=validate the
-- service could never boot against a fresh database. Tables below are the
-- Hibernate-derived DDL for every JPA entity in da-policyengine
-- (generated from the entity model, so `validate` is satisfied exactly).
-- IF NOT EXISTS keeps the migration safe on dev databases that were
-- previously bootstrapped via ddl-auto=update.

CREATE TABLE IF NOT EXISTS policies (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    created_by_user_id UUID,
    id UUID NOT NULL,
    updated_by_user_id UUID,
    description varchar(1000),
    condition_logic TEXT,
    name varchar(255) NOT NULL UNIQUE,
    status varchar(255) NOT NULL CHECK (status IN ('ENABLED','DISABLED')),
    actions jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS policy_rules (
    priority integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    id UUID NOT NULL,
    policy_id UUID NOT NULL,
    description varchar(1000),
    condition TEXT NOT NULL,
    name varchar(255) NOT NULL,
    actions jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS policy_evaluations (
    evaluated_at timestamp(6) with time zone NOT NULL,
    evaluation_triggered_by_user_id UUID,
    id UUID NOT NULL,
    policy_id UUID NOT NULL,
    status varchar(255) NOT NULL CHECK (status IN ('PASS','FAIL','ERROR','PENDING','NOT_APPLICABLE')),
    target_asset_id varchar(255) NOT NULL,
    evaluation_details jsonb,
    triggered_actions jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS policy_drafts (
    version integer NOT NULL,
    approved_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    rejected_at timestamp(6) with time zone,
    submitted_at timestamp(6) with time zone,
    target_implementation_date timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    approved_by_user_id UUID,
    base_policy_id UUID,
    created_by_user_id UUID NOT NULL,
    id UUID NOT NULL,
    published_by_user_id UUID,
    rejected_by_user_id UUID,
    submitted_by_user_id UUID,
    updated_by_user_id UUID,
    priority varchar(50),
    category varchar(100),
    change_summary TEXT,
    expected_impact TEXT,
    description TEXT,
    justification TEXT,
    condition_logic TEXT,
    name varchar(255) NOT NULL,
    status varchar(255) NOT NULL CHECK (status IN ('CREATED','SUBMITTED','UNDER_REVIEW','REQUIRES_CHANGES','APPROVED','REJECTED','PUBLISHED','ARCHIVED')),
    actions jsonb,
    approval_metadata jsonb,
    review_comments jsonb,
    rules_definition jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS policy_draft_tags (
    draft_id UUID NOT NULL,
    tag varchar(255)
);

CREATE TABLE IF NOT EXISTS policy_draft_stakeholders (
    draft_id UUID NOT NULL,
    stakeholder_id UUID
);

CREATE TABLE IF NOT EXISTS event_subscriptions (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    created_by_user_id UUID,
    id UUID NOT NULL,
    updated_by_user_id UUID,
    user_id UUID NOT NULL,
    description varchar(1000),
    name varchar(255) NOT NULL,
    status varchar(255) NOT NULL CHECK (status IN ('ACTIVE','PAUSED','DISABLED','ARCHIVED')),
    action_config jsonb,
    notification_config jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS event_subscription_types (
    subscription_id UUID NOT NULL,
    event_type varchar(255) CHECK (event_type IN ('POLICY_VIOLATION','POLICY_EVALUATION_COMPLETED','POLICY_APPLIED','POLICY_RULE_TRIGGERED','ASSET_DISCOVERED','ASSET_METADATA_CHANGED','ASSET_CLASSIFICATION_CHANGED','ASSET_ACCESS_DETECTED','COMPLIANCE_VIOLATION','COMPLIANCE_SCAN_COMPLETED','REGULATORY_CHANGE','ASSET_ARCHIVAL_SCHEDULED','ASSET_ARCHIVAL_COMPLETED','ASSET_DELETION_SCHEDULED','ASSET_DELETION_COMPLETED','SYSTEM_ALERT','SERVICE_HEALTH_ISSUE','DATA_QUALITY_ISSUE','SECURITY_INCIDENT','USER_ACCESS_ANOMALY','UNAUTHORIZED_ACCESS_ATTEMPT','CUSTOM_EVENT'))
);

CREATE TABLE IF NOT EXISTS event_subscription_severities (
    subscription_id UUID NOT NULL,
    severity varchar(255) CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO'))
);

CREATE TABLE IF NOT EXISTS event_subscription_sources (
    subscription_id UUID NOT NULL,
    source_service varchar(255)
);

CREATE TABLE IF NOT EXISTS event_rules (
    enabled boolean NOT NULL,
    priority integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    created_by_user_id UUID,
    id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    updated_by_user_id UUID,
    description varchar(500),
    condition TEXT NOT NULL,
    name varchar(255) NOT NULL,
    parameters jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS stream_event_ledger (
    processed boolean,
    timestamp timestamp(6) with time zone NOT NULL,
    actor_user_id UUID,
    asset_id UUID,
    id UUID NOT NULL,
    policy_id UUID,
    severity varchar(32) NOT NULL CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW','INFO')),
    event_type varchar(64) NOT NULL CHECK (event_type IN ('POLICY_VIOLATION','POLICY_EVALUATION_COMPLETED','POLICY_APPLIED','POLICY_RULE_TRIGGERED','ASSET_DISCOVERED','ASSET_METADATA_CHANGED','ASSET_CLASSIFICATION_CHANGED','ASSET_ACCESS_DETECTED','COMPLIANCE_VIOLATION','COMPLIANCE_SCAN_COMPLETED','REGULATORY_CHANGE','ASSET_ARCHIVAL_SCHEDULED','ASSET_ARCHIVAL_COMPLETED','ASSET_DELETION_SCHEDULED','ASSET_DELETION_COMPLETED','SYSTEM_ALERT','SERVICE_HEALTH_ISSUE','DATA_QUALITY_ISSUE','SECURITY_INCIDENT','USER_ACCESS_ANOMALY','UNAUTHORIZED_ACCESS_ATTEMPT','CUSTOM_EVENT')),
    source_service varchar(128),
    asset_name varchar(512),
    description varchar(255),
    policy_name varchar(255),
    title varchar(255),
    event_data jsonb,
    metadata jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS subscription_event_matches (
    matched_at timestamp(6) with time zone NOT NULL,
    id UUID NOT NULL,
    ledger_event_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    PRIMARY KEY (id)
);

-- Foreign keys with stable names; duplicate_object guard keeps the
-- migration re-runnable against databases where ddl-auto=update already
-- created equivalent constraints.
DO $$ BEGIN
    ALTER TABLE event_rules
        ADD CONSTRAINT fk_event_rules_subscription
        FOREIGN KEY (subscription_id) REFERENCES event_subscriptions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE event_subscription_types
        ADD CONSTRAINT fk_event_subscription_types_subscription
        FOREIGN KEY (subscription_id) REFERENCES event_subscriptions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE event_subscription_severities
        ADD CONSTRAINT fk_event_subscription_severities_subscription
        FOREIGN KEY (subscription_id) REFERENCES event_subscriptions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE event_subscription_sources
        ADD CONSTRAINT fk_event_subscription_sources_subscription
        FOREIGN KEY (subscription_id) REFERENCES event_subscriptions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE policy_draft_tags
        ADD CONSTRAINT fk_policy_draft_tags_draft
        FOREIGN KEY (draft_id) REFERENCES policy_drafts;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE policy_draft_stakeholders
        ADD CONSTRAINT fk_policy_draft_stakeholders_draft
        FOREIGN KEY (draft_id) REFERENCES policy_drafts;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE policy_rules
        ADD CONSTRAINT fk_policy_rules_policy
        FOREIGN KEY (policy_id) REFERENCES policies;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
