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
import com.dlap.discovery.crawler.model.aws.RdsResource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;

/**
 * Discovers Amazon RDS database instances and publishes a
 * {@link ResourceEvent} per instance. Same shape as
 * {@link S3ResourceCrawler}: gated on {@code cloud.provider.aws.enabled},
 * uses {@link AwsClientFactory} (IMDS/keys + optional endpoint override),
 * publishes to {@code dlap.discovery.asset.discovered}.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class RdsResourceCrawler extends AWSResourceCrawler<RdsResource> {

    private static final Logger log = LoggerFactory.getLogger(RdsResourceCrawler.class);
    private static final String RESOURCE_TYPE_ID = AWSConstants.RESOURCE_TYPE_RDS_INSTANCE;

    private final AWSConfigService configService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final AwsClientFactory clientFactory;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public RdsResourceCrawler(
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
            log.info("RdsResourceCrawler initialized for type: {}", RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found in registry for ID: {}. RdsResourceCrawler may not function.",
                    RESOURCE_TYPE_ID);
        }
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of RDS instances for account: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String regionString = configService.getRegion();
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "RDS", regionString, accountId);
        int discoveredCount = 0;
        try (RdsClient rds = clientFactory.rdsClient(Region.of(regionString))) {
            log.info(DiscoveryConstants.LOG_CLIENT_INITIALIZED, "RDS", regionString);
            ResourceType rt = findSupportedType(RESOURCE_TYPE_ID);
            String marker = null;
            do {
                DescribeDbInstancesRequest.Builder reqBuilder = DescribeDbInstancesRequest.builder();
                if (marker != null) {
                    reqBuilder.marker(marker);
                }
                DescribeDbInstancesResponse response = rds.describeDBInstances(reqBuilder.build());
                for (DBInstance dbi : response.dbInstances()) {
                    RdsResource resource = mapInstanceToResource(dbi, accountId, regionString, rt);
                    if (resource != null) {
                        publishResourceEvent(accountId, resource, ChangeType.CREATE);
                        discoveredCount++;
                        log.debug("Published event for discovered RDS instance: {}", resource.getResourceId());
                    }
                }
                marker = response.marker();
            } while (marker != null);
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "RDS", regionString, discoveredCount);
        } catch (AwsServiceException e) {
            log.error("AWS API error during RDS discovery: {} [Request ID: {}]",
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e.requestId(), e);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "RDS", regionString, e.getMessage(), e);
        }
    }

    private RdsResource mapInstanceToResource(DBInstance dbi, String accountId, String region, ResourceType rt) {
        if (rt == null) {
            log.error("ResourceType record not found for ID: {}. Cannot create RdsResource.", RESOURCE_TYPE_ID);
            return null;
        }
        String id = dbi.dbInstanceIdentifier();
        RdsResource resource = new RdsResource(rt, id, id);
        resource.setAccountId(accountId != null ? accountId : configService.getAccountId());
        resource.setRegion(region);
        // Canonical id (ADR-0047): the ARN. The partition follows the region; the account and the
        // region come from the connection. Without a 12-digit account the ARN is not built (the
        // legacy form wrote "null" into it), and the resource keeps no uri.
        CrawlerAssetIds.assignParsed(resource, id,
                CrawlerAssetIds.hints(null, AssetKind.RDS_INSTANCE, resource.getAccountId(), region));
        resource.setCreatedAt(dbi.instanceCreateTime());
        resource.setUpdatedAt(Instant.now());
        resource.setLastDiscoveredAt(Instant.now());
        resource.setEngine(dbi.engine());
        resource.setEngineVersion(dbi.engineVersion());
        resource.setInstanceClass(dbi.dbInstanceClass());
        resource.setStatus(dbi.dbInstanceStatus());
        resource.setStorageEncrypted(dbi.storageEncrypted());
        if (dbi.endpoint() != null) {
            resource.setEndpoint(dbi.endpoint().address());
        }
        resource.setTags(new HashMap<>());
        return resource;
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

    protected void publishResourceEvent(String accountId, RdsResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AWS.toString(),
                    accountId,
                    changeType,
                    resource);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for RDS instance {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }
}
