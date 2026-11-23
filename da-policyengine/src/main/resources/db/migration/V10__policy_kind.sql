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

-- POL-04 (ADR-0136, Q-871): a discriminator that tells a policy row that never had a DSL document
-- (a legacy rule row, ADR-0057) from a row whose document was blanked later. The PDP skipped every
-- ENABLED row without a document, so a deny blanked by a direct database edit failed open; an
-- ENABLED DSL row without a document is now an invalid deny (policy-store-invalid).
--
-- Backfill from the data: a row with a non-blank policy_document is DSL, every other row is
-- LEGACY_RULE. There is no column default on purpose: a row written without a kind must be refused,
-- not guessed. Every statement is idempotent.

ALTER TABLE policies ADD COLUMN IF NOT EXISTS policy_kind VARCHAR(16);

-- Soft-deleted rows are frozen by trg_policies_soft_delete_guard (V9), so the backfill switches the
-- guard off for this one statement. Nothing else changes in those rows.
ALTER TABLE policies DISABLE TRIGGER trg_policies_soft_delete_guard;

UPDATE policies
   SET policy_kind = CASE
           WHEN policy_document IS NOT NULL AND policy_document ~ '\S' THEN 'DSL'
           ELSE 'LEGACY_RULE'
       END
 WHERE policy_kind IS NULL;

ALTER TABLE policies ENABLE TRIGGER trg_policies_soft_delete_guard;

ALTER TABLE policies ALTER COLUMN policy_kind SET NOT NULL;

DO $$ BEGIN
    ALTER TABLE policies ADD CONSTRAINT ck_policies_policy_kind CHECK (policy_kind IN ('LEGACY_RULE', 'DSL'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
