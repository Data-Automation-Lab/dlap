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

-- N5 (AWS provider): JOINED-inheritance child table for RdsResource.

CREATE TABLE IF NOT EXISTS rds_resource (
    id                    UUID NOT NULL,
    rds_engine            VARCHAR(255),
    rds_engine_version    VARCHAR(255),
    rds_instance_class    VARCHAR(255),
    rds_status            VARCHAR(255),
    rds_endpoint          VARCHAR(512),
    rds_storage_encrypted BOOLEAN,
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE rds_resource
        ADD CONSTRAINT fk_rds_resource_aws_resource
        FOREIGN KEY (id) REFERENCES aws_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
