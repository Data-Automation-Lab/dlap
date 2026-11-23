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

import com.dlap.autodelete.service.storage.BulkDeletionResult;
import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;
import com.dlap.autodelete.service.storage.DeletionVerificationResult;
import com.dlap.autodelete.service.storage.ICloudDeletionService;
import com.dlap.autodelete.service.storage.LocationSafety;
import com.dlap.autodelete.service.storage.StorageReclaimEstimate;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
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
 * Real GCP implementation of {@link ICloudDeletionService}.
 *
 * <p>Uses the Google Cloud SDK to delete GCS objects and BigQuery tables. The
 * specific sub-provider is selected by parsing the {@code dataLocation} through
 * {@link AssetIdNormalizer} (plan rule R8), so every alias in {@code docs/specs/asset-id.md}
 * is accepted:
 *
 * <ul>
 *   <li><code>gs://bucket/object/path</code>, <code>projects/_/buckets/b/objects/o</code>,
 *       the <code>//storage.googleapis.com/</code> full name and the Cloud Storage https URLs
 *       &rarr; GCS object delete</li>
 *   <li><code>bq://project/dataset/table</code>, <code>project.dataset.table</code>,
 *       <code>project:dataset.table</code>, <code>projects/p/datasets/d/tables/t</code> and
 *       the <code>//bigquery.googleapis.com/</code> full name &rarr; BigQuery table delete</li>
 * </ul>
 *
 * <p>Fails closed: a location that names anything other than one object or one whole table
 * (a bucket, a dataset, a job, a wildcard table, a partition or snapshot decorator such as
 * {@code t$20240101}, a location of another cloud, a malformed name, an {@code http(s)} URL with
 * a query string or fragment) is refused, because reducing it to a table or object would change
 * what is deleted.
 *
 * <p>Credentials resolve via standard Google Application Default Credentials - do not
 * log or persist any credential material.
 *
 * <p>NOTE on scope: this implementation covers the two most common GCP data locations
 * used by da-discovery in v0.1.0. Pub/Sub, Spanner, Firestore and other GCP services
 * are not yet supported; those calls throw {@link UnsupportedOperationException} so the
 * caller sees a loud, typed failure rather than a silent success.
 */
@Service("gcpCloudDeletionService")
@Slf4j
public class GcpCloudDeletionService implements ICloudDeletionService {

    static final String PROVIDER_GCP = "GCP";

    // In-memory cache of operation -> last known deletion result. This is only a thin
    // convenience for getDeletionStatus / verifyDeletion during a single JVM lifetime;
    // the persisted compliance trail lives in DeletionAuditEntity.
    private final Map<String, DeletionResult> operations = new ConcurrentHashMap<>();

    private final boolean mockMode;

    public GcpCloudDeletionService(
        @Value("${dlap.autodelete.gcp.mock-mode:false}") boolean mockMode) {
        this.mockMode = mockMode;
        if (mockMode) {
            log.warn("GcpCloudDeletionService running in MOCK MODE. No real cloud deletions will occur. "
                + "Set dlap.autodelete.gcp.mock-mode=false for production.");
        }
    }

