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

-- DISC-01: lock table for ShedLock (JdbcTemplateLockProvider, usingDbTime()).
--
-- The scheduled discovery trigger (ScheduledDiscoveryTrigger) is a singleton job: with
-- more than one da-discovery replica every replica would otherwise start its own scan on
-- the same cron tick. Each tick takes one named row here; the replica that wins the row
-- runs the tick, the others skip it.
--
-- The column set is the one ShedLock requires, unchanged: name is the lock key,
-- lock_until the expiry (written with the database clock, so replica clock skew cannot
-- shorten or extend a lock), locked_at and locked_by identify the holder for operators.
-- ShedLock stores timestamps in UTC, hence TIMESTAMP without time zone.
--
-- Not an entity: Hibernate never maps this table, so ddl-auto=validate is unaffected.
-- Idempotent (CREATE TABLE IF NOT EXISTS) per the migration rules in CLAUDE.md section 7.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
