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

/**
 * DTO for archival agent KPIs (Key Performance Indicators).
 * Provides comprehensive metrics about the archival system performance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalKpisDTO {
    
    /**
     * Number of active archival policies
     */
    private Integer activePolicies;
    
    /**
     * Total number of assets identified for archival
     */
    private Integer assetsIdentified;
    
    /**
     * Total number of assets successfully archived
     */
    private Integer assetsArchived;
    
    /**
     * Estimated monthly cost savings in USD
     */
    private Double estimatedMonthlySavings;
    
    /**
     * Total cost savings to date in USD
     */
    private Double totalSavingsToDate;
    
    /**
     * Total storage reclaimed (e.g., "847.2 TB")
     */
    private String storageReclaimed;
    
    /**
     * Number of assets pending archival
     */
    private Integer pendingArchival;
    
    /**
     * Number of failed archival attempts
     */
    private Integer failedArchival;
    
    /**
     * Average time to archive an asset in minutes
     */
    private Double averageArchivalTime;
    
    /**
     * Human-readable timestamp of last policy run (e.g., "2 hours ago")
     */
    private String lastPolicyRun;
} 