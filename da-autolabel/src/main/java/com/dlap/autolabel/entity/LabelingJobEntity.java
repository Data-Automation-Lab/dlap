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

package com.dlap.autolabel.entity;

import com.dlap.autolabel.client.rest.dto.LabelingJobRequest;
import com.dlap.llm.client.config.MLConfigRequest;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * JPA Entity representing an auto-labeling job.
 */
@Entity
@Table(name = "dlap_autolabel_job")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingJobEntity {

    @Id
    private String jobId;

    private String jobName;

    @Enumerated(EnumType.STRING)
    private JobStatus status;

    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime lastUpdatedAt;
    private LocalDateTime completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private LabelingJobRequest.LabelingScope scope; // Store the original scope

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private MLConfigRequest effectiveMlConfig; // Store the ML config used for this job

    private Integer totalAssetsToProcess;
    private Integer assetsProcessed;
    private Integer assetsSuccessfullyLabeled;
    private Integer assetsFailed;

    private String currentStageDescription;
    private Double progressPercentage;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dlap_autolabel_job_errors", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "error_message", length = 2048) // Increased length for error messages
    private List<String> errorMessages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Integer> labelSummary; // Key: Label Name, Value: Count
    
    private String triggeredByUserId; // User who triggered the job

    /** Fire id of the policy action that started the job; unique when present (V2), null for other jobs. */
    private String fireId;

    // Enum for JobStatus (can be shared or specific to this entity's package)
    public enum JobStatus {
        SUBMITTED, PREPARING_DATA, CALLING_LLM, APPLYING_LABELS, COMPLETED, FAILED, PARTIALLY_COMPLETED, CANCELLED
    }
} 