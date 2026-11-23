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

package com.dlap.discovery.client.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Detailed DTO for a discovery scan.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscoveryScanDetail {

    private String scanId;
    private String scanName;
    private String scanType; // e.g., "GCP_PROJECTS", "AWS_ACCOUNT_FULL", "AZURE_SUBSCRIPTION"
    private String status; // e.g., "PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"
    private Instant submittedAt;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs; // Duration in milliseconds

    private ScanScope scope;
    private ScanConfiguration configuration;
    private ScanSummaryResults summaryResults;
    private String triggeredBy; // e.g., "user:uuid", "system"

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScanScope {

        private String type; // e.g., "AWS_ACCOUNT", "GCP_ORGANIZATION", "AZURE_MANAGEMENT_GROUP"
        // AWS Specific
        private String awsAccountId;
        private List<String> awsRegions;
        private List<String> awsResourceTypes;
        // GCP Specific
        private String gcpProjectId;
        private List<String> gcpProjectIds;
        private String gcpOrganizationId;
        // Azure Specific
        private String azureSubscriptionId;
        private String azureResourceGroup;
        // OCI Specific
        private String ociTenancyId;
        private String ociCompartmentId;
        // Common
        private List<String> includePatterns; // e.g. for resource names/tags
        private List<String> excludePatterns;
        private Map<String, String> tags; // for tag-based discovery
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScanConfiguration {

        private String mode; // "MANUAL", "SCHEDULED"
        private String schedule; // cron expression if mode is SCHEDULED
        private Map<String, Object> overrides; // Specific config overrides, e.g., {"enableFullScan": true}
        private Integer maxDepth; // for hierarchical discovery
        private Boolean incremental; // for incremental scans
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScanSummaryResults {

        private Long assetsScanned; // Total items evaluated by discovery crawler
        private Long assetsMatched; // Items that matched scope/filters before attempting to add/update catalog
        private Long assetsAdded; // New assets added to catalog
        private Long assetsUpdated; // Existing assets updated in catalog
        private Long assetsUnchanged; // Assets found but had no changes
        private Long errorsEncountered; // Number of errors during scan for specific resources
        private List<String> errorMessages; // Sample of error messages
    }
}
