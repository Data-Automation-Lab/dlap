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
import java.util.List;
import java.util.Map;

/**
 * DTO for requesting the evaluation of a specific compliance control.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlEvaluationRequestDTO {
    
    // Specific assets to target for this evaluation. If empty, might apply to all relevant assets.
    private List<String> targetAssetIds; 

    // Override or provide specific parameters for this evaluation run for the control.
    // These would align with or override those in ComplianceControlDTO.evaluationParametersDefinition.
    private Map<String, Object> evaluationParameters; 

    private String triggeredBy; // Optional: User or system that triggered the evaluation
    private String notificationEmail; // Optional: Email to notify upon completion/failure
    private boolean forceReevaluation; // Optional: If true, re-evaluate even if recent results exist
} 