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

import com.dlap.autocompliance.dto.*;
import com.dlap.autocompliance.service.IComplianceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComplianceController.class)
class ComplianceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IComplianceService complianceService;

    @Autowired
    private ObjectMapper objectMapper;

    private ComplianceReportDefinitionDTO reportDefinitionDTO;
    private ReportGenerationRequestDTO reportGenerationRequestDTO;
    private ReportGenerationResponseDTO reportGenerationResponseDTO;
    private ReportJobStatusDTO reportJobStatusDTO;
    private ComplianceReportDTO complianceReportDTO;
    private AssetComplianceStatusDTO assetComplianceStatusDTO;
    private ComplianceControlDTO complianceControlDTO;
    private ControlEvaluationRequestDTO controlEvaluationRequestDTO;
    private ControlEvaluationResponseDTO controlEvaluationResponseDTO;

    @BeforeEach
    void setUp() {
        reportDefinitionDTO = ComplianceReportDefinitionDTO.builder().reportType("test-report").displayName("Test Report").build();
        
        reportGenerationRequestDTO = ReportGenerationRequestDTO.builder().parameters(Map.of("param1", "value1")).build();
        reportGenerationResponseDTO = ReportGenerationResponseDTO.builder().jobId(UUID.randomUUID().toString()).status("ACCEPTED").build();
        
        reportJobStatusDTO = ReportJobStatusDTO.builder().jobId(UUID.randomUUID().toString()).status("COMPLETED_SUCCESS").build();
        
        complianceReportDTO = ComplianceReportDTO.builder().reportId(UUID.randomUUID().toString()).overallStatus("COMPLIANT").build();
        
        assetComplianceStatusDTO = AssetComplianceStatusDTO.builder().assetId("asset-123").overallComplianceStatus("COMPLIANT").build();
        
        complianceControlDTO = ComplianceControlDTO.builder().controlId("control-001").name("Test Control").enabled(true).build();
        
        controlEvaluationRequestDTO = ControlEvaluationRequestDTO.builder().targetAssetIds(List.of("asset-123")).build();
        controlEvaluationResponseDTO = ControlEvaluationResponseDTO.builder().jobId(UUID.randomUUID().toString()).status("ACCEPTED").build();
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void listAvailableReportDefinitions_shouldReturnOk() throws Exception {
        given(complianceService.listAvailableReportDefinitions()).willReturn(Collections.singletonList(reportDefinitionDTO));
        mockMvc.perform(get("/api/v1/compliance/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportType").value("test-report"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void generateComplianceReport_shouldReturnAccepted() throws Exception {
        given(complianceService.generateComplianceReport(anyString(), any(ReportGenerationRequestDTO.class))).willReturn(reportGenerationResponseDTO);
        mockMvc.perform(post("/api/v1/compliance/reports/test-report/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reportGenerationRequestDTO)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void getReportGenerationJobStatus_shouldReturnOk() throws Exception {
        given(complianceService.getReportGenerationJobStatus(anyString())).willReturn(reportJobStatusDTO);
        mockMvc.perform(get("/api/v1/compliance/reports/jobs/some-job-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED_SUCCESS"));
    }
    
    @Test
    @WithMockUser(roles = "AUDITOR")
    void getReportGenerationJobStatus_whenNotFound_shouldReturnNotFound() throws Exception {
        ReportJobStatusDTO notFoundDto = ReportJobStatusDTO.builder().jobId("not-found-id").status("NOT_FOUND").build();
        given(complianceService.getReportGenerationJobStatus("not-found-id")).willReturn(notFoundDto);
        mockMvc.perform(get("/api/v1/compliance/reports/jobs/not-found-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void getGeneratedReport_shouldReturnOk() throws Exception {
        given(complianceService.getGeneratedReport(anyString())).willReturn(complianceReportDTO);
        mockMvc.perform(get("/api/v1/compliance/reports/results/some-report-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("COMPLIANT"));
    }
    
    @Test
    @WithMockUser(roles = "AUDITOR")
    void getGeneratedReport_whenNotFound_shouldReturnNotFound() throws Exception {
        given(complianceService.getGeneratedReport("not-found-id")).willReturn(null);
        mockMvc.perform(get("/api/v1/compliance/reports/results/not-found-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void getAssetComplianceStatus_shouldReturnOk() throws Exception {
        given(complianceService.getAssetComplianceStatus(anyString())).willReturn(assetComplianceStatusDTO);
        mockMvc.perform(get("/api/v1/compliance/assets/asset-123/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallComplianceStatus").value("COMPLIANT"));
    }
    
    @Test
    @WithMockUser(roles = "AUDITOR")
    void getAssetComplianceStatus_whenNotFound_shouldReturnNotFound() throws Exception {
        AssetComplianceStatusDTO notFoundStatus = AssetComplianceStatusDTO.builder().assetId("asset-not-found").overallComplianceStatus("UNKNOWN").relevantReportIds(null).build();
        given(complianceService.getAssetComplianceStatus("asset-not-found")).willReturn(notFoundStatus);
        mockMvc.perform(get("/api/v1/compliance/assets/asset-not-found/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void listAvailableControls_shouldReturnOk() throws Exception {
        given(complianceService.listAvailableControls()).willReturn(Collections.singletonList(complianceControlDTO));
        mockMvc.perform(get("/api/v1/compliance/controls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].controlId").value("control-001"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void evaluateControl_shouldReturnAccepted() throws Exception {
        given(complianceService.evaluateControl(anyString(), any(ControlEvaluationRequestDTO.class))).willReturn(controlEvaluationResponseDTO);
        mockMvc.perform(post("/api/v1/compliance/controls/control-001/evaluate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(controlEvaluationRequestDTO)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void generateComplianceReport_whenRoleNotAuthorized_shouldReturnForbidden() throws Exception {
        // AUDITOR is a read-only role and is NOT in the @PreAuthorize list for generation
        // (ADMIN/POLICY_MANAGER/DIRECTOR). This asserts @PreAuthorize is genuinely enforced.
        mockMvc.perform(post("/api/v1/compliance/reports/test-report/generate").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reportGenerationRequestDTO)))
                .andExpect(status().isForbidden());
    }
} 