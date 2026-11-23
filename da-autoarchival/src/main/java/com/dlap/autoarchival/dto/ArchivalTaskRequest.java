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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * DTO for requesting an archival task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalTaskRequest {

    private String taskName; // Optional user-defined name for the task

    @NotNull(message = "Archival scope cannot be null")
    @Valid
    private ArchivalScope scope;

    // Optional: Allow overriding some global/provider config for this specific task
    private ArchivalConfigOverride overrideConfig;

    private String triggeredBy; // User or system that triggered the task
    private String justification; // Optional justification for the archival

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArchivalScope {
        // Define how assets are specified, e.g., by IDs, by query, by tags
        @NotEmpty(message = "At least one asset ID or a query must be provided.") // Example validation
        private List<String> assetIds; // Direct list of asset IDs from the catalog
        // private String assetQuery; // Or a query to select assets (e.g., SQL-like or from da-catalog search API)
        // private Map<String, String> assetTags; // Or assets matching specific tags
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArchivalConfigOverride {
        private String targetArchivalTier; // Override default tier
        private Long retentionDays; // Override default retention
        private String prefix; // RET-03: narrow a bucket asset to a key prefix
        private Integer afterDays; // RET-03: age in days after which objects move (default: the class minimum)
        // Potentially other specific overrides
    }
} 