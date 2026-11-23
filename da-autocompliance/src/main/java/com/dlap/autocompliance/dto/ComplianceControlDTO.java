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
 * DTO representing a single, executable compliance control or check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceControlDTO {
    private String controlId; // Unique identifier for the control (e.g., "CIS-GCP-1.1", "PCI-REQ-3.4")
    private String name; // Short, human-readable name of the control
    private String description; // Detailed description of what the control checks
    private String severity; // e.g., CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL
    private List<String> applicableFrameworks; // e.g., ["CIS Benchmark v1.3", "PCI DSS v3.2.1"]
    private List<String> targetAssetTypes; // Asset types this control applies to (e.g., "GCP_BUCKET", "AWS_IAM_USER")
    private String remediationSteps; // Suggested steps to remediate a non-compliant finding
    private Map<String, String> evaluationParametersDefinition; // Parameters needed to evaluate this control (name: type)
    private String detailsLink; // Link to external documentation for this control
    private boolean enabled; // Whether this control is currently active for evaluation
} 