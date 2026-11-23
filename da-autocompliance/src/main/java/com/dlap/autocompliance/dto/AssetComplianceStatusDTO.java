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

package com.dlap.autocompliance.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO representing the compliance status of a specific asset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetComplianceStatusDTO {
    private String assetId;
    private String assetType; // e.g., GCP_COMPUTE_INSTANCE, AWS_S3_BUCKET
    private String assetName; // User-friendly name if available
    private String overallComplianceStatus; // e.g., COMPLIANT, NON_COMPLIANT, PARTIALLY_COMPLIANT, UNKNOWN
    private LocalDateTime lastEvaluatedAt;
    private int totalChecksApplied;
    private int compliantChecks;
    private int nonCompliantChecks;
    private List<ComplianceFindingSummaryDTO> recentNonCompliantFindings; // Summaries of key issues

    // Could also include links to full reports involving this asset
    private List<String> relevantReportIds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplianceFindingSummaryDTO {
        private String checkId;
        private String description;
        private String severity;
        private String reportId; // Report where this finding was detailed
        private LocalDateTime findingTimestamp;
    }
} 