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

package com.dlap.reporting.service;

import com.dlap.reporting.dto.ActionItemsDTO;
import com.dlap.reporting.dto.CostSavingsDTO;
import com.dlap.reporting.dto.GovernanceScoreDTO;

/**
 * Service interface for DLAP Dashboard Aggregation operations
 * Provides cross-service data aggregation for role-based dashboards
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
public interface IDashboardService {

    /**
     * Calculate comprehensive governance score with weighted metrics
     * Aggregates data from da-catalog, da-autocompliance, and da-policyengine
     * 
     * @param includeHistory whether to include 30-day historical trend data
     * @param includeBenchmark whether to include industry benchmark comparison
     * @return governance score with breakdown, trends, and recommendations
     */
    GovernanceScoreDTO getGovernanceScore(Boolean includeHistory, Boolean includeBenchmark);

    /**
     * Calculate comprehensive cost savings analysis
     * Aggregates data from da-autoarchival, da-autodelete, and cloud provider APIs
     * 
     * @param includeProjections whether to include future savings projections
     * @param timeframe analysis timeframe: "30_DAYS", "90_DAYS", "12_MONTHS", "ALL_TIME"
     * @return cost savings analysis with ROI calculation and opportunities
     */
    CostSavingsDTO getCostSavings(Boolean includeProjections, String timeframe);

    /**
     * Aggregate action items from all DLAP services
     * Collects data from da-autocompliance, da-policyengine, da-catalog, da-discovery
     * 
     * @param assignedToUser filter to show only items assigned to current user
     * @param severity filter by severity level
     * @param status filter by status
     * @param limit maximum number of action items to return
     * @return aggregated action items with SLA and business impact metrics
     */
    ActionItemsDTO getActionItems(Boolean assignedToUser, String severity, String status, Integer limit);

    /**
     * Refresh all dashboard caches for performance optimization
     * Triggers cache warming for common dashboard queries
     */
    void refreshAllCaches();
} 