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

package com.dlap.autodelete.service.storage.impl;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosException;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.dlap.autodelete.service.storage.BulkDeletionResult;
import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;
import com.dlap.autodelete.service.storage.DeletionVerificationResult;
import com.dlap.autodelete.service.storage.ICloudDeletionService;
import com.dlap.autodelete.service.storage.StorageReclaimEstimate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real Azure implementation of {@link ICloudDeletionService}.
 *
 * <p>Uses the Azure SDK to delete Blob Storage blobs. The specific sub-provider
 * is selected by parsing the {@code dataLocation} URI:
 *
 * <ul>
 *   <li><code>azure://account/container/blob/path</code> &rarr; Blob delete</li>
 *   <li><code>https://account.blob.core.windows.net/container/blob/path</code> &rarr; Blob delete</li>
 * </ul>
 *
 * <p>Credentials resolve via {@link DefaultAzureCredentialBuilder} (environment,
 * managed identity, Azure CLI, etc.) - do not log or persist any credential
 * material.
 *
 * <p>NOTE on scope: this implementation covers Blob Storage only. Cosmos DB,
 * Azure SQL and other Azure services are not yet supported; unsupported
 * locations return a {@code FAILED} {@link DeletionResult} so the caller sees
 * a loud, typed failure rather than a silent success.
 *
 * <p>TODO(M1.2): use per-CloudConnection credentials once asset&rarr;catalog
 * linkage lands. Currently every call uses ambient Azure credentials.
 */
@Service("azureCloudDeletionService")
@Slf4j
public class AzureCloudDeletionService implements ICloudDeletionService {

    static final String PROVIDER_AZURE = "AZURE";

    // In-memory cache of operation -> last known deletion result. This is only a thin
    // convenience for getDeletionStatus / verifyDeletion during a single JVM lifetime;
    // the persisted compliance trail lives in DeletionAuditEntity.
    private final Map<String, DeletionResult> operations = new ConcurrentHashMap<>();

    private final boolean mockMode;

    public AzureCloudDeletionService(
        @Value("${dlap.autodelete.azure.mock-mode:false}") boolean mockMode) {
        this.mockMode = mockMode;
        if (mockMode) {
            log.warn("AzureCloudDeletionService running in MOCK MODE. No real cloud deletions will occur. "
                + "Set dlap.autodelete.azure.mock-mode=false for production.");
        }
    }

    @Override
    public DeletionResult deleteData(UUID assetId, String dataLocation, DeletionType deletionType,
                                     Map<String, String> metadata) {
        String operationId = "azure-" + UUID.randomUUID();
        LocalDateTime initiated = LocalDateTime.now();
        log.info("Azure deleteData: assetId={}, operationId={}, dataLocation={}, type={}",
            assetId, operationId, dataLocation, deletionType);

        DeletionResult.DeletionResultBuilder result = DeletionResult.builder()
            .assetId(assetId)
            .operationId(operationId)
            .dataLocation(dataLocation)
            .deletionType(deletionType)
            .initiatedAt(initiated)
            .metadata(metadata);

        try {
            AzureResourceRef blobRef = AzureResourceRef.parse(dataLocation);
            CosmosRef cosmosRef = CosmosRef.parse(dataLocation);
            SqlDatabaseRef sqlRef = SqlDatabaseRef.parse(dataLocation);
            if (blobRef == null && cosmosRef == null && sqlRef == null) {
                log.error("Azure deleteData: unrecognised URI format assetId={} dataLocation={}", assetId, dataLocation);
                return result
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("Unrecognised Azure data location: " + dataLocation
                        + " (expected blob URL, azurecosmos://account/database/container, "
                        + "or azuresql://subscription/resourceGroup/server/database)")
                    .build();
            }

            if (mockMode) {
                DeletionResult r = result
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage(null)
                    .build();
                operations.put(operationId, r);
                return r;
            }

            DeletionResult real;
            if (blobRef != null) {
                real = deleteBlobInternal(result, blobRef);
            } else if (cosmosRef != null) {
                real = deleteCosmosContainerInternal(result, cosmosRef);
            } else {
                real = deleteSqlDatabaseInternal(result, sqlRef);
            }
            operations.put(operationId, real);
            return real;
        } catch (BlobStorageException bse) {
            log.error("Azure deleteData: Blob SDK failure assetId={} operationId={} statusCode={}",
                assetId, operationId, bse.getStatusCode(), bse);
            // 404 on delete is idempotent success.
            if (bse.getStatusCode() == 404) {
                return result
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(0L)
                    .errorMessage("Blob not found (already deleted) - idempotent success")
                    .build();
            }
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("Azure Blob API error: " + safeMessage(bse))
                .build();
        } catch (RuntimeException ex) {
            log.error("Azure deleteData: unexpected failure assetId={} operationId={}", assetId, operationId, ex);
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("Unexpected error: " + safeMessage(ex))
                .build();
        }
    }

