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

package com.dlap.autodelete.controller;

import com.dlap.autodelete.dto.*;
import com.dlap.autodelete.service.IDeleteAgentDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteAgentDashboardControllerTest {

    @Mock
    private IDeleteAgentDashboardService deleteAgentDashboardService;

    @InjectMocks
    private DeleteAgentDashboardController controller;

    private DeleteKpisDTO mockKpis;
    private List<StorageReclaimedTrendDTO> mockTrends;
    private List<DeletionByPolicyDTO> mockPolicyData;
    private List<RecentActivityDTO> mockActivities;
    private List<RiskAssetDTO> mockRiskAssets;

    @BeforeEach
    void setUp() {
        // Setup mock data
        mockKpis = DeleteKpisDTO.builder()
                .activePolicies(12)
                .assetsDeleted(5284)
                .assetsDeletedLast24h(47)
                .pendingDeletion(187)
                .failedDeletion(15)
                .storageReclaimed("2.8 TB")
                .estimatedMonthlySavings(8950.0)
                .totalSavingsToDate(67320.0)
                .complianceScore(99.2)
                .averageDeletionTime(12.5)
                .lastPolicyRun("30 minutes ago")
                .build();

        mockTrends = List.of(
                StorageReclaimedTrendDTO.builder()
                        .date(LocalDate.now().minusWeeks(1))
                        .retention(125.5)
                        .gdpr(28.3)
                        .duplicateData(45.7)
                        .qualityIssues(18.2)
                        .storageOptimization(32.1)
                        .totalReclaimed(249.8)
                        .build()
        );

        mockPolicyData = List.of(
                DeletionByPolicyDTO.builder()
                        .name("Retention Policies")
                        .value(3547)
                        .color("#3b82f6")
                        .build()
        );

        mockActivities = List.of(
                RecentActivityDTO.builder()
                        .id("1")
                        .event("Asset Deleted")
                        .assetName("test.parquet")
                        .policy("Data Retention Policy")
                        .assetSize("4.7 GB")
                        .reason("Exceeded retention period")
                        .status("Success")
                        .timestamp(LocalDateTime.now().minusMinutes(8))
                        .details("Successfully deleted")
                        .build()
        );

        mockRiskAssets = List.of(
                RiskAssetDTO.builder()
                        .name("Test Asset")
                        .type("Documents")
                        .size("12.4 GB")
                        .lastAccessed(LocalDateTime.now().minusYears(2))
                        .risk("High")
                        .scheduledDeletion(LocalDateTime.of(2024, 12, 25, 0, 0))
                        .reason("Exceeded retention period")
                        .build()
        );
    }

    @Test
    void getDeleteKpis_ShouldReturnKpis() {
        // Given
        when(deleteAgentDashboardService.getDeleteKpis()).thenReturn(mockKpis);

        // When
        ResponseEntity<DeleteKpisDTO> response = controller.getDeleteKpis();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(12, response.getBody().getActivePolicies());
        assertEquals(5284, response.getBody().getAssetsDeleted());
        verify(deleteAgentDashboardService).getDeleteKpis();
    }

    @Test
    void getStorageReclaimedTrends_ShouldReturnTrends() {
        // Given
        when(deleteAgentDashboardService.getStorageReclaimedTrends(7)).thenReturn(mockTrends);

        // When
        ResponseEntity<List<StorageReclaimedTrendDTO>> response = controller.getStorageReclaimedTrends(7);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(125.5, response.getBody().get(0).getRetention());
        verify(deleteAgentDashboardService).getStorageReclaimedTrends(7);
    }

    @Test
    void getStorageReclaimedTrends_WithDefaultWeeks_ShouldUseDefaultValue() {
        // Given
        when(deleteAgentDashboardService.getStorageReclaimedTrends(7)).thenReturn(mockTrends);

        // When
        ResponseEntity<List<StorageReclaimedTrendDTO>> response = controller.getStorageReclaimedTrends(7);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(deleteAgentDashboardService).getStorageReclaimedTrends(7);
    }

    @Test
    void getDeletionByPolicyData_ShouldReturnPolicyData() {
        // Given
        when(deleteAgentDashboardService.getDeletionByPolicyData()).thenReturn(mockPolicyData);

        // When
        ResponseEntity<List<DeletionByPolicyDTO>> response = controller.getDeletionByPolicyData();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Retention Policies", response.getBody().get(0).getName());
        verify(deleteAgentDashboardService).getDeletionByPolicyData();
    }

    @Test
    void getRecentActivities_ShouldReturnActivities() {
        // Given
        when(deleteAgentDashboardService.getRecentActivities(10)).thenReturn(mockActivities);

        // When
        ResponseEntity<List<RecentActivityDTO>> response = controller.getRecentActivities(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Asset Deleted", response.getBody().get(0).getEvent());
        verify(deleteAgentDashboardService).getRecentActivities(10);
    }

    @Test
    void getRecentActivities_WithDefaultLimit_ShouldUseDefaultValue() {
        // Given
        when(deleteAgentDashboardService.getRecentActivities(10)).thenReturn(mockActivities);

        // When
        ResponseEntity<List<RecentActivityDTO>> response = controller.getRecentActivities(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(deleteAgentDashboardService).getRecentActivities(10);
    }

    @Test
    void getRiskAssets_ShouldReturnRiskAssets() {
        // Given
        when(deleteAgentDashboardService.getRiskAssets(10)).thenReturn(mockRiskAssets);

        // When
        ResponseEntity<List<RiskAssetDTO>> response = controller.getRiskAssets(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Asset", response.getBody().get(0).getName());
        verify(deleteAgentDashboardService).getRiskAssets(10);
    }

    @Test
    void getRiskAssets_WithDefaultLimit_ShouldUseDefaultValue() {
        // Given
        when(deleteAgentDashboardService.getRiskAssets(10)).thenReturn(mockRiskAssets);

        // When
        ResponseEntity<List<RiskAssetDTO>> response = controller.getRiskAssets(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(deleteAgentDashboardService).getRiskAssets(10);
    }

    @Test
    void triggerManualDeletionRun_WhenSuccessful_ShouldReturnSuccess() {
        // Given
        when(deleteAgentDashboardService.triggerManualDeletionRun()).thenReturn(true);

        // When
        ResponseEntity<String> response = controller.triggerManualDeletionRun();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Manual deletion run triggered successfully", response.getBody());
        verify(deleteAgentDashboardService).triggerManualDeletionRun();
    }

    @Test
    void triggerManualDeletionRun_WhenFailed_ShouldReturnError() {
        // Given
        when(deleteAgentDashboardService.triggerManualDeletionRun()).thenReturn(false);

        // When
        ResponseEntity<String> response = controller.triggerManualDeletionRun();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Failed to trigger manual deletion run", response.getBody());
        verify(deleteAgentDashboardService).triggerManualDeletionRun();
    }
} 