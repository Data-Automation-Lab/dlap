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

package com.dlap.autoarchival.controller;

import com.dlap.autoarchival.dto.ArchivalKpisDTO;
import com.dlap.autoarchival.dto.ArchivalOverviewResponseDTO;
import com.dlap.autoarchival.dto.ArchivalStatusDataDTO;
import com.dlap.autoarchival.dto.CostSavingsTrendDTO;
import com.dlap.autoarchival.dto.RecentActivityDTO;
import com.dlap.autoarchival.dto.StorageConsumerDTO;
import com.dlap.autoarchival.service.IArchivalOverviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller for archival overview and dashboard functionality.
 * Provides comprehensive data for the archival agent overview page.
 * 
 * Note: Policy configuration endpoints are not included due to the given instructions.
 */
@RestController
@RequestMapping("/api/v1/archival/overview")
@RequiredArgsConstructor
@Slf4j
public class ArchivalOverviewController {

    private final IArchivalOverviewService archivalOverviewService;

    /**
     * Retrieves comprehensive overview data for the archival agent dashboard.
     * This endpoint provides all the data needed for the frontend overview page.
     * 
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @return ResponseEntity with ArchivalOverviewResponseDTO containing all dashboard data
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<ArchivalOverviewResponseDTO> getArchivalOverview() {
        log.info("Request to retrieve comprehensive archival overview data");
        try {
            ArchivalOverviewResponseDTO overview = archivalOverviewService.getArchivalOverview();
            return ResponseEntity.ok(overview);
        } catch (Exception e) {
            log.error("Error retrieving archival overview data: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieves Key Performance Indicators for the archival system.
     * 
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @return ResponseEntity with ArchivalKpisDTO containing KPI metrics
     */
    @GetMapping("/kpis")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<ArchivalKpisDTO> getArchivalKpis() {
        log.info("Request to retrieve archival KPIs");
        try {
            ArchivalKpisDTO kpis = archivalOverviewService.getArchivalKpis();
            return ResponseEntity.ok(kpis);
        } catch (Exception e) {
            log.error("Error retrieving archival KPIs: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieves historical cost savings trends.
     * 
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @param weeks Number of weeks of historical data to retrieve (default: 7)
     * @return ResponseEntity with List of CostSavingsTrendDTO representing historical trends
     */
    @GetMapping("/trends")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<CostSavingsTrendDTO>> getCostSavingsTrends(
            @RequestParam(defaultValue = "7") Integer weeks) {
        log.info("Request to retrieve cost savings trends for {} weeks", weeks);
        try {
            List<CostSavingsTrendDTO> trends = archivalOverviewService.getCostSavingsTrends(weeks);
            return ResponseEntity.ok(trends);
        } catch (Exception e) {
            log.error("Error retrieving cost savings trends: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieves distribution of assets by archival status.
     * 
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @return ResponseEntity with List of ArchivalStatusDataDTO for chart visualization
     */
    @GetMapping("/status-distribution")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<ArchivalStatusDataDTO>> getArchivalStatusData() {
        log.info("Request to retrieve archival status distribution data");
        try {
            List<ArchivalStatusDataDTO> statusData = archivalOverviewService.getArchivalStatusData();
            return ResponseEntity.ok(statusData);
        } catch (Exception e) {
            log.error("Error retrieving archival status data: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieves recent archival activities.
     * 
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @param limit Maximum number of activities to retrieve (default: 10)
     * @return ResponseEntity with List of RecentActivityDTO representing recent events
     */
    @GetMapping("/recent-activities")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<RecentActivityDTO>> getRecentActivities(
            @RequestParam(defaultValue = "10") Integer limit) {
        log.info("Request to retrieve recent archival activities (limit: {})", limit);
        try {
            List<RecentActivityDTO> activities = archivalOverviewService.getRecentActivities(limit);
            return ResponseEntity.ok(activities);
        } catch (Exception e) {
            log.error("Error retrieving recent activities: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieves top storage consumers.
     * 
     * Requires DATA_STEWARD, ADMIN, or USER role.
     *
     * @param limit Maximum number of consumers to retrieve (default: 10)
     * @return ResponseEntity with List of StorageConsumerDTO representing storage consumers
     */
    @GetMapping("/storage-consumers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<List<StorageConsumerDTO>> getStorageConsumers(
            @RequestParam(defaultValue = "10") Integer limit) {
        log.info("Request to retrieve top storage consumers (limit: {})", limit);
        try {
            List<StorageConsumerDTO> consumers = archivalOverviewService.getStorageConsumers(limit);
            return ResponseEntity.ok(consumers);
        } catch (Exception e) {
            log.error("Error retrieving storage consumers: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Triggers a manual archival run.
     * This endpoint initiates the archival process for pending assets.
     * 
     * Requires ADMIN or DATA_STEWARD role.
     *
     * @return ResponseEntity with Boolean indicating if the archival run was successfully triggered
     */
    @PostMapping("/trigger-run")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    public ResponseEntity<Boolean> triggerManualArchivalRun() {
        log.info("Request to trigger manual archival run");
        try {
            Boolean success = archivalOverviewService.triggerManualArchivalRun();
            if (success) {
                return ResponseEntity.ok(true);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(false);
            }
        } catch (Exception e) {
            log.error("Error triggering manual archival run: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(false);
        }
    }
} 