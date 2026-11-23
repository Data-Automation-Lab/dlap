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

-- Delta for the shared dlap_common database (matches 02-create-common-schema.sql).
-- AssetLabelAssignment.confidenceScore is a Java Double, which Hibernate
-- ddl-auto=validate maps to a FLOAT type. The column was DECIMAL(3,2) (numeric),
-- so da-catalog failed validation on boot:
--   wrong column type [confidence_score] found [numeric], expecting [float].
-- The entity's columnDefinition="DECIMAL(3,2)" did not help — it controls DDL
-- generation, not validation. Align the column to double precision (the range is
-- still enforced by the CHECK constraint + bean validation).
--
-- Idempotent; run as the db owner:
--   psql -U dlap -d dlap_common -f 2026-06-29_confidence_score_to_double.sql
\c dlap_common

ALTER TABLE dlap_common.dlap_asset_label_assignments
    ALTER COLUMN confidence_score TYPE DOUBLE PRECISION;
