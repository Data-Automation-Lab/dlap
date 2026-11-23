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

-- C3 (ADR-0040): Consumed-stage decision<->access-log drift detection.
-- Two buffer tables hold each side of the join (recent PDP decisions and
-- recent actual reads); drift_finding records detected divergences.
-- Hibernate-derived DDL for the JPA entities so ddl-auto=validate is satisfied
-- exactly. IF NOT EXISTS keeps the migration safe on dev databases previously
-- bootstrapped via ddl-auto=update.

CREATE TABLE IF NOT EXISTS access_decision_record (
    id UUID NOT NULL,
    decision_id varchar(200) NOT NULL UNIQUE,
    subject_id varchar(512),
    asset_id varchar(512),
    allowed boolean NOT NULL,
    decided_at timestamp(6) with time zone NOT NULL,
    received_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_adr_asset_subject
    ON access_decision_record (asset_id, subject_id);
CREATE INDEX IF NOT EXISTS idx_adr_decided_at
    ON access_decision_record (decided_at);

CREATE TABLE IF NOT EXISTS asset_read_record (
    id UUID NOT NULL,
    event_id varchar(200) NOT NULL UNIQUE,
    subject_id varchar(512),
    asset_id varchar(512),
    read_at timestamp(6) with time zone NOT NULL,
    received_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_arr_asset_subject
    ON asset_read_record (asset_id, subject_id);
CREATE INDEX IF NOT EXISTS idx_arr_read_at
    ON asset_read_record (read_at);

CREATE TABLE IF NOT EXISTS drift_finding (
    id UUID NOT NULL,
    drift_type varchar(40) NOT NULL CHECK (drift_type IN ('UNENFORCED_READ','ENFORCEMENT_BYPASS')),
    subject_id varchar(512) NOT NULL,
    asset_id varchar(512) NOT NULL,
    decision_id varchar(200),
    read_event_id varchar(200),
    read_at timestamp(6) with time zone NOT NULL,
    detail varchar(1000),
    detected_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_drift_finding_asset
    ON drift_finding (asset_id);
CREATE INDEX IF NOT EXISTS idx_drift_finding_subject
    ON drift_finding (subject_id);
CREATE INDEX IF NOT EXISTS idx_drift_finding_read_at
    ON drift_finding (read_at);
