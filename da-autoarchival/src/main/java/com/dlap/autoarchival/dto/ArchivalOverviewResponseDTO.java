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

package com.dlap.autoarchival.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Comprehensive response DTO for archival agent overview dashboard.
 * Contains all the data needed for the frontend archival agent overview page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalOverviewResponseDTO {
    
    /**
     * Key Performance Indicators for the archival system
     */
    private ArchivalKpisDTO kpis;
    
    /**
     * Historical cost savings trends
     */
    private List<CostSavingsTrendDTO> costTrends;
    
    /**
     * Distribution of assets by archival status
     */
    private List<ArchivalStatusDataDTO> archivalStatusData;
    
    /**
     * Recent archival activities
     */
    private List<RecentActivityDTO> recentActivities;
    
    /**
     * Top storage consumers
     */
    private List<StorageConsumerDTO> storageConsumers;
} 