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

package com.dlap.autocompliance.controller;

import com.dlap.autocompliance.dto.ComplianceReportDefinitionDTO;
import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
import com.dlap.autocompliance.dto.ReportJobStatusDTO;
import com.dlap.autocompliance.dto.ComplianceReportDTO;
import com.dlap.autocompliance.dto.AssetComplianceStatusDTO;
import com.dlap.autocompliance.dto.ComplianceControlDTO;
import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.dto.ComplianceKpisDTO;
import com.dlap.autocompliance.dto.ComplianceTrendDTO;
import com.dlap.autocompliance.dto.ComplianceHeatmapDTO;
import com.dlap.autocompliance.dto.MonitoredRegulationDTO;
import com.dlap.autocompliance.dto.ComplianceViolationDTO;
import com.dlap.autocompliance.dto.JobStatusForAdminAggregationDTO;
import com.dlap.autocompliance.service.IComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/compliance")
@Tag(name = "Compliance API", description = "APIs for managing compliance checks, reports, and evaluations")
@RequiredArgsConstructor
public class ComplianceController {

    private final IComplianceService complianceService;

    @Operation(summary = "List available compliance report definitions",
               description = "Provides a list of all configured compliance report types that can be generated.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved report definitions")
               })
    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')") // Broad access to see what reports are available
    public ResponseEntity<List<ComplianceReportDefinitionDTO>> listAvailableReportDefinitions() {
        List<ComplianceReportDefinitionDTO> definitions = complianceService.listAvailableReportDefinitions();
        return ResponseEntity.ok(definitions);
    }

    @Operation(summary = "Create a new compliance report definition",
               description = "Creates a new compliance report definition that can be used for report generation. Report definitions should be added through this API instead of being hardcoded.",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Compliance report definition created successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid request data"),
                   @ApiResponse(responseCode = "409", description = "Report type already exists")
               })
    @PostMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Only admins and data stewards can create report definitions
    public ResponseEntity<ComplianceReportDefinitionDTO> createComplianceReportDefinition(
            @Parameter(description = "Compliance report definition data", required = true)
            @Valid @RequestBody ComplianceReportDefinitionDTO reportDefinitionDto) {
        ComplianceReportDefinitionDTO createdDefinition = complianceService.createComplianceReportDefinition(reportDefinitionDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdDefinition);
    }

    @Operation(summary = "Update an existing compliance report definition",
               description = "Updates an existing compliance report definition.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Compliance report definition updated successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid request data"),
                   @ApiResponse(responseCode = "404", description = "Report definition not found")
               })
    @PutMapping("/reports/{reportType}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Only admins and data stewards can update report definitions
    public ResponseEntity<ComplianceReportDefinitionDTO> updateComplianceReportDefinition(
            @Parameter(description = "Report type to update", required = true) 
            @PathVariable String reportType,
            @Parameter(description = "Updated compliance report definition data", required = true)
            @Valid @RequestBody ComplianceReportDefinitionDTO reportDefinitionDto) {
        ComplianceReportDefinitionDTO updatedDefinition = complianceService.updateComplianceReportDefinition(reportType, reportDefinitionDto);
        return ResponseEntity.ok(updatedDefinition);
    }

    @Operation(summary = "Delete a compliance report definition",
               description = "Deletes a compliance report definition. This will prevent new reports of this type from being generated.",
               responses = {
                   @ApiResponse(responseCode = "204", description = "Compliance report definition deleted successfully"),
                   @ApiResponse(responseCode = "404", description = "Report definition not found")
               })
    @DeleteMapping("/reports/{reportType}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Only admins and data stewards can delete report definitions
    public ResponseEntity<Void> deleteComplianceReportDefinition(
            @Parameter(description = "Report type to delete", required = true) 
            @PathVariable String reportType) {
        complianceService.deleteComplianceReportDefinition(reportType);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Generate a compliance report",
               description = "Triggers the asynchronous generation of a specific compliance report based on its type and provided parameters.",
               responses = {
                   @ApiResponse(responseCode = "202", description = "Report generation request accepted."),
                   @ApiResponse(responseCode = "400", description = "Invalid request (e.g., missing parameters, report type not found)"),
                   @ApiResponse(responseCode = "404", description = "Report type not found") // Covered by 400 in current impl
               })
    @PostMapping("/reports/{reportType}/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Users who can trigger generation
    public ResponseEntity<ReportGenerationResponseDTO> generateComplianceReport(
            @Parameter(description = "Unique identifier of the report type to generate", required = true) 
            @PathVariable String reportType,
            @Parameter(description = "Parameters required for the report generation", required = true)
            @Valid @RequestBody ReportGenerationRequestDTO request) {
        
        ReportGenerationResponseDTO response = complianceService.generateComplianceReport(reportType, request);
        
        if ("FAILED_VALIDATION".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "List compliance report jobs for admin aggregation",
               description = "Returns jobs in the same JSON shape as da-admin-service JobStatusDTO for aggregated dashboards.")
    @GetMapping("/reports/jobs")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR', 'SERVICE_ACCOUNT')") // + M2M service account (da-admin)
    public ResponseEntity<List<JobStatusForAdminAggregationDTO>> listReportJobsForAdminAggregation() {
        return ResponseEntity.ok(complianceService.listReportJobsForAdminAggregation());
    }

    @Operation(summary = "Get compliance report generation job status",
               description = "Retrieves the current status of an asynchronous report generation job.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved job status."),
                   @ApiResponse(responseCode = "404", description = "Job ID not found.")
               })
    @GetMapping("/reports/jobs/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')") // Users who can view job status
    public ResponseEntity<ReportJobStatusDTO> getReportGenerationJobStatus(
            @Parameter(description = "ID of the report generation job", required = true) 
            @PathVariable String jobId) {
        ReportJobStatusDTO jobStatus = complianceService.getReportGenerationJobStatus(jobId);
        if ("NOT_FOUND".equals(jobStatus.getStatus())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(jobStatus);
        }
        return ResponseEntity.ok(jobStatus);
    }

    @Operation(summary = "Retrieve a generated compliance report",
               description = "Fetches the detailed contents of a previously generated compliance report.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved the report."),
                   @ApiResponse(responseCode = "404", description = "Report ID not found.")
               })
    @GetMapping("/reports/results/{reportId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')") // Users who can view reports
    public ResponseEntity<ComplianceReportDTO> getGeneratedReport(
            @Parameter(description = "ID of the generated compliance report", required = true) 
            @PathVariable String reportId) {
        ComplianceReportDTO report = complianceService.getGeneratedReport(reportId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @Operation(summary = "Get compliance status for an asset",
               description = "Retrieves the overall compliance status and recent findings for a specific asset.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved asset compliance status."),
                   @ApiResponse(responseCode = "404", description = "Asset ID not found or no compliance data available.")
               })
    @GetMapping("/assets/{assetId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<AssetComplianceStatusDTO> getAssetComplianceStatus(
            @Parameter(description = "ID of the asset (e.g., cloud resource ID)", required = true) 
            @PathVariable String assetId) {
        AssetComplianceStatusDTO status = complianceService.getAssetComplianceStatus(assetId);
        if ("UNKNOWN".equals(status.getOverallComplianceStatus()) && status.getRelevantReportIds() == null) { // Basic check for 'not found'
             return ResponseEntity.status(HttpStatus.NOT_FOUND).body(status); // Provide status with UNKNOWN if created that way
        }
        return ResponseEntity.ok(status);
    }

    @Operation(summary = "List available compliance controls",
               description = "Provides a list of all configured and typically enabled compliance controls that can be evaluated.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved compliance controls")
               })
    @GetMapping("/controls")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')") // Broad access to see available controls
    public ResponseEntity<List<ComplianceControlDTO>> listAvailableControls() {
        List<ComplianceControlDTO> controls = complianceService.listAvailableControls();
        return ResponseEntity.ok(controls);
    }

    @Operation(summary = "Create a new compliance control",
               description = "Creates a new compliance control that can be evaluated. Controls should be added through this API instead of being hardcoded.",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Compliance control created successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid request data"),
                   @ApiResponse(responseCode = "409", description = "Control ID already exists")
               })
    @PostMapping("/controls")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Only admins and data stewards can create controls
    public ResponseEntity<ComplianceControlDTO> createComplianceControl(
            @Parameter(description = "Compliance control data", required = true)
            @Valid @RequestBody ComplianceControlDTO controlDto) {
        ComplianceControlDTO createdControl = complianceService.createComplianceControl(controlDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdControl);
    }

    @Operation(summary = "Update an existing compliance control",
               description = "Updates an existing compliance control. Only enabled controls can be updated.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Compliance control updated successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid request data"),
                   @ApiResponse(responseCode = "404", description = "Control not found")
               })
    @PutMapping("/controls/{controlId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Only admins and data stewards can update controls
    public ResponseEntity<ComplianceControlDTO> updateComplianceControl(
            @Parameter(description = "Control ID to update", required = true) 
            @PathVariable String controlId,
            @Parameter(description = "Updated compliance control data", required = true)
            @Valid @RequestBody ComplianceControlDTO controlDto) {
        ComplianceControlDTO updatedControl = complianceService.updateComplianceControl(controlId, controlDto);
        return ResponseEntity.ok(updatedControl);
    }

    @Operation(summary = "Delete a compliance control",
               description = "Deletes a compliance control. This will disable the control from being evaluated.",
               responses = {
                   @ApiResponse(responseCode = "204", description = "Compliance control deleted successfully"),
                   @ApiResponse(responseCode = "404", description = "Control not found")
               })
    @DeleteMapping("/controls/{controlId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Only admins and data stewards can delete controls
    public ResponseEntity<Void> deleteComplianceControl(
            @Parameter(description = "Control ID to delete", required = true) 
            @PathVariable String controlId) {
        complianceService.deleteComplianceControl(controlId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Evaluate a compliance control",
               description = "Triggers the asynchronous evaluation of a specific compliance control.",
               responses = {
                   @ApiResponse(responseCode = "202", description = "Control evaluation request accepted."),
                   @ApiResponse(responseCode = "400", description = "Invalid request (e.g., missing parameters, control not enabled)"),
                   @ApiResponse(responseCode = "404", description = "Control ID not found")
               })
    @PostMapping("/controls/{controlId}/evaluate")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')") // Users who can trigger evaluations
    public ResponseEntity<ControlEvaluationResponseDTO> evaluateControl(
            @Parameter(description = "Unique identifier of the control to evaluate", required = true) 
            @PathVariable String controlId,
            @Parameter(description = "Parameters for the control evaluation", required = true)
            @Valid @RequestBody ControlEvaluationRequestDTO request) {
        
        ControlEvaluationResponseDTO response = complianceService.evaluateControl(controlId, request);
        
        if ("FAILED_VALIDATION".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        // Assuming ControlNotFoundException is handled by a global exception handler to return 404
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Get control evaluation job status",
               description = "Returns status for a job created by POST /controls/{controlId}/evaluate.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Job found"),
                   @ApiResponse(responseCode = "404", description = "Job not found")
               })
    @GetMapping("/controls/evaluations/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<ControlEvaluationResponseDTO> getControlEvaluationJobStatus(
            @Parameter(description = "Evaluation job ID", required = true) @PathVariable String jobId) {
        return ResponseEntity.ok(complianceService.getControlEvaluationJobStatus(jobId));
    }

    // New endpoints for Compliance Agent UI
    @Operation(summary = "Get compliance KPIs for dashboard",
               description = "Retrieves key performance indicators for compliance monitoring dashboard.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved compliance KPIs")
               })
    @GetMapping("/kpis")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<ComplianceKpisDTO> getComplianceKpis() {
        ComplianceKpisDTO kpis = complianceService.getComplianceKpis();
        return ResponseEntity.ok(kpis);
    }

    @Operation(summary = "Get compliance trends",
               description = "Retrieves compliance score trends over time for different regulations.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved compliance trends")
               })
    @GetMapping("/trends")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<List<ComplianceTrendDTO>> getComplianceTrends() {
        List<ComplianceTrendDTO> trends = complianceService.getComplianceTrends();
        return ResponseEntity.ok(trends);
    }

    @Operation(summary = "Get compliance heatmap data",
               description = "Retrieves compliance heatmap data by regulation and category.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved compliance heatmap data")
               })
    @GetMapping("/heatmap")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<List<ComplianceHeatmapDTO>> getComplianceHeatmap() {
        List<ComplianceHeatmapDTO> heatmap = complianceService.getComplianceHeatmap();
        return ResponseEntity.ok(heatmap);
    }

    @Operation(summary = "Get monitored regulations",
               description = "Retrieves list of monitored regulations with their compliance status.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved monitored regulations")
               })
    @GetMapping("/regulations")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<List<MonitoredRegulationDTO>> getMonitoredRegulations() {
        List<MonitoredRegulationDTO> regulations = complianceService.getMonitoredRegulations();
        return ResponseEntity.ok(regulations);
    }

    @Operation(summary = "Get compliance violations",
               description = "Retrieves list of compliance violations with details.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved compliance violations")
               })
    @GetMapping("/violations")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST', 'DATA_ANALYST', 'AUDITOR')")
    public ResponseEntity<List<ComplianceViolationDTO>> getComplianceViolations() {
        List<ComplianceViolationDTO> violations = complianceService.getComplianceViolations();
        return ResponseEntity.ok(violations);
    }

    @Operation(summary = "Trigger manual compliance scan",
               description = "Triggers a manual compliance scan across all monitored assets.",
               responses = {
                   @ApiResponse(responseCode = "202", description = "Compliance scan triggered successfully")
               })
    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'POLICY_MANAGER', 'DIRECTOR')")
    public ResponseEntity<Map<String, String>> triggerComplianceScan() {
        String jobId = complianceService.triggerComplianceScan();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "SCAN_TRIGGERED", "jobId", jobId));
    }

} 