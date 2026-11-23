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

package com.dlap.autodelete.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.DeleteKpisDTO;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.service.IDeletionService;

/**
 * Unit tests for {@link DeleteAgentDashboardServiceImpl#getDeleteKpis()} - the KPI
 * math sourced from {@code soft_delete_records} and the compliance audit trail.
 * All repositories are mocked; the test pins the status counts, the bytes→GB
 * reclaimed formatting, the compliance-score branch (terminal attempts vs. the
 * empty-table 100.0 fallback), and the average soft-delete duration.
 */
@ExtendWith(MockitoExtension.class)
class DeleteAgentDashboardServiceImplTest {

    private static final String SOFT_DELETED = "SOFT_DELETED";
    private static final String HARD_DELETED = "HARD_DELETED";
    private static final String HARD_DELETE_FAILED = "HARD_DELETE_FAILED";

    @Mock
    private DeletionTaskRepository deletionTaskRepository;
    @Mock
    private IDeletionService deletionService;
    @Mock
    private SoftDeleteRecordRepository softDeleteRecordRepository;

    private DeleteAgentDashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeleteAgentDashboardServiceImpl(
                deletionTaskRepository,
                deletionService, softDeleteRecordRepository);
    }

    /** Common stubs for the paths getDeleteKpis touches; overridden per test. */
    private void baseStubs() {
        lenient().when(softDeleteRecordRepository.countByStatus(any())).thenReturn(0L);
        lenient().when(softDeleteRecordRepository.countByStatusAndHardDeletedAtAfter(any(), any()))
                .thenReturn(0L);
        lenient().when(softDeleteRecordRepository.sumSizeBytesByStatus(any())).thenReturn(0L);
        lenient().when(softDeleteRecordRepository.sumSizeBytesByStatusSince(any(), any())).thenReturn(0L);
        lenient().when(softDeleteRecordRepository.countDistinctPolicyIds()).thenReturn(0L);
        lenient().when(softDeleteRecordRepository.findMaxQueuedAt()).thenReturn(Optional.empty());
        lenient().when(softDeleteRecordRepository.findByStatusOrderByQueuedAtAsc(HARD_DELETED))
                .thenReturn(List.of());
    }

    @Test
    void kpis_mapStatusCountsAndReclaimedBytes() {
        baseStubs();
        when(softDeleteRecordRepository.countByStatus(HARD_DELETED)).thenReturn(8L);
        when(softDeleteRecordRepository.countByStatus(SOFT_DELETED)).thenReturn(3L);
        when(softDeleteRecordRepository.countByStatus(HARD_DELETE_FAILED)).thenReturn(2L);
        when(softDeleteRecordRepository.countByStatusAndHardDeletedAtAfter(eq(HARD_DELETED), any()))
                .thenReturn(4L);
        when(softDeleteRecordRepository.countDistinctPolicyIds()).thenReturn(2L);
        // 2 GiB reclaimed: the sizes of the records the provider's log proved deleted (Q-619).
        when(softDeleteRecordRepository.sumSizeBytesByStatus(HARD_DELETED))
                .thenReturn(2L * 1024 * 1024 * 1024);

        DeleteKpisDTO kpis = service.getDeleteKpis();

        assertThat(kpis.getAssetsDeleted()).isEqualTo(8L);
        assertThat(kpis.getAssetsDeletedLast24h()).isEqualTo(4);
        assertThat(kpis.getPendingDeletion()).isEqualTo(3);
        assertThat(kpis.getFailedDeletion()).isEqualTo(2);
        assertThat(kpis.getActivePolicies()).isEqualTo(2);
        assertThat(kpis.getStorageReclaimed()).isEqualTo("2.0 GB");
        // completed 8 / (8 + 2 failed) = 80.0 %
        assertThat(kpis.getComplianceScore()).isEqualTo(80.0);
        // savings require billing integration - honest zeros
        assertThat(kpis.getEstimatedMonthlySavings()).isZero();
    }

    /**
     * Q-619: the KPI reads what the executor writes now. It sums the catalog size of records the provider
     * proved deleted (HARD_DELETED) and nothing else: not the dead dlap_deletion_audit table, and not a
     * bucket or container rule in force.
     */
    @Test
    void storageReclaimedReadsTheProvenDeletedRecordsOnly() {
        baseStubs();
        when(softDeleteRecordRepository.sumSizeBytesByStatus(HARD_DELETED)).thenReturn(3L * 1024 * 1024 * 1024);

        assertThat(service.getDeleteKpis().getStorageReclaimed()).isEqualTo("3.0 GB");
        verify(softDeleteRecordRepository).sumSizeBytesByStatus(HARD_DELETED);
        verify(softDeleteRecordRepository, never()).sumSizeBytesByStatus("RULE_IN_FORCE");
        verify(softDeleteRecordRepository, never()).sumSizeBytesByStatus("AWAITING_PROOF");
    }

    @Test
    void totalStorageReclaimedAndItsMonthAndYearReadTheSameRecords() {
        baseStubs();
        when(deletionTaskRepository.findByStatus(any())).thenReturn(List.of());
        when(softDeleteRecordRepository.sumSizeBytesByStatus(HARD_DELETED)).thenReturn(5L * 1024 * 1024 * 1024);
        when(softDeleteRecordRepository.sumSizeBytesByStatusSince(eq(HARD_DELETED), any()))
                .thenReturn(1L * 1024 * 1024 * 1024);

        var total = service.getTotalStorageReclaimed();

        assertThat(total.getTotalStorageReclaimedGB()).isEqualTo(5.0);
        assertThat(total.getStorageReclaimedThisMonth()).isEqualTo("1.0 GB");
        assertThat(total.getStorageReclaimedThisYear()).isEqualTo("1.0 GB");
    }

    @Test
    void complianceScore_is100WhenNoTerminalAttempts() {
        baseStubs();
        // no HARD_DELETED, no HARD_DELETE_FAILED -> nothing has gone wrong yet.

        DeleteKpisDTO kpis = service.getDeleteKpis();

        assertThat(kpis.getComplianceScore()).isEqualTo(100.0);
        assertThat(kpis.getAssetsDeleted()).isZero();
        assertThat(kpis.getStorageReclaimed()).isEqualTo("0.0 GB");
        assertThat(kpis.getLastPolicyRun()).isEqualTo("No recent runs");
    }

    @Test
    void reclaimedBytes_formatAsTerabytesAbove1024Gb() {
        baseStubs();
        when(softDeleteRecordRepository.sumSizeBytesByStatus(HARD_DELETED))
                .thenReturn(2L * 1024 * 1024 * 1024 * 1024); // 2 TiB

        assertThat(service.getDeleteKpis().getStorageReclaimed()).isEqualTo("2.0 TB");
    }

    @Test
    void averageDeletionTime_isMeanQueuedToHardDeletedMinutes() {
        baseStubs();
        Instant now = Instant.now();
        SoftDeleteRecordEntity r1 = SoftDeleteRecordEntity.builder()
                .status(HARD_DELETED)
                .queuedAt(now.minus(Duration.ofMinutes(60)))
                .hardDeletedAt(now.minus(Duration.ofMinutes(40))) // 20 min
                .build();
        SoftDeleteRecordEntity r2 = SoftDeleteRecordEntity.builder()
                .status(HARD_DELETED)
                .queuedAt(now.minus(Duration.ofMinutes(60)))
                .hardDeletedAt(now.minus(Duration.ofMinutes(20))) // 40 min
                .build();
        when(softDeleteRecordRepository.findByStatusOrderByQueuedAtAsc(HARD_DELETED))
                .thenReturn(List.of(r1, r2));

        // mean of 20 and 40 minutes = 30.0
        assertThat(service.getDeleteKpis().getAverageDeletionTime()).isEqualTo(30.0);
    }

    @Test
    void lastPolicyRun_formatsMinutesAgoFromMaxQueuedAt() {
        baseStubs();
        when(softDeleteRecordRepository.findMaxQueuedAt())
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofMinutes(30))));

        assertThat(service.getDeleteKpis().getLastPolicyRun()).isEqualTo("30 minutes ago");
    }

    /** RET-02: a manual run is one retire sweep through the same rules, not an empty task. */
    @Test
    void aManualRunIsOneRetireSweep() {
        org.mockito.Mockito.when(deletionService.processDueHardDeletes()).thenReturn(3);

        assertThat(service.triggerManualDeletionRun()).isTrue();

        org.mockito.Mockito.verify(deletionService).processDueHardDeletes();
    }

    /** RET-02: a sweep that fails reports false. */
    @Test
    void aManualRunThatFailsReportsFalse() {
        org.mockito.Mockito.when(deletionService.processDueHardDeletes()).thenThrow(new IllegalStateException("db"));

        assertThat(service.triggerManualDeletionRun()).isFalse();
    }
}
