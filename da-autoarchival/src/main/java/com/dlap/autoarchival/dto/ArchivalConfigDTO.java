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

import java.util.List;
import java.util.Map;

/**
 * DTO for representing the auto-archival configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalConfigDTO {

    private Boolean enabled; // Global enable/disable for auto-archival
    private String defaultArchivalTier; // e.g., "S3_GLACIER_DEEP_ARCHIVE", "AZURE_ARCHIVE"
    private Long defaultRetentionDays; // Default retention before data is eligible for permanent deletion after archival
    private boolean requireApprovalForArchival; // If manual approval is needed before archival
    private boolean requireApprovalForRestore; // If manual approval is needed for restoration

    // Potentially add provider-specific configurations or rules here if needed
    // For example, specific storage account details or lifecycle policy names
    private Map<String, ProviderArchivalConfig> providerConfigs;

    // Represents archival settings specific to a cloud provider
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderArchivalConfig {
        private String archivalStorageLocation; // e.g., bucket name, container name
        private String defaultRegion; // if applicable
        private Map<String, String> customProperties; // for provider-specific settings
    }
    
    // Could also include a list of rules directly, or link to policies in da-policyengine
    // For simplicity, starting with general config. Rules might be handled by Policy Engine.
} 