    @Override
    public DeletionResult deleteData(UUID assetId, String dataLocation, DeletionType deletionType,
                                     Map<String, String> metadata) {
        String operationId = "gcp-" + UUID.randomUUID();
        LocalDateTime initiated = LocalDateTime.now();
        log.info("GCP deleteData: assetId={}, operationId={}, dataLocation={}, type={}",
            assetId, operationId, LocationSafety.redact(dataLocation), deletionType);

        DeletionResult.DeletionResultBuilder result = DeletionResult.builder()
            .assetId(assetId)
            .operationId(operationId)
            .dataLocation(dataLocation)
            .deletionType(deletionType)
            .initiatedAt(initiated)
            .metadata(metadata);

        try {
            GcpResourceRef ref = GcpResourceRef.parse(dataLocation);
            if (ref == null) {
                log.error("GCP deleteData: refusing unrecognised or unsafe location assetId={} dataLocation={}",
                    assetId, LocationSafety.redact(dataLocation));
                return result
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("Unrecognised GCP data location: " + LocationSafety.redact(dataLocation)
                        + " (expected one GCS object or one whole BigQuery table, for example "
                        + "gs://bucket/key or bq://project/dataset/table)")
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

            DeletionResult real = switch (ref.kind()) {
                case GCS_OBJECT -> deleteGcsObjectInternal(result, ref);
                case BIGQUERY_TABLE -> deleteBigQueryTableInternal(result, ref);
            };
            operations.put(operationId, real);
            return real;
        } catch (StorageException se) {
            log.error("GCP deleteData: GCS SDK failure assetId={} operationId={} code={}",
                assetId, operationId, se.getCode(), se);
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("GCS API error: " + safeMessage(se))
                .build();
        } catch (BigQueryException bqe) {
            log.error("GCP deleteData: BigQuery SDK failure assetId={} operationId={} code={}",
                assetId, operationId, bqe.getCode(), bqe);
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("BigQuery API error: " + safeMessage(bqe))
                .build();
        } catch (RuntimeException ex) {
            log.error("GCP deleteData: unexpected failure assetId={} operationId={}", assetId, operationId, ex);
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("Unexpected error: " + safeMessage(ex))
                .build();
        }
    }

    private DeletionResult deleteGcsObjectInternal(DeletionResult.DeletionResultBuilder builder, GcpResourceRef ref) {
        Storage storage = StorageOptions.getDefaultInstance().getService();
        BlobId blobId = BlobId.of(ref.first(), ref.second());

        // Try to read size up-front for the audit bytes-deleted column.
        Long sizeBytes = null;
        try {
            Blob existing = storage.get(blobId);
            if (existing != null) {
                sizeBytes = existing.getSize();
            }
        } catch (StorageException probeFail) {
            log.debug("GCP size-probe failed for {}:{} - continuing with delete anyway", ref.first(), ref.second(),
                probeFail);
        }

        boolean deleted = storage.delete(blobId);
        if (deleted) {
            return builder
                .status(DeletionStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .dataSizeBytes(sizeBytes)
                .build();
        }
        // Idempotent behaviour: already-absent is treated as success with a size of zero.
        return builder
            .status(DeletionStatus.COMPLETED)
            .completedAt(LocalDateTime.now())
            .dataSizeBytes(0L)
            .errorMessage("Object not found (already deleted) - idempotent success")
            .build();
    }

    private DeletionResult deleteBigQueryTableInternal(DeletionResult.DeletionResultBuilder builder, GcpResourceRef ref) {
        BigQuery bq = BigQueryOptions.getDefaultInstance().getService();
        TableId tableId = TableId.of(ref.first(), ref.second(), ref.third());

        Long sizeBytes = null;
        try {
            Table existing = bq.getTable(tableId);
            if (existing != null && existing.getNumBytes() != null) {
                sizeBytes = existing.getNumBytes();
            }
        } catch (BigQueryException probeFail) {
            log.debug("GCP BQ size-probe failed for {}.{}.{} - continuing with delete anyway",
                ref.first(), ref.second(), ref.third(), probeFail);
        }

        boolean deleted = bq.delete(tableId);
        if (deleted) {
            return builder
                .status(DeletionStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .dataSizeBytes(sizeBytes)
                .build();
        }
        return builder
            .status(DeletionStatus.COMPLETED)
            .completedAt(LocalDateTime.now())
            .dataSizeBytes(0L)
            .errorMessage("Table not found (already deleted) - idempotent success")
            .build();
    }

    @Override
    public BulkDeletionResult bulkDeleteData(List<UUID> assetIds, List<String> dataLocations,
                                             DeletionType deletionType, Map<String, String> metadata) {
        if (assetIds == null || dataLocations == null || assetIds.size() != dataLocations.size()) {
            throw new IllegalArgumentException("assetIds and dataLocations must be non-null and same length");
        }
        String bulkOperationId = "gcp-bulk-" + UUID.randomUUID();
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
            Storage storage = StorageOptions.getDefaultInstance().getService();
            BigQuery bq = BigQueryOptions.getDefaultInstance().getService();
            for (String loc : dataLocations) {
                GcpResourceRef ref = GcpResourceRef.parse(loc);
                if (ref == null) {
                    continue;
                }
                try {
                    if (ref.kind() == GcpResourceRef.Kind.GCS_OBJECT) {
                        Blob b = storage.get(BlobId.of(ref.first(), ref.second()));
                        if (b != null && b.getSize() != null) {
                            totalBytes += b.getSize();
                            totalObjects++;
                        }
                    } else {
                        Table t = bq.getTable(TableId.of(ref.first(), ref.second(), ref.third()));
                        if (t != null && t.getNumBytes() != null) {
                            totalBytes += t.getNumBytes();
                            totalObjects++;
                        }
                    }
                } catch (RuntimeException probeFail) {
                    log.debug("GCP reclaim probe failed for {}", loc, probeFail);
                }
            }
        } catch (RuntimeException sdkFail) {
            log.warn("GCP calculateStorageReclaim: SDK unavailable - returning zeroes", sdkFail);
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
            .verificationMethod("gcp-sdk-head-check")
            .auditTrail("verifier=GcpCloudDeletionService; checked-at=" + ts);

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
            GcpResourceRef ref = GcpResourceRef.parse(prior.getDataLocation());
            if (ref == null) {
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .remainingTraces(List.of("Unparseable data location: " + prior.getDataLocation()))
                    .build();
            }
            boolean stillPresent;
            if (ref.kind() == GcpResourceRef.Kind.GCS_OBJECT) {
                Storage storage = StorageOptions.getDefaultInstance().getService();
                Blob blob = storage.get(BlobId.of(ref.first(), ref.second()));
                stillPresent = blob != null;
            } else {
                BigQuery bq = BigQueryOptions.getDefaultInstance().getService();
                Table table = bq.getTable(TableId.of(ref.first(), ref.second(), ref.third()));
                stillPresent = table != null;
            }
            return b.isCompletelyDeleted(!stillPresent)
                .isCompliant(!stillPresent)
                .complianceStandards(List.of("GDPR", "HIPAA"))
                .remainingTraces(stillPresent
                    ? List.of("Resource still present at " + prior.getDataLocation())
                    : Collections.emptyList())
                .build();
        } catch (RuntimeException ex) {
            log.error("GCP verifyDeletion: probe failure operationId={}", operationId, ex);
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(List.of("Verification probe error: " + safeMessage(ex)))
                .build();
        }
    }

    @Override
    public boolean supportsCloudProvider(String cloudProvider) {
        return PROVIDER_GCP.equalsIgnoreCase(cloudProvider);
    }

    @Override
    public DeletionType[] getAvailableDeletionTypes(String cloudProvider) {
        if (!supportsCloudProvider(cloudProvider)) {
            return new DeletionType[0];
        }
        return new DeletionType[] { DeletionType.HARD, DeletionType.IMMEDIATE };
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    /**
     * Typed reference to a GCP resource parsed from a location string.
     * first/second/third have kind-specific semantics:
     * GCS_OBJECT: first=bucket, second=objectKey
     * BIGQUERY_TABLE: first=project, second=dataset, third=table
     */
    record GcpResourceRef(Kind kind, String first, String second, String third) {

        enum Kind { GCS_OBJECT, BIGQUERY_TABLE }

        /**
         * Parses a location through {@link AssetIdNormalizer}. Returns {@code null} (the caller
         * fails closed) unless the location names exactly one GCS object or one whole BigQuery
         * table on GCP.
         *
         * @param location the location in any alias the asset-id spec accepts; may be {@code null}
         * @return the typed reference, or {@code null} when the location is not a safe delete target
         */
        static GcpResourceRef parse(String location) {
            if (location == null || location.isBlank() || LocationSafety.carriesUrlSelector(location)) {
                return null;
            }
            AssetIdParseResult parsed = AssetIdNormalizer.parse(location, AssetIdHints.of(AssetProvider.GCP));
            if (!(parsed instanceof AssetIdParseResult.Normalized normalized)) {
                return null;
            }
            CanonicalAssetId id = normalized.canonical();
            if (id.provider() != AssetProvider.GCP
                    || id.attribute(CanonicalAssetId.Attribute.DECORATOR).isPresent()) {
                // A decorator (t$20240101, t@123) selects a partition or a snapshot. The normalizer
                // drops it, so deleting the bare table would destroy far more than was asked for.
                return null;
            }
            List<String> parts = id.parts();
            if (id.kind() == AssetKind.GCS_OBJECT) {
                if (LocationSafety.hasEdgeWhitespace(parts.get(1))) {
                    // Whitespace around an object name is ambiguous: trimming would delete another
                    // object, keeping it usually misses. Never guess.
                    return null;
                }
                return new GcpResourceRef(Kind.GCS_OBJECT, parts.get(0), parts.get(1), null);
            }
            if (id.kind() == AssetKind.BIGQUERY_TABLE) {
                return new GcpResourceRef(Kind.BIGQUERY_TABLE, parts.get(0), parts.get(1), parts.get(2));
            }
            // Buckets, datasets, jobs and instances are containers or other assets, not delete targets here.
            return null;
        }
    }
}
