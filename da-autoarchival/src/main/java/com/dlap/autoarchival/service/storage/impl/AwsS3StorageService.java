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
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dlap.autoarchival.config.TestEndpointProperties;
import com.dlap.autoarchival.providers.aws.ActorCredentials;
import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.CostSavingsEstimate;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.autoarchival.service.storage.RestorationResult;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.RestoreObjectRequest;
import software.amazon.awssdk.services.s3.model.RestoreRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.Tier;

/**
 * AWS S3 implementation of cloud storage service for archival operations.
 * Supports S3 Standard, S3 IA, S3 Glacier, and S3 Glacier Deep Archive.
 */
@Service
public class AwsS3StorageService implements ICloudStorageService {

    private static final Logger log = LoggerFactory.getLogger(AwsS3StorageService.class);

    private final S3Client s3Client;
    private final String defaultRegion;

    // Cost per GB per month (USD) - these are approximate values
    private static final double STANDARD_COST_PER_GB = 0.023;
    private static final double IA_COST_PER_GB = 0.0125;
    private static final double GLACIER_COST_PER_GB = 0.004;
    private static final double DEEP_ARCHIVE_COST_PER_GB = 0.00099;

    /**
     * Creates the service for the real AWS.
     *
     * @param region the default region
     */
    public AwsS3StorageService(String region) {
        this(region, "", "", "");
    }

    /**
     * Creates the service on the default credentials chain. A non-empty endpoint is the test-only emulator override
     * (Q-713): the same setting the lifecycle rule provider reads, off by default and refused under the prod profile.
     *
     * @param region    the default region
     * @param endpoint  an S3-compatible emulator endpoint; empty for AWS
     * @param accessKey static access key for the emulator; empty for the default chain
     * @param secretKey static secret key for the emulator
     */
    public AwsS3StorageService(String region, String endpoint, String accessKey, String secretKey) {
        this(region, endpoint, accessKey, secretKey, true, ActorCredentials.defaultChain());
    }

