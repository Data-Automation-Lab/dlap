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

package com.dlap.autocompliance.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.autocompliance.dto.AssetComplianceStatusDTO;
import com.dlap.autocompliance.dto.ComplianceControlDTO;
import com.dlap.autocompliance.dto.ComplianceHeatmapDTO;
import com.dlap.autocompliance.dto.ComplianceKpisDTO;
import com.dlap.autocompliance.dto.ComplianceReportDTO;
import com.dlap.autocompliance.dto.ComplianceReportDefinitionDTO;
import com.dlap.autocompliance.dto.ComplianceTrendDTO;
import com.dlap.autocompliance.dto.ComplianceViolationDTO;
import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.dto.JobStatusForAdminAggregationDTO;
import com.dlap.autocompliance.dto.MonitoredRegulationDTO;
import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
import com.dlap.autocompliance.dto.ReportJobStatusDTO;
import com.dlap.autocompliance.service.IComplianceControlService;
import com.dlap.autocompliance.service.IComplianceKpiService;
import com.dlap.autocompliance.service.IComplianceReportService;
import com.dlap.autocompliance.service.IComplianceScanService;
import com.dlap.autocompliance.service.IComplianceService;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure facade for the autocompliance service surface.
 *
 * <p>R-P1-12 / ADR-0024 phase-2 (v0.2.0 N1) complete: every method
 * delegates to one of the four focused slice impls:
 * {@link ComplianceKpiServiceImpl} (dashboard reads),
 * {@link ComplianceControlServiceImpl} (control CRUD + evaluation),
 * {@link ComplianceReportServiceImpl} (definitions + generation pipeline),
 * {@link ComplianceScanServiceImpl} (violations, asset status, manual scan).
 *
 * <p>This class must stay logic-free: no repository or mapper fields, no
 * helper methods, delegation only. An ArchUnit test
 * ({@code ComplianceFacadeArchTest}) fails the build if business logic
 * creeps back in. Controllers and cross-service callers keep using
 * {@link IComplianceService}; new code should prefer the focused
 * interfaces directly.
 */
@Service("complianceService")
@Slf4j
public class ComplianceServiceImpl implements IComplianceService {

    @Autowired
    @Lazy
    private IComplianceKpiService kpiService;

    @Autowired
    @Lazy
    private IComplianceControlService controlService;

    @Autowired
    @Lazy
    private IComplianceReportService reportService;

    @Autowired
    @Lazy
    private IComplianceScanService scanService;

    @PostConstruct
    @Transactional
    public void initDefaultData() {
        initDefaultReportDefinitions();
        initDefaultControls();
    }

    @Override
    public void initDefaultReportDefinitions() {
        reportService.initDefaultReportDefinitions();
    }

    @Override
    public void initDefaultControls() {
        controlService.initDefaultControls();
    }

    // ---- Report slice -> ComplianceReportServiceImpl ----

    @Override
    public List<ComplianceReportDefinitionDTO> listAvailableReportDefinitions() {
        return reportService.listAvailableReportDefinitions();
    }

    @Override
    public ComplianceReportDefinitionDTO createComplianceReportDefinition(ComplianceReportDefinitionDTO reportDefinitionDto) {
        return reportService.createComplianceReportDefinition(reportDefinitionDto);
    }

    @Override
    public ComplianceReportDefinitionDTO updateComplianceReportDefinition(String reportType, ComplianceReportDefinitionDTO reportDefinitionDto) {
        return reportService.updateComplianceReportDefinition(reportType, reportDefinitionDto);
    }

    @Override
    public void deleteComplianceReportDefinition(String reportType) {
        reportService.deleteComplianceReportDefinition(reportType);
    }

    @Override
    public ReportGenerationResponseDTO generateComplianceReport(String reportType, ReportGenerationRequestDTO request) {
        return reportService.generateComplianceReport(reportType, request);
    }

    @Override
    public ReportJobStatusDTO getReportGenerationJobStatus(String jobId) {
        return reportService.getReportGenerationJobStatus(jobId);
    }

    @Override
    public List<JobStatusForAdminAggregationDTO> listReportJobsForAdminAggregation() {
        return reportService.listReportJobsForAdminAggregation();
    }

    @Override
    public ComplianceReportDTO getGeneratedReport(String reportId) {
        return reportService.getGeneratedReport(reportId);
    }

    // ---- Scan slice -> ComplianceScanServiceImpl ----

    @Override
    public AssetComplianceStatusDTO getAssetComplianceStatus(String assetId) {
        return scanService.getAssetComplianceStatus(assetId);
    }

    @Override
    public List<ComplianceViolationDTO> getComplianceViolations() {
        return scanService.getComplianceViolations();
    }

    @Override
    public String triggerComplianceScan() {
        return scanService.triggerComplianceScan();
    }

    // ---- Control slice -> ComplianceControlServiceImpl ----

    @Override
    public List<ComplianceControlDTO> listAvailableControls() {
        return controlService.listAvailableControls();
    }

    @Override
    public ComplianceControlDTO createComplianceControl(ComplianceControlDTO controlDto) {
        return controlService.createComplianceControl(controlDto);
    }

    @Override
    public ComplianceControlDTO updateComplianceControl(String controlId, ComplianceControlDTO controlDto) {
        return controlService.updateComplianceControl(controlId, controlDto);
    }

    @Override
    public void deleteComplianceControl(String controlId) {
        controlService.deleteComplianceControl(controlId);
    }

    @Override
    public ControlEvaluationResponseDTO evaluateControl(String controlId, ControlEvaluationRequestDTO request) {
        return controlService.evaluateControl(controlId, request);
    }

    @Override
    public ControlEvaluationResponseDTO getControlEvaluationJobStatus(String jobId) {
        return controlService.getControlEvaluationJobStatus(jobId);
    }

    // ---- KPI slice -> ComplianceKpiServiceImpl ----

    @Override
    public ComplianceKpisDTO getComplianceKpis() {
        return kpiService.getComplianceKpis();
    }

    @Override
    public List<ComplianceTrendDTO> getComplianceTrends() {
        return kpiService.getComplianceTrends();
    }

    @Override
    public List<ComplianceHeatmapDTO> getComplianceHeatmap() {
        return kpiService.getComplianceHeatmap();
    }

    @Override
    public List<MonitoredRegulationDTO> getMonitoredRegulations() {
        return kpiService.getMonitoredRegulations();
    }
}
