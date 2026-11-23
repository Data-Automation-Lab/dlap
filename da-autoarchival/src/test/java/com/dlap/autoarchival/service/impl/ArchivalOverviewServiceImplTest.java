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

package com.dlap.autoarchival.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.autoarchival.dto.ArchivalKpisDTO;
import com.dlap.autoarchival.model.ArchivalConfigEntity;
import com.dlap.autoarchival.model.ArchivalStatus;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.repository.ArchivalActivityRepository;
import com.dlap.autoarchival.repository.ArchivalConfigRepository;
import com.dlap.autoarchival.repository.ArchivalMetricsRepository;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;
import com.dlap.autoarchival.repository.StorageConsumerRepository;
import com.dlap.autoarchival.service.IArchivalTaskService;

/**
 * Unit tests for {@link ArchivalOverviewServiceImpl#getArchivalKpis()} - the KPI
 * math that reads real ArchivalTask rows. All repositories are mocked; the test
 * pins the status-to-KPI mapping, the assetsIdentified rollup, the
 * activePolicies-from-config branch, and the lastPolicyRun formatting.
 */
@ExtendWith(MockitoExtension.class)
class ArchivalOverviewServiceImplTest {

    @Mock
    private ArchivalMetricsRepository archivalMetricsRepository;
    @Mock
    private ArchivalActivityRepository archivalActivityRepository;
    @Mock
    private ArchivalTaskRepository archivalTaskRepository;
    @Mock
    private ArchivalConfigRepository archivalConfigRepository;
    @Mock
    private StorageConsumerRepository storageConsumerRepository;
    @Mock
    private IArchivalTaskService archivalTaskService;
    @Mock
    private com.dlap.autoarchival.executor.ArchiveRuleRepository archiveRuleRepository;

