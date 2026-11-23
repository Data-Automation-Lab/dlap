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

package com.dlap.policyengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.repository.PolicyEvaluationRepository;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * BOOT-01: the daily compliance history keeps its {@code date} text whichever type the native query
 * returns for the day bucket. Hibernate ORM 7 returns {@code java.time.LocalDate}; Hibernate ORM 6
 * returned {@code java.sql.Date}, and the old cast to it failed on the new type.
 */
class PolicyEngineMetricsServiceTest {

    private final PolicyEvaluationRepository evaluations = mock(PolicyEvaluationRepository.class);
    private final PolicyEngineMetricsService service =
            new PolicyEngineMetricsService(mock(PolicyRepository.class), evaluations);

    @Test
    void historyPrintsAHibernate7LocalDateBucketAsIsoDate() {
        when(evaluations.summarizeEvaluationsByDay(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[] {LocalDate.of(2026, 10, 19), 3L, 1L}));

        List<Map<String, Object>> history = service.getPolicyComplianceHistory(7);

        assertThat(history).hasSize(1);
        assertThat(history.get(0))
                .containsEntry("date", "2026-10-19")
                .containsEntry("passed", 3L)
                .containsEntry("failed", 1L)
                .containsEntry("complianceRatePercent", 75.0);
    }

    @Test
    void historyStillPrintsASqlDateBucketAsIsoDate() {
        when(evaluations.summarizeEvaluationsByDay(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[] {java.sql.Date.valueOf("2026-10-18"), 0L, 0L}));

        List<Map<String, Object>> history = service.getPolicyComplianceHistory(7);

        assertThat(history.get(0))
                .containsEntry("date", "2026-10-18")
                .containsEntry("complianceRatePercent", 100.0);
    }

    @Test
    void historyKeepsAMissingBucketNull() {
        when(evaluations.summarizeEvaluationsByDay(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[] {null, null, null}));

        List<Map<String, Object>> history = service.getPolicyComplianceHistory(7);

        assertThat(history.get(0)).containsEntry("date", null).containsEntry("passed", 0L);
    }
}
