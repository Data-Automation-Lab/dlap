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

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GlacierJobParameters;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.RestoreObjectRequest;
import software.amazon.awssdk.services.s3.model.RestoreRequest;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.Tier;

/**
 * Archives data to S3 Glacier storage classes. The archive location a restore starts from is
 * parsed through {@link AssetIdNormalizer} (plan rule R8), so any alias of the archived object
 * ({@code s3://b/k}, {@code arn:aws:s3:::b/k}, {@code s3a://}, the S3 https URLs) is accepted.
 * Anything that is not exactly one S3 object in the configured bucket is refused.
 */
@Component
public class AwsS3GlacierArchivalProvider implements ICloudArchivalProvider {

    private static final String PROVIDER_TYPE = "AWS";

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public ArchivalResult archive(ArchivalTask task, InputStream dataToArchive, Map<String, String> providerSpecificConfig)
            throws ArchivalException {
        Map<String, String> cfg = mergeConfig(task, providerSpecificConfig);
        String bucket = required(cfg, "s3.bucket", "targetBucket");
        String key = cfg.getOrDefault("s3.key", "archives/" + task.getAssetId() + "/" + UUID.randomUUID());
        String regionId = cfg.getOrDefault("aws.region", System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        String storageClassName = cfg.getOrDefault("s3.storageClass", "GLACIER");
        StorageClass storageClass;
        try {
            storageClass = StorageClass.fromValue(storageClassName);
        } catch (Exception e) {
            storageClass = StorageClass.GLACIER;
        }

        try (S3Client s3 = S3Client.builder().region(Region.of(regionId)).build()) {
            byte[] payload = dataToArchive.readAllBytes();
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .storageClass(storageClass)
                    .build();
            String versionId = null;
            try {
                versionId = s3.putObject(put, RequestBody.fromBytes(payload)).versionId();
            } catch (Exception ignored) {
                // non-versioned bucket
            }
            String location = "s3://" + bucket + "/" + key;
            return new ArchivalResult(
                    versionId != null ? versionId : key,
                    location,
                    payload.length,
                    null,
                    storageClass.toString(),
                    OffsetDateTime.now());
        } catch (Exception e) {
            throw new ArchivalException("AWS S3 archival failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RestoreResult initiateRestore(ArchivalTask task, Map<String, String> providerSpecificConfig) throws ArchivalException {
        Map<String, String> cfg = mergeConfig(task, providerSpecificConfig);
        String bucket = required(cfg, "s3.bucket", "targetBucket");
        String configuredKey = cfg.get("s3.key");
        String key = configuredKey != null && !configuredKey.isBlank()
                ? configuredKey
                : parseKeyFromLocation(task.getArchiveStorageLocation(), bucket);
        String regionId = cfg.getOrDefault("aws.region", System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        int days = Integer.parseInt(cfg.getOrDefault("restore.days", "7"));
        Tier restoreTier = Tier.STANDARD;
        try {
            restoreTier = Tier.valueOf(cfg.getOrDefault("restore.tier", "Standard").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            // keep STANDARD
        }

        try (S3Client s3 = S3Client.builder().region(Region.of(regionId)).build()) {
            RestoreObjectRequest req = RestoreObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .restoreRequest(RestoreRequest.builder()
                            .days(days)
                            .glacierJobParameters(GlacierJobParameters.builder()
                                    .tier(restoreTier)
                                    .build())
                            .build())
                    .build();
            s3.restoreObject(req);
            RestoreResult result = new RestoreResult();
            result.setRestoreJobId(task.getTaskId() + "-restore");
            result.setStatusMessage("Restore request accepted for " + key);
            result.setRetrievalTier(restoreTier.toString());
            result.setEstimatedCompletionTime(OffsetDateTime.now().plusHours(12));
            return result;
        } catch (Exception e) {
            throw new ArchivalException("AWS S3 restore initiation failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> mergeConfig(ArchivalTask task, Map<String, String> providerSpecificConfig) {
        Map<String, String> cfg = new java.util.HashMap<>();
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
     * Derives the object key from the archive location of a task.
     *
     * @param location       the archive location in any alias the asset-id spec accepts
     * @param expectedBucket the configured archive bucket; the location must name the same bucket
     * @return the object key
     * @throws ArchivalException when the location is not exactly one S3 object in the expected bucket
     */
    static String parseKeyFromLocation(String location, String expectedBucket) throws ArchivalException {
        if (location == null || location.isBlank()) {
            throw new ArchivalException("Cannot derive S3 key from task; set s3.key in provider configuration.");
        }
        if (LocationSafety.carriesUrlSelector(location)) {
            throw new ArchivalException("Invalid s3 location for restore: a URL query string or fragment can "
                    + "select an object version and is not honoured");
        }
        AssetIdParseResult parsed = AssetIdNormalizer.parse(location, AssetIdHints.of(AssetProvider.AWS));
        if (!(parsed instanceof AssetIdParseResult.Normalized normalized)
                || normalized.canonical().kind() != AssetKind.S3_OBJECT) {
            throw new ArchivalException("Invalid s3 location for restore: it must name exactly one S3 object");
        }
        List<String> parts = normalized.canonical().parts();
        if (!parts.get(0).equals(expectedBucket)) {
            throw new ArchivalException("The archive location is in bucket " + parts.get(0)
                    + " but the configured bucket is " + expectedBucket);
        }
        String key = parts.get(1);
        if (LocationSafety.hasEdgeWhitespace(key)) {
            throw new ArchivalException("Invalid s3 location for restore: the object key has leading or trailing "
                    + "whitespace, which is ambiguous");
        }
        return key;
    }
}