    private DeletionResult deleteBlobInternal(DeletionResult.DeletionResultBuilder builder, AzureResourceRef ref) {
        BlobClient blob = resolveBlobClient(ref);

        Long sizeBytes = null;
        try {
            BlobProperties props = blob.getProperties();
            sizeBytes = props.getBlobSize();
        } catch (BlobStorageException probeFail) {
            if (probeFail.getStatusCode() == 404) {
                log.debug("Azure size-probe: blob already absent {} - proceeding with idempotent delete",
                    ref.fullPath());
                return builder
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(0L)
                    .errorMessage("Blob not found (already deleted) - idempotent success")
                    .build();
            }
            log.debug("Azure size-probe failed for {} (status={}) - continuing with delete anyway",
                ref.fullPath(), probeFail.getStatusCode(), probeFail);
        }

        blob.delete();

        return builder
            .status(DeletionStatus.COMPLETED)
            .completedAt(LocalDateTime.now())
            .dataSizeBytes(sizeBytes)
            .build();
    }

    private DeletionResult deleteCosmosContainerInternal(DeletionResult.DeletionResultBuilder builder, CosmosRef ref) {
        try (CosmosClient client = new CosmosClientBuilder()
                .endpoint("https://" + ref.account() + ".documents.azure.com:443/")
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient()) {
            client.getDatabase(ref.databaseName()).getContainer(ref.containerName()).delete();
            return builder
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(null)
                    .build();
        } catch (CosmosException ce) {
            if (ce.getStatusCode() == 404) {
                return builder
                        .status(DeletionStatus.COMPLETED)
                        .completedAt(LocalDateTime.now())
                        .dataSizeBytes(0L)
                        .errorMessage("Cosmos container not found (already deleted) - idempotent success")
                        .build();
            }
            log.error("Azure deleteData: Cosmos failure statusCode={}", ce.getStatusCode(), ce);
            return builder
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("Cosmos API error: " + safeMessage(ce))
                    .build();
        }
    }

    private DeletionResult deleteSqlDatabaseInternal(DeletionResult.DeletionResultBuilder builder, SqlDatabaseRef ref) {
        try {
            AzureProfile profile = new AzureProfile(AzureEnvironment.AZURE);
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            AzureResourceManager arm = AzureResourceManager.authenticate(credential, profile)
                    .withSubscription(ref.subscriptionId());
            SqlServer server = arm.sqlServers().getByResourceGroup(ref.resourceGroup(), ref.serverName());
            server.databases().delete(ref.databaseName());
            return builder
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(null)
                    .build();
        } catch (ManagementException me) {
            log.error("Azure deleteData: ARM SQL failure: {}", safeMessage(me), me);
            return builder
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("Azure SQL database delete error: " + safeMessage(me))
                    .build();
        }
    }

