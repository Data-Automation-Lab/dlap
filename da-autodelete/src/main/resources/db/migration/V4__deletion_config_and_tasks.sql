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

-- V4__deletion_config_and_tasks.sql
--
-- DB-01. The global deletion configuration and the deletion task tables were never
-- migrated: com.dlap.autodelete.model.DeletionConfigEntity and DeletionTaskEntity were
-- created at boot by Hibernate (ddl-auto=update), so a fresh database could not start
-- once the default became validate. This migration creates them.
--
-- Every column below is the DDL Hibernate 6.4 generates for the two entities with the
-- Spring Boot naming strategy (camelCase -> snake_case), so a database that already
-- ran under ddl-auto=update is left untouched (CREATE TABLE IF NOT EXISTS plus a
-- guarded foreign key) and a fresh database ends up with the identical shape. Both
-- entities have not changed since the tables were first generated (last touched by
-- the dalab_* -> dlap_* identifier rename, commit 02b42c2), so no ADD COLUMN repair
-- is needed. AutodeleteFlywayValidateTest proves the result satisfies
-- ddl-auto=validate, and that the status CHECK lists every DeletionStatus value.
--
-- Notes:
--   * Lengths are the JPA default (255) because the entities declare none. A
--     justification or comment longer than 255 characters is rejected by the database;
--     widening needs an entity change plus a V5, which is out of scope here.
--   * The status CHECK and the foreign key keep the names Hibernate generates, so
--     databases bootstrapped by ddl-auto=update and fresh databases agree, and a later
--     migration can drop them by one name.
--   * dlap_deletion_config holds one row (id = 1), inserted by
--     DeletionConfigServiceImpl at startup. It is deliberately not seeded here.

-- Global deletion configuration (DeletionConfigEntity).
CREATE TABLE IF NOT EXISTS dlap_autodelete.dlap_deletion_config (
    id                                  BIGINT       NOT NULL,
    enabled                             BOOLEAN      NOT NULL,
    soft_delete_by_default              BOOLEAN      NOT NULL,
    default_soft_delete_retention_days  BIGINT,
    require_approval_for_hard_delete    BOOLEAN      NOT NULL,
    require_approval_for_soft_delete    BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

-- Element collection DeletionConfigEntity.notificationEmailsOnError (a List<String>
-- without an order column, so Hibernate maps it with no primary key).
CREATE TABLE IF NOT EXISTS dlap_autodelete.dlap_deletion_config_notification_emails (
    config_id  BIGINT NOT NULL,
    email      VARCHAR(255)
);

-- Add the foreign key only when the table has none: a database bootstrapped by
-- ddl-auto=update already carries one under Hibernate's generated name.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'dlap_autodelete.dlap_deletion_config_notification_emails'::regclass
           AND contype = 'f'
    ) THEN
        ALTER TABLE dlap_autodelete.dlap_deletion_config_notification_emails
            ADD CONSTRAINT fkqbbmrkc88m79ahlxxr953y6pw
            FOREIGN KEY (config_id)
            REFERENCES dlap_autodelete.dlap_deletion_config (id);
    END IF;
END $$;

-- Deletion tasks (DeletionTaskEntity). The four jsonb columns carry the scope, the
-- per-task override, the per-asset status list and the task-level error messages.
CREATE TABLE IF NOT EXISTS dlap_autodelete.dlap_deletion_tasks (
    task_id                        VARCHAR(255) NOT NULL,
    task_name                      VARCHAR(255),
    status                         VARCHAR(255),
    submitted_at                   TIMESTAMP(6),
    started_at                     TIMESTAMP(6),
    completed_at                   TIMESTAMP(6),
    triggered_by                   VARCHAR(255),
    justification                  VARCHAR(255),
    scope                          JSONB,
    override_config                JSONB,
    total_assets_in_scope          INTEGER      NOT NULL,
    assets_processed_successfully  INTEGER      NOT NULL,
    assets_failed_to_process       INTEGER      NOT NULL,
    assets_pending_processing      INTEGER      NOT NULL,
    asset_statuses                 JSONB,
    error_messages                 JSONB,
    approval_comments              VARCHAR(255),
    rejection_comments             VARCHAR(255),
    PRIMARY KEY (task_id),
    CONSTRAINT dlap_deletion_tasks_status_check CHECK (status IN (
        'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUBMITTED', 'QUEUED',
        'IN_PROGRESS', 'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED'
    ))
);
