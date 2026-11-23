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

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Request DTO for submitting feedback on auto-suggested labels.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingFeedbackRequest {

    @NotBlank(message = "Asset ID cannot be blank")
    private String assetId;

    @NotBlank(message = "Job ID that generated the suggestions cannot be blank")
    private String labelingJobId; // ID of the job that produced these suggestions

    @NotEmpty(message = "Feedback items cannot be empty")
    private List<FeedbackItem> feedbackItems;

    private String userId; // Optional: User providing the feedback
    private Map<String, String> additionalContext; // Optional: e.g., UI version, session ID

    /**
     * Nested DTO for individual feedback items.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FeedbackItem {
        @NotBlank(message = "Suggested label cannot be blank")
        private String suggestedLabel;

        private Double originalConfidence; // Optional: Confidence score from the LLM

        @NotNull(message = "Feedback type cannot be null")
        private FeedbackType type; // e.g., CONFIRMED, REJECTED, CORRECTED

        private String correctedLabel; // If type is CORRECTED
        private String userComment; // Optional comment
    }

    /**
     * Enum for feedback type.
     */
    public enum FeedbackType {
        CONFIRMED, // User agrees with the suggestion
        REJECTED,  // User disagrees with the suggestion
        CORRECTED, // User provides a corrected label
        FLAGGED    // User flags for further review, without immediate correction
    }
} 