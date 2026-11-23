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
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autocompliance.client.CatalogAssetClient;
import com.dlap.autocompliance.dto.AssetComplianceStatusDTO;
import com.dlap.autocompliance.dto.ComplianceViolationDTO;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportJobRepository;

/**
 * Unit tests for the focused scan-slice impl (N1 phase-2 coverage).
 */
@ExtendWith(MockitoExtension.class)
class ComplianceScanServiceImplTest {

    @Mock
    private ComplianceGeneratedReportRepository generatedReportRepository;
    @Mock
    private ComplianceReportJobRepository reportJobRepository;
    @Mock
    private ComplianceControlRepository controlRepository;
    @Mock
    private CatalogAssetClient catalogAssetClient;
    @Mock
    private AssetComplianceEvaluator evaluator;

    @InjectMocks
    private ComplianceScanServiceImpl scanService;

    @BeforeEach
    void wireSelf() {
        ReflectionTestUtils.setField(scanService, "self", scanService);
    }

    @Test
    void getComplianceViolations_mapsNonCompliantFindingsWithStableIds() {
        ReportFindingData nonCompliant = ReportFindingData.builder()
                .checkId("C-1")
                .assetId("asset-1")
                .status("NON_COMPLIANT")
                .description("Bucket public")
                .severity("HIGH")
                .build();
        ReportFindingData compliant = ReportFindingData.builder()
                .checkId("C-2")
                .assetId("asset-2")
                .status("COMPLIANT")
                .build();
        ComplianceGeneratedReportEntity report = ComplianceGeneratedReportEntity.builder()
                .reportId("rep-1")
                .reportType("SOC2")
                .generationTimestamp(LocalDateTime.now())
                .findingsData(List.of(nonCompliant, compliant))
                .build();
        when(generatedReportRepository.findAll()).thenReturn(List.of(report));

        List<ComplianceViolationDTO> violations = scanService.getComplianceViolations();

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getControlId()).isEqualTo("C-1");
        assertThat(violations.get(0).getRegulation()).isEqualTo("SOC2");
        // SHA-256 hex of the stable key - deterministic across calls
        assertThat(violations.get(0).getId()).hasSize(64);
        assertThat(scanService.getComplianceViolations().get(0).getId())
                .isEqualTo(violations.get(0).getId());
    }

    @Test
    void getAssetComplianceStatus_noReports_returnsUnknown() {
        when(generatedReportRepository.findReportsContainingAssetId("ghost")).thenReturn(List.of());

        AssetComplianceStatusDTO status = scanService.getAssetComplianceStatus("ghost");

        assertThat(status.getOverallComplianceStatus()).isEqualTo("UNKNOWN");
        assertThat(status.getRelevantReportIds()).isNull();
    }

    @Test
    void getAssetComplianceStatus_nonCompliantFinding_flagsAsset() {
        ReportFindingData finding = ReportFindingData.builder()
                .checkId("C-9")
                .assetId("asset-9")
                .status("NON_COMPLIANT")
                .severity("HIGH")
                .build();
        ComplianceGeneratedReportEntity report = ComplianceGeneratedReportEntity.builder()
                .reportId("rep-9")
                .reportType("GDPR")
                .generationTimestamp(LocalDateTime.now())
                .findingsData(List.of(finding))
                .build();
        when(generatedReportRepository.findReportsContainingAssetId("asset-9")).thenReturn(List.of(report));

        AssetComplianceStatusDTO status = scanService.getAssetComplianceStatus("asset-9");

        assertThat(status.getOverallComplianceStatus()).isEqualTo("NON_COMPLIANT");
        assertThat(status.getNonCompliantChecks()).isEqualTo(1);
        assertThat(status.getRecentNonCompliantFindings()).hasSize(1);
    }
}
