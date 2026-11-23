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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autocompliance.client.CatalogAssetClient;
import com.dlap.autocompliance.client.dto.CatalogAssetDTO;
import com.dlap.autocompliance.client.dto.CatalogAssetPageDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportDefinitionEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportJobEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.mapper.ComplianceGeneratedReportMapper;
import com.dlap.autocompliance.model.mapper.ComplianceReportDefinitionMapper;
import com.dlap.autocompliance.model.mapper.ComplianceReportJobMapper;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportDefinitionRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportJobRepository;

/**
 * UI-01: a generated compliance report must be computed from the controls evaluated against the
 * catalog as it is now. It must never be assembled from the findings of earlier reports.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplianceReportGenerationTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final String REPORT_TYPE = "gdpr-art30";
    private static final String JOB_ID = "job-1";

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
    @Mock
    private ComplianceControlRepository controlRepository;
    @Mock
    private CatalogAssetClient catalogAssetClient;

    private ComplianceReportServiceImpl service;
    private ComplianceReportJobEntity job;

    @BeforeEach
    void setUp() {
        service = new ComplianceReportServiceImpl(
                reportDefinitionRepository, reportJobRepository, generatedReportRepository,
                reportDefinitionMapper, reportJobMapper, generatedReportMapper,
                controlRepository, catalogAssetClient, new AssetComplianceEvaluator());
        ReflectionTestUtils.setField(service, "self", service);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        job = ComplianceReportJobEntity.builder()
                .jobId(JOB_ID)
                .reportType(REPORT_TYPE)
                .status("QUEUED")
                .generationParameters(Map.of("scope", "all"))
                .build();
        when(reportJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(reportDefinitionRepository.findByReportTypeIgnoreCase(REPORT_TYPE)).thenReturn(Optional.of(definition(
                List.of("GDPR"), null)));
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static ComplianceReportDefinitionEntity definition(List<String> frameworks, List<String> assetTypes) {
        return ComplianceReportDefinitionEntity.builder()
                .reportType(REPORT_TYPE)
                .displayName("GDPR Article 30")
                .applicableComplianceFrameworks(frameworks)
                .targetAssetTypes(assetTypes)
                .build();
    }

    private static ComplianceControlEntity control(String id, List<String> frameworks) {
        return ComplianceControlEntity.builder()
                .controlId(id)
                .name(id)
                .severity("HIGH")
                .applicableFrameworks(frameworks)
                .enabled(true)
                .build();
    }

    private static CatalogAssetDTO asset(String id, String type, String region, List<String> labels) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(id);
        a.setAssetName(id);
        a.setAssetType(type);
        a.setRegion(region);
        a.setLabels(labels);
        return a;
    }

    private static CatalogAssetPageDTO page(List<CatalogAssetDTO> content, int totalPages, boolean last) {
        CatalogAssetPageDTO p = new CatalogAssetPageDTO();
        p.setContent(content);
        p.setTotalPages(totalPages);
        p.setTotalElements(content.size());
        p.setLast(last);
        return p;
    }

    private ComplianceGeneratedReportEntity savedReport() {
        ArgumentCaptor<ComplianceGeneratedReportEntity> captor =
                ArgumentCaptor.forClass(ComplianceGeneratedReportEntity.class);
        verify(generatedReportRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---- tests -------------------------------------------------------------------------------

    @Test
    void report_isComputedFromTheCurrentCatalog_andNeverReadsEarlierReports() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR", "HIPAA")),
                control("GDPR-RESIDENCY-001", List.of("GDPR")),
                control("PII-ENCRYPTION-001", List.of("PCI-DSS"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("a-public", "GCS_BUCKET", null, List.of("PUBLIC")),
                asset("b-eu", "GCS_BUCKET", "eu-west-1", List.of()),
                asset("c-us", "S3_BUCKET", "us-east-1", List.of())), 1, true));

        service.processReportGenerationAsync(JOB_ID);

        ComplianceGeneratedReportEntity report = savedReport();
        // IAM-DRIFT-001 over three assets, GDPR-RESIDENCY-001 over the two that carry a region.
        // PII-ENCRYPTION-001 belongs to PCI-DSS only, so a GDPR report excludes it.
        assertThat(report.getFindingsData()).extracting(ReportFindingData::getCheckId)
                .containsOnly("IAM-DRIFT-001", "GDPR-RESIDENCY-001");
        assertThat(report.getFindingsData()).hasSize(5);
        assertThat(report.getFindingsData()).filteredOn(f -> "NON_COMPLIANT".equals(f.getStatus()))
                .extracting(f -> f.getCheckId() + ":" + f.getAssetId())
                .containsExactlyInAnyOrder("IAM-DRIFT-001:a-public", "GDPR-RESIDENCY-001:c-us");
        assertThat(report.getOverallStatus()).isEqualTo("PARTIALLY_COMPLIANT");
        assertThat(report.getGenerationTimestamp()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(report.getGenerationParameters()).containsEntry("scope", "all");

        assertThat(job.getStatus()).isEqualTo("COMPLETED_SUCCESS");
        assertThat(job.getGeneratedReportId()).isEqualTo(report.getReportId());
        assertThat(job.getMessage()).contains("2 control(s) evaluated against 3 asset(s)")
                .contains("5 finding(s) (3 compliant, 2 non-compliant)");
        // The old implementation concatenated these; they must not be consulted at all.
        verify(generatedReportRepository, never()).findByReportTypeIgnoreCase(any(), any());
        verify(generatedReportRepository, never()).findAll();
    }

    @Test
    void report_ofACompliantCatalog_isCompliant() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("a", "GCS_BUCKET", null, List.of()),
                asset("b", "GCS_BUCKET", null, List.of())), 1, true));

        service.processReportGenerationAsync(JOB_ID);

        ComplianceGeneratedReportEntity report = savedReport();
        assertThat(report.getOverallStatus()).isEqualTo("COMPLIANT");
        assertThat(report.getFindingsData()).hasSize(2)
                .allSatisfy(f -> assertThat(f.getStatus()).isEqualTo("COMPLIANT"));
    }

    @Test
    void report_whereEveryFindingFails_isNonCompliant() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("a", "GCS_BUCKET", null, List.of("PUBLIC"))), 1, true));

        service.processReportGenerationAsync(JOB_ID);

        assertThat(savedReport().getOverallStatus()).isEqualTo("NON_COMPLIANT");
    }

    @Test
    void report_withNoControlForItsFrameworks_isNoControls_andNeverCallsTheCatalog() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("PII-ENCRYPTION-001", List.of("PCI-DSS"))));

        service.processReportGenerationAsync(JOB_ID);

        ComplianceGeneratedReportEntity report = savedReport();
        assertThat(report.getOverallStatus()).isEqualTo("NO_CONTROLS");
        assertThat(report.getFindingsData()).isEmpty();
        assertThat(job.getStatus()).isEqualTo("COMPLETED_SUCCESS");
        verifyNoInteractions(catalogAssetClient);
    }

    @Test
    void report_overAnEmptyCatalog_isNoData() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(), 0, true));

        service.processReportGenerationAsync(JOB_ID);

        ComplianceGeneratedReportEntity report = savedReport();
        assertThat(report.getOverallStatus()).isEqualTo("NO_DATA");
        assertThat(report.getFindingsData()).isEmpty();
    }

    @Test
    void report_whenTheCatalogIsUnreachable_failsTheJobAndSavesNoReport() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(anyInt(), anyInt())).thenThrow(new IllegalStateException("catalog down"));

        service.processReportGenerationAsync(JOB_ID);

        assertThat(job.getStatus()).isEqualTo("COMPLETED_FAILURE");
        assertThat(job.getMessage()).contains("catalog down");
        assertThat(job.getGeneratedReportId()).isNull();
        verify(generatedReportRepository, never()).save(any());
    }

    @Test
    void report_withNoFrameworksOnItsDefinition_usesEveryEnabledControl() {
        when(reportDefinitionRepository.findByReportTypeIgnoreCase(REPORT_TYPE)).thenReturn(Optional.of(
                definition(List.of(), null)));
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR")),
                control("GDPR-RESIDENCY-001", List.of("GDPR")),
                control("PII-ENCRYPTION-001", List.of("PCI-DSS"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("b-eu", "GCS_BUCKET", "eu-west-1", List.of("PII"))), 1, true));

        service.processReportGenerationAsync(JOB_ID);

        // PII-labelled asset without an encryption marker: the PCI control now applies and fails.
        assertThat(savedReport().getFindingsData()).extracting(ReportFindingData::getCheckId)
                .containsExactlyInAnyOrder("IAM-DRIFT-001", "GDPR-RESIDENCY-001", "PII-ENCRYPTION-001");
    }

    @Test
    void report_matchesFrameworkNamesIgnoringCaseAndPunctuation() {
        when(reportDefinitionRepository.findByReportTypeIgnoreCase(REPORT_TYPE)).thenReturn(Optional.of(
                definition(List.of("pci_dss"), null)));
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("PII-ENCRYPTION-001", List.of("PCI-DSS")),
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("p", "GCS_BUCKET", null, List.of("PII"))), 1, true));

        service.processReportGenerationAsync(JOB_ID);

        assertThat(savedReport().getFindingsData()).extracting(ReportFindingData::getCheckId)
                .containsExactly("PII-ENCRYPTION-001");
    }

    @Test
    void report_narrowsAssetsToTheDefinitionsTargetTypes() {
        when(reportDefinitionRepository.findByReportTypeIgnoreCase(REPORT_TYPE)).thenReturn(Optional.of(
                definition(List.of("GDPR"), List.of("gcs-bucket"))));
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("in-scope", "GCS_BUCKET", null, List.of()),
                asset("out-of-scope", "S3_BUCKET", null, List.of()),
                asset("untyped", null, null, List.of())), 1, true));

        service.processReportGenerationAsync(JOB_ID);

        assertThat(savedReport().getFindingsData()).extracting(ReportFindingData::getAssetId)
                .containsExactly("in-scope");
    }

    @Test
    void report_walksEveryCatalogPage() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        List<CatalogAssetDTO> first = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            first.add(asset("a-" + i, "GCS_BUCKET", null, List.of()));
        }
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(first, 2, false));
        when(catalogAssetClient.getAssets(1, 200)).thenReturn(page(
                List.of(asset("a-200", "GCS_BUCKET", null, List.of())), 2, true));

        service.processReportGenerationAsync(JOB_ID);

        assertThat(savedReport().getFindingsData()).hasSize(201);
        verify(catalogAssetClient, times(1)).getAssets(eq(0), eq(200));
        verify(catalogAssetClient, times(1)).getAssets(eq(1), eq(200));
    }

    @Test
    void report_isRepeatable_twoRunsOverTheSameCatalogAgree() {
        when(controlRepository.findByEnabledTrue()).thenReturn(List.of(
                control("IAM-DRIFT-001", List.of("GDPR"))));
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page(List.of(
                asset("a", "GCS_BUCKET", null, List.of("PUBLIC")),
                asset("b", "GCS_BUCKET", null, List.of())), 1, true));

        service.processReportGenerationAsync(JOB_ID);
        service.processReportGenerationAsync(JOB_ID);

        ArgumentCaptor<ComplianceGeneratedReportEntity> captor =
                ArgumentCaptor.forClass(ComplianceGeneratedReportEntity.class);
        verify(generatedReportRepository, times(2)).save(captor.capture());
        List<ComplianceGeneratedReportEntity> reports = captor.getAllValues();
        assertThat(reports.get(1).getFindingsData()).usingRecursiveComparison()
                .isEqualTo(reports.get(0).getFindingsData());
        assertThat(reports.get(1).getOverallStatus()).isEqualTo(reports.get(0).getOverallStatus());
    }

    @Test
    void report_forAnUnknownJob_doesNothing() {
        when(reportJobRepository.findById("ghost")).thenReturn(Optional.empty());

        service.processReportGenerationAsync("ghost");

        verifyNoInteractions(catalogAssetClient, controlRepository);
        verify(generatedReportRepository, never()).save(any());
    }
}
