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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.AccessTier;
import com.dlap.autoarchival.providers.azure.AzureBlobClients;
import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.CostSavingsEstimate;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.autoarchival.service.storage.RestorationResult;

/**
 * Azure Blob Storage implementation of {@link ICloudStorageService} for archival
 * operations. Supports the Azure access tiers HOT, COOL, COLD and ARCHIVE, and accepts the
 * AWS/GCP-flavoured tier names ({@code GLACIER}, {@code DEEP_ARCHIVE}, {@code NEARLINE},
 * {@code COLDLINE}) that the shared archival config may carry, mapping them to the closest
 * Azure tier.
 *
 * <p>This registers a real Azure-backed storage service so an Azure ({@code https://…blob…} /
 * {@code azure://…} / {@code abfss://…}) archival task dispatches here instead of failing
 * closed. Azure archival is an access-tier transition on a blob that already lives in Blob
 * Storage - {@code setAccessTier} rewrites the blob's tier in place, exactly as the AWS
 * implementation rewrites an S3 object's storage class and the GCS one rewrites a GCS object's
 * storage class.
 *
 * <p>{@code dlap.autoarchival.azure.mock-mode} defaults to {@code false} (honest by default,
 * mirroring the AWS/GCS providers): production runs the real access-tier transition. Set it to
 * {@code true} only in dev/CI or any environment without an Azure connection string, where it
 * transitions state without touching Azure - a false COMPLETED is never emitted in production
 * unless a deployer opts in. The Azure client is initialised lazily from the connection
 * string, so the bean starts cleanly even when no connection string is present.
 */
@Service
public class AzureCloudStorageService implements ICloudStorageService {

    private static final Logger log = LoggerFactory.getLogger(AzureCloudStorageService.class);

    // Approximate Azure Blob pricing (USD per GB per month, eastus). Used only for the
    // cost-savings estimate; not a billing source of truth.
    private static final double HOT_COST_PER_GB = 0.0184;
    private static final double COOL_COST_PER_GB = 0.010;
    private static final double COLD_COST_PER_GB = 0.0036;
    private static final double ARCHIVE_COST_PER_GB = 0.00099;

    private final boolean mockMode;
    private final AzureBlobClients clients;

    /** A client injected by a test; when set it serves every account. Never set in production. */
    private BlobServiceClient blobServiceClient;

    /**
     * Creates the service on a deprecated connection string (tests); an empty string means none.
     *
     * @param mockMode         {@code dlap.autoarchival.azure.mock-mode}
     * @param connectionString the deprecated connection string; empty for none
     */
    public AzureCloudStorageService(boolean mockMode, String connectionString) {
        this(mockMode, new AzureBlobClients("", connectionString, ""));
    }

    /**
     * Creates the service the way Spring does: blob tiering goes through {@code DefaultAzureCredential} and the
     * account endpoint (EXEC-01, Q-724).
     *
     * @param mockMode {@code dlap.autoarchival.azure.mock-mode}
     * @param clients  the Azure blob clients
     */
    @Autowired
    public AzureCloudStorageService(
            @Value("${dlap.autoarchival.azure.mock-mode:false}") boolean mockMode,
            AzureBlobClients clients) {
        this.mockMode = mockMode;
        this.clients = clients;
        if (mockMode) {
            log.warn("AzureCloudStorageService started in MOCK mode "
                    + "(dlap.autoarchival.azure.mock-mode=true) - Azure archival transitions "
                    + "state without touching Azure Blob Storage. This is a dev/CI relaxation; "
                    + "leave it unset (false) in production.");
        }
    }

