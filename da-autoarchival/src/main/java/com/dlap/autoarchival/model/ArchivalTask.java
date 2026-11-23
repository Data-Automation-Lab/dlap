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

package com.dlap.autoarchival.model;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Represents an archival task.
 * This is a placeholder and would typically be a JPA entity or a more detailed DTO.
 */
public class ArchivalTask {

    private String taskId;
    private String assetId; // ID of the asset to be archived/restored
    private String originalDataSourceProvider; // e.g., "AWS", "GCP", "AZURE"
    private String originalDataSourceDetails; // e.g., S3 bucket/key, GCS object path
    private ArchivalStatus status;

    // Fields to store results from ICloudArchivalProvider
    private String archiveId; // From ArchivalResult
    private String archiveStorageLocation; // From ArchivalResult
    private String restoreJobId; // From RestoreResult
    private OffsetDateTime lastArchivedAt;
    private OffsetDateTime lastRestoreInitiatedAt;

    private Map<String, String> providerConfiguration; // To store provider specific config like target bucket/storage class for THIS task


    public enum ArchivalStatus {
        PENDING_ARCHIVAL,
        ARCHIVAL_IN_PROGRESS,
        ARCHIVED,
        PENDING_RESTORATION,
        RESTORATION_IN_PROGRESS,
        RESTORED,
        FAILED_ARCHIVAL,
        FAILED_RESTORATION
    }

    // Constructors, Getters, Setters
    public ArchivalTask(String taskId, String assetId) {
        this.taskId = taskId;
        this.assetId = assetId;
    }
    
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getOriginalDataSourceProvider() { return originalDataSourceProvider; }
    public void setOriginalDataSourceProvider(String originalDataSourceProvider) { this.originalDataSourceProvider = originalDataSourceProvider; }
    public String getOriginalDataSourceDetails() { return originalDataSourceDetails; }
    public void setOriginalDataSourceDetails(String originalDataSourceDetails) { this.originalDataSourceDetails = originalDataSourceDetails; }
    public ArchivalStatus getStatus() { return status; }
    public void setStatus(ArchivalStatus status) { this.status = status; }
    public String getArchiveId() { return archiveId; }
    public void setArchiveId(String archiveId) { this.archiveId = archiveId; }
    public String getArchiveStorageLocation() { return archiveStorageLocation; }
    public void setArchiveStorageLocation(String archiveStorageLocation) { this.archiveStorageLocation = archiveStorageLocation; }
    public String getRestoreJobId() { return restoreJobId; }
    public void setRestoreJobId(String restoreJobId) { this.restoreJobId = restoreJobId; }
    public OffsetDateTime getLastArchivedAt() { return lastArchivedAt; }
    public void setLastArchivedAt(OffsetDateTime lastArchivedAt) { this.lastArchivedAt = lastArchivedAt; }
    public OffsetDateTime getLastRestoreInitiatedAt() { return lastRestoreInitiatedAt; }
    public void setLastRestoreInitiatedAt(OffsetDateTime lastRestoreInitiatedAt) { this.lastRestoreInitiatedAt = lastRestoreInitiatedAt; }
    public Map<String, String> getProviderConfiguration() { return providerConfiguration; }
    public void setProviderConfiguration(Map<String, String> providerConfiguration) { this.providerConfiguration = providerConfiguration; }
} 