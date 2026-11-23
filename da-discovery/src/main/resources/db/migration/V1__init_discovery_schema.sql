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

-- N3 / R-P0-4 (ADR-0008, ADR-0025 phase-2): consolidated Flyway baseline
-- replacing the 65-changeset Liquibase tree. DDL is Hibernate-derived from
-- the current entity model (28 entities -> 35 tables incl. collection and
-- JOINED-inheritance tables), so hibernate.ddl-auto=validate is satisfied
-- exactly. Databases previously managed by Liquibase already contain this
-- schema and are protected by spring.flyway.baseline-on-migrate=true (this
-- V1 is skipped); IF NOT EXISTS + guarded constraints keep it re-runnable
-- on partially-bootstrapped dev databases. Obsolete Liquibase artifacts
-- (gcp_log_checkpoints, update_timestamp triggers superseded by
-- @CreationTimestamp/@UpdateTimestamp) are intentionally not carried over.

CREATE SCHEMA IF NOT EXISTS dlap_discovery;

CREATE TABLE IF NOT EXISTS dlap_discovery.aws_resource (id uuid not null, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.azure_resource (id uuid not null, azure_provisioning_state varchar(255), azure_resource_group varchar(255), azure_subscription_id varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.azure_vm_resource (id uuid not null, azure_os_type varchar(255), azure_vm_size varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.bigquery_resource (id uuid not null, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource (has_children boolean, created_at timestamp(6), last_discovered_at timestamp(6) not null, updated_at timestamp(6), id uuid not null, description varchar(2048), account_id varchar(255), cloud_provider varchar(255) not null check (cloud_provider in ('GCP','AWS','AZURE','OCI','DATABRICKS','UNKNOWN')), location varchar(255), name varchar(255), parent_id varchar(255), project_id varchar(255), region varchar(255), resource_id varchar(255) not null, service_id varchar(255) not null, type_id varchar(255) not null, uri varchar(255), zone varchar(255), raw_json jsonb, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_business_metadata (resource_db_id uuid not null, metadata_value varchar(1024), metadata_key varchar(255) not null, primary key (resource_db_id, metadata_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_compliance (resource_db_id uuid not null, compliance_type varchar(255) not null check (compliance_type in ('GDPR','PII','HIPAA','CCPA','SOX','PCI_DSS','FERPA','GLBA','FISMA','ISO_27001','NIST_800_53','FedRAMP','COPPA','HITECH','PHI','PIPEDA','BASEL_III','FINRA','SEC_17A_4','FFIEC','AML','KYC','FHIR','MIFID_II')), primary key (resource_db_id, compliance_type));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_labels (resource_db_id uuid not null, label varchar(255) not null);

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_subscriber_teams (resource_db_id uuid not null, team_id varchar(255) not null);

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_tags (resource_db_id uuid not null, tag_value varchar(1024), tag_key varchar(255) not null, primary key (resource_db_id, tag_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_technical_metadata (resource_db_id uuid not null, metadata_value varchar(1024), metadata_key varchar(255) not null, primary key (resource_db_id, metadata_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_resource_usage_stats (edit_count bigint, last_accessed_at timestamp(6), read_count bigint, time_window_end timestamp(6) not null, time_window_start timestamp(6) not null, total_edit_count bigint, total_read_count bigint, total_write_count bigint, write_count bigint, id uuid not null, resource_db_id uuid not null unique, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.cloud_sql_resource (id uuid not null, sql_db_version varchar(255), sql_status varchar(255), sql_tier varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.compute_resource (compute_cpu_count integer, compute_memory_mb bigint, id uuid not null, compute_machine_type varchar(255), compute_network_id varchar(255), compute_os varchar(255), compute_status varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.discovery_job (is_one_time_job boolean not null, is_periodic_job boolean not null, is_trigger_job boolean not null, progress_percentage integer, completed_at timestamp(6), created_at timestamp(6) not null, started_at timestamp(6), updated_at timestamp(6) not null, job_id uuid not null, account_id varchar(255) not null, cloud_provider varchar(255) not null check (cloud_provider in ('GCP','AWS','AZURE','OCI','DATABRICKS','UNKNOWN')), error_message varchar(255), execution_mode varchar(255) not null check (execution_mode in ('DEFAULT','SPARK','NIFI')), executor_id varchar(255), job_name varchar(255) not null, job_type varchar(255) not null check (job_type in ('RESOURCE_CRAWLER','LOG_ANALYZER')), last_execution_id varchar(255), progress_message varchar(255), schedule_info varchar(255), status varchar(255) not null check (status in ('CREATED','PENDING','RUNNING','COMPLETED','FAILED','CANCELLED','PAUSED','UNKNOWN')), context jsonb, parameters jsonb, primary key (job_id));

CREATE TABLE IF NOT EXISTS dlap_discovery.dlap_discovery_connection_config (is_enabled boolean, scan_interval_hours integer, created_at timestamp(6) not null, updated_at timestamp(6) not null, id uuid not null, cloud_connection_id varchar(255) not null unique, scan_type varchar(255) check (scan_type in ('GCP_PROJECTS','GCP_ORGANIZATION','GCP_BIGQUERY_DATASETS','AWS_ACCOUNT_FULL','AWS_S3_BUCKETS','AWS_EC2_INSTANCES','AZURE_SUBSCRIPTION','AZURE_RESOURCE_GROUP','OCI_TENANCY','OCI_COMPARTMENT','FULL','INCREMENTAL')), custom_parameters jsonb, resource_types_to_exclude jsonb, resource_types_to_include jsonb, specific_regions jsonb, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.dlap_discovery_global_config (default_scan_interval_hours integer, enable_auto_remediation boolean, max_concurrent_scans integer, scan_history_retention_days integer, created_at timestamp(6) not null, updated_at timestamp(6) not null, config_key varchar(255) not null, default_output_location varchar(255), default_scan_type varchar(255) check (default_scan_type in ('GCP_PROJECTS','GCP_ORGANIZATION','GCP_BIGQUERY_DATASETS','AWS_ACCOUNT_FULL','AWS_S3_BUCKETS','AWS_EC2_INSTANCES','AZURE_SUBSCRIPTION','AZURE_RESOURCE_GROUP','OCI_TENANCY','OCI_COMPARTMENT','FULL','INCREMENTAL')), default_resource_types_to_exclude jsonb, global_crawler_properties jsonb, primary key (config_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.ec2_resource (id uuid not null, ec2_image_id varchar(255), ec2_instance_type varchar(255), ec2_private_ip varchar(255), ec2_public_ip varchar(255), ec2_status varchar(255), ec2_subnet_id varchar(255), ec2_vpc_id varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.gcp_log_exclusion_filters (created_at timestamp(6), id bigserial not null, updated_at timestamp(6), account_id varchar(255) not null, filters_json jsonb, primary key (id), unique (account_id));

CREATE TABLE IF NOT EXISTS dlap_discovery.gcp_log_source_details (created_at timestamp(6), id bigserial not null, updated_at timestamp(6), account_id varchar(255) not null, source_type varchar(255) not null check (source_type in ('API','BIGQUERY','GCS')), details_json jsonb, primary key (id), unique (account_id, source_type));

CREATE TABLE IF NOT EXISTS dlap_discovery.gcp_resource (id uuid not null, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.gcs_resource (gcs_is_public boolean, gcs_size_bytes bigint, id uuid not null, gcs_content_type varchar(255), gcs_parent_resource varchar(255), gcs_storage_class varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.jhi_authority (name varchar(50) not null, primary key (name));

CREATE TABLE IF NOT EXISTS dlap_discovery.jhi_date_time_wrapper (local_date date, local_time time(6), offset_time time(6), id bigserial not null, instant timestamp(6), local_date_time timestamp(6), offset_date_time timestamp(6), zoned_date_time timestamp(6), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.jhi_user (activated boolean not null, created_date timestamp(6), last_modified_date timestamp(6), lang_key varchar(10), created_by varchar(50) not null, first_name varchar(50), last_modified_by varchar(50), last_name varchar(50), login varchar(50) not null unique, email varchar(254) unique, image_url varchar(256), id varchar(255) not null, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.jhi_user_authority (authority_name varchar(50) not null, user_id varchar(255) not null, primary key (authority_name, user_id));

CREATE TABLE IF NOT EXISTS dlap_discovery.lineage (timestamp timestamp(6) not null, id uuid not null, resource_db_id uuid not null, action varchar(255), actor_id varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.lineage_source_resources (lineage_id uuid not null, source_resource_id uuid);

CREATE TABLE IF NOT EXISTS dlap_discovery.log_analysis_checkpoints (last_updated timestamp(6), timestamp timestamp(6) not null, provider varchar(50), account_id varchar(255), checkpoint_key varchar(255) not null, context varchar(255), primary key (checkpoint_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.metric_tags (metric_id uuid not null, tag_key varchar(255) not null, tag_value jsonb, primary key (metric_id, tag_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.metrics (metric_value float(53) not null, timestamp timestamp(6) not null, window_end timestamp(6), window_start timestamp(6), id uuid not null, metric_name varchar(255) not null, primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.oci_resource_metadata (resource_db_id uuid not null, metadata_value varchar(1024), metadata_key varchar(255) not null, primary key (resource_db_id, metadata_key));

CREATE TABLE IF NOT EXISTS dlap_discovery.oracle_resource (id uuid not null, oci_compartment_id varchar(255), oci_lifecycle_state varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.resource_change (timestamp timestamp(6) not null, id uuid not null, actor varchar(255), actor_email varchar(255), change_type varchar(255) not null check (change_type in ('CREATE','UPDATE','DELETE','ACCESS','PERMISSION','UNKNOWN')), cloud_provider varchar(255) check (cloud_provider in ('GCP','AWS','AZURE','OCI','DATABRICKS','UNKNOWN')), project_id varchar(255), resource_id varchar(255) not null, service_id varchar(255), type_id varchar(255), primary key (id));

CREATE TABLE IF NOT EXISTS dlap_discovery.resource_change_details (change_id uuid not null, detail_value varchar(2048), detail_key varchar(255) not null, primary key (change_id, detail_key));

DO $$ BEGIN
    alter table if exists dlap_discovery.aws_resource add constraint FKrfgo034rgxqmyrnlawrwg6ase foreign key (id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.azure_resource add constraint FK3edq9kc1s6gq4s3pvy48qt8nr foreign key (id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.azure_vm_resource add constraint FK5huhcflvjlapyptsifg8aiom foreign key (id) references dlap_discovery.azure_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.bigquery_resource add constraint FK24f3lck2169u9nodv4mc4f9av foreign key (id) references dlap_discovery.gcp_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_business_metadata add constraint FKahnvttgqdpy4t314f6eflsb8m foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_compliance add constraint FKfp4ett01vitvsqxrpvvdej6mn foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_labels add constraint FK7d7rjq8k2bwy8pvhvivbmwgjd foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_subscriber_teams add constraint FKmesouqvs9f0gflk3a1lp3f2w6 foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_tags add constraint FKjsjp2a0ier0eq2osx30l9oet7 foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_technical_metadata add constraint FKejryw9wangecj9cv8fo2why6a foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_resource_usage_stats add constraint FK7u66ad7hj9qrhme68quq3h2k foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.cloud_sql_resource add constraint FKbesijx1iq564eb37kt082u1qs foreign key (id) references dlap_discovery.gcp_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.compute_resource add constraint FK7oiejwjc310a57ujyx8w93koe foreign key (id) references dlap_discovery.gcp_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.ec2_resource add constraint FKqhca2y2np0vfqh3jyg745wfpc foreign key (id) references dlap_discovery.aws_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.gcp_resource add constraint FKf4c17haqrdlqt3sj685oiaaw6 foreign key (id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.gcs_resource add constraint FK4otliu59givuggo7489o9a4gx foreign key (id) references dlap_discovery.gcp_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.jhi_user_authority add constraint FK4psxl0jtx6nr7rhqbynr6itoc foreign key (authority_name) references dlap_discovery.jhi_authority;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.jhi_user_authority add constraint FK290okww5jujghp4el5i7mgwu0 foreign key (user_id) references dlap_discovery.jhi_user;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.lineage add constraint FKgp7m82qlsgfown24e8p5uwd9i foreign key (resource_db_id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.lineage_source_resources add constraint FKdqisr511vgj2ovs8qam8eeyla foreign key (lineage_id) references dlap_discovery.lineage;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.metric_tags add constraint FKgvotwnl8p4jx04lf1ksxx7oqn foreign key (metric_id) references dlap_discovery.metrics;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.oci_resource_metadata add constraint FKp3b1bpwxw0jlnak8x45gsumm5 foreign key (resource_db_id) references dlap_discovery.oracle_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.oracle_resource add constraint FKg70v7i4g1a9g1ex9bfdk16qin foreign key (id) references dlap_discovery.cloud_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    alter table if exists dlap_discovery.resource_change_details add constraint FKge7421gb4m0oo77a1vcpwmidj foreign key (change_id) references dlap_discovery.resource_change;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

