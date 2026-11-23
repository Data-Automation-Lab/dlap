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

-- ADR-0040 (C2b): a policy draft can now author a kind:AccessPolicy in addition
-- to the default kind:Policy. `kind` selects the translator path at publish time;
-- the access_* columns carry the structured AccessPolicy input (subjects, actions,
-- request-context condition, effect, obligations). All nullable/defaulted so
-- existing lifecycle drafts are unaffected. The asset selector is reused from the
-- existing rules_definition column (spec.match).
ALTER TABLE policy_drafts ADD COLUMN IF NOT EXISTS kind VARCHAR(64) NOT NULL DEFAULT 'Policy';
ALTER TABLE policy_drafts ADD COLUMN IF NOT EXISTS access_subjects JSONB;
ALTER TABLE policy_drafts ADD COLUMN IF NOT EXISTS access_actions JSONB;
ALTER TABLE policy_drafts ADD COLUMN IF NOT EXISTS access_condition JSONB;
ALTER TABLE policy_drafts ADD COLUMN IF NOT EXISTS access_effect VARCHAR(16);
ALTER TABLE policy_drafts ADD COLUMN IF NOT EXISTS access_obligations JSONB;
