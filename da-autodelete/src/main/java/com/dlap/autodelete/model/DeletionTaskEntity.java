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

package com.dlap.autodelete.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
// import java.util.Map; // If using Map for complex JSON fields

@Entity
@Table(name = "dlap_deletion_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeletionTaskEntity {

    @Id
    private String taskId;

    private String taskName;

    @Enumerated(EnumType.STRING)
    private DeletionStatus status;

    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String triggeredBy;
    private String justification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private DeletionScopeData scope; // Store as JSON

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private DeletionConfigOverrideData overrideConfig; // Store as JSON

    private int totalAssetsInScope;
    private int assetsProcessedSuccessfully;
    private int assetsFailedToProcess;
    private int assetsPendingProcessing;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<AssetDeletionStatusData> assetStatuses; // Store detailed asset status as JSON

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> errorMessages; // Store task-level error messages as JSON
    
    private String approvalComments;
    private String rejectionComments;

    /** RET-02: the JWT subject that submitted the task (never a value from the request body). */
    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    /** RET-02: when the retire sweeper looks at the pending approvals of this task next. */
    @Column(name = "next_check_at")
    private java.time.Instant nextCheckAt;

    // Static inner classes for JSONB data structures
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DeletionScopeData {
        private List<String> assetIds;
        // Potentially add other criteria here like tags, patterns etc.
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DeletionConfigOverrideData {
        private Boolean softDelete;
        private Long softDeleteRetentionDays;
        private Boolean requireApproval;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AssetDeletionStatusData {
        private String assetId;
        private String assetName; // Optional, for easier reporting
        private String status; // e.g., PENDING, SOFT_DELETED, HARD_DELETED, FAILED
        private String deletionType; // SOFT, HARD
        private String errorMessage;
        private LocalDateTime processedAt;
        /** RET-02: the approval request (policy engine) that decides this asset. */
        private String approvalId;
        /** RET-02: the soft-delete record created once the approval was executable. */
        private String softDeleteRecordId;

        /**
         * The six original fields; the approval and record ids start empty.
         *
         * @param assetId       the asset id as submitted
         * @param assetName     a display name, or null
         * @param status        the per-asset status
         * @param deletionType  SOFT or HARD
         * @param errorMessage  why it failed, or null
         * @param processedAt   when it reached its final status, or null
         */
        public AssetDeletionStatusData(String assetId, String assetName, String status, String deletionType,
                String errorMessage, LocalDateTime processedAt) {
            this(assetId, assetName, status, deletionType, errorMessage, processedAt, null, null);
        }
    }
} 