/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.autodelete.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persisted compliance audit trail for cloud deletion operations.
 *
 * <p>Every successful or failed invocation of an {@code ICloudDeletionService} implementation
 * must write a row here. This table is the source of truth for "did we actually delete the data?"
 * compliance questions (GDPR right-to-be-forgotten, HIPAA disposal, SOX record retention).
 *
 * <p>Rows are append-only in intent - callers should never update or delete entries, only insert.
 * The table is not partitioned yet; this is fine for v0.1.0 volumes.
 */
@Entity
@Table(
    name = "dlap_deletion_audit",
    indexes = {
        @Index(name = "idx_deletion_audit_task_id", columnList = "task_id"),
        @Index(name = "idx_deletion_audit_asset_id", columnList = "asset_id"),
        @Index(name = "idx_deletion_audit_performed_at", columnList = "performed_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeletionAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Correlates back to the originating {@code DeletionTaskEntity.taskId}. */
    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    /** The asset (or asset location) that was targeted for deletion. */
    @Column(name = "asset_id", length = 512, nullable = false)
    private String assetId;

    /** AWS / GCP / AZURE (upper-case). */
    @Column(name = "cloud_provider", length = 32, nullable = false)
    private String cloudProvider;

    /** Short machine action, e.g. DELETE_GCS_OBJECT, DELETE_BQ_TABLE, DELETE_S3_OBJECT. */
    @Column(name = "action", length = 64, nullable = false)
    private String action;

    /** SUCCESS / FAILED / SKIPPED / VERIFIED / VERIFICATION_FAILED. */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    /** User or system principal that triggered the deletion. Never a credential. */
    @Column(name = "performed_by", length = 256)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    /** VERIFIED / NOT_FOUND / STILL_PRESENT / NOT_CHECKED. */
    @Column(name = "verification_status", length = 32)
    private String verificationStatus;

    /**
     * Human-readable summary of the SDK response (never the raw credentials / tokens).
     * Kept short for indexing; detailed traces belong in application logs.
     */
    @Column(name = "raw_response_summary", length = 2048)
    private String rawResponseSummary;

    /** Reclaimed bytes, if known from the SDK response. Null when not reported. */
    @Column(name = "bytes_deleted")
    private Long bytesDeleted;
}