    @Override
    public BulkDeletionResult bulkDeleteData(List<UUID> assetIds, List<String> dataLocations,
                                             DeletionType deletionType, Map<String, String> metadata) {
        if (assetIds == null || dataLocations == null || assetIds.size() != dataLocations.size()) {
            throw new IllegalArgumentException("assetIds and dataLocations must be non-null and same length");
        }
        String bulkOperationId = "azure-bulk-" + UUID.randomUUID();
        LocalDateTime initiated = LocalDateTime.now();
        List<DeletionResult> results = new ArrayList<>(assetIds.size());
        int success = 0;
        int fail = 0;
        long totalBytes = 0L;

        for (int i = 0; i < assetIds.size(); i++) {
            DeletionResult r = deleteData(assetIds.get(i), dataLocations.get(i), deletionType, metadata);
            results.add(r);
            if (r.getStatus() == DeletionStatus.COMPLETED) {
                success++;
                if (r.getDataSizeBytes() != null) {
                    totalBytes += r.getDataSizeBytes();
                }
            } else {
                fail++;
            }
        }

        DeletionStatus overall;
        if (fail == 0) {
            overall = DeletionStatus.COMPLETED;
        } else if (success == 0) {
            overall = DeletionStatus.FAILED;
        } else {
            overall = DeletionStatus.IN_PROGRESS; // partial - repo/consumer reinterprets upstream
        }

        return BulkDeletionResult.builder()
            .bulkOperationId(bulkOperationId)
            .individualResults(results)
            .totalItems(assetIds.size())
            .successfulDeletions(success)
            .failedDeletions(fail)
            .overallStatus(overall)
            .initiatedAt(initiated)
            .completedAt(LocalDateTime.now())
            .totalStorageReclaimed(totalBytes / (1024.0 * 1024.0 * 1024.0))
            .build();
    }

    @Override
    public DeletionStatus getDeletionStatus(String operationId) {
        DeletionResult r = operations.get(operationId);
        return r != null ? r.getStatus() : DeletionStatus.FAILED;
    }

    @Override
    public StorageReclaimEstimate calculateStorageReclaim(List<String> dataLocations) {
        if (dataLocations == null || dataLocations.isEmpty() || mockMode) {
            return StorageReclaimEstimate.builder()
                .totalObjects(0).totalSizeBytes(0L).totalSizeGB(0.0)
                .currency("USD").build();
        }
        long totalBytes = 0L;
        int totalObjects = 0;
        try {
            for (String loc : dataLocations) {
                AzureResourceRef ref = AzureResourceRef.parse(loc);
                if (ref == null) {
                    continue;
                }
                try {
                    BlobClient blob = resolveBlobClient(ref);
                    BlobProperties props = blob.getProperties();
                    totalBytes += props.getBlobSize();
                    totalObjects++;
                } catch (BlobStorageException probeFail) {
                    log.debug("Azure reclaim probe failed for {} (status={})", loc, probeFail.getStatusCode());
                } catch (RuntimeException probeFail) {
                    log.debug("Azure reclaim probe failed for {}", loc, probeFail);
                }
            }
        } catch (RuntimeException sdkFail) {
            log.warn("Azure calculateStorageReclaim: SDK unavailable - returning zeroes", sdkFail);
        }
        double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
        return StorageReclaimEstimate.builder()
            .totalObjects(totalObjects)
            .totalSizeBytes(totalBytes)
            .totalSizeGB(totalGb)
            .currency("USD")
            .build();
    }

    @Override
    public DeletionVerificationResult verifyDeletion(String operationId) {
        LocalDateTime ts = LocalDateTime.now();
        DeletionResult prior = operations.get(operationId);
        DeletionVerificationResult.DeletionVerificationResultBuilder b = DeletionVerificationResult.builder()
            .operationId(operationId)
            .verificationTimestamp(ts)
            .verificationMethod("azure-sdk-head-check")
            .auditTrail("verifier=AzureCloudDeletionService; checked-at=" + ts);

        if (prior == null) {
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(Collections.singletonList("No prior operation state for id " + operationId))
                .build();
        }
        if (mockMode) {
            return b.isCompletelyDeleted(true)
                .isCompliant(true)
                .complianceStandards(List.of("MOCK"))
                .remainingTraces(Collections.emptyList())
                .build();
        }

        try {
            AzureResourceRef blobRef = AzureResourceRef.parse(prior.getDataLocation());
            CosmosRef cosmosRef = CosmosRef.parse(prior.getDataLocation());
            SqlDatabaseRef sqlRef = SqlDatabaseRef.parse(prior.getDataLocation());
            if (blobRef == null && cosmosRef == null && sqlRef == null) {
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .remainingTraces(List.of("Unparseable data location: " + prior.getDataLocation()))
                    .build();
            }
            if (blobRef != null) {
                return verifyBlobDeleted(b, blobRef, prior, operationId);
            }
            if (cosmosRef != null) {
                return verifyCosmosContainerDeleted(b, cosmosRef, operationId);
            }
            return verifySqlDatabaseDeleted(b, sqlRef, operationId);
        } catch (RuntimeException ex) {
            log.error("Azure verifyDeletion: probe failure operationId={}", operationId, ex);
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(List.of("Verification probe error: " + safeMessage(ex)))
                .build();
        }
    }

