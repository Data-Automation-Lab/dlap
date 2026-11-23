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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ControlEvaluationJobEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.mapper.ComplianceControlMapper;
import com.dlap.autocompliance.model.mapper.ControlEvaluationJobMapper;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ControlEvaluationJobRepository;

/**
 * Unit tests for the focused control-slice impl. Migrated from
 * {@code ComplianceServiceImplTest} when the N1 phase-2 move pulled the
 * control method bodies out of the facade.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceControlServiceImplTest {

    @Mock
    private ComplianceControlRepository controlRepository;
    @Mock
    private ControlEvaluationJobRepository controlEvaluationJobRepository;
    @Mock
    private ComplianceGeneratedReportRepository generatedReportRepository;
    @Mock
    private ComplianceControlMapper controlMapper;
    @Mock
    private ControlEvaluationJobMapper controlEvaluationJobMapper;
    @Mock
    private AssetComplianceEvaluator assetComplianceEvaluator;
    @Mock
    private com.dlap.autocompliance.client.CatalogAssetClient catalogAssetClient;

    @InjectMocks
    private ComplianceControlServiceImpl controlService;

    @Test
    void evaluateControl_realEvaluatorNonCompliant_isFailure() {
        // The real AssetComplianceEvaluator (not a findings lookup) runs against the asset's
        // live catalog attributes and flags it NON_COMPLIANT - the choke-point-#2 fix.
        ComplianceControlEntity control = ComplianceControlEntity.builder()
                .controlId("IAM-DRIFT-001").name("IAM drift").enabled(true).build();
        when(controlRepository.findByControlIdIgnoreCase("IAM-DRIFT-001")).thenReturn(Optional.of(control));

        ControlEvaluationJobEntity job = ControlEvaluationJobEntity.builder()
                .jobId("job-9").controlId("IAM-DRIFT-001").status("QUEUED")
                .targetAssetIds(List.of("asset-9")).build();
        when(controlEvaluationJobMapper.fromRequestDto(any(), anyString())).thenReturn(job);
        when(controlEvaluationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(controlEvaluationJobMapper.toResponseDto(any())).thenAnswer(inv -> {
            ControlEvaluationJobEntity e = inv.getArgument(0);
            return ControlEvaluationResponseDTO.builder()
                    .jobId(e.getJobId()).controlId(e.getControlId())
                    .status(e.getStatus()).message(e.getMessage()).build();
        });

        com.dlap.autocompliance.client.dto.CatalogAssetDTO asset =
                new com.dlap.autocompliance.client.dto.CatalogAssetDTO();
        asset.setAssetId("asset-9");
        asset.setAssetUri("gs://bucket/pii");
        com.dlap.autocompliance.client.dto.CatalogAssetPageDTO page =
                new com.dlap.autocompliance.client.dto.CatalogAssetPageDTO();
        page.setContent(List.of(asset));
        page.setLast(true);
        when(catalogAssetClient.getAssets(0, 200)).thenReturn(page);
        when(assetComplianceEvaluator.evaluate(any(), any())).thenReturn(
                com.dlap.autocompliance.model.entity.ReportFindingData.builder()
                        .assetId("asset-9").checkId("IAM-DRIFT-001").status("NON_COMPLIANT").build());

        ControlEvaluationResponseDTO resp = controlService.evaluateControl("IAM-DRIFT-001",
                ControlEvaluationRequestDTO.builder().targetAssetIds(List.of("asset-9")).build());

        assertThat(resp.getStatus()).isEqualTo("COMPLETED_FAILURE");
        verify(assetComplianceEvaluator).evaluate(any(), any());
        // The real evaluator produced a definite finding - the findings-lookup fallback is skipped.
        verify(generatedReportRepository, org.mockito.Mockito.never())
                .findReportsContainingAssetId(anyString());
    }

    @Test
    void evaluateControl_noTargets_isPartial() {
        ComplianceControlEntity control = ComplianceControlEntity.builder()
                .controlId("C-1")
                .name("encryption")
                .enabled(true)
                .build();
        when(controlRepository.findByControlIdIgnoreCase("C-1")).thenReturn(Optional.of(control));

        ControlEvaluationJobEntity job = ControlEvaluationJobEntity.builder()
                .jobId("job-1")
                .controlId("C-1")
                .status("QUEUED")
                .targetAssetIds(List.of())
                .build();
        when(controlEvaluationJobMapper.fromRequestDto(any(), anyString())).thenReturn(job);
        when(controlEvaluationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(controlEvaluationJobMapper.toResponseDto(any())).thenAnswer(inv -> {
            ControlEvaluationJobEntity e = inv.getArgument(0);
            return ControlEvaluationResponseDTO.builder()
                    .jobId(e.getJobId())
                    .controlId(e.getControlId())
                    .status(e.getStatus())
                    .message(e.getMessage())
                    .targetedAssetCount(e.getTargetAssetIds() != null ? e.getTargetAssetIds().size() : 0)
                    .build();
        });

        ControlEvaluationRequestDTO req = ControlEvaluationRequestDTO.builder()
                .targetAssetIds(List.of())
                .build();

        ControlEvaluationResponseDTO resp = controlService.evaluateControl("C-1", req);
        assertThat(resp.getStatus()).isEqualTo("COMPLETED_PARTIAL");
    }

    @Test
    void evaluateControl_disabledControl_isFailure() {
        ComplianceControlEntity control = ComplianceControlEntity.builder()
                .controlId("C-2")
                .name("unused")
                .enabled(false)
                .build();
        when(controlRepository.findByControlIdIgnoreCase("C-2")).thenReturn(Optional.of(control));

        ControlEvaluationJobEntity job = ControlEvaluationJobEntity.builder()
                .jobId("job-2")
                .controlId("C-2")
                .status("QUEUED")
                .targetAssetIds(List.of("a1"))
                .build();
        when(controlEvaluationJobMapper.fromRequestDto(any(), anyString())).thenReturn(job);
        when(controlEvaluationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(controlEvaluationJobMapper.toResponseDto(any())).thenAnswer(inv -> {
            ControlEvaluationJobEntity e = inv.getArgument(0);
            return ControlEvaluationResponseDTO.builder()
                    .jobId(e.getJobId())
                    .controlId(e.getControlId())
                    .status(e.getStatus())
                    .message(e.getMessage())
                    .targetedAssetCount(1)
                    .build();
        });

        ControlEvaluationResponseDTO resp = controlService.evaluateControl("C-2",
                ControlEvaluationRequestDTO.builder().targetAssetIds(List.of("a1")).build());
        assertThat(resp.getStatus()).isEqualTo("COMPLETED_FAILURE");
    }

    @Test
    void evaluateControl_matchingCompliantFinding_isSuccess() {
        ComplianceControlEntity control = ComplianceControlEntity.builder()
                .controlId("C-3")
                .name("check")
                .enabled(true)
                .build();
        when(controlRepository.findByControlIdIgnoreCase("C-3")).thenReturn(Optional.of(control));

        ControlEvaluationJobEntity job = ControlEvaluationJobEntity.builder()
                .jobId("job-3")
                .controlId("C-3")
                .status("QUEUED")
                .targetAssetIds(List.of("asset-1"))
                .build();
        when(controlEvaluationJobMapper.fromRequestDto(any(), anyString())).thenReturn(job);
        when(controlEvaluationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(controlEvaluationJobMapper.toResponseDto(any())).thenAnswer(inv -> {
            ControlEvaluationJobEntity e = inv.getArgument(0);
            return ControlEvaluationResponseDTO.builder()
                    .jobId(e.getJobId())
                    .controlId(e.getControlId())
                    .status(e.getStatus())
                    .message(e.getMessage())
                    .targetedAssetCount(1)
                    .build();
        });

        ReportFindingData finding = ReportFindingData.builder()
                .checkId("C-3")
                .assetId("asset-1")
                .status("COMPLIANT")
                .build();
        ComplianceGeneratedReportEntity report = ComplianceGeneratedReportEntity.builder()
                .reportId(UUID.randomUUID().toString())
                .reportType("SOC2")
                .findingsData(List.of(finding))
                .build();
        when(generatedReportRepository.findReportsContainingAssetId("asset-1")).thenReturn(List.of(report));

        ControlEvaluationResponseDTO resp = controlService.evaluateControl("C-3",
                ControlEvaluationRequestDTO.builder().targetAssetIds(List.of("asset-1")).build());
        assertThat(resp.getStatus()).isEqualTo("COMPLETED_SUCCESS");
    }
}
