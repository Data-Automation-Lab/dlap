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

-- IDEM-01 (Q-856): dlap.policies.actions is delivered at least once and the policy engine republishes a
-- fire with the same fireId when its source event is redelivered. A policy addLabel creates one job row
-- (dlap_autolabel_job); the row carries the fire id, and a fire is applied and recorded once.
--
-- Jobs from before this migration, manual runs and actions without a fireId keep a NULL fire_id; the index
-- is partial, so any number of NULL rows stay valid.
ALTER TABLE dlap_autolabel.dlap_autolabel_job ADD COLUMN IF NOT EXISTS fire_id character varying(255);

CREATE UNIQUE INDEX IF NOT EXISTS ux_autolabel_job_fire_id
    ON dlap_autolabel.dlap_autolabel_job (fire_id)
    WHERE fire_id IS NOT NULL;
