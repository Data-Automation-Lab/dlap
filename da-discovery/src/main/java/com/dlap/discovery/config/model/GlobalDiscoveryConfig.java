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

package com.dlap.discovery.config.model;

import com.dlap.discovery.client.rest.dto.DiscoveryScanRequest.ScanType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dlap_discovery_global_config")
public class GlobalDiscoveryConfig {

    public static final String GLOBAL_CONFIG_KEY_VALUE = "GLOBAL_CONFIG_SINGLETON";

    @Id
    private String configKey; // e.g., "GLOBAL_CONFIG_SINGLETON"

    private Integer defaultScanIntervalHours;

    @Enumerated(EnumType.STRING)
    private ScanType defaultScanType;

    private Integer maxConcurrentScans;

    private Integer scanHistoryRetentionDays;

    // New fields for DTO mapping
    private Boolean enableAutoRemediation;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> defaultResourceTypesToExclude;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> globalCrawlerProperties;

    private String defaultOutputLocation;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // Default constructor for JPA
    public GlobalDiscoveryConfig() {}

    public GlobalDiscoveryConfig(String configKey) {
        this.configKey = configKey;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

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

    // New getters and setters for DTO mapping
    public Boolean getEnableAutoRemediation() {
        return enableAutoRemediation;
    }

    public void setEnableAutoRemediation(Boolean enableAutoRemediation) {
        this.enableAutoRemediation = enableAutoRemediation;
    }

    public List<String> getDefaultResourceTypesToExclude() {
        return defaultResourceTypesToExclude;
    }

    public void setDefaultResourceTypesToExclude(List<String> defaultResourceTypesToExclude) {
        this.defaultResourceTypesToExclude = defaultResourceTypesToExclude;
    }

    public Map<String, String> getGlobalCrawlerProperties() {
        return globalCrawlerProperties;
    }

    public void setGlobalCrawlerProperties(Map<String, String> globalCrawlerProperties) {
        this.globalCrawlerProperties = globalCrawlerProperties;
    }

    public String getDefaultOutputLocation() {
        return defaultOutputLocation;
    }

    public void setDefaultOutputLocation(String defaultOutputLocation) {
        this.defaultOutputLocation = defaultOutputLocation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
