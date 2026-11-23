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
--
-- RET-09 (Q-708, Q-710, Q-711), follow-ups to RET-07.
--   1. Q-708: a task whose assets all ended RULE_REMOVED gets its own task status, RULE_REMOVED, instead of
--      RESTORED (which reads as if objects were restored).
--   2. Q-711: a task whose every asset is already covered by an identical live GCS row gets the task status
--      ALREADY_HELD. That is not a refusal and not a failure: nothing is left to do.
--   3. Q-710: at most one live GCS row per scope. A GCS lifecycle rule has no id, so two live rows for one
--      bucket, prefix, class and age would share one provider rule. The planner already refuses the second row;
--      this partial unique index closes the window in which two approvals planned in the same instant both pass.
--      The index is created only after a check for existing duplicates. If there are any, this migration stops
--      with a message that names them, and nothing is changed. Resolve them (restore or remove all but one of each
--      group, see da-autoarchival/README.md) and start the service again.

ALTER TABLE dlap_autoarchival.dlap_archival_tasks DROP CONSTRAINT IF EXISTS dlap_archival_tasks_status_check;
ALTER TABLE dlap_autoarchival.dlap_archival_tasks ADD CONSTRAINT dlap_archival_tasks_status_check CHECK (status IN
    ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUBMITTED', 'IN_PROGRESS', 'COMPLETED', 'PARTIALLY_COMPLETED',
     'FAILED', 'CANCELLED', 'RESTORATION_REQUESTED', 'RESTORATION_IN_PROGRESS', 'RESTORED', 'RESTORATION_FAILED',
     'RULE_APPLIED', 'RULE_RENDERED', 'EXECUTED', 'REFUSED', 'EXPIRED', 'RULE_REMOVED', 'ALREADY_HELD'));

DO $$
DECLARE
    duplicates text;
BEGIN
    SELECT string_agg(g.description, '; ' ORDER BY g.description) INTO duplicates
      FROM (
        SELECT 'bucket ' || scope_bucket || ', prefix "' || scope_prefix || '", class ' || native_class
               || ', after ' || after_days || ' days: rows '
               || string_agg(rule_id || ' (' || status || ')', ', ' ORDER BY created_at, rule_id) AS description
          FROM dlap_autoarchival.dlap_archive_rules
         WHERE provider = 'GCP' AND mode = 'NATIVE_RULE' AND mock = false
           AND status IN ('PLANNED', 'EXECUTING', 'APPLIED', 'EXECUTED')
         GROUP BY provider, scope_bucket, scope_prefix, native_class, after_days
        HAVING count(*) > 1
      ) g;
    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'V4 cannot create ux_archive_rules_live_gcs_scope: live GCS archive rows share one scope (%). '
            'Restore or remove all but one row of each group (see da-autoarchival/README.md, "Duplicate GCS scope"), '
            'then start the service again. No change was made.', duplicates;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_archive_rules_live_gcs_scope ON dlap_autoarchival.dlap_archive_rules
    (provider, scope_bucket, scope_prefix, native_class, after_days)
    WHERE provider = 'GCP' AND mode = 'NATIVE_RULE' AND mock = false
      AND status IN ('PLANNED', 'EXECUTING', 'APPLIED', 'EXECUTED');