    @Override
    public boolean supportsCloudProvider(String cloudProvider) {
        return PROVIDER_AZURE.equalsIgnoreCase(cloudProvider);
    }

    @Override
    public DeletionType[] getAvailableDeletionTypes(String cloudProvider) {
        if (!supportsCloudProvider(cloudProvider)) {
            return new DeletionType[0];
        }
        return new DeletionType[] { DeletionType.HARD, DeletionType.IMMEDIATE };
    }

    /**
     * Builds a {@link BlobClient} for the given reference using the default Azure
     * credential chain.
     *
     * <p>TODO(M1.2): use per-CloudConnection credentials once asset&rarr;catalog
     * linkage lands. Currently every call creates a short-lived service client.
     */
    BlobClient resolveBlobClient(AzureResourceRef ref) {
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
            .endpoint("https://" + ref.account() + ".blob.core.windows.net")
            .credential(credential)
            .buildClient();
        BlobContainerClient container = serviceClient.getBlobContainerClient(ref.container());
        return container.getBlobClient(ref.blobPath());
    }

    private DeletionVerificationResult verifyBlobDeleted(
            DeletionVerificationResult.DeletionVerificationResultBuilder b,
            AzureResourceRef ref,
            DeletionResult prior,
            String operationId) {
        BlobClient blob = resolveBlobClient(ref);
        try {
            blob.getProperties();
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .complianceStandards(List.of("GDPR", "HIPAA"))
                .remainingTraces(List.of("Resource still present at " + prior.getDataLocation()))
                .build();
        } catch (BlobStorageException bse) {
            if (bse.getStatusCode() == 404) {
                return b.isCompletelyDeleted(true)
                    .isCompliant(true)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(Collections.emptyList())
                    .build();
            }
            log.error("Azure verifyDeletion: unexpected Blob error operationId={} statusCode={}",
                operationId, bse.getStatusCode(), bse);
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(List.of("Verification probe error (status=" + bse.getStatusCode()
                    + "): " + safeMessage(bse)))
                .build();
        }
    }

    private DeletionVerificationResult verifyCosmosContainerDeleted(
            DeletionVerificationResult.DeletionVerificationResultBuilder b,
            CosmosRef ref,
            String operationId) {
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        CosmosClient client = new CosmosClientBuilder()
                .endpoint("https://" + ref.account() + ".documents.azure.com:443/")
                .credential(credential)
                .buildClient();
        try {
            client.getDatabase(ref.databaseName()).getContainer(ref.containerName()).read();
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .complianceStandards(List.of("GDPR", "HIPAA"))
                .remainingTraces(List.of("Cosmos container still present: " + ref.databaseName() + "/" + ref.containerName()))
                .build();
        } catch (CosmosException ce) {
            if (ce.getStatusCode() == 404) {
                return b.isCompletelyDeleted(true)
                    .isCompliant(true)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(Collections.emptyList())
                    .build();
            }
            log.error("Azure verifyDeletion: Cosmos error operationId={}", operationId, ce);
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(List.of("Cosmos verification error: " + safeMessage(ce)))
                .build();
        } finally {
            client.close();
        }
    }

