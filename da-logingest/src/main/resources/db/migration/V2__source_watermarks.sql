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

-- V2__source_watermarks.sql
--
-- ADR-0023. Watermark table for per-cloud pull-based log sources.
-- One row per (provider, accountId); rows are upserted by the source
-- adapter after each successful batch.

SET search_path TO dlap_logingest, public;

CREATE TABLE IF NOT EXISTS source_watermarks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider          VARCHAR(32)  NOT NULL,
    account_id        VARCHAR(256) NOT NULL,
    last_event_time   TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_source_watermark_provider_account UNIQUE (provider, account_id)
);

CREATE INDEX IF NOT EXISTS idx_source_watermarks_provider
    ON source_watermarks (provider);
