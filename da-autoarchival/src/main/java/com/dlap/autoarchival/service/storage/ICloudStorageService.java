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

package com.dlap.autoarchival.service.storage;

import java.util.Map;
import java.util.UUID;

/**
 * Interface for cloud storage operations supporting archival and restoration.
 * Abstracts different cloud providers (AWS S3/Glacier, Azure Blob, GCP Cloud Storage).
 */
public interface ICloudStorageService {

    /**
     * Archive data to cold storage tier.
     * 
     * @param assetId The unique identifier of the asset being archived
     * @param sourceLocation The original location of the data (e.g., S3 key, blob name)
     * @param targetTier The target storage tier (e.g., "GLACIER", "ARCHIVE", "COLDLINE")
     * @param metadata Additional metadata to store with the archived object
     * @return ArchivalResult containing the archived location and operation details
     */
    ArchivalResult archiveData(UUID assetId, String sourceLocation, String targetTier, Map<String, String> metadata);

    /**
     * Restore data from cold storage to accessible tier.
     * 
     * @param assetId The unique identifier of the asset being restored
     * @param archivedLocation The location of the archived data
     * @param targetTier The target tier for restoration (e.g., "STANDARD", "HOT")
     * @return RestorationResult containing the restoration job details
     */
    RestorationResult restoreData(UUID assetId, String archivedLocation, String targetTier);

    /**
     * Get the status of an archival operation.
     * 
     * @param operationId The ID of the archival operation
     * @return OperationStatus indicating the current state
     */
    OperationStatus getArchivalStatus(String operationId);

    /**
     * Get the status of a restoration operation.
     * 
     * @param operationId The ID of the restoration operation
     * @return OperationStatus indicating the current state
     */
    OperationStatus getRestorationStatus(String operationId);

    /**
     * Calculate the cost savings from archiving data.
     * 
     * @param sourceLocation The original location
     * @param targetTier The archival tier
     * @param dataSizeBytes The size of data in bytes
     * @return CostSavingsEstimate with monthly savings information
     */
    CostSavingsEstimate calculateCostSavings(String sourceLocation, String targetTier, long dataSizeBytes);

    /**
     * Check if the storage service supports the specified cloud provider.
     * 
     * @param cloudProvider The cloud provider (AWS, AZURE, GCP)
     * @return true if supported, false otherwise
     */
    boolean supportsCloudProvider(String cloudProvider);

    /**
     * Get available storage tiers for the cloud provider.
     * 
     * @param cloudProvider The cloud provider
     * @return Array of available tier names
     */
    String[] getAvailableStorageTiers(String cloudProvider);
} 