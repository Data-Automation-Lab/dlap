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
import com.dlap.autolabel.exception.AutoLabelingException;
import com.dlap.autolabel.service.ILabelAgentDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for SmartLabel Agent dashboard operations.
 * Provides endpoints for retrieving dashboard data and triggering labeling operations.
 */
@RestController
@RequestMapping("/api/v1/label-agent")
@RequiredArgsConstructor
@Slf4j
public class LabelAgentDashboardController {

    private final ILabelAgentDashboardService labelAgentDashboardService;

    /**
     * Get Key Performance Indicators for the SmartLabel Agent.
     *
     * @return ResponseEntity containing LabelAgentKpis
     */
    @GetMapping("/kpis")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<LabelAgentKpis> getLabelAgentKpis() {
        try {
            log.info("GET /api/v1/label-agent/kpis - Retrieving SmartLabel Agent KPIs");
            LabelAgentKpis kpis = labelAgentDashboardService.getLabelAgentKpis();
            return ResponseEntity.ok(kpis);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving SmartLabel Agent KPIs", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving SmartLabel Agent KPIs", e);
            throw new AutoLabelingException("Failed to retrieve SmartLabel Agent KPIs", e);
        }
    }

    /**
     * Get top applied labels with their statistics.
     *
     * @param limit maximum number of labels to return (default: 10)
     * @return ResponseEntity containing list of top applied labels
     */
    @GetMapping("/top-labels")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<TopAppliedLabel>> getTopAppliedLabels(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/top-labels - Retrieving top applied labels with limit: {}", limit);
            List<TopAppliedLabel> topLabels = labelAgentDashboardService.getTopAppliedLabels(limit);
            return ResponseEntity.ok(topLabels);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving top applied labels", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving top applied labels", e);
            throw new AutoLabelingException("Failed to retrieve top applied labels", e);
        }
    }

    /**
     * Get rule activities and performance metrics.
     *
     * @param limit maximum number of rules to return (default: 10)
     * @return ResponseEntity containing list of rule activities
     */
    @GetMapping("/rule-activities")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<RuleActivity>> getRuleActivities(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/rule-activities - Retrieving rule activities with limit: {}", limit);
            List<RuleActivity> ruleActivities = labelAgentDashboardService.getRuleActivities(limit);
            return ResponseEntity.ok(ruleActivities);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving rule activities", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving rule activities", e);
            throw new AutoLabelingException("Failed to retrieve rule activities", e);
        }
    }

    /**
     * Get recent labeling activities.
     *
     * @param limit maximum number of activities to return (default: 10)
     * @return ResponseEntity containing list of recent label activities
     */
    @GetMapping("/recent-activities")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<RecentLabelActivity>> getRecentLabelActivities(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/recent-activities - Retrieving recent label activities with limit: {}", limit);
            List<RecentLabelActivity> activities = labelAgentDashboardService.getRecentLabelActivities(limit);
            return ResponseEntity.ok(activities);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving recent label activities", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving recent label activities", e);
            throw new AutoLabelingException("Failed to retrieve recent label activities", e);
        }
    }

    /**
     * Get recent activities with detailed information for the UI table.
     * This endpoint provides the specific data structure needed for the "Recent Activities" tab.
     *
     * @param limit maximum number of activities to return (default: 10)
     * @return ResponseEntity containing list of recent activities with UI-specific fields
     */
    @GetMapping("/recent-activities-detailed")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> getRecentActivitiesDetailed(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/recent-activities-detailed - Retrieving detailed recent activities with limit: {}", limit);
            List<Map<String, Object>> activities = labelAgentDashboardService.getRecentActivitiesDetailed(limit);
            return ResponseEntity.ok(activities);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving detailed recent activities", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving detailed recent activities", e);
            throw new AutoLabelingException("Failed to retrieve detailed recent activities", e);
        }
    }

    /**
     * Get top applied labels with detailed information for the UI cards.
     * This endpoint provides the specific data structure needed for the "Top Applied Labels" tab.
     *
     * @param limit maximum number of labels to return (default: 10)
     * @return ResponseEntity containing list of top labels with UI-specific fields
     */
    @GetMapping("/top-labels-detailed")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> getTopLabelsDetailed(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/top-labels-detailed - Retrieving detailed top labels with limit: {}", limit);
            List<Map<String, Object>> labels = labelAgentDashboardService.getTopLabelsDetailed(limit);
            return ResponseEntity.ok(labels);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving detailed top labels", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving detailed top labels", e);
            throw new AutoLabelingException("Failed to retrieve detailed top labels", e);
        }
    }

    /**
     * Get rule performance with detailed information for the UI table.
     * This endpoint provides the specific data structure needed for the "Rule Performance" tab.
     *
     * @param limit maximum number of rules to return (default: 10)
     * @return ResponseEntity containing list of rule performance with UI-specific fields
     */
    @GetMapping("/rule-performance")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> getRulePerformance(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/rule-performance - Retrieving rule performance with limit: {}", limit);
            List<Map<String, Object>> rules = labelAgentDashboardService.getRulePerformance(limit);
            return ResponseEntity.ok(rules);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving rule performance", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving rule performance", e);
            throw new AutoLabelingException("Failed to retrieve rule performance", e);
        }
    }

    /**
     * Get asset events and labeling activities.
     * This endpoint provides asset-specific labeling events for the UI.
     *
     * @param limit maximum number of events to return (default: 10)
     * @return ResponseEntity containing list of asset events
     */
    @GetMapping("/asset-events")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> getAssetEvents(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/asset-events - Retrieving asset events with limit: {}", limit);
            List<Map<String, Object>> events = labelAgentDashboardService.getAssetEvents(limit);
            return ResponseEntity.ok(events);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving asset events", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving asset events", e);
            throw new AutoLabelingException("Failed to retrieve asset events", e);
        }
    }

    /**
     * Get labeling methods and their statistics.
     * This endpoint provides information about different labeling methods used.
     *
     * @return ResponseEntity containing labeling methods statistics
     */
    @GetMapping("/labeling-methods")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> getLabelingMethods() {
        try {
            log.info("GET /api/v1/label-agent/labeling-methods - Retrieving labeling methods statistics");
            List<Map<String, Object>> methods = labelAgentDashboardService.getLabelingMethods();
            return ResponseEntity.ok(methods);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving labeling methods", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving labeling methods", e);
            throw new AutoLabelingException("Failed to retrieve labeling methods", e);
        }
    }

    /**
     * Get labeling status statistics.
     * This endpoint provides information about labeling job statuses.
     *
     * @return ResponseEntity containing labeling status statistics
     */
    @GetMapping("/labeling-status")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> getLabelingStatus() {
        try {
            log.info("GET /api/v1/label-agent/labeling-status - Retrieving labeling status statistics");
            List<Map<String, Object>> statuses = labelAgentDashboardService.getLabelingStatus();
            return ResponseEntity.ok(statuses);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving labeling status", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving labeling status", e);
            throw new AutoLabelingException("Failed to retrieve labeling status", e);
        }
    }

    /**
     * Trigger a manual labeling run.
     *
     * @return ResponseEntity indicating success or failure
     */
    @PostMapping("/trigger-labeling")
    @PreAuthorize("hasAnyAuthority('ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_ADMIN')")
    public ResponseEntity<Object> triggerLabelingRun() {
        try {
            log.info("POST /api/v1/label-agent/trigger-labeling - Triggering manual labeling run");
            boolean success = labelAgentDashboardService.triggerLabelingRun();
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Labeling run triggered successfully",
                    "timestamp", java.time.LocalDateTime.now()
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Failed to trigger labeling run",
                    "timestamp", java.time.LocalDateTime.now()
                ));
            }
        } catch (AutoLabelingException e) {
            log.error("Error triggering labeling run", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error triggering labeling run", e);
            throw new AutoLabelingException("Failed to trigger labeling run", e);
        }
    }

    /**
     * Get all dashboard data in a single request for efficiency.
     *
     * @param limit maximum number of items to return for each category (default: 10)
     * @return ResponseEntity containing all dashboard data
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<Map<String, Object>> getDashboardData(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            log.info("GET /api/v1/label-agent/dashboard - Retrieving complete dashboard data with limit: {}", limit);
            
            Map<String, Object> dashboardData = Map.of(
                "kpis", labelAgentDashboardService.getLabelAgentKpis(),
                "topLabels", labelAgentDashboardService.getTopAppliedLabels(limit),
                "ruleActivities", labelAgentDashboardService.getRuleActivities(limit),
                "recentActivities", labelAgentDashboardService.getRecentLabelActivities(limit),
                "timestamp", java.time.LocalDateTime.now()
            );
            
            return ResponseEntity.ok(dashboardData);
        } catch (AutoLabelingException e) {
            log.error("Error retrieving dashboard data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving dashboard data", e);
            throw new AutoLabelingException("Failed to retrieve dashboard data", e);
        }
    }
} 