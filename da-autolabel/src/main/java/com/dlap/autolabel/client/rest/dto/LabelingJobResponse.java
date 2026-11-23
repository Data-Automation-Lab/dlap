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

package com.dlap.autolabel.client.rest.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Response DTO after submitting an auto-labeling job.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingJobResponse {

    /**
     * The unique identifier for the submitted labeling job.
     */
    private String jobId;

    /**
     * The asset ID that was processed (for single asset jobs).
     */
    private String assetId;

    /**
     * The current status of the job (e.g., "SUBMITTED", "RUNNING", "COMPLETED", "FAILED").
     */
    private String status;

    /**
     * Timestamp when the job was submitted/created.
     */
    private LocalDateTime submittedAt;

    /**
     * Timestamp when the job was created.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the job was completed.
     */
    private LocalDateTime completedAt;

    /**
     * Processing time in milliseconds.
     */
    private Long processingTimeMs;

    /**
     * A brief message providing more details about the job submission.
     */
    private String message;

    /**
     * Error message if the job failed.
     */
    private String errorMessage;

    /**
     * List of label suggestions with confidence scores.
     */
    private List<LabelSuggestionDTO> suggestions;

    /**
     * DTO for label suggestions.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LabelSuggestionDTO {
        private String labelName;
        private Double confidence;
        private String reasoning;
        private String category;
    }
} 