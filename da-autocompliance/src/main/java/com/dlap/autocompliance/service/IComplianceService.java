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

import com.dlap.autocompliance.dto.ComplianceReportDefinitionDTO;
import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
import com.dlap.autocompliance.dto.ReportJobStatusDTO;
import com.dlap.autocompliance.dto.ComplianceReportDTO;
import com.dlap.autocompliance.dto.AssetComplianceStatusDTO;
import com.dlap.autocompliance.dto.ComplianceControlDTO;
import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.dto.ViolationDTO;
import com.dlap.autocompliance.dto.ComplianceKpisDTO;
import com.dlap.autocompliance.dto.ComplianceTrendDTO;
import com.dlap.autocompliance.dto.ComplianceHeatmapDTO;
import com.dlap.autocompliance.dto.MonitoredRegulationDTO;
import com.dlap.autocompliance.dto.ComplianceViolationDTO;
import com.dlap.autocompliance.dto.JobStatusForAdminAggregationDTO;
// Future DTOs:
// import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
// import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
// import com.dlap.autocompliance.dto.ReportJobStatusDTO;

import java.util.List;

public interface IComplianceService {

    /**
     * Lists all available compliance report definitions.
     * @return A list of ComplianceReportDefinitionDTOs.
     */
    List<ComplianceReportDefinitionDTO> listAvailableReportDefinitions();

    /**
     * Creates a new compliance report definition in the database.
     * @param reportDefinitionDto The compliance report definition data to create.
     * @return The created ComplianceReportDefinitionDTO.
     */
    ComplianceReportDefinitionDTO createComplianceReportDefinition(ComplianceReportDefinitionDTO reportDefinitionDto);

    /**
     * Updates an existing compliance report definition in the database.
     * @param reportType The report type to update.
     * @param reportDefinitionDto The updated compliance report definition data.
     * @return The updated ComplianceReportDefinitionDTO.
     */
    ComplianceReportDefinitionDTO updateComplianceReportDefinition(String reportType, ComplianceReportDefinitionDTO reportDefinitionDto);

    /**
     * Deletes a compliance report definition from the database.
     * @param reportType The report type to delete.
     */
    void deleteComplianceReportDefinition(String reportType);

    /**
     * Initiates the generation of a specific compliance report.
     * @param reportType The type of report to generate.
     * @param request DTO containing parameters for report generation.
     * @return A response DTO with the job ID and status.
     */
    ReportGenerationResponseDTO generateComplianceReport(String reportType, ReportGenerationRequestDTO request);

    /**
     * Retrieves the status of a specific compliance report generation job.
     * @param jobId The ID of the job.
     * @return A DTO with the job status details.
     */
    ReportJobStatusDTO getReportGenerationJobStatus(String jobId);

    /**
     * Lists report generation jobs for admin-service aggregation (JSON matches admin {@code JobStatusDTO}).
     */
    List<JobStatusForAdminAggregationDTO> listReportJobsForAdminAggregation();

    /**
     * Retrieves a generated compliance report by its ID.
     * @param reportId The ID of the generated report.
     * @return The ComplianceReportDTO or null if not found.
     */
    ComplianceReportDTO getGeneratedReport(String reportId);

    /**
     * Retrieves the overall compliance status for a specific asset.
     * @param assetId The ID of the asset.
     * @return AssetComplianceStatusDTO or null if asset not found or no status available.
     */
    AssetComplianceStatusDTO getAssetComplianceStatus(String assetId);

    /**
     * Lists all available (and typically enabled) compliance controls.
     * @return A list of ComplianceControlDTOs.
     */
    List<ComplianceControlDTO> listAvailableControls();

    /**
     * Creates a new compliance control in the database.
     * @param controlDto The compliance control data to create.
     * @return The created ComplianceControlDTO.
     */
    ComplianceControlDTO createComplianceControl(ComplianceControlDTO controlDto);

    /**
     * Updates an existing compliance control in the database.
     * @param controlId The ID of the control to update.
     * @param controlDto The updated compliance control data.
     * @return The updated ComplianceControlDTO.
     */
    ComplianceControlDTO updateComplianceControl(String controlId, ComplianceControlDTO controlDto);

    /**
     * Deletes a compliance control from the database.
     * @param controlId The ID of the control to delete.
     */
    void deleteComplianceControl(String controlId);

    /**
     * Initiates the evaluation of a specific compliance control.
     * @param controlId The ID of the control to evaluate.
     * @param request DTO containing parameters for the evaluation.
     * @return A response DTO with the job ID and status.
     */
    ControlEvaluationResponseDTO evaluateControl(String controlId, ControlEvaluationRequestDTO request);

    /**
     * Returns persisted status for a control evaluation job created via {@link #evaluateControl}.
     */
    ControlEvaluationResponseDTO getControlEvaluationJobStatus(String jobId);

    /**
     * Retrieves compliance KPIs for dashboard display.
     * @return ComplianceKpisDTO with key performance indicators.
     */
    ComplianceKpisDTO getComplianceKpis();

    /**
     * Retrieves compliance trends over time.
     * @return List of ComplianceTrendDTOs.
     */
    List<ComplianceTrendDTO> getComplianceTrends();

    /**
     * Retrieves compliance heatmap data by regulation and category.
     * @return List of ComplianceHeatmapDTOs.
     */
    List<ComplianceHeatmapDTO> getComplianceHeatmap();

    /**
     * Retrieves monitored regulations with their compliance status.
     * @return List of MonitoredRegulationDTOs.
     */
    List<MonitoredRegulationDTO> getMonitoredRegulations();

    /**
     * Retrieves compliance violations with details.
     * @return List of ComplianceViolationDTOs.
     */
    List<ComplianceViolationDTO> getComplianceViolations();

    /**
     * Triggers a manual compliance scan across all monitored assets.
     * @return Job ID for the scan operation.
     */
    String triggerComplianceScan();

    // Default-seed hooks (delegated to by the focused control/report facades).
    void initDefaultControls();

    void initDefaultReportDefinitions();

    // Methods to be added later:
    // ReportGenerationResponseDTO generateComplianceReport(String reportType, ReportGenerationRequestDTO request);
    // ReportJobStatusDTO getReportGenerationJobStatus(String jobId);
    // ... and others for controls and asset status
} 