    private ArchivalOverviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ArchivalOverviewServiceImpl(
                archivalMetricsRepository, archivalActivityRepository, archivalTaskRepository,
                archivalConfigRepository, storageConsumerRepository, archivalTaskService, archiveRuleRepository);
    }

    /** All statuses default to 0 unless explicitly stubbed. */
    private void stubAllStatusCountsZero() {
        for (ArchivalStatus s : ArchivalStatus.values()) {
            lenient().when(archivalTaskRepository.countByStatus(s)).thenReturn(0L);
        }
    }

    private ArchivalConfigEntity config(boolean enabled) {
        ArchivalConfigEntity cfg = new ArchivalConfigEntity();
        cfg.setId(1L);
        cfg.setEnabled(enabled);
        return cfg;
    }

    @Test
    void kpis_rollUpCountsAndComputeAssetsIdentified() {
        stubAllStatusCountsZero();
        when(archivalTaskRepository.countByStatus(ArchivalStatus.COMPLETED)).thenReturn(5L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.FAILED)).thenReturn(2L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.PENDING_APPROVAL)).thenReturn(1L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.APPROVED)).thenReturn(1L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.SUBMITTED)).thenReturn(1L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.IN_PROGRESS)).thenReturn(1L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.PARTIALLY_COMPLETED)).thenReturn(3L);
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(config(true)));
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(List.of());

        ArchivalKpisDTO kpis = service.getArchivalKpis();

        assertThat(kpis.getAssetsArchived()).isEqualTo(5);
        assertThat(kpis.getFailedArchival()).isEqualTo(2);
        // pending = PENDING_APPROVAL + APPROVED + SUBMITTED + IN_PROGRESS = 4
        assertThat(kpis.getPendingArchival()).isEqualTo(4);
        // identified = archived(5) + failed(2) + pending(4) + partiallyCompleted(3) = 14
        assertThat(kpis.getAssetsIdentified()).isEqualTo(14);
        // config enabled -> 1 active policy
        assertThat(kpis.getActivePolicies()).isEqualTo(1);
        // honest zeros for not-yet-aggregated savings metrics
        assertThat(kpis.getEstimatedMonthlySavings()).isZero();
        assertThat(kpis.getStorageReclaimed()).isEqualTo("0 TB");
        // no task activity -> "Never"
        assertThat(kpis.getLastPolicyRun()).isEqualTo("Never");
    }

    @Test
    void activePolicies_isZeroWhenConfigDisabled() {
        stubAllStatusCountsZero();
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(config(false)));
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(List.of());

        assertThat(service.getArchivalKpis().getActivePolicies()).isZero();
    }

    @Test
    void activePolicies_isZeroWhenNoConfigRow() {
        stubAllStatusCountsZero();
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(List.of());

        ArchivalKpisDTO kpis = service.getArchivalKpis();
        assertThat(kpis.getActivePolicies()).isZero();
        assertThat(kpis.getAssetsIdentified()).isZero();
    }

    @Test
    void lastPolicyRun_reflectsMostRecentTaskSubmission() {
        stubAllStatusCountsZero();
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(config(true)));

        ArchivalTaskEntity recent = new ArchivalTaskEntity();
        recent.setSubmittedAt(LocalDateTime.now().minusMinutes(90));
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(List.of(recent));

        // 90 minutes ago -> "1 hours ago" per formatTimeAgo bucketing.
        assertThat(service.getArchivalKpis().getLastPolicyRun()).isEqualTo("1 hours ago");
    }

    // ---- Q-605: the "assets archived" KPI counts log-proven rows, not rules that are merely in place ----

    @Test
    void kpis_assetsArchivedCountsExecutedRowsAndNotRulesThatAreOnlyApplied() {
        stubAllStatusCountsZero();
        when(archiveRuleRepository.countByStatus("EXECUTED")).thenReturn(4L);
        // Nine rules are in place and no provider event has shown them fire: they are pending, not archived.
        lenient().when(archiveRuleRepository.countByStatus("APPLIED")).thenReturn(9L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.RULE_APPLIED)).thenReturn(9L);
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(config(true)));
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(List.of());

        var kpis = service.getArchivalKpis();

        assertThat(kpis.getAssetsArchived()).isEqualTo(4);
        assertThat(kpis.getPendingArchival()).isEqualTo(9);
        assertThat(kpis.getAssetsIdentified()).isEqualTo(13);
        org.mockito.Mockito.verify(archiveRuleRepository, org.mockito.Mockito.never()).countByStatus("APPLIED");
    }

    @Test
    void kpis_aRenderedRuleIsPendingToo() {
        stubAllStatusCountsZero();
        when(archivalTaskRepository.countByStatus(ArchivalStatus.RULE_RENDERED)).thenReturn(2L);
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(config(true)));
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(List.of());

        var kpis = service.getArchivalKpis();

        assertThat(kpis.getAssetsArchived()).isZero();
        assertThat(kpis.getPendingArchival()).isEqualTo(2);
    }

    @Test
    void statusDistribution_archivedSliceIsTheSameProvenCount() {
        stubAllStatusCountsZero();
        when(archiveRuleRepository.countByStatus("EXECUTED")).thenReturn(6L);
        when(archivalMetricsRepository.findMostRecentMetrics()).thenReturn(Optional.empty());

        var data = service.getArchivalStatusData();

        assertThat(data).filteredOn(d -> "Archived".equals(d.getName())).singleElement()
                .satisfies(d -> assertThat(d.getValue()).isEqualTo(6));
    }

    @Test
    void manualRun_recordsTheRequestAndDispatchesEveryApprovedAndSubmittedTask() {
        ArchivalTaskEntity approved = new ArchivalTaskEntity();
        approved.setTaskId("t-approved");
        ArchivalTaskEntity submitted = new ArchivalTaskEntity();
        submitted.setTaskId("t-submitted");
        when(archivalTaskRepository.findByStatusOrderBySubmittedAtDesc(ArchivalStatus.APPROVED))
                .thenReturn(List.of(approved));
        when(archivalTaskRepository.findByStatusOrderBySubmittedAtDesc(ArchivalStatus.SUBMITTED))
                .thenReturn(List.of(submitted));

        Boolean ok = service.triggerManualArchivalRun();

        assertThat(ok).isTrue();
        org.mockito.ArgumentCaptor<com.dlap.autoarchival.model.ArchivalActivityEntity> saved =
                org.mockito.ArgumentCaptor.forClass(com.dlap.autoarchival.model.ArchivalActivityEntity.class);
        org.mockito.Mockito.verify(archivalActivityRepository).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("MANUAL_RUN_REQUESTED");
        assertThat(saved.getValue().getDetails()).startsWith("Manual archival run requested");
        org.mockito.Mockito.verify(archivalTaskService).processArchivalTaskAsync("t-approved");
        org.mockito.Mockito.verify(archivalTaskService).processArchivalTaskAsync("t-submitted");
    }
}
