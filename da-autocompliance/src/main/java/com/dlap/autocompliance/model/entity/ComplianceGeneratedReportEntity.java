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
@Table(name = "compliance_generated_reports")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceGeneratedReportEntity extends AbstractAuditableEntity {

    @Id
    @Column(length = 36) // UUID length
    private String reportId;

    @Column(nullable = false, length = 100)
    private String reportType; // Corresponds to ComplianceReportDefinitionEntity.reportType

    @Column(length = 255)
    private String displayName; // User-friendly name of the report type at the time of generation

    @Column(nullable = false)
    private LocalDateTime generationTimestamp;

    @Column(length = 50)
    private String overallStatus; // e.g., COMPLIANT, NON_COMPLIANT, PARTIALLY_COMPLIANT, ERROR

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> generationParameters; // Parameters used to generate this specific report instance

    // Storing summary and findings as JSONB. 
    // If specific fields within these need to be frequently queried, consider normalizing them.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ReportSummaryData summaryData; // Corresponds to ComplianceReportDTO.SummaryDTO

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<ReportFindingData> findingsData; // Corresponds to List<ComplianceReportDTO.FindingDTO>

    // Link back to the job that generated this report
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", referencedColumnName = "jobId")
    private ComplianceReportJobEntity reportJob;
} 