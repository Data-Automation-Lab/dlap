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
import com.dlap.discovery.crawler.model.aws.GlueResource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.providers.aws.config.AwsClientFactory;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Table;

/**
 * Discovers AWS Glue Data Catalog tables and publishes a
 * {@link ResourceEvent} per table. Same shape as
 * {@link RdsResourceCrawler}: gated on {@code cloud.provider.aws.enabled},
 * uses {@link AwsClientFactory}, publishes to
 * {@code dlap.discovery.asset.discovered}.
 *
 * <p>Walks the catalog two levels deep: paginate over Glue databases,
 * then paginate over the tables in each database. Each table becomes a
 * {@link GlueResource}.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class GlueResourceCrawler extends AWSResourceCrawler<GlueResource> {

    private static final Logger log = LoggerFactory.getLogger(GlueResourceCrawler.class);
    private static final String RESOURCE_TYPE_ID = AWSConstants.RESOURCE_TYPE_GLUE_TABLE;

    private final AWSConfigService configService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;
    private final AwsClientFactory clientFactory;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public GlueResourceCrawler(
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
            log.info("GlueResourceCrawler initialized for type: {}", RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found in registry for ID: {}. GlueResourceCrawler may not function.",
                    RESOURCE_TYPE_ID);
        }
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of Glue tables for account: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String regionString = configService.getRegion();
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "Glue", regionString, accountId);
        int discoveredCount = 0;
        try (GlueClient glue = clientFactory.glueClient(Region.of(regionString))) {
            log.info(DiscoveryConstants.LOG_CLIENT_INITIALIZED, "Glue", regionString);
            ResourceType rt = findSupportedType(RESOURCE_TYPE_ID);
            for (String databaseName : listDatabases(glue)) {
                discoveredCount += discoverTablesIn(glue, databaseName, accountId, regionString, rt);
            }
            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "Glue", regionString, discoveredCount);
        } catch (AwsServiceException e) {
            log.error("AWS API error during Glue discovery: {} [Request ID: {}]",
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e.requestId(), e);
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "Glue", regionString, e.getMessage(), e);
        }
    }

    private List<String> listDatabases(GlueClient glue) {
        List<String> names = new java.util.ArrayList<>();
        String token = null;
        do {
            GetDatabasesRequest.Builder req = GetDatabasesRequest.builder();
            if (token != null) {
                req.nextToken(token);
            }
            GetDatabasesResponse response = glue.getDatabases(req.build());
            for (Database db : response.databaseList()) {
                names.add(db.name());
            }
            token = response.nextToken();
        } while (token != null);
        return names;
    }

    private int discoverTablesIn(GlueClient glue, String databaseName, String accountId,
            String region, ResourceType rt) {
        int count = 0;
        String token = null;
        do {
            GetTablesRequest.Builder req = GetTablesRequest.builder().databaseName(databaseName);
            if (token != null) {
                req.nextToken(token);
            }
            GetTablesResponse response = glue.getTables(req.build());
            for (Table table : response.tableList()) {
                GlueResource resource = mapTableToResource(table, databaseName, accountId, region, rt);
                if (resource != null) {
                    publishResourceEvent(accountId, resource, ChangeType.CREATE);
                    count++;
                    log.debug("Published event for discovered Glue table: {}", resource.getResourceId());
                }
            }
            token = response.nextToken();
        } while (token != null);
        return count;
    }

    private GlueResource mapTableToResource(Table table, String databaseName, String accountId,
            String region, ResourceType rt) {
        if (rt == null) {
            log.error("ResourceType record not found for ID: {}. Cannot create GlueResource.", RESOURCE_TYPE_ID);
            return null;
        }
        // Stable id: database-qualified table name (unique within a catalog/region).
        String id = databaseName + "." + table.name();
        GlueResource resource = new GlueResource(rt, id, table.name());
        resource.setAccountId(accountId != null ? accountId : configService.getAccountId());
        resource.setRegion(region);
        // Canonical id (ADR-0047): the Glue table ARN, with the database and table lower-cased as
        // Glue stores them. Without a 12-digit account no ARN is built, and the resource keeps no uri.
        CrawlerAssetIds.assignParsed(resource, id,
                CrawlerAssetIds.hints(null, AssetKind.GLUE_TABLE, resource.getAccountId(), region));
        resource.setCreatedAt(table.createTime());
        resource.setUpdatedAt(Instant.now());
        resource.setLastDiscoveredAt(Instant.now());
        resource.setDatabase(databaseName);
        resource.setTableType(table.tableType());
        if (table.storageDescriptor() != null) {
            resource.setLocation(table.storageDescriptor().location());
        }
        if (table.parameters() != null) {
            resource.setClassification(table.parameters().get("classification"));
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

    protected void publishResourceEvent(String accountId, GlueResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AWS.toString(),
                    accountId,
                    changeType,
                    resource);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for Glue table {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }
}
