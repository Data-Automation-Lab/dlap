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

package com.dlap.reporting.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
// import org.springframework.security.core.annotation.AuthenticationPrincipal; 
// import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.common.security.SecurityUtils;
import com.dlap.reporting.dto.GeneratedReportOutputDTO;
import com.dlap.reporting.dto.ReportDefinitionDTO;
import com.dlap.reporting.dto.ReportGenerationRequestDTO;
import com.dlap.reporting.service.IReportService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/reporting")
@RequiredArgsConstructor
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    /**
     * Read access to report definitions, generated reports and exports: all nine
     * human roles of the Access Model (docs/specs/access-model.md, "Reports &
     * dashboards": every tier may view). A token with no DLAP role, and the
     * machine principal ROLE_SERVICE_ACCOUNT (no M2M caller reaches reporting),
     * are denied.
     */
    static final String READ_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', "
            + "'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', "
            + "'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_VIEWER')";

    /**
     * Write access to report definitions and report generation: the Executive tier
     * only. The Access Model grants Leadership, Practitioner and Reader tiers a
     * read-only view of reports, and conformance rule (b) requires every mutating
     * endpoint to deny the Reader tier.
     */
    static final String MANAGE_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO')";

    private final IReportService reportService;

    // --- Report Definitions --- 

    @PostMapping("/definitions")
    @PreAuthorize(MANAGE_ACCESS)
    public ResponseEntity<ReportDefinitionDTO> createReportDefinition(@Valid @RequestBody ReportDefinitionDTO reportDefinitionDTO) {
        return new ResponseEntity<>(reportService.createReportDefinition(reportDefinitionDTO), HttpStatus.CREATED);
    }

    @GetMapping("/definitions/{id}")
    @PreAuthorize(READ_ACCESS)
    public ResponseEntity<ReportDefinitionDTO> getReportDefinitionById(@PathVariable UUID id) {
        return ResponseEntity.ok(reportService.getReportDefinitionById(id));
    }

    @GetMapping("/definitions/key/{reportKey}")
    @PreAuthorize(READ_ACCESS)
    public ResponseEntity<ReportDefinitionDTO> getReportDefinitionByKey(@PathVariable String reportKey) {
        return ResponseEntity.ok(reportService.getReportDefinitionByKey(reportKey));
    }

    @GetMapping("/definitions")
    @PreAuthorize(READ_ACCESS)
    public ResponseEntity<Page<ReportDefinitionDTO>> getAllReportDefinitions(Pageable pageable) {
        return ResponseEntity.ok(reportService.getAllReportDefinitions(pageable));
    }

    @PutMapping("/definitions/{id}")
    @PreAuthorize(MANAGE_ACCESS)
    public ResponseEntity<ReportDefinitionDTO> updateReportDefinition(@PathVariable UUID id, @Valid @RequestBody ReportDefinitionDTO reportDefinitionDTO) {
        return ResponseEntity.ok(reportService.updateReportDefinition(id, reportDefinitionDTO));
    }

    @DeleteMapping("/definitions/{id}")
    @PreAuthorize(MANAGE_ACCESS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReportDefinition(@PathVariable UUID id) {
        reportService.deleteReportDefinition(id);
    }

    // --- Report Generation & Retrieval --- 

    @PostMapping("/key/{reportKey}/generate")
    @PreAuthorize(MANAGE_ACCESS)
    public ResponseEntity<GeneratedReportOutputDTO> generateReport(
            @PathVariable String reportKey,
            @RequestBody(required = false) ReportGenerationRequestDTO requestDTO) {
        log.info("REST request to generate report: {}", reportKey);
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        GeneratedReportOutputDTO generatedReport = reportService.generateReport(reportKey, requestDTO, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(generatedReport);
    }

    @PostMapping("/id/{id}/generate")
    @PreAuthorize(MANAGE_ACCESS)
    public ResponseEntity<GeneratedReportOutputDTO> regenerateReport(@PathVariable UUID id) {
        log.info("REST request to regenerate report: {}", id);
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        GeneratedReportOutputDTO generatedReport = reportService.regenerateReport(id, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(generatedReport);
    }

    @GetMapping("/reports/jobs/{generatedReportId}")
    @PreAuthorize(READ_ACCESS)
    public ResponseEntity<GeneratedReportOutputDTO> getGeneratedReportStatus(@PathVariable UUID generatedReportId) {
        // This DTO contains status, content (if ready), etc.
        return ResponseEntity.ok(reportService.getGeneratedReportById(generatedReportId));
    }
    
    @GetMapping("/reports/key/{reportKey}")
    @PreAuthorize(READ_ACCESS)
    public ResponseEntity<Page<GeneratedReportOutputDTO>> getGeneratedReportsByReportKey(@PathVariable String reportKey, Pageable pageable) {
        return ResponseEntity.ok(reportService.getGeneratedReportsByReportKey(reportKey, pageable));
    }

    @GetMapping("/reports")
    @PreAuthorize(READ_ACCESS)
    public ResponseEntity<Page<GeneratedReportOutputDTO>> getAllGeneratedReports(Pageable pageable) {
        return ResponseEntity.ok(reportService.getAllGeneratedReports(pageable));
    }
} 