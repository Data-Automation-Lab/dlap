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

CREATE SCHEMA IF NOT EXISTS dlap_autolabel;


CREATE TABLE IF NOT EXISTS dlap_autolabel.dlap_autolabel_feedback (
    processed_at timestamp(6) without time zone,
    received_at timestamp(6) without time zone NOT NULL,
    asset_id character varying(255) NOT NULL,
    feedback_id character varying(255) NOT NULL,
    labeling_job_id character varying(255) NOT NULL,
    processing_status character varying(255),
    user_id character varying(255),
    additional_context jsonb,
    feedback_items jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS dlap_autolabel.dlap_autolabel_job (
    assets_failed integer,
    assets_processed integer,
    assets_successfully_labeled integer,
    progress_percentage double precision,
    total_assets_to_process integer,
    completed_at timestamp(6) without time zone,
    last_updated_at timestamp(6) without time zone,
    started_at timestamp(6) without time zone,
    submitted_at timestamp(6) without time zone,
    current_stage_description character varying(255),
    job_id character varying(255) NOT NULL,
    job_name character varying(255),
    status character varying(255),
    triggered_by_user_id character varying(255),
    effective_ml_config jsonb,
    label_summary jsonb,
    scope jsonb,
    CONSTRAINT dlap_autolabel_job_status_check CHECK (((status)::text = ANY ((ARRAY['SUBMITTED'::character varying, 'PREPARING_DATA'::character varying, 'CALLING_LLM'::character varying, 'APPLYING_LABELS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'PARTIALLY_COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE IF NOT EXISTS dlap_autolabel.dlap_autolabel_job_errors (
    error_message character varying(2048),
    job_id character varying(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS dlap_autolabel.dlap_autolabel_label_feedback (
    accepted boolean NOT NULL,
    id bigint NOT NULL,
    ts timestamp(6) without time zone NOT NULL,
    comment character varying(2048),
    asset_id character varying(255) NOT NULL,
    suggested_label character varying(255) NOT NULL,
    user_id character varying(255)
);

CREATE SEQUENCE dlap_autolabel.dlap_autolabel_label_feedback_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE dlap_autolabel.dlap_autolabel_label_feedback_id_seq OWNED BY dlap_autolabel.dlap_autolabel_label_feedback.id;

CREATE TABLE IF NOT EXISTS dlap_autolabel.manual_review_queue (
    confidence double precision NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    reviewed_at timestamp(6) with time zone,
    asset_id uuid NOT NULL,
    id uuid NOT NULL,
    reviewer_user_id uuid,
    status character varying(32) NOT NULL,
    llm_provider character varying(64),
    category character varying(100),
    label character varying(255) NOT NULL,
    reasoning text,
    review_notes text
);

CREATE TABLE IF NOT EXISTS dlap_autolabel.ml_global_config (
    id smallint NOT NULL,
    config_json text NOT NULL
);

ALTER TABLE ONLY dlap_autolabel.dlap_autolabel_label_feedback ALTER COLUMN id SET DEFAULT nextval('dlap_autolabel.dlap_autolabel_label_feedback_id_seq'::regclass);

ALTER TABLE ONLY dlap_autolabel.dlap_autolabel_feedback
    ADD CONSTRAINT dlap_autolabel_feedback_pkey PRIMARY KEY (feedback_id);

ALTER TABLE ONLY dlap_autolabel.dlap_autolabel_job
    ADD CONSTRAINT dlap_autolabel_job_pkey PRIMARY KEY (job_id);

ALTER TABLE ONLY dlap_autolabel.dlap_autolabel_label_feedback
    ADD CONSTRAINT dlap_autolabel_label_feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlap_autolabel.manual_review_queue
    ADD CONSTRAINT manual_review_queue_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlap_autolabel.ml_global_config
    ADD CONSTRAINT ml_global_config_pkey PRIMARY KEY (id);

CREATE INDEX idx_mrq_asset_id ON dlap_autolabel.manual_review_queue USING btree (asset_id);

CREATE INDEX idx_mrq_created_at ON dlap_autolabel.manual_review_queue USING btree (created_at);

CREATE INDEX idx_mrq_status ON dlap_autolabel.manual_review_queue USING btree (status);

ALTER TABLE ONLY dlap_autolabel.dlap_autolabel_job_errors
    ADD CONSTRAINT fkm0rfj5xop3d4u9y62fa01mvj FOREIGN KEY (job_id) REFERENCES dlap_autolabel.dlap_autolabel_job(job_id);

