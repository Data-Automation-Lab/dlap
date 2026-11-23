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

-- N3 / R-P0-4: reference-data seeds ported from the Liquibase loadData
-- changesets (authority.csv, gcp_log_source_details.csv,
-- gcp_log_exclusion_filters.csv). ON CONFLICT DO NOTHING keeps the
-- migration safe on databases that already carry the Liquibase-loaded rows.

INSERT INTO dlap_discovery.jhi_authority (name) VALUES
    ('ROLE_ADMIN'),
    ('ROLE_USER')
ON CONFLICT (name) DO NOTHING;

INSERT INTO dlap_discovery.gcp_log_source_details (account_id, source_type, details_json, created_at, updated_at) VALUES
    ('default', 'API',      '{"sourceProjectId":"default-project"}'::jsonb, now(), now()),
    ('default', 'BIGQUERY', '{"sourceProjectId":"default-project","bigQueryDatasetId":"cloud_audit_logs","bigQueryTableId":"cloudaudit_googleapis_com_activity"}'::jsonb, now(), now()),
    ('default', 'GCS',      '{"sourceProjectId":"default-project","gcsBucketName":"audit-logs-export","gcsObjectPrefix":"activity_logs/"}'::jsonb, now(), now())
ON CONFLICT (account_id, source_type) DO NOTHING;

INSERT INTO dlap_discovery.gcp_log_exclusion_filters (account_id, filters_json, created_at, updated_at) VALUES
    ('default', '["protoPayload.methodName=\"Get\"","protoPayload.methodName=\"List\"","protoPayload.resourceName=~\"projects/.*/logs\""]'::jsonb, now(), now())
ON CONFLICT (account_id) DO NOTHING;
