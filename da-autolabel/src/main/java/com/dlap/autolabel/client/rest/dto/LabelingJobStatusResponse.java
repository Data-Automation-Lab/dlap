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
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Response DTO for querying the status of an auto-labeling job.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingJobStatusResponse {

    private String jobId;
    private String jobName; // From the original request
    private String status; // e.g., SUBMITTED, PREPARING_DATA, CALLING_LLM, APPLYING_LABELS, COMPLETED, FAILED, PARTIALLY_COMPLETED
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime lastUpdatedAt;
    private LocalDateTime completedAt;

    private Integer totalAssetsToProcess;
    private Integer assetsProcessed;
    private Integer assetsSuccessfullyLabeled;
    private Integer assetsFailed;

    private String currentStageDescription; // e.g., "Fetching metadata for asset X", "Calling LLM for batch Y"
    private Double progressPercentage; // Overall progress

    private List<String> errorMessages; // List of errors if any occurred

    /**
     * Optional: Link to where detailed results or logs might be found, if applicable.
     */
    private String resultsLink;

    /**
     * Optional: summary of labels applied or suggested if the job is complete or partially complete.
     * Key: Label Name, Value: Count of assets this label was applied to.
     */
    private Map<String, Integer> labelSummary;
} 