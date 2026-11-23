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

package com.dlap.autoarchival.service.storage.impl;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.CostSavingsEstimate;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.autoarchival.service.storage.RestorationResult;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageOptions;

/**
 * Google Cloud Storage implementation of {@link ICloudStorageService} for archival
 * operations. Supports the GCS storage classes STANDARD, NEARLINE, COLDLINE and ARCHIVE,
 * and accepts the AWS-flavoured tier names ({@code GLACIER}, {@code DEEP_ARCHIVE}) that the
 * shared archival config may carry, mapping them to the closest GCS cold tier.
 *
 * <p>Choke-point #4: this registers a real GCP-backed storage service so a {@code gs://}
 * archival task dispatches here instead of failing closed or (previously) being silently
 * archived to AWS. Archival is a storage-class transition on an object that already lives in
 * GCS - the asset is copied onto itself with the colder storage class, exactly as the AWS
 * implementation rewrites an S3 object's storage class.
 *
 * <p>{@code dlap.autoarchival.gcs.mock-mode} defaults to {@code false} (honest by default,
 * mirroring the deletion providers): production runs the real storage-class transition. Set
 * it to {@code true} only in dev/CI or any environment without Application Default
 * Credentials, where it transitions state without touching GCS - a false COMPLETED is never
 * emitted in production unless a deployer opts in. The GCS client is initialised lazily, so
 * the bean starts cleanly even when no credentials are present.
 */
@Service
public class GcsCloudStorageService implements ICloudStorageService {

    private static final Logger log = LoggerFactory.getLogger(GcsCloudStorageService.class);

    // Approximate GCS pricing (USD per GB per month, us multi-region). Used only for the
    // cost-savings estimate; not a billing source of truth.
    private static final double STANDARD_COST_PER_GB = 0.020;
    private static final double NEARLINE_COST_PER_GB = 0.010;
    private static final double COLDLINE_COST_PER_GB = 0.004;
    private static final double ARCHIVE_COST_PER_GB = 0.0012;

    private final boolean mockMode;

    /** Lazily initialised; never touched in mock mode. Package-private setter for tests. */
    private Storage storage;

    /** The test-only emulator host (Q-713); empty for GCS. */
    private final String endpointOverride;

    /** The project to use with an emulator host. */
    private final String endpointProject;

    /**
     * Creates the service for the real GCS.
     *
     * @param mockMode whether transitions are mocked
     */
    public GcsCloudStorageService(boolean mockMode) {
        this(mockMode, "", "dlap-emulator");
    }