    private DeletionVerificationResult verifySqlDatabaseDeleted(
            DeletionVerificationResult.DeletionVerificationResultBuilder b,
            SqlDatabaseRef ref,
            String operationId) {
        try {
            AzureProfile profile = new AzureProfile(AzureEnvironment.AZURE);
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            AzureResourceManager arm = AzureResourceManager.authenticate(credential, profile)
                    .withSubscription(ref.subscriptionId());
            var server = arm.sqlServers().getByResourceGroup(ref.resourceGroup(), ref.serverName());
            boolean exists = server.databases().list().stream()
                    .anyMatch(d -> ref.databaseName().equalsIgnoreCase(d.name()));
            if (!exists) {
                return b.isCompletelyDeleted(true)
                    .isCompliant(true)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(Collections.emptyList())
                    .build();
            }
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .complianceStandards(List.of("GDPR", "HIPAA"))
                .remainingTraces(List.of("SQL database still present: " + ref.databaseName()))
                .build();
        } catch (ManagementException me) {
            if (me.getResponse() != null && me.getResponse().getStatusCode() == 404) {
                return b.isCompletelyDeleted(true)
                    .isCompliant(true)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(Collections.emptyList())
                    .build();
            }
            log.error("Azure verifyDeletion: ARM SQL error operationId={}", operationId, me);
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(List.of("SQL verification error: " + safeMessage(me)))
                .build();
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    record CosmosRef(String account, String databaseName, String containerName) {

        static CosmosRef parse(String location) {
            if (location == null || location.isBlank()) {
                return null;
            }
            String trimmed = location.trim();
            if (!trimmed.startsWith("azurecosmos://")) {
                return null;
            }
            String rest = trimmed.substring("azurecosmos://".length());
            String[] parts = rest.split("/", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return null;
            }
            return new CosmosRef(parts[0], parts[1], parts[2]);
        }
    }

    record SqlDatabaseRef(String subscriptionId, String resourceGroup, String serverName, String databaseName) {

        static SqlDatabaseRef parse(String location) {
            if (location == null || location.isBlank()) {
                return null;
            }
            String trimmed = location.trim();
            if (!trimmed.startsWith("azuresql://")) {
                return null;
            }
            String rest = trimmed.substring("azuresql://".length());
            String[] parts = rest.split("/", 4);
            if (parts.length != 4 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()) {
                return null;
            }
            return new SqlDatabaseRef(parts[0], parts[1], parts[2], parts[3]);
        }
    }

    /**
     * Typed reference to an Azure Blob Storage resource parsed from a location string.
     * Accepted forms:
     * <ul>
     *   <li>{@code azure://account/container/blob/path}</li>
     *   <li>{@code https://account.blob.core.windows.net/container/blob/path}</li>
     * </ul>
     */
    record AzureResourceRef(String account, String container, String blobPath) {

        String fullPath() {
            return account + "/" + container + "/" + blobPath;
        }

        static AzureResourceRef parse(String location) {
            if (location == null || location.isBlank()) {
                return null;
            }
            String trimmed = location.trim();
            if (trimmed.startsWith("azure://")) {
                String rest = trimmed.substring("azure://".length());
                int firstSlash = rest.indexOf('/');
                if (firstSlash <= 0 || firstSlash == rest.length() - 1) {
                    return null;
                }
                String account = rest.substring(0, firstSlash);
                String containerAndBlob = rest.substring(firstSlash + 1);
                int secondSlash = containerAndBlob.indexOf('/');
                if (secondSlash <= 0 || secondSlash == containerAndBlob.length() - 1) {
                    return null;
                }
                String container = containerAndBlob.substring(0, secondSlash);
                String blob = containerAndBlob.substring(secondSlash + 1);
                if (account.isBlank() || container.isBlank() || blob.isBlank()) {
                    return null;
                }
                return new AzureResourceRef(account, container, blob);
            }
            if (trimmed.startsWith("https://")) {
                // https://account.blob.core.windows.net/container/blob/path
                String rest = trimmed.substring("https://".length());
                int firstSlash = rest.indexOf('/');
                if (firstSlash <= 0 || firstSlash == rest.length() - 1) {
                    return null;
                }
                String host = rest.substring(0, firstSlash);
                String path = rest.substring(firstSlash + 1);
                String suffix = ".blob.core.windows.net";
                if (!host.endsWith(suffix)) {
                    return null;
                }
                String account = host.substring(0, host.length() - suffix.length());
                int secondSlash = path.indexOf('/');
                if (secondSlash <= 0 || secondSlash == path.length() - 1) {
                    return null;
                }
                String container = path.substring(0, secondSlash);
                String blob = path.substring(secondSlash + 1);
                if (account.isBlank() || container.isBlank() || blob.isBlank()) {
                    return null;
                }
                return new AzureResourceRef(account, container, blob);
            }
            return null;
        }
    }
}
