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

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * DTO for requesting a new deletion task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletionTaskRequest {

    @Size(max = 255, message = "Task name cannot exceed 255 characters.")
    private String taskName; // Optional friendly name for the task

    @NotNull(message = "Deletion scope cannot be null.")
    private DeletionScope scope;

    private String triggeredBy; // User or system that triggered the task
    private String justification; // Reason for deletion

    private DeletionConfigOverride overrideConfig; // Optional config overrides for this specific task

    /**
     * Defines the scope of assets to be deleted.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeletionScope {
        @NotEmpty(message = "At least one asset ID must be provided for deletion.")
        private List<String> assetIds; // List of asset IDs to delete
        // Could also include criteria like tags, creation date ranges, etc.
    }

    /**
     * Allows overriding global deletion settings for this specific task.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeletionConfigOverride {
        private Boolean softDelete; // Override global soft-delete setting
        private Long softDeleteRetentionDays; // Override retention if soft-deleting
        private Boolean requireApproval; // Override approval requirement for this task
    }
} 