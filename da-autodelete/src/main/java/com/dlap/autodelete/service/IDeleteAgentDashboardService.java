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

package com.dlap.autodelete.service;

import com.dlap.autodelete.dto.*;

import java.util.List;

/**
 * Service interface for Delete Agent dashboard functionality.
 */
public interface IDeleteAgentDashboardService {

    /**
     * Retrieves key performance indicators for the Delete Agent.
     * @return Delete Agent KPIs
     */
    DeleteKpisDTO getDeleteKpis();

    /**
     * Retrieves storage reclaimed trends over time.
     * @param weeks Number of weeks to retrieve (default 7)
     * @return List of storage reclaimed trends
     */
    List<StorageReclaimedTrendDTO> getStorageReclaimedTrends(int weeks);

    /**
     * Retrieves deletion statistics by policy type.
     * @return List of deletion by policy data
     */
    List<DeletionByPolicyDTO> getDeletionByPolicyData();

    /**
     * Retrieves recent deletion activities.
     * @param limit Maximum number of activities to retrieve (default 10)
     * @return List of recent activities
     */
    List<RecentActivityDTO> getRecentActivities(int limit);

    /**
     * Retrieves assets at risk of deletion.
     * @param limit Maximum number of assets to retrieve (default 10)
     * @return List of risk assets
     */
    List<RiskAssetDTO> getRiskAssets(int limit);

    /**
     * Triggers a manual deletion run.
     * @return Success status
     */
    boolean triggerManualDeletionRun();

    /**
     * Retrieves total storage reclaimed through deletion operations.
     * @return Total storage reclaimed data
     */
    TotalStorageReclaimedDTO getTotalStorageReclaimed();

    /**
     * Retrieves total cost savings achieved through deletion operations.
     * @return Total cost savings data
     */
    TotalCostSavingsDTO getTotalCostSavings();
} 