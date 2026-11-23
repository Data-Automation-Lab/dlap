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

package com.dlap.autocompliance.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO representing compliance KPIs for dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceKpisDTO {
    private double overallComplianceScore;
    private int activeChecks;
    private int violationsLast24h;
    private int violationsLast7Days;
    private int criticalViolations;
    private int resolvedViolations;
    private double averageResolutionTime;
    /**
     * Indicates whether {@link #averageResolutionTime} reflects a real measurement.
     * It is {@code false} until per-violation creation/resolution timestamp
     * tracking exists; consumers must not treat a {@code 0.0}
     * {@code averageResolutionTime} as a measured value while this is {@code false}.
     */
    private boolean resolutionMetricsAvailable;
    private String lastScanTime;
    private int regulationsMonitored;
    private int highRiskAssets;
} 