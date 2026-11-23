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

package com.dlap.autoarchival.service;

import com.dlap.autoarchival.dto.ArchivalKpisDTO;
import com.dlap.autoarchival.dto.ArchivalOverviewResponseDTO;
import com.dlap.autoarchival.dto.ArchivalStatusDataDTO;
import com.dlap.autoarchival.dto.CostSavingsTrendDTO;
import com.dlap.autoarchival.dto.RecentActivityDTO;
import com.dlap.autoarchival.dto.StorageConsumerDTO;
import com.dlap.autoarchival.model.ArchivalActivityEntity;
import com.dlap.autoarchival.model.ArchivalConfigEntity;
import com.dlap.autoarchival.model.ArchivalMetricsEntity;
import com.dlap.autoarchival.model.ArchivalStatus;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.model.StorageConsumerEntity;
import com.dlap.autoarchival.repository.ArchivalActivityRepository;
import com.dlap.autoarchival.repository.ArchivalConfigRepository;
import com.dlap.autoarchival.repository.ArchivalMetricsRepository;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;
import com.dlap.autoarchival.repository.StorageConsumerRepository;
import com.dlap.autoarchival.service.impl.ArchivalOverviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for ArchivalOverviewService to verify database-only implementation.
 * Tests that all data comes from database queries with no hardcoded data.
 */
@ExtendWith(MockitoExtension.class)
class ArchivalOverviewServiceIntegrationTest {

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

    // The service constructor takes this collaborator. With @InjectMocks, a constructor argument
    // that has no mock is passed as null, which is what made these tests throw an NPE on the
    // config repository instead of failing for a real reason.
    @Mock
    private IArchivalTaskService archivalTaskService;

    @Mock
    private com.dlap.autoarchival.executor.ArchiveRuleRepository archiveRuleRepository;

    @InjectMocks
    private ArchivalOverviewServiceImpl archivalOverviewService;

    private ArchivalMetricsEntity sampleMetrics;
    private ArchivalActivityEntity sampleActivity;
    private StorageConsumerEntity sampleConsumer;
    private ArchivalTaskEntity sampleTask;

    @BeforeEach
    void setUp() {
        // Create sample metrics
        sampleMetrics = new ArchivalMetricsEntity();
        sampleMetrics.setId(1L);
        sampleMetrics.setMetricDate(LocalDateTime.now());
        sampleMetrics.setActivePolicies(8);
        sampleMetrics.setAssetsIdentified(1842);
        sampleMetrics.setAssetsArchived(1256);
        sampleMetrics.setEstimatedMonthlySavings(12475.0);
        sampleMetrics.setTotalSavingsToDate(89320.0);
        sampleMetrics.setStorageReclaimedTb(847.2);
        sampleMetrics.setPendingArchival(156);
        sampleMetrics.setFailedArchival(23);
        sampleMetrics.setAverageArchivalTimeMinutes(45.3);
        sampleMetrics.setLastPolicyRun(LocalDateTime.now().minusHours(2));

        // Create sample activity
        sampleActivity = new ArchivalActivityEntity();
        sampleActivity.setId(1L);
        sampleActivity.setActivityId("activity-001");
        sampleActivity.setEventType("ASSET_ARCHIVED");
        sampleActivity.setAssetName("test-asset.csv");
        sampleActivity.setPolicyName("Test Policy");
        sampleActivity.setStorageSizeGb(2.3);
        sampleActivity.setSavingsAmount(156.0);
        sampleActivity.setStatus("SUCCESS");
        sampleActivity.setActivityTimestamp(LocalDateTime.now().minusMinutes(15));
        sampleActivity.setDetails("Test archival activity");

        // Create sample consumer
        sampleConsumer = new StorageConsumerEntity();
        sampleConsumer.setId(1L);
        sampleConsumer.setConsumerName("Test Consumer");
        sampleConsumer.setCurrentSizeTb(45.7);
        sampleConsumer.setLastAccessed(LocalDateTime.now().minusMonths(6));
        sampleConsumer.setMonthlyCost(2847.0);
        sampleConsumer.setRecommendation("ARCHIVE_NOW");
        sampleConsumer.setPriority("HIGH");

        // Create sample task
        sampleTask = new ArchivalTaskEntity();
        sampleTask.setTaskId("task-001");
        sampleTask.setTaskName("Test Task");
        sampleTask.setStatus(ArchivalStatus.COMPLETED);
        sampleTask.setSubmittedAt(LocalDateTime.now().minusDays(1));
        sampleTask.setStartedAt(LocalDateTime.now().minusDays(1).plusHours(1));
        sampleTask.setCompletedAt(LocalDateTime.now().minusDays(1).plusHours(2));
        sampleTask.setTriggeredBy("test-user");
        sampleTask.setTotalAssetsInScope(50);
        sampleTask.setAssetsArchivedSuccessfully(45);
        sampleTask.setAssetsFailedToArchive(5);
        sampleTask.setAssetsPendingArchival(0);
    }

