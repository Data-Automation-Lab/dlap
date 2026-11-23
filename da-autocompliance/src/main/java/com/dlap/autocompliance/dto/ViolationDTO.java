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
 * DTO representing a compliance violation/finding for API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViolationDTO {
    private String id; // Unique identifier for the violation (e.g., finding or reportFindingId)
    private String controlId; // Corresponds to checkId
    private String title; // Short title/summary
    private String description; // Detailed description
    private String severity; // LOW, MEDIUM, HIGH, CRITICAL
    private String regulation; // Regulation/framework (e.g., HIPAA, GDPR)
    private List<String> affectedAssets; // List of asset IDs/names
    private LocalDateTime detectedAt; // When detected
    private String status; // Open, In Progress, Resolved, False Positive
    private String assignedTo; // Optional: who is assigned
    private LocalDateTime dueDate; // Optional: due date
    private String recommendation; // Remediation steps
    private Map<String, Object> details; // Additional details
    private String evidenceLink; // Link to evidence if any
} 