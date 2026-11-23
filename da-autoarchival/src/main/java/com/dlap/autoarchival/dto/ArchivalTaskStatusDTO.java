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

package com.dlap.autoarchival.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for representing the detailed status of an archival task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalTaskStatusDTO {
    private String taskId;
    private String taskName;
    private String status; // e.g., SUBMITTED, PENDING_APPROVAL, IN_PROGRESS, COMPLETED, FAILED, PARTIALLY_COMPLETED
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String triggeredBy;
    private String justification;
    private ArchivalTaskRequest.ArchivalScope scope; // The original scope
    private ArchivalTaskRequest.ArchivalConfigOverride overrideConfig; // Original overrides

    private int totalAssetsInScope;
    private int assetsArchivedSuccessfully;
    private int assetsFailedToArchive;
    private int assetsPendingArchival;

    private List<AssetArchivalStatus> assetStatuses; // Status for each asset in the task
    private List<String> errorMessages; // Overall task error messages

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetArchivalStatus {
        private String assetId;
        private String assetName; // Optional, might need to be fetched from catalog
        private String status; // PENDING, IN_PROGRESS, ARCHIVED, FAILED, RESTORED
        private String archivalLocation; // e.g., S3 URI, Azure Blob URI post-archival
        private String errorMessage; // If archival failed for this asset
        private LocalDateTime archivedAt;
        private LocalDateTime restoredAt;
        private String approvalId; // the approval this asset's archive runs under
        private String ruleId; // the native rule (or direct change) record
    }
} 