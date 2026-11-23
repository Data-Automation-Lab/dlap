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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for Delete Agent dashboard functionality.
 */
@RestController
@RequestMapping("/api/v1/delete-agent/dashboard")
@Tag(name = "Delete Agent Dashboard API", description = "APIs for Delete Agent dashboard data")
@RequiredArgsConstructor
public class DeleteAgentDashboardController {

    private final IDeleteAgentDashboardService deleteAgentDashboardService;

    @Operation(summary = "Get Delete Agent KPIs",
               description = "Retrieves key performance indicators for the Delete Agent",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved KPIs"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/kpis")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<DeleteKpisDTO> getDeleteKpis() {
        DeleteKpisDTO kpis = deleteAgentDashboardService.getDeleteKpis();
        return ResponseEntity.ok(kpis);
    }

    @Operation(summary = "Get storage reclaimed trends",
               description = "Retrieves storage reclaimed trends over time",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved trends"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/trends/storage-reclaimed")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<List<StorageReclaimedTrendDTO>> getStorageReclaimedTrends(
            @Parameter(description = "Number of weeks to retrieve (default: 7)")
            @RequestParam(defaultValue = "7") int weeks) {
        List<StorageReclaimedTrendDTO> trends = deleteAgentDashboardService.getStorageReclaimedTrends(weeks);
        return ResponseEntity.ok(trends);
    }

    @Operation(summary = "Get deletion by policy data",
               description = "Retrieves deletion statistics grouped by policy type",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved policy data"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/policies/deletion-stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<List<DeletionByPolicyDTO>> getDeletionByPolicyData() {
        List<DeletionByPolicyDTO> policyData = deleteAgentDashboardService.getDeletionByPolicyData();
        return ResponseEntity.ok(policyData);
    }

    @Operation(summary = "Get recent activities",
               description = "Retrieves recent deletion activities",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved activities"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/activities/recent")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<List<RecentActivityDTO>> getRecentActivities(
            @Parameter(description = "Maximum number of activities to retrieve (default: 10)")
            @RequestParam(defaultValue = "10") int limit) {
        List<RecentActivityDTO> activities = deleteAgentDashboardService.getRecentActivities(limit);
        return ResponseEntity.ok(activities);
    }

    @Operation(summary = "Get risk assets",
               description = "Retrieves assets at risk of deletion",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved risk assets"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/assets/risk")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<List<RiskAssetDTO>> getRiskAssets(
            @Parameter(description = "Maximum number of assets to retrieve (default: 10)")
            @RequestParam(defaultValue = "10") int limit) {
        List<RiskAssetDTO> riskAssets = deleteAgentDashboardService.getRiskAssets(limit);
        return ResponseEntity.ok(riskAssets);
    }

    @Operation(summary = "Trigger manual deletion run",
               description = "Triggers a manual deletion run",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Manual deletion run triggered successfully"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @PostMapping("/trigger-run")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO')")
    public ResponseEntity<String> triggerManualDeletionRun() {
        boolean success = deleteAgentDashboardService.triggerManualDeletionRun();
        if (success) {
            return ResponseEntity.ok("Manual deletion run triggered successfully");
        } else {
            return ResponseEntity.internalServerError().body("Failed to trigger manual deletion run");
        }
    }

    @Operation(summary = "Get total storage reclaimed",
               description = "Retrieves total storage space reclaimed through deletion operations",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved total storage reclaimed"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/storage/total-reclaimed")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<TotalStorageReclaimedDTO> getTotalStorageReclaimed() {
        TotalStorageReclaimedDTO totalStorage = deleteAgentDashboardService.getTotalStorageReclaimed();
        return ResponseEntity.ok(totalStorage);
    }

    @Operation(summary = "Get total cost savings",
               description = "Retrieves total cost savings achieved through deletion operations",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved total cost savings"),
                   @ApiResponse(responseCode = "500", description = "Internal server error")
               })
    @GetMapping("/costs/total-savings")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DATA_ENGINEER')")
    public ResponseEntity<TotalCostSavingsDTO> getTotalCostSavings() {
        TotalCostSavingsDTO totalSavings = deleteAgentDashboardService.getTotalCostSavings();
        return ResponseEntity.ok(totalSavings);
    }
} 