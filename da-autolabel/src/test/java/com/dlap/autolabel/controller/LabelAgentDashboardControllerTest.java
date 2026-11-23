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

package com.dlap.autolabel.controller;

import com.dlap.autolabel.dto.LabelAgentKpis;
import com.dlap.autolabel.dto.RecentLabelActivity;
import com.dlap.autolabel.dto.RuleActivity;
import com.dlap.autolabel.dto.TopAppliedLabel;
import com.dlap.autolabel.service.ILabelAgentDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Test class for LabelAgentDashboardController.
 */
@ExtendWith(MockitoExtension.class)
class LabelAgentDashboardControllerTest {

    @Mock
    private ILabelAgentDashboardService labelAgentDashboardService;

    @InjectMocks
    private LabelAgentDashboardController controller;

    private LabelAgentKpis mockKpis;
    private List<TopAppliedLabel> mockTopLabels;
    private List<RuleActivity> mockRuleActivities;
    private List<RecentLabelActivity> mockRecentActivities;

    @BeforeEach
    void setUp() {
        // Setup mock data
        mockKpis = LabelAgentKpis.builder()
                .activeModels(5)
                .appliedLabelsTotal(2847)
                .appliedLabelsLast24h(156)
                .modelAccuracy(94.7)
                .rulePoliciesActive(12)
                .mlPoliciesActive(5)
                .assetsProcessed(1247)
                .assetsProcessedLast24h(89)
                .averageProcessingTime(2.3)
                .errorRate(1.2)
                .build();

        mockTopLabels = Arrays.asList(
                TopAppliedLabel.builder()
                        .name("PII")
                        .value(487)
                        .category("Security")
                        .color("#ef4444")
                        .build(),
                TopAppliedLabel.builder()
                        .name("Financial")
                        .value(356)
                        .category("Classification")
                        .color("#3b82f6")
                        .build()
        );

        mockRuleActivities = Arrays.asList(
                RuleActivity.builder()
                        .ruleName("Credit Card Detection")
                        .category("PII")
                        .hitCount(142)
                        .successRate(98.5)
                        .lastTriggered("15 minutes ago")
                        .assetsAffected(23)
                        .build()
        );

        mockRecentActivities = Arrays.asList(
                RecentLabelActivity.builder()
                        .id("1")
                        .event("Labels Applied")
                        .assetName("customer_payments.csv")
                        .labelsApplied(Arrays.asList("PII", "Financial", "Customer Data"))
                        .method("ML Model")
                        .confidence(97.8)
                        .status("Success")
                        .timestamp("5 minutes ago")
                        .details("Detected credit card numbers and customer IDs")
                        .build()
        );
    }

    @Test
    void testGetLabelAgentKpis() {
        // Given
        when(labelAgentDashboardService.getLabelAgentKpis()).thenReturn(mockKpis);

        // When
        ResponseEntity<LabelAgentKpis> response = controller.getLabelAgentKpis();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(mockKpis.getActiveModels(), response.getBody().getActiveModels());
        assertEquals(mockKpis.getAppliedLabelsTotal(), response.getBody().getAppliedLabelsTotal());
        assertEquals(mockKpis.getModelAccuracy(), response.getBody().getModelAccuracy());
    }

    @Test
    void testGetTopAppliedLabels() {
        // Given
        when(labelAgentDashboardService.getTopAppliedLabels(10)).thenReturn(mockTopLabels);

        // When
        ResponseEntity<List<TopAppliedLabel>> response = controller.getTopAppliedLabels(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("PII", response.getBody().get(0).getName());
        assertEquals(487, response.getBody().get(0).getValue());
    }

    @Test
    void testGetTopAppliedLabelsWithDefaultLimit() {
        // Given
        when(labelAgentDashboardService.getTopAppliedLabels(10)).thenReturn(mockTopLabels);

        // When
        ResponseEntity<List<TopAppliedLabel>> response = controller.getTopAppliedLabels(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetRuleActivities() {
        // Given
        when(labelAgentDashboardService.getRuleActivities(10)).thenReturn(mockRuleActivities);

        // When
        ResponseEntity<List<RuleActivity>> response = controller.getRuleActivities(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Credit Card Detection", response.getBody().get(0).getRuleName());
        assertEquals(98.5, response.getBody().get(0).getSuccessRate());
    }

    @Test
    void testGetRecentLabelActivities() {
        // Given
        when(labelAgentDashboardService.getRecentLabelActivities(10)).thenReturn(mockRecentActivities);

        // When
        ResponseEntity<List<RecentLabelActivity>> response = controller.getRecentLabelActivities(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("customer_payments.csv", response.getBody().get(0).getAssetName());
        assertEquals("ML Model", response.getBody().get(0).getMethod());
    }

    @Test
    void testTriggerLabelingRun() {
        // Given
        when(labelAgentDashboardService.triggerLabelingRun()).thenReturn(true);

        // When
        ResponseEntity<Object> response = controller.triggerLabelingRun();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
        assertTrue((Boolean) responseBody.get("success"));
        assertEquals("Labeling run triggered successfully", responseBody.get("message"));
    }

    @Test
    void testTriggerLabelingRunFailure() {
        // Given
        when(labelAgentDashboardService.triggerLabelingRun()).thenReturn(false);

        // When
        ResponseEntity<Object> response = controller.triggerLabelingRun();

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
        assertFalse((Boolean) responseBody.get("success"));
        assertEquals("Failed to trigger labeling run", responseBody.get("message"));
    }

    @Test
    void testGetDashboardData() {
        // Given
        when(labelAgentDashboardService.getLabelAgentKpis()).thenReturn(mockKpis);
        when(labelAgentDashboardService.getTopAppliedLabels(10)).thenReturn(mockTopLabels);
        when(labelAgentDashboardService.getRuleActivities(10)).thenReturn(mockRuleActivities);
        when(labelAgentDashboardService.getRecentLabelActivities(10)).thenReturn(mockRecentActivities);

        // When
        ResponseEntity<Map<String, Object>> response = controller.getDashboardData(10);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        Map<String, Object> dashboardData = response.getBody();
        assertNotNull(dashboardData.get("kpis"));
        assertNotNull(dashboardData.get("topLabels"));
        assertNotNull(dashboardData.get("ruleActivities"));
        assertNotNull(dashboardData.get("recentActivities"));
        assertNotNull(dashboardData.get("timestamp"));
    }
} 