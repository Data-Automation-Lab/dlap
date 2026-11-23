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

package com.dlap.autolabel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.autolabel.dto.LabelAgentKpis;
import com.dlap.autolabel.dto.TopAppliedLabel;
import com.dlap.autolabel.entity.LabelingJobEntity;
import com.dlap.autolabel.repository.LabelingJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link LabelAgentDashboardServiceImpl} KPI + top-label
 * aggregation. The repository is mocked; the tests pin the totals rollup, the
 * error-rate / model-accuracy math, the average-processing-time computation, the
 * empty-data zero path, and the top-applied-label sort/limit and category mapping.
 *
 * <p>ML-config / scope driven fields (active models, rule/ML policies) are left at
 * their null-config zero values to keep the fixtures deterministic and free of the
 * nested MLConfigRequest/scope wiring.
 */
@ExtendWith(MockitoExtension.class)
class LabelAgentDashboardServiceImplTest {

    @Mock
    private LabelingJobRepository labelingJobRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LabelAgentDashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LabelAgentDashboardServiceImpl(
                labelingJobRepository, kafkaTemplate, objectMapper);
    }

    private LabelingJobEntity completedJob(String id, int processed, int labeled, int failed,
                                           LocalDateTime startedAt, LocalDateTime completedAt,
                                           Map<String, Integer> labelSummary) {
        return LabelingJobEntity.builder()
                .jobId(id)
                .jobName("job-" + id)
                .status(LabelingJobEntity.JobStatus.COMPLETED)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .assetsProcessed(processed)
                .assetsSuccessfullyLabeled(labeled)
                .assetsFailed(failed)
                .labelSummary(labelSummary)
                .build();
    }

    @Test
    void kpis_rollUpTotalsAndComputeRates() {
        LocalDateTime now = LocalDateTime.now();
        // recent (within 24h): processed 100, labeled 90, failed 10, took 60s
        LabelingJobEntity recent = completedJob("a", 100, 90, 10,
                now.minusMinutes(5), now.minusMinutes(4), Map.of("PII", 90));
        // older (>24h): processed 100, labeled 100, failed 0, took 30s
        LabelingJobEntity older = completedJob("b", 100, 100, 0,
                now.minusDays(3), now.minusDays(3).plusSeconds(30), Map.of("Financial", 100));
        when(labelingJobRepository.findAll()).thenReturn(List.of(recent, older));

        LabelAgentKpis kpis = service.getLabelAgentKpis();

        assertThat(kpis.getAssetsProcessed()).isEqualTo(200);
        assertThat(kpis.getAppliedLabelsTotal()).isEqualTo(190);
        // last-24h only counts the recent job
        assertThat(kpis.getAssetsProcessedLast24h()).isEqualTo(100);
        assertThat(kpis.getAppliedLabelsLast24h()).isEqualTo(90);
        // errorRate = 10 failed / 200 processed * 100 = 5.0
        assertThat(kpis.getErrorRate()).isEqualTo(5.0);
        // modelAccuracy = (200 - 10) / 200 * 100 = 95.0
        assertThat(kpis.getModelAccuracy()).isEqualTo(95.0);
        // avg processing time = mean(60s, 30s) = 45.0
        assertThat(kpis.getAverageProcessingTime()).isEqualTo(45.0);
        // no ML config on the fixtures -> zero active models / ml policies
        assertThat(kpis.getActiveModels()).isZero();
        assertThat(kpis.getMlPoliciesActive()).isZero();
    }

    @Test
    void kpis_areZeroWhenNoCompletedJobs() {
        // A non-completed job is ignored by every completed-only aggregation.
        LabelingJobEntity submitted = LabelingJobEntity.builder()
                .jobId("x")
                .status(LabelingJobEntity.JobStatus.SUBMITTED)
                .assetsProcessed(50)
                .build();
        when(labelingJobRepository.findAll()).thenReturn(List.of(submitted));

        LabelAgentKpis kpis = service.getLabelAgentKpis();

        assertThat(kpis.getAssetsProcessed()).isZero();
        assertThat(kpis.getAppliedLabelsTotal()).isZero();
        assertThat(kpis.getErrorRate()).isZero();
        assertThat(kpis.getModelAccuracy()).isZero();
        assertThat(kpis.getAverageProcessingTime()).isZero();
    }

    @Test
    void topAppliedLabels_aggregateSortDescAndRespectLimit() {
        LocalDateTime now = LocalDateTime.now();
        LabelingJobEntity j1 = completedJob("a", 10, 10, 0,
                now.minusMinutes(10), now.minusMinutes(9),
                Map.of("PII", 5, "Customer", 3));
        LabelingJobEntity j2 = completedJob("b", 10, 10, 0,
                now.minusMinutes(8), now.minusMinutes(7),
                Map.of("PII", 4, "Financial", 8));
        when(labelingJobRepository.findAll()).thenReturn(List.of(j1, j2));

        List<TopAppliedLabel> top = service.getTopAppliedLabels(2);

        // counts: PII=9, Financial=8, Customer=3 -> top 2 by count desc.
        assertThat(top).hasSize(2);
        assertThat(top.get(0).getName()).isEqualTo("PII");
        assertThat(top.get(0).getValue()).isEqualTo(9);
        // "pii" maps to the Security category (getCategoryForLabel).
        assertThat(top.get(0).getCategory()).isEqualTo("Security");
        assertThat(top.get(1).getName()).isEqualTo("Financial");
        assertThat(top.get(1).getValue()).isEqualTo(8);
    }

    @Test
    void topAppliedLabels_emptyWhenNoLabelSummaries() {
        when(labelingJobRepository.findAll()).thenReturn(List.of());
        assertThat(service.getTopAppliedLabels(5)).isEmpty();
    }
}