    /**
     * Creates the service. A non-empty host is the test-only emulator override (Q-713): the same property the
     * lifecycle rule provider reads, off by default and refused under the prod profile.
     *
     * @param mockMode whether transitions are mocked
     * @param host     an emulator host such as fake-gcs-server; empty for GCS
     * @param project  the project for an emulator host
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GcsCloudStorageService(
            @Value("${dlap.autoarchival.gcs.mock-mode:false}") boolean mockMode,
            @Value(com.dlap.autoarchival.config.TestEndpointProperties.GCS) String host,
            @Value("${dlap.autoarchival.gcs.endpoint-project:dlap-emulator}") String project) {
        this.endpointOverride = host == null ? "" : host;
        this.endpointProject = project;
        this.mockMode = mockMode;
        if (mockMode) {
            log.warn("GcsCloudStorageService started in MOCK mode "
                    + "(dlap.autoarchival.gcs.mock-mode=true) - GCP archival transitions state "
                    + "without touching GCS. This is a dev/CI relaxation; leave it unset "
                    + "(false) in production.");
        }
    }

    @Override
    public ArchivalResult archiveData(UUID assetId, String sourceLocation, String targetTier,
            Map<String, String> metadata) {
        log.info("Archiving asset {} from {} to GCS tier {} (mock={})",
                assetId, sourceLocation, targetTier, mockMode);
        String operationId = UUID.randomUUID().toString();
        try {
            StorageClass storageClass = mapToGcsStorageClass(targetTier);

            if (mockMode) {
                return ArchivalResult.builder()
                        .assetId(assetId)
                        .operationId(operationId)
                        .sourceLocation(sourceLocation)
                        .archivedLocation(sourceLocation)
                        .targetTier(storageClass.name())
                        .status(OperationStatus.COMPLETED)
                        .initiatedAt(LocalDateTime.now())
                        .completedAt(LocalDateTime.now())
                        .dataSizeBytes(0L)
                        .metadata(metadata)
                        .estimatedMonthlySavings(0.0)
                        .build();
            }

            String[] parts = parseGcsLocation(sourceLocation);
            BlobId blobId = BlobId.of(parts[0], parts[1]);
            Blob existing = storage().get(blobId);
            if (existing == null) {
                return failedArchival(assetId, operationId, sourceLocation, targetTier,
                        "GCS object not found: " + sourceLocation);
            }

            // Rewrite the object onto itself with the colder storage class - the canonical
            // GCS way to transition an existing object's storage tier.
            Storage.CopyRequest copyRequest = Storage.CopyRequest.newBuilder()
                    .setSource(blobId)
                    .setTarget(BlobInfo.newBuilder(blobId).setStorageClass(storageClass).build())
                    .build();
            Blob updated = storage().copy(copyRequest).getResult();
            long size = updated.getSize() == null ? 0L : updated.getSize();

            return ArchivalResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .sourceLocation(sourceLocation)
                    .archivedLocation(sourceLocation) // same location, colder storage class
                    .targetTier(storageClass.name())
                    .status(OperationStatus.COMPLETED)
                    .initiatedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(size)
                    .metadata(metadata)
                    .estimatedMonthlySavings(calculateMonthlySavings("STANDARD", storageClass.name(), size))
                    .build();

        } catch (IllegalArgumentException e) {
            return failedArchival(assetId, operationId, sourceLocation, targetTier, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to archive asset {} to GCS: {}", assetId, e.getMessage(), e);
            return failedArchival(assetId, operationId, sourceLocation, targetTier, e.getMessage());
        }
    }

    @Override
    public RestorationResult restoreData(UUID assetId, String archivedLocation, String targetTier) {
        log.info("Restoring asset {} from {} to GCS tier {} (mock={})",
                assetId, archivedLocation, targetTier, mockMode);
        String operationId = UUID.randomUUID().toString();
        try {
            // GCS ARCHIVE/COLDLINE/NEARLINE objects are directly readable - there is no async
            // rehydration job as in S3 Glacier. Restoration is an immediate storage-class
            // transition back to a warmer tier.
            StorageClass storageClass = mapToGcsStorageClass(
                    targetTier == null || targetTier.isBlank() ? "STANDARD" : targetTier);

            if (mockMode) {
                return RestorationResult.builder()
                        .assetId(assetId)
                        .operationId(operationId)
                        .archivedLocation(archivedLocation)
                        .restoredLocation(archivedLocation)
                        .targetTier(storageClass.name())
                        .status(OperationStatus.COMPLETED)
                        .initiatedAt(LocalDateTime.now())
                        .completedAt(LocalDateTime.now())
                        .build();
            }

            String[] parts = parseGcsLocation(archivedLocation);
            BlobId blobId = BlobId.of(parts[0], parts[1]);
            Blob existing = storage().get(blobId);
            if (existing == null) {
                return RestorationResult.builder()
                        .assetId(assetId)
                        .operationId(operationId)
                        .archivedLocation(archivedLocation)
                        .targetTier(targetTier)
                        .status(OperationStatus.FAILED)
                        .initiatedAt(LocalDateTime.now())
                        .errorMessage("GCS object not found: " + archivedLocation)
                        .build();
            }

            Storage.CopyRequest copyRequest = Storage.CopyRequest.newBuilder()
                    .setSource(blobId)
                    .setTarget(BlobInfo.newBuilder(blobId).setStorageClass(storageClass).build())
                    .build();
            storage().copy(copyRequest).getResult();

            return RestorationResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .archivedLocation(archivedLocation)
                    .restoredLocation(archivedLocation)
                    .targetTier(storageClass.name())
                    .status(OperationStatus.COMPLETED)
                    .initiatedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Failed to restore asset {} from GCS: {}", assetId, e.getMessage(), e);
            return RestorationResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .archivedLocation(archivedLocation)
                    .targetTier(targetTier)
                    .status(OperationStatus.FAILED)
                    .initiatedAt(LocalDateTime.now())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public OperationStatus getArchivalStatus(String operationId) {
        // operationId is expected to be a gs:// location that was archived. Inspect the
        // object's current storage class to determine the real archival state.
        if (mockMode) {
            return OperationStatus.COMPLETED;
        }
        try {
            String[] parts = parseGcsLocation(operationId);
            Blob blob = storage().get(BlobId.of(parts[0], parts[1]));
            if (blob == null) {
                return OperationStatus.UNKNOWN;
            }
            StorageClass sc = blob.getStorageClass();
            if (sc == null || sc == StorageClass.STANDARD
                    || sc == StorageClass.MULTI_REGIONAL || sc == StorageClass.REGIONAL) {
                return OperationStatus.NOT_ARCHIVED;
            }
            if (sc == StorageClass.ARCHIVE || sc == StorageClass.COLDLINE
                    || sc == StorageClass.NEARLINE) {
                return OperationStatus.COMPLETED;
            }
            return OperationStatus.UNKNOWN;
        } catch (IllegalArgumentException e) {
            log.error("Invalid gs:// location for archival status lookup: {}", operationId, e);
            return OperationStatus.UNKNOWN;
        } catch (Exception e) {
            log.error("Failed to retrieve GCS archival status for {}: {}", operationId, e.getMessage(), e);
            return OperationStatus.UNKNOWN;
        }
    }

    @Override
    public OperationStatus getRestorationStatus(String operationId) {
        // GCS has no asynchronous restore job; a warmed object simply reports a warm storage
        // class. STANDARD => restored; a cold class => not restored.
        if (mockMode) {
            return OperationStatus.COMPLETED;
        }
        try {
            String[] parts = parseGcsLocation(operationId);
            Blob blob = storage().get(BlobId.of(parts[0], parts[1]));
            if (blob == null) {
                return OperationStatus.UNKNOWN;
            }
            StorageClass sc = blob.getStorageClass();
            if (sc == null || sc == StorageClass.STANDARD
                    || sc == StorageClass.MULTI_REGIONAL || sc == StorageClass.REGIONAL) {
                return OperationStatus.COMPLETED;
            }
            return OperationStatus.NOT_RESTORED;
        } catch (IllegalArgumentException e) {
            log.error("Invalid gs:// location for restoration status lookup: {}", operationId, e);
            return OperationStatus.UNKNOWN;
        } catch (Exception e) {
            log.error("Failed to retrieve GCS restoration status for {}: {}", operationId, e.getMessage(), e);
            return OperationStatus.UNKNOWN;
        }
    }

    @Override
    public CostSavingsEstimate calculateCostSavings(String sourceLocation, String targetTier, long dataSizeBytes) {
        double dataSizeGB = dataSizeBytes / (1024.0 * 1024.0 * 1024.0);
        String targetClass = mapToGcsStorageClass(targetTier).name();

        double currentCost = STANDARD_COST_PER_GB * dataSizeGB;
        double targetCost = getCostPerGB(targetClass) * dataSizeGB;
        double savings = currentCost - targetCost;
        double savingsPercentage = currentCost == 0 ? 0 : (savings / currentCost) * 100;

        return CostSavingsEstimate.builder()
                .sourceStorageClass("STANDARD")
                .targetStorageClass(targetClass)
                .dataSizeBytes(dataSizeBytes)
                .currentMonthlyCost(currentCost)
                .archivedMonthlyCost(targetCost)
                .monthlySavings(savings)
                .savingsPercentage(savingsPercentage)
                .currency("USD")
                .region("us")
                .build();
    }

    @Override
    public boolean supportsCloudProvider(String cloudProvider) {
        return "GCP".equalsIgnoreCase(cloudProvider)
                || "GOOGLE".equalsIgnoreCase(cloudProvider)
                || "GCS".equalsIgnoreCase(cloudProvider);
    }

    @Override
    public String[] getAvailableStorageTiers(String cloudProvider) {
        if (supportsCloudProvider(cloudProvider)) {
            return new String[]{"STANDARD", "NEARLINE", "COLDLINE", "ARCHIVE"};
        }
        return new String[0];
    }

    // --- helpers -----------------------------------------------------------------------

    private ArchivalResult failedArchival(UUID assetId, String operationId, String sourceLocation,
            String targetTier, String error) {
        log.warn("GCS archival failed for asset {}: {}", assetId, error);
        return ArchivalResult.builder()
                .assetId(assetId)
                .operationId(operationId)
                .sourceLocation(sourceLocation)
                .targetTier(targetTier)
                .status(OperationStatus.FAILED)
                .initiatedAt(LocalDateTime.now())
                .errorMessage(error)
                .build();
    }

    /** Lazily resolves Application Default Credentials. Never called in mock mode. */
    Storage storage() {
        if (storage == null) {
            storage = endpointOverride.isBlank() ? StorageOptions.getDefaultInstance().getService()
                    : StorageOptions.newBuilder().setHost(endpointOverride).setProjectId(endpointProject)
                            .setCredentials(com.google.cloud.NoCredentials.getInstance()).build().getService();
        }
        return storage;
    }

