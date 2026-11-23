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

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.dlap.autoarchival.exception.ArchivalException;
import com.dlap.autoarchival.model.ArchivalResult;
import com.dlap.autoarchival.model.ArchivalTask;
import com.dlap.autoarchival.model.RestoreResult;
import com.dlap.autoarchival.service.LocationSafety;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageOptions;

/**
 * Archives data to Google Cloud Storage. The archive location a restore starts from is parsed
 * through {@link AssetIdNormalizer} (plan rule R8), so any alias of the archived object
 * ({@code gs://b/o}, {@code projects/_/buckets/b/objects/o}, the Cloud Storage https URLs) is
 * accepted. Anything that is not exactly one GCS object in the configured bucket is refused.
 */
@Component
public class GcsArchiveStorageProvider implements ICloudArchivalProvider {

    private static final String PROVIDER_TYPE = "GCP";

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public ArchivalResult archive(ArchivalTask task, InputStream dataToArchive, Map<String, String> providerSpecificConfig)
            throws ArchivalException {
        Map<String, String> cfg = merge(task, providerSpecificConfig);
        String bucket = required(cfg, "gcs.bucket", "targetBucket");
        String objectName = cfg.getOrDefault("gcs.object", "archives/" + task.getAssetId() + "/" + UUID.randomUUID());
        StorageClass storageClass = StorageClass.ARCHIVE;

        try {
            Storage storage = StorageOptions.getDefaultInstance().getService();
            byte[] bytes = dataToArchive.readAllBytes();
            BlobId blobId = BlobId.of(bucket, objectName);
            BlobInfo info = BlobInfo.newBuilder(blobId)
                    .setStorageClass(storageClass)
                    .setContentType("application/octet-stream")
                    .build();
            storage.create(info, bytes);
            String location = "gs://" + bucket + "/" + objectName;
            return new ArchivalResult(objectName, location, bytes.length, null, storageClass.name(), OffsetDateTime.now());
        } catch (Exception e) {
            throw new ArchivalException("GCS archival failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RestoreResult initiateRestore(ArchivalTask task, Map<String, String> providerSpecificConfig) throws ArchivalException {
        Map<String, String> cfg = merge(task, providerSpecificConfig);
        String bucket = required(cfg, "gcs.bucket", "targetBucket");
        String configuredObject = cfg.get("gcs.object");
        String objectName = configuredObject != null && !configuredObject.isBlank()
                ? configuredObject
                : parseObjectFromGsUri(task.getArchiveStorageLocation(), bucket);

        RestoreResult result = new RestoreResult();
        result.setRestoreJobId(task.getTaskId() + "-gcs-restore");
        result.setStatusMessage("GCS Archive class objects are directly accessible; no restore job required for " + bucket + "/" + objectName);
        result.setRetrievalTier("N/A");
        result.setEstimatedCompletionTime(OffsetDateTime.now());
        return result;
    }

    private static Map<String, String> merge(ArchivalTask task, Map<String, String> providerSpecificConfig) {
        Map<String, String> cfg = new HashMap<>();
        if (task.getProviderConfiguration() != null) {
            cfg.putAll(task.getProviderConfiguration());
        }
        if (providerSpecificConfig != null) {
            cfg.putAll(providerSpecificConfig);
        }
        return cfg;
    }

    private static String required(Map<String, String> cfg, String primary, String alias) throws ArchivalException {
        String v = cfg.get(primary);
        if (v == null || v.isBlank()) {
            v = cfg.get(alias);
        }
        if (v == null || v.isBlank()) {
            throw new ArchivalException("Missing required config: " + primary + " (or " + alias + ")");
        }
        return v;
    }

    /**
     * Derives the object name from the archive location of a task.
     *
     * @param uri            the archive location in any alias the asset-id spec accepts
     * @param expectedBucket the configured archive bucket; the location must name the same bucket
     * @return the object name
     * @throws ArchivalException when the location is not exactly one GCS object in the expected bucket
     */
    static String parseObjectFromGsUri(String uri, String expectedBucket) throws ArchivalException {
        if (uri == null || uri.isBlank()) {
            throw new ArchivalException("Cannot derive GCS object from task; set gcs.object in provider configuration.");
        }
        if (LocationSafety.carriesUrlSelector(uri)) {
            throw new ArchivalException("Invalid archive location: a URL query string or fragment can select a "
                    + "generation and is not honoured");
        }
        AssetIdParseResult parsed = AssetIdNormalizer.parse(uri, AssetIdHints.of(AssetProvider.GCP));
        if (!(parsed instanceof AssetIdParseResult.Normalized normalized)
                || normalized.canonical().kind() != AssetKind.GCS_OBJECT) {
            throw new ArchivalException("Invalid GCS archive location: it must name exactly one GCS object");
        }
        List<String> parts = normalized.canonical().parts();
        if (!parts.get(0).equalsIgnoreCase(expectedBucket)) {
            throw new ArchivalException("The archive location is in bucket " + parts.get(0)
                    + " but the configured bucket is " + expectedBucket);
        }
        String objectName = parts.get(1);
        if (LocationSafety.hasEdgeWhitespace(objectName)) {
            throw new ArchivalException("Invalid GCS archive location: the object name has leading or trailing "
                    + "whitespace, which is ambiguous");
        }
        return objectName;
    }
}
