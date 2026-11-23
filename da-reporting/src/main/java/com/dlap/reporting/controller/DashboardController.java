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

package com.dlap.reporting.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.common.security.SecurityUtils;
import com.dlap.reporting.dto.ActionItemsDTO;
import com.dlap.reporting.dto.CostSavingsDTO;
import com.dlap.reporting.dto.GovernanceScoreDTO;
import com.dlap.reporting.service.IDashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for DLAP Dashboard Aggregation APIs
 * Provides cross-service aggregation for role-based dashboards
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
@RestController
@RequestMapping("/api/v1/reporting/dashboards")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Dashboard aggregation and analytics APIs")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final IDashboardService dashboardService;

    /**
     * Get governance score dashboard for CDO/VP and Director roles
     * Aggregates data from da-catalog, da-autocompliance, and da-policyengine
     * 
     * @param includeHistory whether to include 30-day historical trend data
     * @param includeBenchmark whether to include industry benchmark comparison
     * @return comprehensive governance score with breakdown and trends
     */
    @GetMapping("/cdo/governance-score")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')")
    @Cacheable(value = "governanceScore", key = "#includeHistory + '_' + #includeBenchmark")
    @Operation(summary = "Get governance score dashboard", 
               description = "Provides comprehensive governance metrics with weighted scoring across policy compliance, data classification, metadata completeness, and lineage coverage")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Governance score retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Access denied - insufficient privileges"),
        @ApiResponse(responseCode = "500", description = "Internal server error during cross-service aggregation")
    })
    public ResponseEntity<GovernanceScoreDTO> getGovernanceScore(
            @Parameter(description = "Include 30-day historical trend data") 
            @RequestParam(defaultValue = "true") Boolean includeHistory,
            @Parameter(description = "Include industry benchmark comparison") 
            @RequestParam(defaultValue = "true") Boolean includeBenchmark) {
        
        log.info("REST request to get governance score dashboard by user: {}", SecurityUtils.getAuthenticatedUserId());
        GovernanceScoreDTO governanceScore = dashboardService.getGovernanceScore(includeHistory, includeBenchmark);
        return ResponseEntity.ok(governanceScore);
    }

    /**
     * Get cost savings dashboard for CDO/VP and executive roles
     * Aggregates data from da-autoarchival, da-autodelete, and cloud provider APIs
     * 
     * @param includeProjections whether to include future savings projections
     * @param timeframe analysis timeframe: "30_DAYS", "90_DAYS", "12_MONTHS", "ALL_TIME"
     * @return comprehensive cost savings analysis with ROI calculation
     */
    @GetMapping("/cdo/cost-savings")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')")
    @Cacheable(value = "costSavings", key = "#includeProjections + '_' + #timeframe")
    @Operation(summary = "Get cost savings dashboard", 
               description = "Provides comprehensive cost analysis including archival savings, deletion reclamation, and ROI calculations across multiple cloud providers")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cost savings data retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Access denied - insufficient privileges"),
        @ApiResponse(responseCode = "500", description = "Internal server error during cost calculation"),
        @ApiResponse(responseCode = "503", description = "Cloud provider API temporarily unavailable")
    })
    public ResponseEntity<CostSavingsDTO> getCostSavings(
            @Parameter(description = "Include future savings projections") 
            @RequestParam(defaultValue = "true") Boolean includeProjections,
            @Parameter(description = "Analysis timeframe") 
            @RequestParam(defaultValue = "12_MONTHS") String timeframe) {
        
        log.info("REST request to get cost savings dashboard for timeframe: {} by user: {}", timeframe, SecurityUtils.getAuthenticatedUserId());
        CostSavingsDTO costSavings = dashboardService.getCostSavings(includeProjections, timeframe);
        return ResponseEntity.ok(costSavings);
    }

    /**
     * Get aggregated action items across all DLAP services
     * Aggregates data from da-autocompliance, da-policyengine, da-catalog, da-discovery
     * 
     * @param assignedToUser filter to show only items assigned to current user
     * @param severity filter by severity level: "CRITICAL", "HIGH", "MEDIUM", "LOW"
     * @param status filter by status: "OPEN", "IN_PROGRESS", "PENDING_APPROVAL"
     * @param limit maximum number of action items to return (default 50, max 200)
     * @return comprehensive action items aggregation with SLA and business impact metrics
     */
    @GetMapping("/cdo/action-items")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR', 'DATA_ENGINEER', 'DATA_SCIENTIST')")
    @Cacheable(value = "actionItems", key = "#assignedToUser + '_' + #severity + '_' + #status + '_' + #limit")
    @Operation(summary = "Get aggregated action items dashboard", 
               description = "Provides comprehensive action items aggregation across all DLAP services with SLA compliance metrics and business impact assessment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Action items retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid filter parameters"),
        @ApiResponse(responseCode = "403", description = "Access denied - insufficient privileges"),
        @ApiResponse(responseCode = "500", description = "Internal server error during aggregation")
    })
    public ResponseEntity<ActionItemsDTO> getActionItems(
            @Parameter(description = "Filter to show only items assigned to current user") 
            @RequestParam(defaultValue = "false") Boolean assignedToUser,
            @Parameter(description = "Filter by severity level") 
            @RequestParam(required = false) String severity,
            @Parameter(description = "Filter by status") 
            @RequestParam(required = false) String status,
            @Parameter(description = "Maximum number of items to return") 
            @RequestParam(defaultValue = "50") Integer limit) {
        
        log.info("REST request to get action items dashboard with filters - assignedToUser: {}, severity: {}, status: {}, limit: {} by user: {}", 
                assignedToUser, severity, status, limit, SecurityUtils.getAuthenticatedUserId());
        
        // Validate limit parameter
        if (limit > 200) {
            limit = 200;
        }
        
        ActionItemsDTO actionItems = dashboardService.getActionItems(assignedToUser, severity, status, limit);
        return ResponseEntity.ok(actionItems);
    }

    /**
     * Refresh dashboard cache for better performance
     * Useful for scheduled cache warming or manual refresh
     */
    @GetMapping("/cache/refresh")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO')")
    @Operation(summary = "Refresh dashboard cache", 
               description = "Manually triggers cache refresh for all dashboard endpoints to ensure fresh data")
    @ApiResponse(responseCode = "200", description = "Cache refresh initiated successfully")
    public ResponseEntity<String> refreshDashboardCache() {
        log.info("REST request to refresh dashboard cache by user: {}", SecurityUtils.getAuthenticatedUserId());
        dashboardService.refreshAllCaches();
        return ResponseEntity.ok("Dashboard cache refresh initiated successfully");
    }
} 