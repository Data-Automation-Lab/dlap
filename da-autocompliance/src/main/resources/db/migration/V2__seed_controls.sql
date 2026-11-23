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

-- Group D: seed the IAM-DRIFT-001 control (plus its frameworks and
-- target asset types) so the permission-change consumer
-- (PermissionChangeEventListener) has a control to evaluate against.
--
-- Flyway runs before Hibernate `ddl-auto=update`, so the control tables
-- are created here idempotently (CREATE TABLE IF NOT EXISTS) to match the
-- ComplianceControlEntity mapping. Hibernate's later `update` pass sees
-- the tables already present and leaves them untouched. All names match
-- the entity exactly:
--   ComplianceControlEntity @Table(name = "compliance_controls")
--   @CollectionTable(name = "control_frameworks", joinColumns control_id; column framework_name)
--   @CollectionTable(name = "control_asset_types", joinColumns control_id; column asset_type)
--   AbstractAuditableEntity audit columns: created_by/created_date/last_modified_by/last_modified_date
-- Runs under spring.flyway.schemas=dlap_autocompliance; tables are
-- schema-qualified for clarity.

CREATE TABLE IF NOT EXISTS dlap_autocompliance.compliance_controls (
    control_id                        VARCHAR(100) NOT NULL,
    name                              VARCHAR(255) NOT NULL,
    description                       TEXT,
    severity                          VARCHAR(50),
    remediation_steps                 TEXT,
    evaluation_parameters_definition  JSONB,
    details_link                      VARCHAR(512),
    enabled                           BOOLEAN NOT NULL DEFAULT TRUE,
    created_by                        VARCHAR(50) NOT NULL,
    created_date                      TIMESTAMP NOT NULL,
    last_modified_by                  VARCHAR(50),
    last_modified_date                TIMESTAMP,
    CONSTRAINT pk_compliance_controls PRIMARY KEY (control_id)
);

CREATE INDEX IF NOT EXISTS idx_control_severity ON dlap_autocompliance.compliance_controls (severity);
CREATE INDEX IF NOT EXISTS idx_control_enabled  ON dlap_autocompliance.compliance_controls (enabled);

CREATE TABLE IF NOT EXISTS dlap_autocompliance.control_frameworks (
    control_id     VARCHAR(100) NOT NULL,
    framework_name VARCHAR(255),
    CONSTRAINT uq_control_frameworks UNIQUE (control_id, framework_name),
    CONSTRAINT fk_control_frameworks_control
        FOREIGN KEY (control_id) REFERENCES dlap_autocompliance.compliance_controls (control_id)
);

CREATE TABLE IF NOT EXISTS dlap_autocompliance.control_asset_types (
    control_id VARCHAR(100) NOT NULL,
    asset_type VARCHAR(255),
    CONSTRAINT uq_control_asset_types UNIQUE (control_id, asset_type),
    CONSTRAINT fk_control_asset_types_control
        FOREIGN KEY (control_id) REFERENCES dlap_autocompliance.compliance_controls (control_id)
);

-- ---------------------------------------------------------------------------
-- IAM-DRIFT-001 control
-- ---------------------------------------------------------------------------
INSERT INTO dlap_autocompliance.compliance_controls (
    control_id,
    name,
    description,
    severity,
    remediation_steps,
    evaluation_parameters_definition,
    details_link,
    enabled,
    created_by,
    created_date,
    last_modified_by,
    last_modified_date
) VALUES (
    'IAM-DRIFT-001',
    'IAM Permission Drift Detection',
    'Detects unexpected changes to an asset''s IAM bindings (permission drift). '
        || 'Triggered by asset permission-change events from the discovery log spine; '
        || 'evaluates whether the new access posture is compliant with policy.',
    'HIGH',
    'Review the asset''s current IAM policy against the approved baseline. '
        || 'Remove principals that were not authorized, restore least-privilege bindings, '
        || 'and record the justification for any intentional grant.',
    '{"baseline":"least-privilege","alertOnPublicAccess":"true"}'::jsonb,
    'https://dlap.dev/controls/IAM-DRIFT-001',
    TRUE,
    'system',
    NOW(),
    'system',
    NOW()
)
ON CONFLICT (control_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Applicable frameworks for IAM-DRIFT-001 (GDPR / HIPAA / SOC2)
-- ---------------------------------------------------------------------------
INSERT INTO dlap_autocompliance.control_frameworks (control_id, framework_name) VALUES
    ('IAM-DRIFT-001', 'GDPR'),
    ('IAM-DRIFT-001', 'HIPAA'),
    ('IAM-DRIFT-001', 'SOC2')
ON CONFLICT (control_id, framework_name) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Target asset types for IAM-DRIFT-001 (GCS + BigQuery, the v0.1.0 bestiary)
-- ---------------------------------------------------------------------------
INSERT INTO dlap_autocompliance.control_asset_types (control_id, asset_type) VALUES
    ('IAM-DRIFT-001', 'GCS_BUCKET'),
    ('IAM-DRIFT-001', 'BIGQUERY_DATASET'),
    ('IAM-DRIFT-001', 'BIGQUERY_TABLE')
ON CONFLICT (control_id, asset_type) DO NOTHING;