    /** Package-private for tests to inject a mock Storage. */
    void setStorage(Storage storage) {
        this.storage = storage;
    }

    private static String[] parseGcsLocation(String gsLocation) {
        if (gsLocation == null || !gsLocation.startsWith("gs://")) {
            throw new IllegalArgumentException("Invalid GCS location format: " + gsLocation);
        }
        String withoutProtocol = gsLocation.substring("gs://".length());
        int firstSlash = withoutProtocol.indexOf('/');
        if (firstSlash <= 0 || firstSlash == withoutProtocol.length() - 1) {
            throw new IllegalArgumentException("Invalid GCS location format: " + gsLocation);
        }
        return new String[]{withoutProtocol.substring(0, firstSlash), withoutProtocol.substring(firstSlash + 1)};
    }

    /**
     * Maps a target tier to a GCS storage class. Accepts native GCS classes and the
     * AWS-flavoured names carried by the shared archival config (whose default tier is
     * {@code GLACIER}), so a GCP task with a default tier never throws.
     */
    private static StorageClass mapToGcsStorageClass(String targetTier) {
        if (targetTier == null || targetTier.isBlank()) {
            return StorageClass.ARCHIVE;
        }
        return switch (targetTier.toUpperCase(Locale.ROOT)) {
            case "STANDARD", "HOT" -> StorageClass.STANDARD;
            case "NEARLINE", "STANDARD_IA", "IA" -> StorageClass.NEARLINE;
            case "COLDLINE" -> StorageClass.COLDLINE;
            case "ARCHIVE", "GLACIER", "DEEP_ARCHIVE" -> StorageClass.ARCHIVE;
            default -> {
                log.warn("Unknown archival tier '{}' for GCS; defaulting to ARCHIVE.", targetTier);
                yield StorageClass.ARCHIVE;
            }
        };
    }

    private static double getCostPerGB(String storageClass) {
        return switch (storageClass.toUpperCase(Locale.ROOT)) {
            case "STANDARD" -> STANDARD_COST_PER_GB;
            case "NEARLINE" -> NEARLINE_COST_PER_GB;
            case "COLDLINE" -> COLDLINE_COST_PER_GB;
            case "ARCHIVE" -> ARCHIVE_COST_PER_GB;
            default -> STANDARD_COST_PER_GB;
        };
    }

    private static double calculateMonthlySavings(String sourceClass, String targetClass, long dataSizeBytes) {
        double dataSizeGB = dataSizeBytes / (1024.0 * 1024.0 * 1024.0);
        double sourceCost = getCostPerGB(sourceClass) * dataSizeGB;
        double targetCost = getCostPerGB(targetClass) * dataSizeGB;
        return Math.max(0, sourceCost - targetCost);
    }
}
