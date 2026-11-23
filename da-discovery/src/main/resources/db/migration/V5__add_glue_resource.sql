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

-- N5 (AWS provider): JOINED-inheritance child table for GlueResource.

CREATE TABLE IF NOT EXISTS glue_resource (
    id                  UUID NOT NULL,
    glue_database       VARCHAR(255),
    glue_table_type     VARCHAR(255),
    glue_location       VARCHAR(1024),
    glue_classification VARCHAR(255),
    PRIMARY KEY (id)
);

DO $$ BEGIN
    ALTER TABLE glue_resource
        ADD CONSTRAINT fk_glue_resource_aws_resource
        FOREIGN KEY (id) REFERENCES aws_resource;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
