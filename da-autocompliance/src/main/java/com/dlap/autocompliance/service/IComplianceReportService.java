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

package com.dlap.autocompliance.service;

import com.dlap.autocompliance.dto.ComplianceReportDTO;
import com.dlap.autocompliance.dto.ComplianceReportDefinitionDTO;
import com.dlap.autocompliance.dto.JobStatusForAdminAggregationDTO;
import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
import com.dlap.autocompliance.dto.ReportJobStatusDTO;
import java.util.List;

/**
 * Report definition + report-generation surface of the compliance
 * service (R-P1-12 / ADR-0020 split). The largest of the four split
 * services - owns the async {@code processReportGenerationAsync}
 * pipeline and the {@code @Lazy self} proxy that lets the async
 * method actually fire through Spring's proxy.
 */
public interface IComplianceReportService {

    List<ComplianceReportDefinitionDTO> listAvailableReportDefinitions();

    ComplianceReportDefinitionDTO createComplianceReportDefinition(ComplianceReportDefinitionDTO reportDefinitionDto);

    ComplianceReportDefinitionDTO updateComplianceReportDefinition(String reportType, ComplianceReportDefinitionDTO reportDefinitionDto);

    void deleteComplianceReportDefinition(String reportType);

    ReportGenerationResponseDTO generateComplianceReport(String reportType, ReportGenerationRequestDTO request);

    ReportJobStatusDTO getReportGenerationJobStatus(String jobId);

    List<JobStatusForAdminAggregationDTO> listReportJobsForAdminAggregation();

    ComplianceReportDTO getGeneratedReport(String reportId);

    /** Idempotent report-definition bootstrap. */
    void initDefaultReportDefinitions();
}
