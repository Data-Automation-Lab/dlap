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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiscoveryStatsResponse {

    private Long totalScansInitiated;
    private Integer scansInProgress;
    private Integer scansCompletedLast24h;
    private Integer scansFailedLast24h;
    private Double avgScanDurationSeconds;
    private Long totalAssetsDiscoveredByDiscoveryService; // Clarified name
    private Long totalAssetsInCentralCatalog;
    private Map<String, ProviderScanStats> providerSpecificStats;

    // Getters and Setters
    public Long getTotalScansInitiated() {
        return totalScansInitiated;
    }

    public void setTotalScansInitiated(Long totalScansInitiated) {
        this.totalScansInitiated = totalScansInitiated;
    }

    public Integer getScansInProgress() {
        return scansInProgress;
    }

    public void setScansInProgress(Integer scansInProgress) {
        this.scansInProgress = scansInProgress;
    }

    public Integer getScansCompletedLast24h() {
        return scansCompletedLast24h;
    }

    public void setScansCompletedLast24h(Integer scansCompletedLast24h) {
        this.scansCompletedLast24h = scansCompletedLast24h;
    }

    public Integer getScansFailedLast24h() {
        return scansFailedLast24h;
    }

    public void setScansFailedLast24h(Integer scansFailedLast24h) {
        this.scansFailedLast24h = scansFailedLast24h;
    }

    public Double getAvgScanDurationSeconds() {
        return avgScanDurationSeconds;
    }

    public void setAvgScanDurationSeconds(Double avgScanDurationSeconds) {
        this.avgScanDurationSeconds = avgScanDurationSeconds;
    }

    public Long getTotalAssetsDiscoveredByDiscoveryService() {
        return totalAssetsDiscoveredByDiscoveryService;
    }

    public void setTotalAssetsDiscoveredByDiscoveryService(Long totalAssetsDiscoveredByDiscoveryService) {
        this.totalAssetsDiscoveredByDiscoveryService = totalAssetsDiscoveredByDiscoveryService;
    }

    public Long getTotalAssetsInCentralCatalog() {
        return totalAssetsInCentralCatalog;
    }

    public void setTotalAssetsInCentralCatalog(Long totalAssetsInCentralCatalog) {
        this.totalAssetsInCentralCatalog = totalAssetsInCentralCatalog;
    }

    public Map<String, ProviderScanStats> getProviderSpecificStats() {
        return providerSpecificStats;
    }

    public void setProviderSpecificStats(Map<String, ProviderScanStats> providerSpecificStats) {
        this.providerSpecificStats = providerSpecificStats;
    }

    // Nested DTO for ProviderScanStats
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProviderScanStats {

        private String providerName;
        private Long totalScans;
        private Long assetsDiscovered;

        public ProviderScanStats(String providerName, Long totalScans, Long assetsDiscovered) {
            this.providerName = providerName;
            this.totalScans = totalScans;
            this.assetsDiscovered = assetsDiscovered;
        }

        // Getters and Setters
        public String getProviderName() {
            return providerName;
        }

        public void setProviderName(String providerName) {
            this.providerName = providerName;
        }

        public Long getTotalScans() {
            return totalScans;
        }

        public void setTotalScans(Long totalScans) {
            this.totalScans = totalScans;
        }

        public Long getAssetsDiscovered() {
            return assetsDiscovered;
        }

        public void setAssetsDiscovered(Long assetsDiscovered) {
            this.assetsDiscovered = assetsDiscovered;
        }
    }
}