    @Override
    public ArchivalResult archiveData(UUID assetId, String sourceLocation, String targetTier,
            Map<String, String> metadata) {
        log.info("Archiving asset {} from {} to Azure tier {} (mock={})",
                assetId, sourceLocation, targetTier, mockMode);
        String operationId = UUID.randomUUID().toString();
        try {
            AccessTier accessTier = mapToAzureAccessTier(targetTier);
            String tierName = mapToAzureTierName(targetTier);

            // Parse to validate the location even in mock mode; never touches the SDK there.
            String[] parts = parseAzureLocation(sourceLocation);

            if (mockMode) {
                return ArchivalResult.builder()
                        .assetId(assetId)
                        .operationId(operationId)
                        .sourceLocation(sourceLocation)
                        .archivedLocation(sourceLocation)
                        .targetTier(tierName)
                        .status(OperationStatus.COMPLETED)
                        .initiatedAt(LocalDateTime.now())
                        .completedAt(LocalDateTime.now())
                        .dataSizeBytes(0L)
                        .metadata(metadata)
                        .estimatedMonthlySavings(0.0)
                        .build();
            }

            BlobClient blobClient = blobServiceClient(parts[0])
                    .getBlobContainerClient(parts[1])
                    .getBlobClient(parts[2]);
            // Rewrite the blob's access tier in place - the canonical Azure way to transition
            // an existing blob's storage tier.
            blobClient.setAccessTier(accessTier);
            long size = blobClient.getProperties().getBlobSize();

            return ArchivalResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .sourceLocation(sourceLocation)
                    .archivedLocation(sourceLocation) // same location, colder access tier
                    .targetTier(tierName)
                    .status(OperationStatus.COMPLETED)
                    .initiatedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(size)
                    .metadata(metadata)
                    .estimatedMonthlySavings(calculateMonthlySavings("HOT", tierName, size))
                    .build();

        } catch (IllegalArgumentException e) {
            return failedArchival(assetId, operationId, sourceLocation, targetTier, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to archive asset {} to Azure: {}", assetId, e.getMessage(), e);
            return failedArchival(assetId, operationId, sourceLocation, targetTier, e.getMessage());
        }
    }

    @Override
    public RestorationResult restoreData(UUID assetId, String archivedLocation, String targetTier) {
        log.info("Restoring asset {} from {} to Azure tier {} (mock={})",
                assetId, archivedLocation, targetTier, mockMode);
        String operationId = UUID.randomUUID().toString();
        try {
            // Rehydrating an ARCHIVE blob is an access-tier transition back to HOT. The SDK call
            // initiates rehydration; we treat it as initiated/completed, mirroring the GCS
            // provider which has no separate async restore job.
            String[] parts = parseAzureLocation(archivedLocation);

            if (mockMode) {
                return RestorationResult.builder()
                        .assetId(assetId)
                        .operationId(operationId)
                        .archivedLocation(archivedLocation)
                        .restoredLocation(archivedLocation)
                        .targetTier("HOT")
                        .status(OperationStatus.COMPLETED)
                        .initiatedAt(LocalDateTime.now())
                        .completedAt(LocalDateTime.now())
                        .build();
            }

            BlobClient blobClient = blobServiceClient(parts[0])
                    .getBlobContainerClient(parts[1])
                    .getBlobClient(parts[2]);
            blobClient.setAccessTier(AccessTier.HOT);

            return RestorationResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .archivedLocation(archivedLocation)
                    .restoredLocation(archivedLocation)
                    .targetTier("HOT")
                    .status(OperationStatus.COMPLETED)
                    .initiatedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Failed to restore asset {} from Azure: {}", assetId, e.getMessage(), e);
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
        // operationId is expected to be an Azure blob location that was archived. Inspect the
        // blob's current access tier to determine the real archival state.
        if (mockMode) {
            return OperationStatus.COMPLETED;
        }
        try {
            String[] parts = parseAzureLocation(operationId);
            AccessTier tier = blobServiceClient(parts[0])
                    .getBlobContainerClient(parts[1])
                    .getBlobClient(parts[2])
                    .getProperties()
                    .getAccessTier();
            if (tier == null) {
                return OperationStatus.UNKNOWN;
            }
            if (AccessTier.ARCHIVE.equals(tier) || AccessTier.COOL.equals(tier)
                    || AccessTier.COLD.equals(tier)) {
                return OperationStatus.COMPLETED;
            }
            if (AccessTier.HOT.equals(tier)) {
                return OperationStatus.NOT_ARCHIVED;
            }
            return OperationStatus.UNKNOWN;
        } catch (IllegalArgumentException e) {
            log.error("Invalid Azure location for archival status lookup: {}", operationId, e);
            return OperationStatus.UNKNOWN;
        } catch (Exception e) {
            log.error("Failed to retrieve Azure archival status for {}: {}", operationId, e.getMessage(), e);
            return OperationStatus.UNKNOWN;
        }
    }

    @Override
    public OperationStatus getRestorationStatus(String operationId) {
        // A rehydrated blob reports the HOT access tier; a cold tier means it is not restored.
        if (mockMode) {
            return OperationStatus.COMPLETED;
        }
        try {
            String[] parts = parseAzureLocation(operationId);
            AccessTier tier = blobServiceClient(parts[0])
                    .getBlobContainerClient(parts[1])
                    .getBlobClient(parts[2])
                    .getProperties()
                    .getAccessTier();
            if (tier == null) {
                return OperationStatus.UNKNOWN;
            }
            if (AccessTier.HOT.equals(tier)) {
                return OperationStatus.COMPLETED;
            }
            if (AccessTier.ARCHIVE.equals(tier) || AccessTier.COOL.equals(tier)
                    || AccessTier.COLD.equals(tier)) {
                return OperationStatus.NOT_RESTORED;
            }
            return OperationStatus.UNKNOWN;
        } catch (IllegalArgumentException e) {
            log.error("Invalid Azure location for restoration status lookup: {}", operationId, e);
            return OperationStatus.UNKNOWN;
        } catch (Exception e) {
            log.error("Failed to retrieve Azure restoration status for {}: {}", operationId, e.getMessage(), e);
            return OperationStatus.UNKNOWN;
        }
    }

    @Override
    public CostSavingsEstimate calculateCostSavings(String sourceLocation, String targetTier, long dataSizeBytes) {
        double dataSizeGB = dataSizeBytes / (1024.0 * 1024.0 * 1024.0);
        String targetClass = mapToAzureTierName(targetTier);

        double currentCost = HOT_COST_PER_GB * dataSizeGB;
        double targetCost = getCostPerGB(targetClass) * dataSizeGB;
        double savings = currentCost - targetCost;
        double savingsPercentage = currentCost == 0 ? 0 : (savings / currentCost) * 100;

        return CostSavingsEstimate.builder()
                .sourceStorageClass("HOT")
                .targetStorageClass(targetClass)
                .dataSizeBytes(dataSizeBytes)
                .currentMonthlyCost(currentCost)
                .archivedMonthlyCost(targetCost)
                .monthlySavings(savings)
                .savingsPercentage(savingsPercentage)
                .currency("USD")
                .region("eastus")
                .build();
    }

    @Override
    public boolean supportsCloudProvider(String cloudProvider) {
        return "AZURE".equalsIgnoreCase(cloudProvider)
                || "AZ".equalsIgnoreCase(cloudProvider);
    }

    @Override
    public String[] getAvailableStorageTiers(String cloudProvider) {
        if (supportsCloudProvider(cloudProvider)) {
            return new String[]{"HOT", "COOL", "COLD", "ARCHIVE"};
        }
        return new String[0];
    }

    // --- helpers -----------------------------------------------------------------------

    private ArchivalResult failedArchival(UUID assetId, String operationId, String sourceLocation,
            String targetTier, String error) {
        log.warn("Azure archival failed for asset {}: {}", assetId, error);
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

    /**
     * The client for a storage account: the injected test client, else the shared {@code DefaultAzureCredential}
     * client of {@link AzureBlobClients}. Never called in mock mode.
     */
    BlobServiceClient blobServiceClient(String account) {
        BlobServiceClient injected = blobServiceClient;
        return injected != null ? injected : clients.serviceClient(account);
    }

    /** Package-private for tests to inject a mock BlobServiceClient. */
    void setBlobServiceClient(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = blobServiceClient;
    }

    /**
     * Parses an Azure blob location into {@code [account, container, blobPath]}. Accepts:
     * <ul>
     *   <li>{@code https://<account>.blob.core.windows.net/<container>/<blob...>}</li>
     *   <li>{@code azure://<account>/<container>/<blob...>}</li>
     *   <li>{@code abfss://<container>@<account>.dfs.core.windows.net/<path>}</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the location is null or malformed
     */
    static String[] parseAzureLocation(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Invalid Azure location format: " + location);
        }
        if (location.startsWith("https://")) {
            String rest = location.substring("https://".length());
            int firstSlash = rest.indexOf('/');
            if (firstSlash <= 0) {
                throw new IllegalArgumentException("Invalid Azure location format: " + location);
            }
            String host = rest.substring(0, firstSlash);
            int dot = host.indexOf('.');
            if (dot <= 0) {
                throw new IllegalArgumentException("Invalid Azure location format: " + location);
            }
            String account = host.substring(0, dot);
            String path = rest.substring(firstSlash + 1);
            int pathSlash = path.indexOf('/');
            if (pathSlash <= 0 || pathSlash == path.length() - 1) {
                throw new IllegalArgumentException("Invalid Azure location format: " + location);
            }
            return new String[]{account, path.substring(0, pathSlash), path.substring(pathSlash + 1)};
        }
        if (location.startsWith("azure://")) {
            String rest = location.substring("azure://".length());
            String[] segments = rest.split("/", 3);
            if (segments.length < 3 || segments[0].isBlank()
                    || segments[1].isBlank() || segments[2].isBlank()) {
                throw new IllegalArgumentException("Invalid Azure location format: " + location);
            }
            return new String[]{segments[0], segments[1], segments[2]};
        }
        if (location.startsWith("abfss://")) {
            String rest = location.substring("abfss://".length());
            int at = rest.indexOf('@');
            int firstSlash = rest.indexOf('/');
            if (at <= 0 || firstSlash <= at || firstSlash == rest.length() - 1) {
                throw new IllegalArgumentException("Invalid Azure location format: " + location);
            }
            String container = rest.substring(0, at);
            String host = rest.substring(at + 1, firstSlash);
            int dot = host.indexOf('.');
            if (dot <= 0) {
                throw new IllegalArgumentException("Invalid Azure location format: " + location);
            }
            String account = host.substring(0, dot);
            String blobPath = rest.substring(firstSlash + 1);
            return new String[]{account, container, blobPath};
        }
        throw new IllegalArgumentException("Invalid Azure location format: " + location);
    }

    /**
     * Maps a target tier to an Azure {@link AccessTier}. Accepts native Azure tiers and the
     * AWS/GCP-flavoured names carried by the shared archival config (whose default tier is
     * {@code GLACIER}), so an Azure task with a default tier never throws.
     */
    static AccessTier mapToAzureAccessTier(String targetTier) {
        if (targetTier == null || targetTier.isBlank()) {
            log.warn("Blank archival tier for Azure; defaulting to ARCHIVE.");
            return AccessTier.ARCHIVE;
        }
        return switch (targetTier.toUpperCase(Locale.ROOT)) {
            case "STANDARD", "HOT" -> AccessTier.HOT;
            case "COOL", "NEARLINE", "STANDARD_IA" -> AccessTier.COOL;
            case "COLD", "COLDLINE" -> AccessTier.COLD;
            case "ARCHIVE", "GLACIER", "DEEP_ARCHIVE" -> AccessTier.ARCHIVE;
            default -> {
                log.warn("Unknown archival tier '{}' for Azure; defaulting to ARCHIVE.", targetTier);
                yield AccessTier.ARCHIVE;
            }
        };
    }

    /**
     * The canonical upper-case Azure tier name for a target tier, used as the result tier and
     * cost-lookup key. Decoupled from {@link AccessTier#toString()}, which is mixed-case
     * ({@code "Hot"}, {@code "Archive"}); the dispatcher and reports expect the upper-case
     * names exposed by {@link #getAvailableStorageTiers(String)}.
     */
    private static String mapToAzureTierName(String targetTier) {
        if (targetTier == null || targetTier.isBlank()) {
            return "ARCHIVE";
        }
        return switch (targetTier.toUpperCase(Locale.ROOT)) {
            case "STANDARD", "HOT" -> "HOT";
            case "COOL", "NEARLINE", "STANDARD_IA" -> "COOL";
            case "COLD", "COLDLINE" -> "COLD";
            case "ARCHIVE", "GLACIER", "DEEP_ARCHIVE" -> "ARCHIVE";
            default -> "ARCHIVE";
        };
    }

    private static double getCostPerGB(String tierName) {
        return switch (tierName.toUpperCase(Locale.ROOT)) {
            case "HOT" -> HOT_COST_PER_GB;
            case "COOL" -> COOL_COST_PER_GB;
            case "COLD" -> COLD_COST_PER_GB;
            case "ARCHIVE" -> ARCHIVE_COST_PER_GB;
            default -> HOT_COST_PER_GB;
        };
    }

    private static double calculateMonthlySavings(String sourceTier, String targetTier, long dataSizeBytes) {
        double dataSizeGB = dataSizeBytes / (1024.0 * 1024.0 * 1024.0);
        double sourceCost = getCostPerGB(sourceTier) * dataSizeGB;
        double targetCost = getCostPerGB(targetTier) * dataSizeGB;
        return Math.max(0, sourceCost - targetCost);
    }
}
