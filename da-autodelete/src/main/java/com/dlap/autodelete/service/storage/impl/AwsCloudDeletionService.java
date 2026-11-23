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

import com.dlap.autodelete.providers.aws.ActorCredentials;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.RdsException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Real AWS implementation of {@link ICloudDeletionService}.
 *
 * <p>Uses the AWS SDK v2 to delete cloud resources. The {@code dataLocation} is parsed through
 * {@link AssetIdNormalizer} (plan rule R8), so every alias in {@code docs/specs/asset-id.md} is
 * accepted:
 *
 * <ul>
 *   <li><code>s3://bucket/object/key</code>, <code>s3a://</code>, <code>s3n://</code>,
 *       <code>arn:aws:s3:::bucket/key</code> and the S3 https URLs &rarr; S3 object delete</li>
 *   <li><code>arn:aws:rds:region:account:db:id</code> or <code>rds://region/db-instance-id</code>
 *       &rarr; RDS instance delete</li>
 *   <li><code>arn:aws:dynamodb:region:account:table/name</code> or
 *       <code>dynamodb://region/table-name</code> &rarr; DynamoDB table delete</li>
 * </ul>
 *
 * <p>Fails closed: a bucket, a key with leading or trailing whitespace, an ARN of another service,
 * a location of another cloud, a malformed name or an {@code http(s)} URL with a query string or
 * fragment (which can select an object version) is refused.
 *
 * <p><strong>Account check for ARNs.</strong> The SDK acts in the account of the ambient
 * credentials and never sees the account in an ARN, so an RDS or DynamoDB ARN of another account
 * would delete a same-named resource in the wrong account. An ARN target is therefore accepted
 * only when its account is listed in {@code dlap.autodelete.aws.allowed-account-ids}
 * (comma-separated, empty by default, which refuses every ARN target). The region-qualified
 * {@code rds://} and {@code dynamodb://} short forms carry no account and keep their previous
 * behaviour.
 *
 * <p><strong>Region is mandatory</strong> for {@code rds://} and {@code dynamodb://}: a
 * destructive delete must never guess the region. Region-less forms (e.g.
 * {@code rds://db-id}) are rejected (parse returns null &rarr; {@code deleteData} FAILED)
 * rather than silently defaulting to {@code us-east-1} and deleting - or falsely reporting
 * deleted - the wrong region's resource.
 *
 * <p>Credentials resolve via {@link DefaultCredentialsProvider} (environment,
 * instance profile, shared credentials file, etc.) - do not log or persist any
 * credential material.
 *
 * <p>RDS and DynamoDB deletes are destructive; {@code mock-mode} short-circuits all paths.
 *
 * <p>TODO(M1.2): use per-CloudConnection credentials once asset&rarr;catalog
 * linkage lands. Currently every call uses ambient AWS credentials.
 */
@Service("awsCloudDeletionService")
@Slf4j
public class AwsCloudDeletionService implements ICloudDeletionService {

    static final String PROVIDER_AWS = "AWS";

    // In-memory cache of operation -> last known deletion result. This is only a thin
    // convenience for getDeletionStatus / verifyDeletion during a single JVM lifetime;
    // the persisted compliance trail lives in DeletionAuditEntity.
    private final Map<String, DeletionResult> operations = new ConcurrentHashMap<>();

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");

    private final boolean mockMode;
    private final Set<String> allowedAccountIds;
    private final ActorCredentials credentials;

    /**
     * Creates the service with no ARN account allow-list, so ARN targets for RDS and DynamoDB
     * are refused.
     *
     * @param mockMode when true, no real cloud deletion is performed
     */
    public AwsCloudDeletionService(boolean mockMode) {
        this(mockMode, "");
    }

    /**
     * Creates the service.
     *
     * @param mockMode          when true, no real cloud deletion is performed
     * @param allowedAccountIds comma-separated 12-digit AWS account IDs whose RDS and DynamoDB ARNs
     *                          may be deleted with the ambient credentials; blank refuses every ARN target
     */
    public AwsCloudDeletionService(boolean mockMode, String allowedAccountIds) {
        this(mockMode, allowedAccountIds, ActorCredentials.defaultChain());
    }

    /**
     * Creates the service the way Spring does: every client signs with the Actor credentials (EXEC-01, Q-722), the
     * assumed role when {@code dlap.autodelete.aws.credentials-ref} is set, else the default chain.
     *
     * @param mockMode          when true, no real cloud deletion is performed
     * @param allowedAccountIds comma-separated 12-digit AWS account IDs whose RDS and DynamoDB ARNs may be deleted;
     *                          blank refuses every ARN target
     * @param credentials       the Actor credentials
     */
    @Autowired
    public AwsCloudDeletionService(
        @Value("${dlap.autodelete.aws.mock-mode:false}") boolean mockMode,
        @Value("${dlap.autodelete.aws.allowed-account-ids:}") String allowedAccountIds,
        ActorCredentials credentials) {
        this.credentials = credentials;
        this.mockMode = mockMode;
        this.allowedAccountIds = parseAccountIds(allowedAccountIds);
        if (mockMode) {
            log.warn("AwsCloudDeletionService running in MOCK MODE. No real cloud deletions will occur. "
                + "Set dlap.autodelete.aws.mock-mode=false for production.");
        }
    }

    @Override
    public DeletionResult deleteData(UUID assetId, String dataLocation, DeletionType deletionType,
                                     Map<String, String> metadata) {
        String operationId = "aws-" + UUID.randomUUID();
        LocalDateTime initiated = LocalDateTime.now();
        log.info("AWS deleteData: assetId={}, operationId={}, dataLocation={}, type={}",
            assetId, operationId, LocationSafety.redact(dataLocation), deletionType);

        DeletionResult.DeletionResultBuilder result = DeletionResult.builder()
            .assetId(assetId)
            .operationId(operationId)
            .dataLocation(dataLocation)
            .deletionType(deletionType)
            .initiatedAt(initiated)
            .metadata(metadata);

        try {
            AwsResourceRef ref = AwsResourceRef.parse(dataLocation);
            if (ref == null) {
                log.error("AWS deleteData: refusing unrecognised or unsafe location assetId={} dataLocation={}",
                    assetId, LocationSafety.redact(dataLocation));
                return result
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("Unrecognised AWS data location: " + LocationSafety.redact(dataLocation)
                        + " (expected one S3 object, an RDS instance or a DynamoDB table: an s3:// URI, "
                        + "an ARN, or an rds:// or dynamodb:// URI with a region).")
                    .build();
            }

            if (ref.account() != null && !allowedAccountIds.contains(ref.account())) {
                log.error("AWS deleteData: refusing ARN target in account {} (not in "
                    + "dlap.autodelete.aws.allowed-account-ids) assetId={}", ref.account(), assetId);
                return result
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("AWS account " + ref.account() + " is not listed in "
                        + "dlap.autodelete.aws.allowed-account-ids; the SDK acts in the account of the "
                        + "ambient credentials, so an ARN of another account is refused")
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
                case S3_OBJECT -> deleteS3ObjectInternal(result, ref);
                case RDS_DB_INSTANCE -> deleteRdsInstanceInternal(result, ref);
                case DYNAMODB_TABLE -> deleteDynamoTableInternal(result, ref);
            };
            operations.put(operationId, real);
            return real;
        } catch (S3Exception se) {
            log.error("AWS deleteData: S3 SDK failure assetId={} operationId={} statusCode={}",
                assetId, operationId, se.statusCode(), se);
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("S3 API error: " + safeMessage(se))
                .build();
        } catch (RuntimeException ex) {
            log.error("AWS deleteData: unexpected failure assetId={} operationId={}", assetId, operationId, ex);
            return result
                .status(DeletionStatus.FAILED)
                .completedAt(LocalDateTime.now())
                .errorMessage("Unexpected error: " + safeMessage(ex))
                .build();
        }
    }

    private DeletionResult deleteS3ObjectInternal(DeletionResult.DeletionResultBuilder builder, AwsResourceRef ref) {
        try (S3Client s3 = buildS3Client()) {
            // Probe size BEFORE delete for the audit bytes-deleted column.
            Long sizeBytes = null;
            try {
                HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(ref.first())
                    .key(ref.second())
                    .build());
                sizeBytes = head.contentLength();
            } catch (NoSuchKeyException probeMissing) {
                log.debug("AWS size-probe: object already absent s3://{}/{} - proceeding with idempotent delete",
                    ref.first(), ref.second());
                return builder
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(0L)
                    .errorMessage("Object not found (already deleted) - idempotent success")
                    .build();
            } catch (S3Exception probeFail) {
                log.debug("AWS size-probe failed for s3://{}/{} - continuing with delete anyway",
                    ref.first(), ref.second(), probeFail);
            }

            s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(ref.first())
                .key(ref.second())
                .build());

            return builder
                .status(DeletionStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .dataSizeBytes(sizeBytes)
                .build();
        }
    }

    private DeletionResult deleteRdsInstanceInternal(DeletionResult.DeletionResultBuilder builder, AwsResourceRef ref) {
        try (RdsClient rds = RdsClient.builder()
                .credentialsProvider(credentials.provider())
                .region(Region.of(ref.first()))
                .build()) {
            rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                    .dbInstanceIdentifier(ref.second())
                    .skipFinalSnapshot(true)
                    .deleteAutomatedBackups(true)
                    .build());
            return builder
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(null)
                    .build();
        } catch (RdsException se) {
            log.error("AWS deleteData: RDS SDK failure statusCode={}", se.statusCode(), se);
            return builder
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("RDS API error: " + safeMessage(se))
                    .build();
        }
    }

    private DeletionResult deleteDynamoTableInternal(DeletionResult.DeletionResultBuilder builder, AwsResourceRef ref) {
        try (DynamoDbClient ddb = DynamoDbClient.builder()
                .credentialsProvider(credentials.provider())
                .region(Region.of(ref.first()))
                .build()) {
            ddb.deleteTable(DeleteTableRequest.builder()
                    .tableName(ref.second())
                    .build());
            return builder
                    .status(DeletionStatus.COMPLETED)
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(null)
                    .build();
        } catch (software.amazon.awssdk.services.dynamodb.model.DynamoDbException se) {
            log.error("AWS deleteData: DynamoDB SDK failure statusCode={}", se.statusCode(), se);
            return builder
                    .status(DeletionStatus.FAILED)
                    .completedAt(LocalDateTime.now())
                    .errorMessage("DynamoDB API error: " + safeMessage(se))
                    .build();
        }
    }

    @Override
    public BulkDeletionResult bulkDeleteData(List<UUID> assetIds, List<String> dataLocations,
                                             DeletionType deletionType, Map<String, String> metadata) {
        if (assetIds == null || dataLocations == null || assetIds.size() != dataLocations.size()) {
            throw new IllegalArgumentException("assetIds and dataLocations must be non-null and same length");
        }
        String bulkOperationId = "aws-bulk-" + UUID.randomUUID();
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
        try (S3Client s3 = buildS3Client()) {
            for (String loc : dataLocations) {
                AwsResourceRef ref = AwsResourceRef.parse(loc);
                if (ref == null || ref.kind() != AwsResourceRef.Kind.S3_OBJECT) {
                    continue;
                }
                try {
                    HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                        .bucket(ref.first())
                        .key(ref.second())
                        .build());
                    if (head.contentLength() != null) {
                        totalBytes += head.contentLength();
                        totalObjects++;
                    }
                } catch (NoSuchKeyException missing) {
                    log.debug("AWS reclaim probe: object absent s3://{}/{}", ref.first(), ref.second());
                } catch (S3Exception probeFail) {
                    log.debug("AWS reclaim probe failed for {}", loc, probeFail);
                }
            }
        } catch (RuntimeException sdkFail) {
            log.warn("AWS calculateStorageReclaim: SDK unavailable - returning zeroes", sdkFail);
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
            .verificationMethod("aws-sdk-head-check")
            .auditTrail("verifier=AwsCloudDeletionService; checked-at=" + ts);

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
            AwsResourceRef ref = AwsResourceRef.parse(prior.getDataLocation());
            if (ref == null) {
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .remainingTraces(List.of("Unparseable data location: " + prior.getDataLocation()))
                    .build();
            }
            return switch (ref.kind()) {
                case S3_OBJECT -> verifyS3ObjectDeleted(b, ref, prior, operationId);
                case RDS_DB_INSTANCE -> verifyRdsInstanceDeleted(b, ref, operationId);
                case DYNAMODB_TABLE -> verifyDynamoTableDeleted(b, ref, operationId);
            };
        } catch (RuntimeException ex) {
            log.error("AWS verifyDeletion: probe failure operationId={}", operationId, ex);
            return b.isCompletelyDeleted(false)
                .isCompliant(false)
                .remainingTraces(List.of("Verification probe error: " + safeMessage(ex)))
                .build();
        }
    }

    private DeletionVerificationResult verifyS3ObjectDeleted(
            DeletionVerificationResult.DeletionVerificationResultBuilder b,
            AwsResourceRef ref,
            DeletionResult prior,
            String operationId) {
        try (S3Client s3 = buildS3Client()) {
            try {
                s3.headObject(HeadObjectRequest.builder()
                    .bucket(ref.first())
                    .key(ref.second())
                    .build());
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(List.of("Resource still present at " + prior.getDataLocation()))
                    .build();
            } catch (NoSuchKeyException absent) {
                return b.isCompletelyDeleted(true)
                    .isCompliant(true)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(Collections.emptyList())
                    .build();
            } catch (S3Exception se) {
                if (se.statusCode() == 404) {
                    return b.isCompletelyDeleted(true)
                        .isCompliant(true)
                        .complianceStandards(List.of("GDPR", "HIPAA"))
                        .remainingTraces(Collections.emptyList())
                        .build();
                }
                log.error("AWS verifyDeletion: unexpected S3 error operationId={} statusCode={}",
                    operationId, se.statusCode(), se);
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .remainingTraces(List.of("Verification probe error (status=" + se.statusCode()
                        + "): " + safeMessage(se)))
                    .build();
            }
        }
    }

    private DeletionVerificationResult verifyRdsInstanceDeleted(
            DeletionVerificationResult.DeletionVerificationResultBuilder b,
            AwsResourceRef ref,
            String operationId) {
        try (RdsClient rds = RdsClient.builder()
                .credentialsProvider(credentials.provider())
                .region(Region.of(ref.first()))
                .build()) {
            try {
                rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                        .dbInstanceIdentifier(ref.second())
                        .build());
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(List.of("RDS instance still present: " + ref.second()))
                    .build();
            } catch (RdsException e) {
                if ("DBInstanceNotFound".equals(e.awsErrorDetails().errorCode())) {
                    return b.isCompletelyDeleted(true)
                        .isCompliant(true)
                        .complianceStandards(List.of("GDPR", "HIPAA"))
                        .remainingTraces(Collections.emptyList())
                        .build();
                }
                log.error("AWS verifyDeletion: RDS error operationId={} code={}", operationId,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "?", e);
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .remainingTraces(List.of("RDS verification error: " + safeMessage(e)))
                    .build();
            }
        }
    }

    private DeletionVerificationResult verifyDynamoTableDeleted(
            DeletionVerificationResult.DeletionVerificationResultBuilder b,
            AwsResourceRef ref,
            String operationId) {
        try (DynamoDbClient ddb = DynamoDbClient.builder()
                .credentialsProvider(credentials.provider())
                .region(Region.of(ref.first()))
                .build()) {
            try {
                ddb.describeTable(DescribeTableRequest.builder().tableName(ref.second()).build());
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(List.of("DynamoDB table still present: " + ref.second()))
                    .build();
            } catch (ResourceNotFoundException gone) {
                return b.isCompletelyDeleted(true)
                    .isCompliant(true)
                    .complianceStandards(List.of("GDPR", "HIPAA"))
                    .remainingTraces(Collections.emptyList())
                    .build();
            } catch (software.amazon.awssdk.services.dynamodb.model.DynamoDbException e) {
                log.error("AWS verifyDeletion: DynamoDB error operationId={}", operationId, e);
                return b.isCompletelyDeleted(false)
                    .isCompliant(false)
                    .remainingTraces(List.of("DynamoDB verification error: " + safeMessage(e)))
                    .build();
            }
        }
    }

    @Override
    public boolean supportsCloudProvider(String cloudProvider) {
        return PROVIDER_AWS.equalsIgnoreCase(cloudProvider);
    }

    @Override
    public DeletionType[] getAvailableDeletionTypes(String cloudProvider) {
        if (!supportsCloudProvider(cloudProvider)) {
            return new DeletionType[0];
        }
        return new DeletionType[] { DeletionType.HARD, DeletionType.IMMEDIATE };
    }

    /**
     * Builds an {@link S3Client} using the default credentials provider chain.
     *
     * <p>TODO(M1.2): use per-CloudConnection credentials once asset&rarr;catalog
     * linkage lands. Each call currently creates a short-lived client; callers
     * must wrap in try-with-resources to release HTTP connections.
     */
    S3Client buildS3Client() {
        return S3Client.builder()
            .credentialsProvider(credentials.provider())
            .build();
    }

    private static Set<String> parseAccountIds(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String token : csv.split("[,\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (ACCOUNT_ID.matcher(token).matches()) {
                out.add(token);
            } else {
                log.warn("Ignoring dlap.autodelete.aws.allowed-account-ids entry that is not a 12-digit account ID");
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    /**
     * Typed reference to an AWS resource parsed from a location string.
     * first/second semantics by kind:
     * <ul>
     *   <li>{@code S3_OBJECT}: first=bucket, second=objectKey, account=null</li>
     *   <li>{@code RDS_DB_INSTANCE}: first=region, second=dbInstanceIdentifier</li>
     *   <li>{@code DYNAMODB_TABLE}: first=region, second=tableName</li>
     * </ul>
     * {@code account} is the AWS account of an ARN-form RDS or DynamoDB target, and {@code null}
     * for S3 and for the account-less {@code rds://} and {@code dynamodb://} short forms.
     */
    record AwsResourceRef(Kind kind, String first, String second, String account) {

        enum Kind {
            S3_OBJECT,
            RDS_DB_INSTANCE,
            DYNAMODB_TABLE
        }

        /**
         * Parses a location through {@link AssetIdNormalizer}. Returns {@code null} (the caller
         * fails closed) unless the location names exactly one S3 object, one RDS instance or one
         * DynamoDB table. The region-qualified {@code rds://region/id} and
         * {@code dynamodb://region/table} short forms carry no account, which the normalizer needs
         * to form an ARN, so they keep their dedicated parser.
         *
         * @param location the location in any alias the asset-id spec accepts; may be {@code null}
         * @return the typed reference, or {@code null} when the location is not a safe delete target
         */
        static AwsResourceRef parse(String location) {
            if (location == null || location.isBlank()) {
                return null;
            }
            String trimmed = location.trim();
            if (trimmed.startsWith("rds://")) {
                return parseRds(trimmed.substring("rds://".length()));
            }
            if (trimmed.startsWith("dynamodb://")) {
                return parseDynamo(trimmed.substring("dynamodb://".length()));
            }
            if (LocationSafety.carriesUrlSelector(location)) {
                return null;
            }
            AssetIdParseResult parsed = AssetIdNormalizer.parse(location, AssetIdHints.of(AssetProvider.AWS));
            if (!(parsed instanceof AssetIdParseResult.Normalized normalized)) {
                return null;
            }
            CanonicalAssetId id = normalized.canonical();
            if (id.provider() != AssetProvider.AWS) {
                return null;
            }
            List<String> parts = id.parts();
            if (id.kind() == AssetKind.S3_OBJECT) {
                String key = parts.get(1);
                if (LocationSafety.hasEdgeWhitespace(key)) {
                    // Whitespace around a key is ambiguous: trimming would delete another object, keeping it
                    // usually misses and reports a false "already deleted". Never guess.
                    return null;
                }
                return new AwsResourceRef(Kind.S3_OBJECT, parts.get(0), key, null);
            }
            if (id.kind() == AssetKind.RDS_INSTANCE) {
                return new AwsResourceRef(Kind.RDS_DB_INSTANCE, parts.get(2), parts.get(4).substring("db:".length()),
                    parts.get(3));
            }
            if (id.kind() == AssetKind.DYNAMODB_TABLE) {
                return new AwsResourceRef(Kind.DYNAMODB_TABLE, parts.get(2), parts.get(4).substring("table/".length()),
                    parts.get(3));
            }
            // Buckets, Glue, Redshift, EMR and Athena ids are not delete targets here.
            return null;
        }

        private static AwsResourceRef parseRds(String rest) {
            // Region is mandatory: require rds://region/db-instance-id. Reject region-less
            // forms rather than guess a region for a destructive delete (fail closed).
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String region = rest.substring(0, slash);
            String id = rest.substring(slash + 1);
            if (region.isBlank() || id.isBlank()) {
                return null;
            }
            return new AwsResourceRef(Kind.RDS_DB_INSTANCE, region, id, null);
        }

        private static AwsResourceRef parseDynamo(String rest) {
            // Region is mandatory: require dynamodb://region/table-name. Reject region-less
            // forms rather than guess a region for a destructive delete (fail closed).
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String region = rest.substring(0, slash);
            String table = rest.substring(slash + 1);
            if (region.isBlank() || table.isBlank()) {
                return null;
            }
            return new AwsResourceRef(Kind.DYNAMODB_TABLE, region, table, null);
        }
    }
}
