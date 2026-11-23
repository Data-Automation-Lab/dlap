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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
import com.dlap.autocompliance.model.entity.ComplianceReportDefinitionEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportJobEntity;
import com.dlap.autocompliance.model.mapper.ComplianceGeneratedReportMapper;
import com.dlap.autocompliance.model.mapper.ComplianceReportDefinitionMapper;
import com.dlap.autocompliance.model.mapper.ComplianceReportJobMapper;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportDefinitionRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportJobRepository;
import com.dlap.autocompliance.service.exception.ReportJobNotFoundException;

/**
 * Unit tests for the focused report-slice impl (N1 phase-2 coverage).
 */
@ExtendWith(MockitoExtension.class)
class ComplianceReportServiceImplTest {

    @Mock
    private ComplianceReportDefinitionRepository reportDefinitionRepository;
    @Mock
    private ComplianceReportJobRepository reportJobRepository;
    @Mock
    private ComplianceGeneratedReportRepository generatedReportRepository;
    @Mock
    private ComplianceReportDefinitionMapper reportDefinitionMapper;
    @Mock
    private ComplianceReportJobMapper reportJobMapper;
    @Mock
    private ComplianceGeneratedReportMapper generatedReportMapper;

    @InjectMocks
    private ComplianceReportServiceImpl reportService;

    @BeforeEach
    void wireSelf() {
        ReflectionTestUtils.setField(reportService, "self", reportService);
    }

    @Test
    void generateComplianceReport_missingRequiredParameters_failsValidationWithoutDispatch() {
        ComplianceReportDefinitionEntity definition = ComplianceReportDefinitionEntity.builder()
                .reportType("SOC2")
                .displayName("SOC2")
                .version("1")
                .generationParametersDefinition(Map.of("scope", "string"))
                .build();
        when(reportDefinitionRepository.findByReportTypeIgnoreCase("SOC2")).thenReturn(Optional.of(definition));

        ReportGenerationRequestDTO request = ReportGenerationRequestDTO.builder()
                .parameters(Map.of())
                .build();

        ReportGenerationResponseDTO resp = reportService.generateComplianceReport("SOC2", request);

        assertThat(resp.getStatus()).isEqualTo("FAILED_VALIDATION");
        assertThat(resp.getJobId()).isNull();
        verify(reportJobRepository, never()).save(any(ComplianceReportJobEntity.class));
    }

    @Test
    void getReportGenerationJobStatus_unknownJob_throws() {
        when(reportJobRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getReportGenerationJobStatus("nope"))
                .isInstanceOf(ReportJobNotFoundException.class);
    }
}
