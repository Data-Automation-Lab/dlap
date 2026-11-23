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

-- LSE-01 (docs/specs/lifecycle-state.md section 3.8, ADR-0097): the Log Event ids behind each
-- Lifecycle State field. The Asset Descriptor keeps evidence channels only; the processed-event
-- table keeps ids without the field they set and is pruned. These tables keep, per asset and
-- field, the ids of the applied events whose value equals the stored value, so the emitter can
-- name them and never invents one. A value without a row here is emitted as null (unknown).

CREATE TABLE IF NOT EXISTS dlap_catalog.lifecycle_evidence (
    asset_id     UUID          NOT NULL,
    field        VARCHAR(40)   NOT NULL,
    value_key    VARCHAR(600)  NOT NULL,
    event_id     CHAR(64)      NOT NULL,
    event_time   TIMESTAMPTZ   NOT NULL,
    channel      VARCHAR(64),
    binding_id   VARCHAR(255),
    executed_by  VARCHAR(16),
    recorded_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_lifecycle_evidence PRIMARY KEY (asset_id, field, event_id),
    CONSTRAINT ck_lifecycle_evidence_field CHECK (field IN ('createdAt', 'lastReadAt', 'lastWrittenAt',
        'sizeBytes', 'disposition', 'retention:expirationTime', 'retention:retentionPeriodDays')),
    CONSTRAINT ck_lifecycle_evidence_event_id CHECK (event_id ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_lifecycle_evidence_executed_by CHECK (executed_by IS NULL
        OR executed_by IN ('native-rule', 'engine', 'other'))
);

COMMENT ON TABLE dlap_catalog.lifecycle_evidence IS
    'LSE-01: Log Event ids whose value equals the asset''s stored value, per Lifecycle State field.';
COMMENT ON COLUMN dlap_catalog.lifecycle_evidence.value_key IS
    'The value the events prove, as text; rows whose value_key differs from the stored value are not evidence.';
COMMENT ON COLUMN dlap_catalog.lifecycle_evidence.executed_by IS
    'disposition only: native-rule (provider principal SYSTEM), engine (a configured engine principal) or other.';
COMMENT ON COLUMN dlap_catalog.lifecycle_evidence.recorded_at IS
    'DLAP processing time (operational only).';

CREATE TABLE IF NOT EXISTS dlap_catalog.lifecycle_coverage_evidence (
    event_id     CHAR(64)      NOT NULL,
    provider     VARCHAR(20)   NOT NULL,
    root_uri     VARCHAR(2048) NOT NULL,
    applies_to   VARCHAR(512)  NOT NULL,
    observed_at  TIMESTAMPTZ   NOT NULL,
    recorded_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_lifecycle_coverage_evidence PRIMARY KEY (event_id),
    CONSTRAINT ck_lifecycle_coverage_evidence_event_id CHECK (event_id ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_lifecycle_coverage_evidence_observed
    ON dlap_catalog.lifecycle_coverage_evidence (provider, observed_at);
CREATE INDEX IF NOT EXISTS idx_lifecycle_coverage_evidence_root
    ON dlap_catalog.lifecycle_coverage_evidence (provider, root_uri);

COMMENT ON TABLE dlap_catalog.lifecycle_coverage_evidence IS
    'LSE-01: applied read-coverage observations, so readCoverage provenance names the observation behind read_coverage_observed_at.';
