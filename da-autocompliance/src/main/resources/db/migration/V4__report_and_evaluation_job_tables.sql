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

-- V4: report + evaluation-job tables.
--
-- These four entity tables (+ their @ElementCollection side tables) were previously created
-- at runtime by Hibernate ddl-auto=update. They are now defined here so the schema is owned
-- by Flyway and ddl-auto can be pinned to `validate` (CLAUDE.md §6).
--
-- Type conventions mirror V2 (compliance_controls): String -> VARCHAR, @Lob String -> TEXT,
-- @JdbcTypeCode(JSON) -> JSONB, LocalDateTime and the AbstractAuditableEntity Instant audit
-- columns -> TIMESTAMP, boolean -> BOOLEAN. Runs under spring.flyway.schemas=dlap_autocompliance.
-- Idempotent (CREATE TABLE IF NOT EXISTS) so it is safe on databases where ddl-auto=update
-- previously created equivalent tables.

-- ---------------------------------------------------------------------------
-- compliance_report_jobs (referenced by compliance_generated_reports.job_id)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlap_autocompliance.compliance_report_jobs (
    job_id                 VARCHAR(36)  NOT NULL,
    report_type            VARCHAR(100) NOT NULL,
    status                 VARCHAR(50)  NOT NULL,
    submitted_at           TIMESTAMP,
    started_at             TIMESTAMP,
    completed_at           TIMESTAMP,
    message                TEXT,
    generated_report_id    VARCHAR(36),
    generation_parameters  JSONB,
    triggered_by           VARCHAR(255),
    notification_email     VARCHAR(255),
    created_by             VARCHAR(50)  NOT NULL,
    created_date           TIMESTAMP    NOT NULL,
    last_modified_by       VARCHAR(50),
    last_modified_date     TIMESTAMP,
    CONSTRAINT pk_compliance_report_jobs PRIMARY KEY (job_id)
);

-- ---------------------------------------------------------------------------
-- compliance_generated_reports
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlap_autocompliance.compliance_generated_reports (
    report_id              VARCHAR(36)  NOT NULL,
    report_type            VARCHAR(100) NOT NULL,
    display_name           VARCHAR(255),
    generation_timestamp   TIMESTAMP    NOT NULL,
    overall_status         VARCHAR(50),
    generation_parameters  JSONB,
    summary_data           JSONB,
    findings_data          JSONB,
    job_id                 VARCHAR(36),
    created_by             VARCHAR(50)  NOT NULL,
    created_date           TIMESTAMP    NOT NULL,
    last_modified_by       VARCHAR(50),
    last_modified_date     TIMESTAMP,
    CONSTRAINT pk_compliance_generated_reports PRIMARY KEY (report_id),
    CONSTRAINT fk_generated_reports_job
        FOREIGN KEY (job_id) REFERENCES dlap_autocompliance.compliance_report_jobs (job_id)
);

-- ---------------------------------------------------------------------------
-- compliance_report_definitions (+ its element-collection tables)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlap_autocompliance.compliance_report_definitions (
    report_type                       VARCHAR(100) NOT NULL,
    display_name                      VARCHAR(255) NOT NULL,
    description                       TEXT,
    version                           VARCHAR(50),
    generation_parameters_definition  JSONB,
    details_link                      VARCHAR(512),
    created_by                        VARCHAR(50)  NOT NULL,
    created_date                      TIMESTAMP    NOT NULL,
    last_modified_by                  VARCHAR(50),
    last_modified_date                TIMESTAMP,
    CONSTRAINT pk_compliance_report_definitions PRIMARY KEY (report_type)
);

CREATE TABLE IF NOT EXISTS dlap_autocompliance.report_def_frameworks (
    report_type    VARCHAR(100) NOT NULL,
    framework_name VARCHAR(255),
    CONSTRAINT fk_report_def_frameworks
        FOREIGN KEY (report_type) REFERENCES dlap_autocompliance.compliance_report_definitions (report_type)
);

CREATE TABLE IF NOT EXISTS dlap_autocompliance.report_def_asset_types (
    report_type VARCHAR(100) NOT NULL,
    asset_type  VARCHAR(255),
    CONSTRAINT fk_report_def_asset_types
        FOREIGN KEY (report_type) REFERENCES dlap_autocompliance.compliance_report_definitions (report_type)
);

-- ---------------------------------------------------------------------------
-- compliance_control_evaluation_jobs (+ its element-collection table)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlap_autocompliance.compliance_control_evaluation_jobs (
    job_id                 VARCHAR(36)  NOT NULL,
    control_id             VARCHAR(100) NOT NULL,
    status                 VARCHAR(50)  NOT NULL,
    submitted_at           TIMESTAMP,
    started_at             TIMESTAMP,
    completed_at           TIMESTAMP,
    message                TEXT,
    evaluation_parameters  JSONB,
    force_reevaluation     BOOLEAN      NOT NULL DEFAULT FALSE,
    triggered_by           VARCHAR(255),
    notification_email     VARCHAR(255),
    created_by             VARCHAR(50)  NOT NULL,
    created_date           TIMESTAMP    NOT NULL,
    last_modified_by       VARCHAR(50),
    last_modified_date     TIMESTAMP,
    CONSTRAINT pk_compliance_control_evaluation_jobs PRIMARY KEY (job_id)
);

CREATE INDEX IF NOT EXISTS idx_ctrl_eval_job_control_id
    ON dlap_autocompliance.compliance_control_evaluation_jobs (control_id);
CREATE INDEX IF NOT EXISTS idx_ctrl_eval_job_status
    ON dlap_autocompliance.compliance_control_evaluation_jobs (status);

CREATE TABLE IF NOT EXISTS dlap_autocompliance.control_eval_job_target_assets (
    job_id   VARCHAR(36) NOT NULL,
    asset_id VARCHAR(255),
    CONSTRAINT fk_ctrl_eval_target_assets
        FOREIGN KEY (job_id) REFERENCES dlap_autocompliance.compliance_control_evaluation_jobs (job_id)
);