    /**
     * Creates the service the way Spring does. The endpoint is the test-only
     * {@code dlap.autoarchival.test-endpoint.s3} (the deprecated {@code aws.endpoint-override} still works). Every
     * request signs with the Actor credentials (EXEC-01, Q-722): the assumed role of
     * {@code dlap.autoarchival.aws.credentials-ref}, else the default chain.
     *
     * @param region      the default region
     * @param endpoint    an S3-compatible emulator endpoint; empty for AWS
     * @param accessKey   static access key for the emulator (deprecated); empty for the default chain
     * @param secretKey   static secret key for the emulator (deprecated)
     * @param pathStyle   path-style requests against the endpoint
     * @param credentials the Actor credentials
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AwsS3StorageService(@Value("${aws.region:us-east-1}") String region,
            @Value(TestEndpointProperties.S3) String endpoint,
            @Value("${dlap.autoarchival.aws.endpoint-access-key:}") String accessKey,
            @Value("${dlap.autoarchival.aws.endpoint-secret-key:}") String secretKey,
            @Value(TestEndpointProperties.S3_PATH_STYLE) boolean pathStyle,
            ActorCredentials credentials) {
        this.defaultRegion = region;
        this.s3Client = endpoint == null || endpoint.isBlank()
                ? S3Client.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials.provider())
                        .build()
                : com.dlap.autoarchival.providers.aws.S3LifecycleRuleProvider.build(region, endpoint, accessKey,
                        secretKey, pathStyle, credentials);
    }

    @Override
    public ArchivalResult archiveData(UUID assetId, String sourceLocation, String targetTier, Map<String, String> metadata) {
        log.info("Archiving asset {} from {} to tier {}", assetId, sourceLocation, targetTier);
        
        try {
            // Parse S3 location (format: s3://bucket/key)
            String[] parts = parseS3Location(sourceLocation);
            String bucket = parts[0];
            String key = parts[1];
            
            // Get object metadata
            HeadObjectResponse headResponse = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            
            // Create copy request with new storage class
            StorageClass storageClass = mapToS3StorageClass(targetTier);
            String operationId = UUID.randomUUID().toString();
            
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(key)
                    .destinationBucket(bucket)
                    .destinationKey(key)
                    .storageClass(storageClass)
                    .metadata(metadata)
                    .build();
            
            s3Client.copyObject(copyRequest);
            
            return ArchivalResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .sourceLocation(sourceLocation)
                    .archivedLocation(sourceLocation) // Same location, different storage class
                    .targetTier(targetTier)
                    .status(OperationStatus.COMPLETED)
                    .initiatedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .dataSizeBytes(headResponse.contentLength())
                    .metadata(metadata)
                    .estimatedMonthlySavings(calculateMonthlySavings("STANDARD", targetTier, headResponse.contentLength()))
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to archive asset {}: {}", assetId, e.getMessage(), e);
            return ArchivalResult.builder()
                    .assetId(assetId)
                    .operationId(UUID.randomUUID().toString())
                    .sourceLocation(sourceLocation)
                    .targetTier(targetTier)
                    .status(OperationStatus.FAILED)
                    .initiatedAt(LocalDateTime.now())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public RestorationResult restoreData(UUID assetId, String archivedLocation, String targetTier) {
        log.info("Restoring asset {} from {} to tier {}", assetId, archivedLocation, targetTier);
        
        try {
            String[] parts = parseS3Location(archivedLocation);
            String bucket = parts[0];
            String key = parts[1];
            
            String operationId = UUID.randomUUID().toString();
            
            // For Glacier/Deep Archive, initiate restore request
            if (targetTier.equals("STANDARD") || targetTier.equals("EXPEDITED")) {
                RestoreRequest restoreRequest = RestoreRequest.builder()
                        .days(7) // Restore for 7 days
                        .glacierJobParameters(builder -> builder.tier(Tier.STANDARD))
                        .build();
                
                RestoreObjectRequest request = RestoreObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .restoreRequest(restoreRequest)
                        .build();
                
                s3Client.restoreObject(request);
                
                return RestorationResult.builder()
                        .assetId(assetId)
                        .operationId(operationId)
                        .archivedLocation(archivedLocation)
                        .restoredLocation(archivedLocation)
                        .targetTier(targetTier)
                        .status(OperationStatus.IN_PROGRESS)
                        .initiatedAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusDays(7))
                        .estimatedCost(0.01) // Approximate restore cost
                        .build();
            }
            
            // For other tiers, use copy operation
            return RestorationResult.builder()
                    .assetId(assetId)
                    .operationId(operationId)
                    .archivedLocation(archivedLocation)
                    .restoredLocation(archivedLocation)
                    .targetTier(targetTier)
                    .status(OperationStatus.COMPLETED)
                    .initiatedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to restore asset {}: {}", assetId, e.getMessage(), e);
            return RestorationResult.builder()
                    .assetId(assetId)
                    .operationId(UUID.randomUUID().toString())
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
        // operationId is expected to be an S3 location (s3://bucket/key) that was
        // archived. We inspect the object's current storage class to determine the
        // real archival state.
        try {
            String[] parts = parseS3Location(operationId);
            String bucket = parts[0];
            String key = parts[1];

            HeadObjectResponse headResponse = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());

            StorageClass storageClass = headResponse.storageClass();
            if (storageClass == null || storageClass == StorageClass.STANDARD
                    || storageClass == StorageClass.STANDARD_IA
                    || storageClass == StorageClass.ONEZONE_IA
                    || storageClass == StorageClass.REDUCED_REDUNDANCY
                    || storageClass == StorageClass.INTELLIGENT_TIERING) {
                return OperationStatus.NOT_ARCHIVED;
            }
            if (storageClass == StorageClass.GLACIER
                    || storageClass == StorageClass.GLACIER_IR
                    || storageClass == StorageClass.DEEP_ARCHIVE) {
                return OperationStatus.COMPLETED;
            }
            return OperationStatus.UNKNOWN;
        } catch (S3Exception e) {
            log.error("Failed to retrieve archival status for operationId {}: {}", operationId, e.getMessage(), e);
            return OperationStatus.UNKNOWN;
        } catch (IllegalArgumentException e) {
            log.error("Invalid operationId format for archival status lookup: {}", operationId, e);
            return OperationStatus.UNKNOWN;
        }
    }

    @Override
    public OperationStatus getRestorationStatus(String operationId) {
        // operationId is expected to be an S3 location (s3://bucket/key) whose
        // restore was initiated. We inspect the x-amz-restore header returned by
        // headObject to determine the real restoration state.
        try {
            String[] parts = parseS3Location(operationId);
            String bucket = parts[0];
            String key = parts[1];

            HeadObjectResponse headResponse = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());

            String restore = headResponse.restore();
            if (restore == null || restore.isBlank()) {
                return OperationStatus.NOT_RESTORED;
            }
            // Restore header format: ongoing-request="true" | ongoing-request="false", expiry-date="..."
            if (restore.contains("ongoing-request=\"true\"")) {
                return OperationStatus.IN_PROGRESS;
            }
            if (restore.contains("ongoing-request=\"false\"")) {
                return OperationStatus.COMPLETED;
            }
            return OperationStatus.UNKNOWN;
        } catch (S3Exception e) {
            log.error("Failed to retrieve restoration status for operationId {}: {}", operationId, e.getMessage(), e);
            return OperationStatus.UNKNOWN;
        } catch (IllegalArgumentException e) {
            log.error("Invalid operationId format for restoration status lookup: {}", operationId, e);
            return OperationStatus.UNKNOWN;
        }
    }

    @Override
    public CostSavingsEstimate calculateCostSavings(String sourceLocation, String targetTier, long dataSizeBytes) {
        double dataSizeGB = dataSizeBytes / (1024.0 * 1024.0 * 1024.0);
        
        double currentCost = STANDARD_COST_PER_GB * dataSizeGB;
        double targetCost = getCostPerGB(targetTier) * dataSizeGB;
        double savings = currentCost - targetCost;
        double savingsPercentage = (savings / currentCost) * 100;
        
        return CostSavingsEstimate.builder()
                .sourceStorageClass("STANDARD")
                .targetStorageClass(targetTier)
                .dataSizeBytes(dataSizeBytes)
                .currentMonthlyCost(currentCost)
                .archivedMonthlyCost(targetCost)
                .monthlySavings(savings)
                .savingsPercentage(savingsPercentage)
                .currency("USD")
                .region(defaultRegion)
                .build();
    }

    @Override
    public boolean supportsCloudProvider(String cloudProvider) {
        return "AWS".equalsIgnoreCase(cloudProvider);
    }

    @Override
    public String[] getAvailableStorageTiers(String cloudProvider) {
        if (supportsCloudProvider(cloudProvider)) {
            return new String[]{"STANDARD", "STANDARD_IA", "ONEZONE_IA", "GLACIER_IR", "GLACIER", "DEEP_ARCHIVE"};
        }
        return new String[0];
    }

    private String[] parseS3Location(String s3Location) {
        // Parse s3://bucket/key format
        if (!s3Location.startsWith("s3://")) {
            throw new IllegalArgumentException("Invalid S3 location format: " + s3Location);
        }
        
        String withoutProtocol = s3Location.substring(5);
        int firstSlash = withoutProtocol.indexOf('/');
        if (firstSlash == -1) {
            throw new IllegalArgumentException("Invalid S3 location format: " + s3Location);
        }
        
        String bucket = withoutProtocol.substring(0, firstSlash);
        String key = withoutProtocol.substring(firstSlash + 1);
        
        return new String[]{bucket, key};
    }

    private StorageClass mapToS3StorageClass(String targetTier) {
        return switch (targetTier.toUpperCase()) {
            case "STANDARD" -> StorageClass.STANDARD;
            case "STANDARD_IA" -> StorageClass.STANDARD_IA;
            case "ONEZONE_IA" -> StorageClass.ONEZONE_IA;
            case "GLACIER_IR" -> StorageClass.GLACIER_IR;
            case "GLACIER" -> StorageClass.GLACIER;
            case "DEEP_ARCHIVE" -> StorageClass.DEEP_ARCHIVE;
            default -> throw new IllegalArgumentException("Unsupported storage tier: " + targetTier);
        };
    }

    private double getCostPerGB(String storageClass) {
        return switch (storageClass.toUpperCase()) {
            case "STANDARD" -> STANDARD_COST_PER_GB;
            case "STANDARD_IA", "ONEZONE_IA" -> IA_COST_PER_GB;
            case "GLACIER_IR" -> GLACIER_COST_PER_GB;
            case "GLACIER" -> GLACIER_COST_PER_GB;
            case "DEEP_ARCHIVE" -> DEEP_ARCHIVE_COST_PER_GB;
            default -> STANDARD_COST_PER_GB;
        };
    }

    private double calculateMonthlySavings(String sourceClass, String targetClass, long dataSizeBytes) {
        double dataSizeGB = dataSizeBytes / (1024.0 * 1024.0 * 1024.0);
        double sourceCost = getCostPerGB(sourceClass) * dataSizeGB;
        double targetCost = getCostPerGB(targetClass) * dataSizeGB;
        return Math.max(0, sourceCost - targetCost);
    }
} 