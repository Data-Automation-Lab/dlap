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

CREATE SCHEMA IF NOT EXISTS dlap_admin;


CREATE TABLE IF NOT EXISTS dlap_admin.dlap_cloud_connection (
    enabled boolean NOT NULL,
    created_date timestamp(6) with time zone NOT NULL,
    last_connection_test_at timestamp(6) without time zone,
    last_modified_date timestamp(6) with time zone,
    created_by character varying(50) NOT NULL,
    last_connection_test_status character varying(50),
    last_modified_by character varying(50),
    provider_type character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    encrypted_credentials text,
    id character varying(36) NOT NULL,
    connection_parameters jsonb,
    CONSTRAINT dlap_cloud_connection_provider_type_check CHECK (((provider_type)::text = ANY ((ARRAY['AWS'::character varying, 'AZURE'::character varying, 'GCP'::character varying, 'OTHER'::character varying])::text[])))
);

CREATE TABLE IF NOT EXISTS dlap_admin.service_configs (
    enabled boolean NOT NULL,
    version character varying(50),
    service_id character varying(100) NOT NULL,
    endpoint character varying(500),
    description character varying(1000),
    display_name character varying(255)
);

ALTER TABLE ONLY dlap_admin.dlap_cloud_connection
    ADD CONSTRAINT dlap_cloud_connection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dlap_admin.service_configs
    ADD CONSTRAINT service_configs_pkey PRIMARY KEY (service_id);

