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

/**
 * DTO for representing the status of a compliance report generation job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportJobStatusDTO {
    private String jobId;
    private String reportType;
    private String status; // e.g., QUEUED, PROCESSING, COMPLETED_SUCCESS, COMPLETED_FAILURE, NOT_FOUND
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String message; // e.g., "Processing started", "Report generated successfully", "Failed due to: ..."
    private String reportId; // ID of the generated report if completed successfully
    private String downloadLink; // Link to download the report if applicable
} 