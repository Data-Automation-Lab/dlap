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

-- RPT-03 (enforcement-contract.md section 6.1): principal identity map for the drift join.
-- A cloud audit log names the reader in the provider's form (an AWS role ARN, a GCP principal
-- email, an Entra object id); the PDP names the same actor by its DLAP subject, the AuthZEN
-- subject.id. One row links the two. principal_key is the PrincipalKeys.key form: IAM member
-- prefix removed, emails and GUIDs lower-cased, anything else as written.
--
-- provider ANY applies to every log; a provider-specific row wins over an ANY row for the same
-- key. source SEED rows belong to the seed file (dlap.reporting.identity-map.seed-file) and are
-- rewritten at start-up; MANUAL rows are written by an operator, for example:
--   INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id)
--   VALUES ('GCP', 'marketing-agent@acme-prod.iam.gserviceaccount.com', 'svc-marketing-agent');
-- The defaults serve such SQL; the service itself always sets every column.

CREATE TABLE IF NOT EXISTS principal_identity_map (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    provider varchar(16) NOT NULL
        CHECK (provider IN ('ANY','AWS','GCP','AZURE','OCI','UNITY','DATABRICKS','SNOWFLAKE')),
    principal_key varchar(512) NOT NULL CHECK (length(principal_key) > 0),
    subject_id varchar(512) NOT NULL CHECK (length(subject_id) > 0),
    source varchar(16) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('SEED','MANUAL')),
    created_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_principal_identity_map_key UNIQUE (provider, principal_key)
);

-- Lookups search by key across two scopes (the event's provider and ANY).
CREATE INDEX IF NOT EXISTS idx_pim_principal_key
    ON principal_identity_map (principal_key);
