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

package com.dlap.autodelete.service.storage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Interface for cloud storage deletion operations.
 * Abstracts different cloud providers (AWS S3, Azure Blob, GCP Cloud Storage).
 */
public interface ICloudDeletionService {

    /**
     * Perform secure deletion of data from cloud storage.
     * 
     * @param assetId The unique identifier of the asset being deleted
     * @param dataLocation The location of the data to delete (e.g., S3 key, blob name)
     * @param deletionType The type of deletion (SOFT, HARD, SECURE_OVERWRITE)
     * @param metadata Additional metadata for the deletion operation
     * @return DeletionResult containing the operation details
     */
    DeletionResult deleteData(UUID assetId, String dataLocation, DeletionType deletionType, Map<String, String> metadata);

    /**
     * Perform bulk deletion of multiple data objects.
     * 
     * @param assetIds List of asset IDs to delete
     * @param dataLocations List of data locations corresponding to the assets
     * @param deletionType The type of deletion to perform
     * @param metadata Additional metadata for the bulk operation
     * @return BulkDeletionResult containing results for all operations
     */
    BulkDeletionResult bulkDeleteData(List<UUID> assetIds, List<String> dataLocations, DeletionType deletionType, Map<String, String> metadata);

    /**
     * Get the status of a deletion operation.
     * 
     * @param operationId The ID of the deletion operation
     * @return DeletionStatus indicating the current state
     */
    DeletionStatus getDeletionStatus(String operationId);

    /**
     * Calculate storage space that would be reclaimed by deletion.
     * 
     * @param dataLocations List of data locations to analyze
     * @return StorageReclaimEstimate with space and cost information
     */
    StorageReclaimEstimate calculateStorageReclaim(List<String> dataLocations);

    /**
     * Verify that data has been completely deleted (compliance check).
     * 
     * @param operationId The deletion operation ID
     * @return DeletionVerificationResult with compliance details
     */
    DeletionVerificationResult verifyDeletion(String operationId);

    /**
     * Check if the deletion service supports the specified cloud provider.
     * 
     * @param cloudProvider The cloud provider (AWS, AZURE, GCP)
     * @return true if supported, false otherwise
     */
    boolean supportsCloudProvider(String cloudProvider);

    /**
     * Get available deletion types for the cloud provider.
     * 
     * @param cloudProvider The cloud provider
     * @return Array of available deletion type names
     */
    DeletionType[] getAvailableDeletionTypes(String cloudProvider);
} 