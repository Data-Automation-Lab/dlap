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

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/**
 * DTO for requesting the generation of a specific compliance report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportGenerationRequestDTO {
    
    // Parameters required for this specific report type, matching those defined in ComplianceReportDefinitionDTO.generationParameters
    // For example, {"gcpProjectId": "my-gcp-project", "targetRegions": ["us-central1", "us-east1"]}
    @NotEmpty(message = "Generation parameters cannot be empty.")
    private Map<String, Object> parameters; 

    private String triggeredBy; // Optional: User or system that triggered the generation
    private String notificationEmail; // Optional: Email to notify upon completion/failure
} 