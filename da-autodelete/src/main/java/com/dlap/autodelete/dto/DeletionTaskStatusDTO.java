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

package com.dlap.autodelete.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for representing the detailed status of a deletion task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletionTaskStatusDTO {
    private String taskId;
    private String taskName;
    // PENDING_APPROVAL, APPROVED, REJECTED, SUBMITTED, QUEUED, IN_PROGRESS, COMPLETED, FAILED, PARTIALLY_COMPLETED, CANCELLED,
    // and for a soft-delete record in the list: DRY_RUN (nothing was deleted) and RULE_IN_FORCE (RET-04),
    // RULE_ENDED and RESTORE_FAILED (RET-06), RULE_ENDED_UNPROVEN (RET-08)
    private String status;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    /**
     * When the soft-delete window closes, from the soft-delete record (UI-04, Q-623). Set on every list row that
     * comes from a soft-delete record; null when the record has no window.
     */
    @Schema(description = "When the soft-delete window closes (ISO-8601 instant, UTC), from the record's window; "
            + "null when the record has none", nullable = true)
    private java.time.Instant scheduledHardDeleteAt;
    /**
     * When the bucket, prefix or container rule was applied. Set on a RULE_IN_FORCE row and on an applied container
     * rule that still waits for its proof (IN_PROGRESS); null otherwise (RET-06 Q-677, UI-04 Q-746).
     */
    @Schema(description = "When the container rule was applied; set for RULE_IN_FORCE and for an applied container "
            + "rule that waits for its proof (IN_PROGRESS), null otherwise", nullable = true)
    private java.time.Instant ruleAppliedAt;
    /** Whole days since the rule was applied; set with {@link #ruleAppliedAt}. */
    @Schema(description = "Whole days since the rule was applied; set with ruleAppliedAt", nullable = true)
    private Long ruleAgeDays;
    /** When the executor removes the rule (dlap.autodelete.rule-in-force.max-age); set with {@link #ruleAppliedAt}. */
    @Schema(description = "When the executor removes the rule at its maximum age; set with ruleAppliedAt",
            nullable = true)
    private java.time.Instant ruleEndsAt;
    private String triggeredBy;
    private String justification;

    private DeletionTaskRequest.DeletionScope scope; // Original scope
    private DeletionTaskRequest.DeletionConfigOverride overrideConfig; // Applied overrides

    private int totalAssetsInScope;
    private int assetsProcessedSuccessfully;
    private int assetsFailedToProcess;
    private int assetsPendingProcessing;

    // Could add a list of individual asset statuses if needed for detailed view
    // private List<AssetDeletionStatus> assetStatuses;

    private String approvalComments;
    private String rejectionComments;
    private List<String> errorMessages;
} 