    /** Stubs every status count to zero; individual tests override the ones they care about. */
    private void stubAllStatusCountsZero() {
        for (ArchivalStatus status : ArchivalStatus.values()) {
            lenient().when(archivalTaskRepository.countByStatus(status)).thenReturn(0L);
        }
    }

    private static ArchivalConfigEntity enabledConfig() {
        ArchivalConfigEntity config = new ArchivalConfigEntity();
        config.setId(1L);
        config.setEnabled(true);
        return config;
    }

    @Test
    void testGetArchivalKpis_WithData() {
        // KPIs come from the archival task table (the table PolicyActionEventListener fills), not
        // from the never-written metrics aggregate. Distinct counts prove each KPI is derived.
        stubAllStatusCountsZero();
        when(archivalTaskRepository.countByStatus(ArchivalStatus.COMPLETED)).thenReturn(1256L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.FAILED)).thenReturn(23L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.PENDING_APPROVAL)).thenReturn(100L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.APPROVED)).thenReturn(20L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.SUBMITTED)).thenReturn(30L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.IN_PROGRESS)).thenReturn(6L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.PARTIALLY_COMPLETED)).thenReturn(7L);
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(enabledConfig()));
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(Arrays.asList(sampleTask));

        // When
        ArchivalKpisDTO result = archivalOverviewService.getArchivalKpis();

        // Then
        assertNotNull(result);
        assertEquals(1, result.getActivePolicies());
        assertEquals(1256, result.getAssetsArchived());
        assertEquals(23, result.getFailedArchival());
        assertEquals(156, result.getPendingArchival()); // 100 + 20 + 30 + 6
        assertEquals(1442, result.getAssetsIdentified()); // 1256 + 23 + 156 + 7
        // Savings and reclaimed storage are not aggregated from real bytes yet: honest zeros.
        assertEquals(0.0, result.getEstimatedMonthlySavings());
        assertEquals(0.0, result.getTotalSavingsToDate());
        assertEquals("0 TB", result.getStorageReclaimed());
        assertEquals(0.0, result.getAverageArchivalTime());
        // The latest task was submitted a day ago.
        assertEquals("1 days ago", result.getLastPolicyRun());

        verify(archivalTaskRepository).countByStatus(ArchivalStatus.COMPLETED);
        verify(archivalTaskRepository).findMostRecentTasks();
        verify(archivalConfigRepository).findById(1L);
    }

    @Test
    void testGetArchivalKpis_EmptyDatabase() {
        // Given: no tasks and no config row
        stubAllStatusCountsZero();
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(Arrays.asList());

        // When
        ArchivalKpisDTO result = archivalOverviewService.getArchivalKpis();

        // Then: every figure is an honest zero, never a placeholder
        assertNotNull(result);
        assertEquals(0, result.getActivePolicies());
        assertEquals(0, result.getAssetsIdentified());
        assertEquals(0, result.getAssetsArchived());
        assertEquals(0.0, result.getEstimatedMonthlySavings());
        assertEquals(0.0, result.getTotalSavingsToDate());
        assertEquals("0 TB", result.getStorageReclaimed());
        assertEquals(0, result.getPendingArchival());
        assertEquals(0, result.getFailedArchival());
        assertEquals(0.0, result.getAverageArchivalTime());
        assertEquals("Never", result.getLastPolicyRun());

        verify(archivalConfigRepository).findById(1L);
        verify(archivalTaskRepository).findMostRecentTasks();
    }

    @Test
    void testGetCostSavingsTrends_WithData() {
        // Given
        List<ArchivalMetricsEntity> metrics = Arrays.asList(sampleMetrics);
        when(archivalMetricsRepository.findMetricsForLastDays(any(LocalDateTime.class)))
                .thenReturn(metrics);

        // When
        List<CostSavingsTrendDTO> result = archivalOverviewService.getCostSavingsTrends(7);

        // Then
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());

        CostSavingsTrendDTO trend = result.get(0);
        assertEquals(12475.0, trend.getMonthlySavings());
        assertEquals(89320.0, trend.getCumulativeSavings());
        assertEquals(847.2, trend.getStorageReclaimed());

        verify(archivalMetricsRepository).findMetricsForLastDays(any(LocalDateTime.class));
    }

    @Test
    void testGetCostSavingsTrends_EmptyDatabase() {
        // Given
        when(archivalMetricsRepository.findMetricsForLastDays(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList());

        // When
        List<CostSavingsTrendDTO> result = archivalOverviewService.getCostSavingsTrends(7);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());

        verify(archivalMetricsRepository).findMetricsForLastDays(any(LocalDateTime.class));
    }

    @Test
    void testGetArchivalStatusData_WithData() {
        // Given
        when(archivalTaskRepository.countByStatus(ArchivalStatus.COMPLETED)).thenReturn(1L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.PENDING_APPROVAL)).thenReturn(2L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.IN_PROGRESS)).thenReturn(3L);
        when(archivalTaskRepository.countByStatus(ArchivalStatus.FAILED)).thenReturn(4L);
        when(archivalMetricsRepository.findMostRecentMetrics())
                .thenReturn(Optional.of(sampleMetrics));

        // When
        List<ArchivalStatusDataDTO> result = archivalOverviewService.getArchivalStatusData();

        // Then
        assertNotNull(result);
        assertEquals(5, result.size()); // 5 status categories

        // Verify Archived status
        ArchivalStatusDataDTO archived = result.stream()
                .filter(s -> "Archived".equals(s.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(archived);
        assertEquals(1, archived.getValue());
        assertEquals("#10b981", archived.getColor());

        verify(archivalTaskRepository).countByStatus(ArchivalStatus.COMPLETED);
        verify(archivalTaskRepository).countByStatus(ArchivalStatus.PENDING_APPROVAL);
        verify(archivalTaskRepository).countByStatus(ArchivalStatus.IN_PROGRESS);
        verify(archivalTaskRepository).countByStatus(ArchivalStatus.FAILED);
        verify(archivalMetricsRepository).findMostRecentMetrics();
    }

    @Test
    void testGetRecentActivities_WithData() {
        // Given
        List<ArchivalActivityEntity> activities = Arrays.asList(sampleActivity);
        when(archivalActivityRepository.findRecentActivities(any()))
                .thenReturn(activities);

        // When
        List<RecentActivityDTO> result = archivalOverviewService.getRecentActivities(10);

        // Then
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());

        RecentActivityDTO activity = result.get(0);
        assertEquals("activity-001", activity.getId());
        assertEquals("ASSET_ARCHIVED", activity.getEvent());
        assertEquals("test-asset.csv", activity.getAssetName());
        assertEquals("Test Policy", activity.getPolicy());
        assertEquals("2.3 GB", activity.getStorageSize());
        assertEquals(156.0, activity.getSavingsAmount());
        assertEquals("SUCCESS", activity.getStatus());
        assertNotNull(activity.getTimestamp());
        assertEquals("Test archival activity", activity.getDetails());

        verify(archivalActivityRepository).findRecentActivities(any());
    }

    @Test
    void testGetStorageConsumers_WithData() {
        // Given
        List<StorageConsumerEntity> consumers = Arrays.asList(sampleConsumer);
        when(storageConsumerRepository.findTopStorageConsumers(any()))
                .thenReturn(consumers);

        // When
        List<StorageConsumerDTO> result = archivalOverviewService.getStorageConsumers(10);

        // Then
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());

        StorageConsumerDTO consumer = result.get(0);
        assertEquals("Test Consumer", consumer.getName());
        assertEquals("45.7 TB", consumer.getCurrentSize());
        assertNotNull(consumer.getLastAccessed());
        assertEquals(2847.0, consumer.getCost());
        assertEquals("ARCHIVE_NOW", consumer.getRecommendation());
        assertEquals("HIGH", consumer.getPriority());

        verify(storageConsumerRepository).findTopStorageConsumers(any());
    }

    @Test
    void testGetArchivalOverview_WithData() {
        // Given
        when(archivalMetricsRepository.findMostRecentMetrics())
                .thenReturn(Optional.of(sampleMetrics));
        when(archivalTaskRepository.countByStatus(any())).thenReturn(0L);
        when(archivalTaskRepository.findMostRecentTasks()).thenReturn(Arrays.asList(sampleTask));
        when(archivalConfigRepository.findById(1L)).thenReturn(Optional.of(enabledConfig()));
        when(archivalMetricsRepository.findMetricsForLastDays(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(sampleMetrics));
        when(archivalActivityRepository.findRecentActivities(any()))
                .thenReturn(Arrays.asList(sampleActivity));
        when(storageConsumerRepository.findTopStorageConsumers(any()))
                .thenReturn(Arrays.asList(sampleConsumer));

        // When
        ArchivalOverviewResponseDTO result = archivalOverviewService.getArchivalOverview();

        // Then
        assertNotNull(result);
        assertNotNull(result.getKpis());
        assertNotNull(result.getCostTrends());
        assertNotNull(result.getArchivalStatusData());
        assertNotNull(result.getRecentActivities());
        assertNotNull(result.getStorageConsumers());

        // Verify all components are populated. Archival is enabled in the config row, so the
        // KPI reports one active policy (the service holds a single global config, not a policy
        // table), not the metrics aggregate's figure.
        assertEquals(1, result.getKpis().getActivePolicies());
        assertFalse(result.getCostTrends().isEmpty());
        assertFalse(result.getArchivalStatusData().isEmpty());
        assertFalse(result.getRecentActivities().isEmpty());
        assertFalse(result.getStorageConsumers().isEmpty());
    }
} 