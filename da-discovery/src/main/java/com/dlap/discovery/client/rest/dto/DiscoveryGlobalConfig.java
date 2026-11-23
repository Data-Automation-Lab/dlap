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

package com.dlap.discovery.client.rest.dto;

import com.dlap.discovery.client.rest.dto.DiscoveryScanRequest.ScanType;
import jakarta.validation.constraints.Min;

public class DiscoveryGlobalConfig {

    @Min(1)
    private Integer defaultScanIntervalHours; // e.g., 24

    private ScanType defaultScanType; // e.g., INCREMENTAL

    @Min(1)
    private Integer maxConcurrentScans; // e.g., 5

    @Min(1)
    private Integer scanHistoryRetentionDays; // e.g., 90

    // Getters and Setters
    public Integer getDefaultScanIntervalHours() {
        return defaultScanIntervalHours;
    }

    public void setDefaultScanIntervalHours(Integer defaultScanIntervalHours) {
        this.defaultScanIntervalHours = defaultScanIntervalHours;
    }

    public ScanType getDefaultScanType() {
        return defaultScanType;
    }

    public void setDefaultScanType(ScanType defaultScanType) {
        this.defaultScanType = defaultScanType;
    }

    public Integer getMaxConcurrentScans() {
        return maxConcurrentScans;
    }

    public void setMaxConcurrentScans(Integer maxConcurrentScans) {
        this.maxConcurrentScans = maxConcurrentScans;
    }

    public Integer getScanHistoryRetentionDays() {
        return scanHistoryRetentionDays;
    }

    public void setScanHistoryRetentionDays(Integer scanHistoryRetentionDays) {
        this.scanHistoryRetentionDays = scanHistoryRetentionDays;
    }
}
