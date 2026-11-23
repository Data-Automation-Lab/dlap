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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "dlap_discovery_connection_config",
    indexes = { @Index(name = "idx_connection_config_cloud_conn_id", columnList = "cloudConnectionId", unique = true) }
)
public class ConnectionDiscoveryConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO) // Or GenerationType.UUID if supported and preferred
    private UUID id;

    @Column(nullable = false, unique = true)
    private String cloudConnectionId; // Link to the connection ID from da-admin-service

    private Boolean isEnabled;

    private Integer scanIntervalHours;

    @Enumerated(EnumType.STRING)
    private ScanType scanType;

    @Column(columnDefinition = "jsonb") // Assuming PostgreSQL for JSONB
    @JdbcTypeCode(SqlTypes.JSON) // Hibernate 6 type mapping for Map to JSON
    private Map<String, String> customParameters;

    // New fields for DTO mapping
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> resourceTypesToInclude;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> resourceTypesToExclude;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> specificRegions;

    // When the scheduler last fired a scan for this connection. Drives the
    // per-connection scanIntervalHours cadence so the global cron doesn't
    // re-scan a connection more often than its config asks.
    @Column
    private Instant lastScanStartedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = Instant.now();
        if (this.id == null) { // Ensure ID is generated if not set by GenerationType.AUTO for some reason before persist
            this.id = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCloudConnectionId() {
        return cloudConnectionId;
    }

    public void setCloudConnectionId(String cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    public Boolean getIsEnabled() {
        return isEnabled;
    }

    public void setIsEnabled(Boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public Integer getScanIntervalHours() {
        return scanIntervalHours;
    }

    public void setScanIntervalHours(Integer scanIntervalHours) {
        this.scanIntervalHours = scanIntervalHours;
    }

    public ScanType getScanType() {
        return scanType;
    }

    public void setScanType(ScanType scanType) {
        this.scanType = scanType;
    }

    public Map<String, String> getCustomParameters() {
        return customParameters;
    }

    public void setCustomParameters(Map<String, String> customParameters) {
        this.customParameters = customParameters;
    }

    // New getters and setters for DTO mapping
    public List<String> getResourceTypesToInclude() {
        return resourceTypesToInclude;
    }

    public void setResourceTypesToInclude(List<String> resourceTypesToInclude) {
        this.resourceTypesToInclude = resourceTypesToInclude;
    }

    public List<String> getResourceTypesToExclude() {
        return resourceTypesToExclude;
    }

    public void setResourceTypesToExclude(List<String> resourceTypesToExclude) {
        this.resourceTypesToExclude = resourceTypesToExclude;
    }

    public List<String> getSpecificRegions() {
        return specificRegions;
    }

    public void setSpecificRegions(List<String> specificRegions) {
        this.specificRegions = specificRegions;
    }

    public Instant getLastScanStartedAt() {
        return lastScanStartedAt;
    }

    public void setLastScanStartedAt(Instant lastScanStartedAt) {
        this.lastScanStartedAt = lastScanStartedAt;
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
