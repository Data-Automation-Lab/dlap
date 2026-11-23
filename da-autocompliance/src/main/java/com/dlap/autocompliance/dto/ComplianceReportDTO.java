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
 * DTO representing a generated compliance report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceReportDTO {
    private String reportId; // Unique ID of this generated report instance
    private String reportType; // e.g., "cis-gcp-foundations-v1.3"
    private String displayName; // User-friendly name of the report type
    private LocalDateTime generationTimestamp;
    private String overallStatus; // e.g., COMPLIANT, NON_COMPLIANT, PARTIALLY_COMPLIANT, ERROR
    private Map<String, Object> generationParameters; // Parameters used to generate this report

    private SummaryDTO summary; // High-level summary statistics
    private List<FindingDTO> findings; // Detailed list of findings

    // Nested DTOs for structure
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryDTO {
        private int totalChecks;
        private int compliantChecks;
        private int nonCompliantChecks;
        private int errorChecks;
        private double complianceScore; // e.g., percentage
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FindingDTO {
        private String checkId; // Unique identifier for the specific check/control
        private String description;
        private String assetId; // ID of the asset evaluated
        private String assetType;
        private String assetName; // User-friendly name of the asset
        private String status; // COMPLIANT, NON_COMPLIANT, ERROR, NOT_APPLICABLE
        private String severity; // INFO, LOW, MEDIUM, HIGH, CRITICAL
        private String recommendation;
        private Map<String, Object> details; // Specific details about the finding
        private String evidenceLink; // Link to evidence if applicable
    }
} 