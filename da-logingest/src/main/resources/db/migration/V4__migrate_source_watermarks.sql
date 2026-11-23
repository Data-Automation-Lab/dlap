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

-- V4__migrate_source_watermarks.sql
--
-- LOG-02bc (design B8 step 3.2, ADR-0060). Copies the legacy per-(provider, account) watermarks
-- of V2 into the cursor model of V3, so a registry transport can resume where the legacy source
-- stopped instead of re-reading its whole lookback.
--
--   AWS rows: source_watermarks.account_id holds the REGION (CloudTrailLogSource keys its cursor
--             by region), so each row becomes binding legacy-aws-<region> of source kind
--             AWS_CLOUDTRAIL with stream lookup-events and partition_key <region>.
--   GCP rows: account_id holds the project id; each row becomes binding legacy-gcp-<project> of
--             source kind GCP_CLOUD_AUDIT with stream logging-api and partition_key ''.
--   Other providers: never written by a shipped source; left alone.
--
-- low_watermark and high_watermark both take the legacy last_event_time: the point the legacy
-- source would resume from. A transport re-reads [low_watermark - overlap, ...], and event_id
-- dedup absorbs the overlap.
--
-- This is a copy. source_watermarks stays as it is, because the legacy sources keep reading it
-- while dlap.logingest.pipeline=legacy (the default); it is dropped with the legacy sources
-- (design B8 step 4.3). The bindings are config_origin MIGRATED and state MIGRATED: the
-- synchronizer never marks them REMOVED, and they run nothing until a configured binding adopts
-- the cursor. Binding ids keep only [a-z0-9._:-] (other characters become '-') and at most 128
-- characters; rows that collide after that keep the oldest watermark, which re-reads more rather
-- than skipping events.

SET search_path TO dlap_logingest, public;

WITH legacy AS (
    SELECT
        upper(provider) AS provider,
        account_id,
        last_event_time,
        updated_at,
        left('legacy-' || lower(provider) || '-'
                 || regexp_replace(lower(account_id), '[^a-z0-9._:-]', '-', 'g'), 128) AS binding_id
    FROM source_watermarks
    WHERE upper(provider) IN ('AWS', 'GCP')
),
bindings AS (
    SELECT DISTINCT ON (binding_id)
        binding_id,
        provider,
        account_id
    FROM legacy
    ORDER BY binding_id, last_event_time ASC
)
INSERT INTO source_binding (
    binding_id, source_id, source_kind, config_origin, state, enabled, scope, effective_config,
    config_hash, deployment_config_hash, first_synced_at, last_synced_at, removed_at)
SELECT
    b.binding_id,
    'legacy-' || lower(b.provider),
    CASE b.provider WHEN 'AWS' THEN 'AWS_CLOUDTRAIL' ELSE 'GCP_CLOUD_AUDIT' END,
    'MIGRATED',
    'MIGRATED',
    FALSE,
    CASE b.provider
        WHEN 'AWS' THEN jsonb_build_object('regions', jsonb_build_array(b.account_id))
        ELSE jsonb_build_object('project-id', b.account_id)
    END,
    jsonb_build_object('migrated-from', 'source_watermarks', 'provider', b.provider,
                       'account-id', b.account_id),
    encode(sha256(convert_to('source_watermarks|' || b.provider || '|' || b.account_id, 'UTF8')), 'hex'),
    NULL,
    now(),
    now(),
    NULL
FROM bindings b
ON CONFLICT (binding_id) DO NOTHING;

WITH legacy AS (
    SELECT
        upper(provider) AS provider,
        account_id,
        last_event_time,
        updated_at,
        left('legacy-' || lower(provider) || '-'
                 || regexp_replace(lower(account_id), '[^a-z0-9._:-]', '-', 'g'), 128) AS binding_id
    FROM source_watermarks
    WHERE upper(provider) IN ('AWS', 'GCP')
)
INSERT INTO stream_cursor (
    binding_id, stream_id, partition_key, low_watermark, high_watermark, cursor, updated_at)
SELECT
    binding_id,
    CASE min(provider) WHEN 'AWS' THEN 'lookup-events' ELSE 'logging-api' END,
    CASE min(provider) WHEN 'AWS' THEN left(lower(min(account_id)), 256) ELSE '' END,
    min(last_event_time),
    min(last_event_time),
    jsonb_build_object('migrated-from', 'source_watermarks', 'provider', min(provider),
                       'account-id', min(account_id), 'legacy-rows', count(*)),
    max(updated_at)
FROM legacy
GROUP BY binding_id
ON CONFLICT (binding_id, stream_id, partition_key) DO NOTHING;
