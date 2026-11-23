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

package com.dlap.reporting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientPolicyEngineClient;
import com.dlap.reporting.dto.ReportDefinitionDTO;
import com.dlap.reporting.mapper.ReportMapper;
import com.dlap.reporting.model.GeneratedReport;
import com.dlap.reporting.model.ReportDefinition;
import com.dlap.reporting.model.ReportStatus;
import com.dlap.reporting.repository.GeneratedReportRepository;
import com.dlap.reporting.repository.ReportDefinitionRepository;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportDefinitionRepository reportDefinitionRepository;
    @Mock
    private GeneratedReportRepository generatedReportRepository;
    @Mock
    private ReportMapper reportMapper;
    @Mock
    private ResilientCatalogClient catalogClient;
    @Mock
    private ResilientPolicyEngineClient policyEngineClient;

    /**
     * BOOT-01: a create request that carries an id still inserts a new definition. Since Hibernate ORM
     * 6.6 saving a generated-id entity with a preset id is a merge that fails when no row exists.
     */
    @Test
    void createReportDefinition_clearsAClientSentIdSoTheSaveInserts() {
        ReportDefinitionDTO request = new ReportDefinitionDTO();
        request.setId(UUID.randomUUID());
        request.setReportKey("weekly");
        ReportDefinition mapped = new ReportDefinition();
        mapped.setId(request.getId());
        mapped.setReportKey("weekly");
        when(reportDefinitionRepository.findByReportKey("weekly")).thenReturn(Optional.empty());
        when(reportMapper.toEntity(request)).thenReturn(mapped);
        when(reportDefinitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReportService svc = new ReportService(reportDefinitionRepository, generatedReportRepository, reportMapper,
                catalogClient, policyEngineClient);

        svc.createReportDefinition(request);

        ArgumentCaptor<ReportDefinition> saved = ArgumentCaptor.forClass(ReportDefinition.class);
        verify(reportDefinitionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    void processReportGenerationJobAsync_writesJsonEnvelope() {
        UUID reportId = UUID.randomUUID();
        ReportDefinition def = new ReportDefinition();
        def.setReportKey("summary");
        def.setEnabled(true);

        GeneratedReport gr = new GeneratedReport();
        gr.setId(reportId);
        gr.setReportDefinition(def);
        gr.setStatus(ReportStatus.PENDING);

        when(generatedReportRepository.findById(reportId)).thenReturn(Optional.of(gr));
        when(generatedReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(catalogClient.getTotalAssetCount()).thenReturn(3L);
        when(catalogClient.getClassifiedAssetsCount()).thenReturn(1L);
        when(catalogClient.getAssetsWithMetadataCount()).thenReturn(2L);
        when(catalogClient.getAssetsWithBusinessContextCount()).thenReturn(0L);
        when(catalogClient.getAssetsWithLineageCount()).thenReturn(0L);
        when(catalogClient.getTotalLineageConnectionsCount()).thenReturn(0L);
        when(catalogClient.countAssetsByStatus("ARCHIVED")).thenReturn(0L);
        when(catalogClient.countAssetsByStatus("PENDING_DELETION")).thenReturn(0L);
        when(policyEngineClient.getActivePoliciesCount()).thenReturn(1L);
        when(policyEngineClient.getPolicyViolationsCount(null)).thenReturn(0L);
        when(policyEngineClient.getPolicyComplianceRate()).thenReturn(0.9);
        when(policyEngineClient.getPolicyActionItems(null, null, 50)).thenReturn(java.util.List.of());

        ReportService svc = new ReportService(
                reportDefinitionRepository,
                generatedReportRepository,
                reportMapper,
                catalogClient,
                policyEngineClient);

        svc.processReportGenerationJobAsync(reportId, "summary", Map.of("window", "7d"));

        assertThat(gr.getStatus()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(gr.getReportContent()).contains("\"reportKey\":\"summary\"");
        assertThat(gr.getReportContent()).contains("\"catalog\"");
        assertThat(gr.getReportContent()).contains("\"policyEngine\"");
    }
}
