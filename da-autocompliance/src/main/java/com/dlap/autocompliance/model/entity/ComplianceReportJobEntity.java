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

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "compliance_report_jobs")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceReportJobEntity extends AbstractAuditableEntity {

    @Id
    @Column(length = 36) // UUID length
    private String jobId;

    @Column(nullable = false, length = 100)
    private String reportType; // Corresponds to ComplianceReportDefinitionEntity.reportType

    @Column(nullable = false, length = 50)
    private String status; // e.g., QUEUED, PROCESSING, COMPLETED_SUCCESS, COMPLETED_FAILURE

    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Lob
    // Map to text (not oid) deterministically so the Flyway V4 DDL matches ddl-auto=validate.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
    @Basic(fetch = FetchType.LAZY) // Message could be long, fetch lazily
    private String message;

    @Column(length = 36) // UUID length, if report is generated
    private String generatedReportId; // Links to ComplianceGeneratedReportEntity.reportId

    // Parameters used for this specific job instance.
    // In DTO this was Map<String, Object>. For DB, JSON is suitable.
    // Consider if complex objects need to be queryable. If so, separate columns or normalized tables might be better.
    // For now, storing as JSONB is a flexible approach.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> generationParameters;

    @Column(length = 255)
    private String triggeredBy; // User or system that triggered it

    @Column(length = 255)
    private String notificationEmail; // Email for notification
} 