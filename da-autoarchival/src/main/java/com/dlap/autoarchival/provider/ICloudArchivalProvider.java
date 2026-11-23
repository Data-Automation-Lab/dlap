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

package com.dlap.autoarchival.provider;

import com.dlap.autoarchival.model.ArchivalTask; 
import com.dlap.autoarchival.model.ArchivalResult;
import com.dlap.autoarchival.model.RestoreResult;
import com.dlap.autoarchival.exception.ArchivalException;

import java.io.InputStream;
import java.util.Map;

/**
 * Interface for cloud-specific archival storage providers.
 * Implementations will handle the actual interaction with services like AWS S3 Glacier, GCS Archive, etc.
 */
public interface ICloudArchivalProvider {

    /**
     * Gets the specific cloud provider type this provider supports (e.g., "AWS", "GCP").
     * This can be used by a factory to select the correct provider.
     * @return The cloud provider type identifier.
     */
    String getProviderType();

    /**
     * Archives the given data stream for the specified archival task.
     *
     * @param task The ArchivalTask entity containing details about the asset and archival job.
     * @param dataToArchive An InputStream containing the data to be archived.
     * @param providerSpecificConfig Configuration specific to this provider (e.g., bucket name, region, storage class).
     * @return An ArchivalResult containing details of the archival operation (e.g., archive ID, location).
     * @throws ArchivalException if any error occurs during the archival process.
     */
    ArchivalResult archive(ArchivalTask task, InputStream dataToArchive, Map<String, String> providerSpecificConfig) throws ArchivalException;

    /**
     * Initiates the restoration of an archived asset.
     * For some services (like Glacier), this might be an asynchronous operation.
     *
     * @param task The ArchivalTask entity for which data needs to be restored.
     *             It should contain necessary identifiers from a previous successful archival.
     * @param providerSpecificConfig Configuration specific to this provider.
     * @return A RestoreResult indicating the status of the restore initiation (e.g., job ID for tracking).
     * @throws ArchivalException if any error occurs during the restore initiation.
     */
    RestoreResult initiateRestore(ArchivalTask task, Map<String, String> providerSpecificConfig) throws ArchivalException;

    // Optional methods for more detailed restore management - can be added later
    // String getRestoreStatus(ArchivalTask task, String restoreJobId, Map<String, String> providerSpecificConfig) throws ArchivalException;
    // InputStream retrieveRestoredData(ArchivalTask task, String restoreJobId, Map<String, String> providerSpecificConfig) throws ArchivalException;
} 