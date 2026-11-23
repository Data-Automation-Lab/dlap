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

package com.dlap.discovery.crawler.service.aws;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.dlap.common.assetid.AssetKind;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.constants.DiscoveryConstants;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.aws.S3Resource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;

/**
 * Discovers Amazon S3 buckets and publishes a {@link ResourceEvent} per
 * bucket. Mirrors {@link EC2InstanceCrawler}: gated on
 * {@code cloud.provider.aws.enabled}, uses the AWS SDK against the
 * {@link AWSConfigService}-resolved region/endpoint, and publishes to
 * {@code dlap.discovery.asset.discovered} for da-catalog ingestion.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class S3ResourceCrawler extends AWSResourceCrawler<S3Resource> {

    private static final Logger log = LoggerFactory.getLogger(S3ResourceCrawler.class);
    private static final String RESOURCE_TYPE_ID = AWSConstants.RESOURCE_TYPE_S3_BUCKET;

    private final AWSConfigService configService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final AwsClientFactory clientFactory;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public S3ResourceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            AWSConfigService configService,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate,
            AwsClientFactory clientFactory) {
        super(List.of());
        this.hierarchyRegistry = hierarchyRegistry;
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory cannot be null");
        ResourceType resourceType = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        if (resourceType != null) {
            getSupportedResourceTypes().add(resourceType);
            log.info("S3ResourceCrawler initialized for type: {}", RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found in registry for ID: {}. S3ResourceCrawler may not function.",
                    RESOURCE_TYPE_ID);
        }
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of S3 buckets for account: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String regionString = configService.getRegion();
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "S3", regionString, accountId);
        int discoveredCount = 0;
        try (S3Client s3 = clientFactory.s3Client(Region.of(regionString))) {
            log.info(DiscoveryConstants.LOG_CLIENT_INITIALIZED, "S3", regionString);
            ListBucketsResponse response = s3.listBuckets();
            ResourceType rt = findSupportedType(RESOURCE_TYPE_ID);
            for (Bucket bucket : response.buckets()) {
                S3Resource resource = mapBucketToResource(s3, bucket, accountId, regionString, rt);
                if (resource != null) {
                    publishResourceEvent(accountId, resource, ChangeType.CREATE);
                    discoveredCount++;
                    log.debug("Published event for discovered S3 bucket: {}", resource.getResourceId());
                }
            }
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "S3", regionString, discoveredCount);
        } catch (AwsServiceException e) {
            log.error("AWS API error during S3 discovery: {} [Request ID: {}]",
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e.requestId(), e);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "S3", regionString, e.getMessage(), e);
        }
    }

    private S3Resource mapBucketToResource(S3Client s3, Bucket bucket, String accountId,
            String defaultRegion, ResourceType rt) {
        if (rt == null) {
            log.error("ResourceType record not found for ID: {}. Cannot create S3Resource.", RESOURCE_TYPE_ID);
            return null;
        }
        String name = bucket.name();
        S3Resource resource = new S3Resource(rt, name, name);
        resource.setAccountId(accountId != null ? accountId : configService.getAccountId());
        resource.setRegion(resolveBucketRegion(s3, name, defaultRegion));
        // Canonical id (ADR-0047): s3://{bucket}. Account and region are attributes, not identity.
        CrawlerAssetIds.assignBuilt(resource, AssetKind.S3_BUCKET, name);
        resource.setCreatedAt(bucket.creationDate());
        resource.setUpdatedAt(Instant.now());
        resource.setLastDiscoveredAt(Instant.now());
        resource.setVersioningStatus(resolveVersioning(s3, name));
        resource.setEncryption(resolveEncryption(s3, name));
        resource.setTags(new HashMap<>());
        return resource;
    }

    private String resolveBucketRegion(S3Client s3, String bucket, String defaultRegion) {
        try {
            String loc = s3.getBucketLocation(GetBucketLocationRequest.builder().bucket(bucket).build())
                    .locationConstraintAsString();
            // Empty/us-east-1 special case: S3 returns "" for us-east-1.
            return (loc == null || loc.isEmpty()) ? defaultRegion : loc;
        } catch (Exception e) {
            log.debug("Could not resolve region for bucket {}: {}", bucket, e.getMessage());
            return defaultRegion;
        }
    }

    private String resolveVersioning(S3Client s3, String bucket) {
        try {
            GetBucketVersioningResponse v = s3.getBucketVersioning(
                    GetBucketVersioningRequest.builder().bucket(bucket).build());
            return v.statusAsString() != null ? v.statusAsString() : "Disabled";
        } catch (Exception e) {
            log.debug("Could not resolve versioning for bucket {}: {}", bucket, e.getMessage());
            return null;
        }
    }

    private String resolveEncryption(S3Client s3, String bucket) {
        try {
            ServerSideEncryptionByDefault sse = s3.getBucketEncryption(
                            GetBucketEncryptionRequest.builder().bucket(bucket).build())
                    .serverSideEncryptionConfiguration().rules().stream().findFirst()
                    .map(r -> r.applyServerSideEncryptionByDefault())
                    .orElse(null);
            return sse != null ? sse.sseAlgorithmAsString() : null;
        } catch (Exception e) {
            // No encryption configured is a normal 404 from S3.
            log.debug("No bucket encryption for {}: {}", bucket, e.getMessage());
            return null;
        }
    }

    private ResourceType findSupportedType(String typeId) {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (typeId.equals(type.id())) {
                return type;
            }
        }
        log.warn("ResourceType not found in supported types: {}", typeId);
        return null;
    }

    protected void publishResourceEvent(String accountId, S3Resource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AWS.toString(),
                    accountId,
                    changeType,
                    resource);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for S3 bucket {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }
}
