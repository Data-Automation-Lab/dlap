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
--
-- Authoritative schema generated from the JPA entities (Hibernate ddl-auto=create
-- dump) so ddl-auto=validate passes on a fresh database. Sandbox: no data to
-- preserve, so prior incremental migrations were consolidated into this init.

CREATE SCHEMA IF NOT EXISTS dlap_autoarchival;


CREATE FUNCTION dlap_autoarchival.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS dlap_autoarchival.dlap_archival_activities (
    savings_amount double precision,
    storage_size_gb double precision,
    activity_timestamp timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone,
    id bigint NOT NULL,
    activity_id character varying(255) NOT NULL,
    asset_id character varying(255),
    asset_name character varying(255),
    details text,
    event_type character varying(255) NOT NULL,
    policy_name character varying(255),
    status character varying(255) NOT NULL,
    task_id character varying(255)
);

CREATE SEQUENCE dlap_autoarchival.dlap_archival_activities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE dlap_autoarchival.dlap_archival_activities_id_seq OWNED BY dlap_autoarchival.dlap_archival_activities.id;

CREATE TABLE IF NOT EXISTS dlap_autoarchival.dlap_archival_config (
    enabled boolean NOT NULL,
    require_approval_for_archival boolean NOT NULL,
    require_approval_for_restore boolean NOT NULL,
    default_retention_days bigint,
    id bigint NOT NULL,
    default_archival_tier character varying(255),
    provider_configs jsonb
);

CREATE TABLE IF NOT EXISTS dlap_autoarchival.dlap_archival_metrics (
    active_policies integer,
    assets_archived integer,
    assets_identified integer,
    average_archival_time_minutes double precision,
    estimated_monthly_savings double precision,
    failed_archival integer,
    pending_archival integer,
    storage_reclaimed_tb double precision,
    total_savings_to_date double precision,
    created_at timestamp(6) without time zone,
    id bigint NOT NULL,
    last_policy_run timestamp(6) without time zone,
    metric_date timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone
);

CREATE SEQUENCE dlap_autoarchival.dlap_archival_metrics_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE dlap_autoarchival.dlap_archival_metrics_id_seq OWNED BY dlap_autoarchival.dlap_archival_metrics.id;

CREATE TABLE IF NOT EXISTS dlap_autoarchival.dlap_archival_tasks (
    assets_archived_successfully integer NOT NULL,
    assets_failed_to_archive integer NOT NULL,
    assets_pending_archival integer NOT NULL,
    total_assets_in_scope integer NOT NULL,
    completed_at timestamp(6) without time zone,
    last_restore_requested_at timestamp(6) without time zone,
    started_at timestamp(6) without time zone,
    submitted_at timestamp(6) without time zone,
    approval_comments character varying(255),
    justification character varying(255),
    last_restore_status character varying(255),
    rejection_comments character varying(255),
    status character varying(255),
    task_id character varying(255) NOT NULL,
    task_name character varying(255),
    triggered_by character varying(255),
    asset_statuses jsonb,
    error_messages jsonb,
    override_config jsonb,
    restore_request_details jsonb,
    scope jsonb,
    CONSTRAINT dlap_archival_tasks_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'SUBMITTED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'PARTIALLY_COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'RESTORATION_REQUESTED'::character varying, 'RESTORATION_IN_PROGRESS'::character varying, 'RESTORED'::character varying, 'RESTORATION_FAILED'::character varying])::text[])))
);

CREATE TABLE IF NOT EXISTS dlap_autoarchival.dlap_storage_consumers (
    current_size_tb double precision NOT NULL,
    estimated_savings double precision,
    monthly_cost double precision,
    created_at timestamp(6) without time zone,
    id bigint NOT NULL,
    last_accessed timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    consumer_name character varying(255) NOT NULL,
    consumer_type character varying(255),
    priority character varying(255),
    recommendation character varying(255)
);

CREATE SEQUENCE dlap_autoarchival.dlap_storage_consumers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE dlap_autoarchival.dlap_storage_consumers_id_seq OWNED BY dlap_autoarchival.dlap_storage_consumers.id;

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_activities ALTER COLUMN id SET DEFAULT nextval('dlap_autoarchival.dlap_archival_activities_id_seq'::regclass);

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_metrics ALTER COLUMN id SET DEFAULT nextval('dlap_autoarchival.dlap_archival_metrics_id_seq'::regclass);

ALTER TABLE ONLY dlap_autoarchival.dlap_storage_consumers ALTER COLUMN id SET DEFAULT nextval('dlap_autoarchival.dlap_storage_consumers_id_seq'::regclass);

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_activities
    ADD CONSTRAINT dlap_archival_activities_activity_id_key UNIQUE (activity_id);

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_activities
    ADD CONSTRAINT dlap_archival_activities_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_config
    ADD CONSTRAINT dlap_archival_config_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_metrics
    ADD CONSTRAINT dlap_archival_metrics_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlap_autoarchival.dlap_archival_tasks
    ADD CONSTRAINT dlap_archival_tasks_pkey PRIMARY KEY (task_id);

ALTER TABLE ONLY dlap_autoarchival.dlap_storage_consumers
    ADD CONSTRAINT dlap_storage_consumers_pkey PRIMARY KEY (id);

