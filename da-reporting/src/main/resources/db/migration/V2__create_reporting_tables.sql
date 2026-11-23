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
-- Hibernate-derived DDL for every JPA entity in da-reporting (generated
-- from the entity model, so `validate` is satisfied exactly).
-- IF NOT EXISTS keeps the migration safe on dev databases that were
-- previously bootstrapped via ddl-auto=update.

CREATE TABLE IF NOT EXISTS report_definitions (
    enabled boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    id UUID NOT NULL,
    default_schedule_cron varchar(100),
    report_key varchar(100) NOT NULL UNIQUE,
    description varchar(1000),
    display_name varchar(255) NOT NULL,
    default_parameters jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS generated_reports (
    completed_at timestamp(6) with time zone,
    requested_at timestamp(6) with time zone NOT NULL,
    started_at timestamp(6) with time zone,
    id UUID NOT NULL,
    report_definition_id UUID NOT NULL,
    requested_by_user_id uuid,
    failure_reason TEXT,
    status varchar(255) NOT NULL CHECK (status IN ('PENDING','GENERATING','COMPLETED','FAILED','CANCELLED')),
    generation_parameters jsonb,
    report_content jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS policy_action_events (
    notification_dispatched boolean NOT NULL,
    received_at timestamp(6) with time zone NOT NULL,
    id UUID NOT NULL,
    action_type varchar(200),
    asset_id varchar(512),
    policy_id varchar(512),
    payload jsonb,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS user_notification_preferences (
    enabled boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    id UUID NOT NULL,
    user_id uuid NOT NULL,
    notification_type_key varchar(100) NOT NULL,
    channel varchar(255) NOT NULL CHECK (channel IN ('EMAIL','SLACK')),
    channel_configuration jsonb,
    PRIMARY KEY (id),
    UNIQUE (user_id, notification_type_key, channel)
);

-- Foreign key with a stable name; duplicate_object guard keeps the
-- migration re-runnable against databases where ddl-auto=update already
-- created an equivalent constraint.
DO $$ BEGIN
    ALTER TABLE generated_reports
        ADD CONSTRAINT fk_generated_reports_report_definition
        FOREIGN KEY (report_definition_id) REFERENCES report_definitions;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
