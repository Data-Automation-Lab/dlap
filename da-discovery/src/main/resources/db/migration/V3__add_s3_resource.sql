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

-- N5 (AWS provider): JOINED-inheritance child table for S3Resource,
-- a subtype of aws_resource. Matches the Hibernate-derived mapping.

CREATE TABLE IF NOT EXISTS s3_resource (
    id                   UUID NOT NULL,
    s3_versioning_status VARCHAR(255),
    s3_is_public         BOOLEAN,
    s3_encryption        VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE s3_resource
        ADD CONSTRAINT fk_s3_resource_aws_resource
        FOREIGN KEY (id) REFERENCES aws_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
