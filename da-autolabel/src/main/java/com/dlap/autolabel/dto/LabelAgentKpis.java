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

package com.dlap.autolabel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing Key Performance Indicators for the SmartLabel Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelAgentKpis {
    
    /**
     * Number of active ML models
     */
    private Integer activeModels;
    
    /**
     * Total number of labels applied across all time
     */
    private Integer appliedLabelsTotal;
    
    /**
     * Number of labels applied in the last 24 hours
     */
    private Integer appliedLabelsLast24h;
    
    /**
     * ML model accuracy percentage
     */
    private Double modelAccuracy;
    
    /**
     * Number of active rule-based policies
     */
    private Integer rulePoliciesActive;
    
    /**
     * Number of active ML-based policies
     */
    private Integer mlPoliciesActive;
    
    /**
     * Total number of assets processed
     */
    private Integer assetsProcessed;
    
    /**
     * Number of assets processed in the last 24 hours
     */
    private Integer assetsProcessedLast24h;
    
    /**
     * Average processing time in seconds
     */
    private Double averageProcessingTime;
    
    /**
     * Error rate percentage
     */
    private Double errorRate;
} 