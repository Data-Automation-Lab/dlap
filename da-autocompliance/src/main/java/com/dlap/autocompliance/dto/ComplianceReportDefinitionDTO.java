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

import java.util.List;
import java.util.Map;

/**
 * DTO representing the definition of a compliance report type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceReportDefinitionDTO {
    private String reportType; // Unique identifier for the report type (e.g., "pci-dss-q1", "cis-benchmark-gcp")
    private String displayName; // User-friendly name (e.g., "PCI DSS Requirement 1 Check", "CIS Google Cloud Platform Foundation Benchmark")
    private String description;
    private String version; // Version of the report definition/benchmark
    private List<String> applicableComplianceFrameworks; // e.g., ["PCI DSS v3.2.1", "HIPAA"]
    private List<String> targetAssetTypes; // e.g., ["GCP_COMPUTE_INSTANCE", "AWS_S3_BUCKET"]
    private Map<String, String> generationParameters; // Key-value pairs for parameters needed to generate this report (e.g., {"gcpProjectId": "string"})
    private String detailsLink; // Link to more information about this compliance check/report
} 