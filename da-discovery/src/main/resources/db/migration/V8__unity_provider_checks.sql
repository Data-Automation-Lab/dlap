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

-- UC-5: UNITY is a new CloudProvider. V7 widened cloud_resource; discovery_job and
-- resource_change carry the same cloud_provider CHECK and must accept UNITY too, or
-- creating/executing a UNITY discovery job fails the constraint.
ALTER TABLE discovery_job DROP CONSTRAINT IF EXISTS discovery_job_cloud_provider_check;
ALTER TABLE discovery_job
    ADD CONSTRAINT discovery_job_cloud_provider_check
    CHECK (cloud_provider IN ('GCP','AWS','AZURE','OCI','DATABRICKS','UNITY','UNKNOWN'));

ALTER TABLE resource_change DROP CONSTRAINT IF EXISTS resource_change_cloud_provider_check;
ALTER TABLE resource_change
    ADD CONSTRAINT resource_change_cloud_provider_check
    CHECK (cloud_provider IN ('GCP','AWS','AZURE','OCI','DATABRICKS','UNITY','UNKNOWN'));
