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

import java.util.List;

/**
 * Service interface for archival overview and dashboard functionality.
 * Provides comprehensive data for the archival agent overview page.
 */
public interface IArchivalOverviewService {

    /**
     * Retrieves comprehensive overview data for the archival agent dashboard.
     *
     * @return ArchivalOverviewResponseDTO containing all dashboard data
     */
    ArchivalOverviewResponseDTO getArchivalOverview();

    /**
     * Retrieves Key Performance Indicators for the archival system.
     *
     * @return ArchivalKpisDTO containing KPI metrics
     */
    ArchivalKpisDTO getArchivalKpis();

    /**
     * Retrieves historical cost savings trends.
     *
     * @param weeks Number of weeks of historical data to retrieve (default: 7)
     * @return List of CostSavingsTrendDTO representing historical trends
     */
    List<CostSavingsTrendDTO> getCostSavingsTrends(Integer weeks);

    /**
     * Retrieves distribution of assets by archival status.
     *
     * @return List of ArchivalStatusDataDTO for chart visualization
     */
    List<ArchivalStatusDataDTO> getArchivalStatusData();

    /**
     * Retrieves recent archival activities.
     *
     * @param limit Maximum number of activities to retrieve (default: 10)
     * @return List of RecentActivityDTO representing recent events
     */
    List<RecentActivityDTO> getRecentActivities(Integer limit);

    /**
     * Retrieves top storage consumers.
     *
     * @param limit Maximum number of consumers to retrieve (default: 10)
     * @return List of StorageConsumerDTO representing storage consumers
     */
    List<StorageConsumerDTO> getStorageConsumers(Integer limit);

    /**
     * Triggers a manual archival run.
     * This method initiates the archival process for pending assets.
     *
     * @return Boolean indicating if the archival run was successfully triggered
     */
    Boolean triggerManualArchivalRun();
} 