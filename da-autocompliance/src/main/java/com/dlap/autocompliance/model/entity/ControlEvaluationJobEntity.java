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

package com.dlap.autocompliance.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "compliance_control_evaluation_jobs", indexes = {
    @Index(name = "idx_ctrl_eval_job_control_id", columnList = "controlId"),
    @Index(name = "idx_ctrl_eval_job_status", columnList = "status")
})
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ControlEvaluationJobEntity extends AbstractAuditableEntity {

    @Id
    @Column(length = 36) // UUID length
    private String jobId;

    @Column(nullable = false, length = 100)
    private String controlId; // Foreign key to ComplianceControlEntity.controlId

    @Column(nullable = false, length = 50)
    private String status; // e.g., QUEUED, PROCESSING, COMPLETED_SUCCESS, COMPLETED_FAILURE, PARTIAL_FAILURE

    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Lob
    // Map to text (not oid) deterministically so the Flyway V4 DDL matches ddl-auto=validate.
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Basic(fetch = FetchType.LAZY)
    private String message; // Overall message for the job execution

    @ElementCollection(fetch = FetchType.LAZY) // Store targeted asset IDs
    @CollectionTable(name = "control_eval_job_target_assets", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "asset_id")
    private List<String> targetAssetIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> evaluationParameters; // Parameters used for this specific evaluation run
    
    private boolean forceReevaluation;

    @Column(length = 255)
    private String triggeredBy;

    @Column(length = 255)
    private String notificationEmail;

    // In a more detailed model, you might have a list of ControlEvaluationResultEntity linked here.
    // For now, the results (findings) would likely be new entries in the main GeneratedReport or a similar table,
    // or a dedicated ControlFindingEntity. This part needs further thought based on how results are stored/queried.
    // For simplicity, this job entity primarily tracks the execution.
} 