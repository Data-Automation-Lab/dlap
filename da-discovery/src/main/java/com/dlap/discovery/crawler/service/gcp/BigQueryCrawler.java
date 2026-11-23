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

package com.dlap.discovery.crawler.service.gcp;

import com.dlap.common.assetid.AssetKind;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.BigQueryResource;
import com.dlap.discovery.crawler.service.CrawlerException;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.log.exception.CloudProviderException;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Discovers BigQuery datasets and tables asynchronously, publishing events to Kafka.
 */
@Primary
@Service
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class BigQueryCrawler extends GCPResourceCrawler<BigQueryResource> {

    private static final Logger log = LoggerFactory.getLogger(BigQueryCrawler.class);
    private static final String RESOURCE_TYPE_ID_DATASET = "gcp_bigquery_dataset";
    private static final String RESOURCE_TYPE_ID_TABLE = "gcp_bigquery_table";

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public BigQueryCrawler(CloudHierarchyRegistry hierarchyRegistry, KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        super(new ArrayList<>());
        this.hierarchyRegistry = hierarchyRegistry;
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
    }

    @PostConstruct
    public void initSupportedTypes() {
        ResourceType rtDataset = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID_DATASET);
        if (rtDataset != null && !getSupportedResourceTypes().contains(rtDataset)) {
            getSupportedResourceTypes().add(rtDataset);
            log.info("BigQueryCrawler initialized for type: {}", RESOURCE_TYPE_ID_DATASET);
        } else if (rtDataset == null) {
            log.error(
                "ResourceType record not found for ID: {}. BigQueryCrawler may not function correctly for datasets.",
                RESOURCE_TYPE_ID_DATASET
            );
        }

        ResourceType rtTable = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID_TABLE);
        if (rtTable != null && !getSupportedResourceTypes().contains(rtTable)) {
            getSupportedResourceTypes().add(rtTable);
            log.info("BigQueryCrawler initialized for type: {}", RESOURCE_TYPE_ID_TABLE);
        } else if (rtTable == null) {
            log.error(
                "ResourceType record not found for ID: {}. BigQueryCrawler may not function correctly for tables.",
                RESOURCE_TYPE_ID_TABLE
            );
        }
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous BigQuery Dataset and Table discovery for project: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String projectId = accountId;
        log.info("Starting async BigQuery Dataset and Table discovery for project: {}", projectId);
        int discoveredCount = 0;

        try {
            Credentials credentials = getAuthService().getCredentials();
            if (!(credentials instanceof GoogleCredentials)) {
                log.error("Invalid credentials type obtained for GCP: {}", credentials.getClass().getName());
                throw new CloudProviderException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "GCP",
                    "authenticate",
                    "Credentials are not GoogleCredentials"
                );
            }
            GoogleCredentials googleCredentials = (GoogleCredentials) credentials;

            String actualProjectId = getProjectId(accountId, context);
            if (actualProjectId == null) return; // Error logged in helper

            BigQuery bigquery = BigQueryOptions
                .newBuilder()
                .setCredentials(googleCredentials)
                .setProjectId(actualProjectId)
                .build()
                .getService();

            com.google.api.gax.paging.Page<Dataset> datasets = bigquery.listDatasets(
                actualProjectId,
                BigQuery.DatasetListOption.pageSize(100)
            );

            ResourceType rtDataset = findSupportedType(RESOURCE_TYPE_ID_DATASET);
            if (rtDataset == null) {
                log.error(
                    "ResourceType record not found for ID: {}. Cannot create BigQueryResource for datasets.",
                    RESOURCE_TYPE_ID_DATASET
                );
                return;
            }

            ResourceType rtTable = findSupportedType(RESOURCE_TYPE_ID_TABLE);
            if (rtTable == null) {
                log.error("ResourceType record not found for ID: {}. Cannot create BigQueryResource for tables.", RESOURCE_TYPE_ID_TABLE);
                // Continue with datasets even if table type is missing
            }

            Map<String, String> stringContext = convertToStringMap(context);

            for (Dataset dataset : datasets.iterateAll()) {
                // Process dataset
                BigQueryResource bqDatasetResource = createResourceFromDataset(dataset, actualProjectId, rtDataset);
                if (bqDatasetResource != null && matchesFilters(bqDatasetResource, stringContext)) {
                    publishResourceEvent(projectId, bqDatasetResource, ChangeType.CREATE);
                    discoveredCount++;
                    log.debug("Published event for BigQuery Dataset: {}", bqDatasetResource.getName());
                }

                // Process tables if table resource type is available
                if (rtTable != null) {
                    try {
                        String datasetLocation = dataset.getLocation(); // Get dataset location
                        com.google.api.gax.paging.Page<Table> tables = bigquery.listTables(
                            dataset.getDatasetId(),
                            BigQuery.TableListOption.pageSize(100)
                        );
                        for (Table table : tables.iterateAll()) {
                            BigQueryResource bqTableResource = createResourceFromTable(table, actualProjectId, rtTable, datasetLocation);
                            if (bqTableResource != null && matchesFilters(bqTableResource, stringContext)) {
                                publishResourceEvent(projectId, bqTableResource, ChangeType.CREATE);
                                discoveredCount++;
                                log.debug("Published event for BigQuery Table: {}", bqTableResource.getName());
                            }
                        }
                    } catch (Exception e) {
                        log.error(
                            "Error listing tables for dataset {} in project {}: {}",
                            dataset.getDatasetId().getDataset(),
                            actualProjectId,
                            e.getMessage(),
                            e
                        );
                        // Continue with other datasets
                    }
                }
            }

            log.info(
                "Completed async BigQuery Dataset and Table discovery for project {}. Published {} resources.",
                actualProjectId,
                discoveredCount
            );
        } catch (Exception e) {
            log.error("Error during async BigQuery Dataset and Table discovery for project {}: {}", projectId, e.getMessage(), e);
            if (e instanceof CloudProviderException || e instanceof CrawlerException) {
                throw (RuntimeException) e;
            } else {
                throw ExceptionUtils.crawlerExecutionFailed("BigQueryCrawler", null, "listDatasetsAndTables", e);
            }
        }
    }

    private String getProjectId(String accountId, Map<String, Object> context) {
        String projectId = accountId; // Default to accountId
        if (context != null && context.containsKey("projectId")) {
            Object ctxProjectId = context.get("projectId");
            if (ctxProjectId != null) {
                projectId = ctxProjectId.toString();
            }
        }
        if (projectId == null || projectId.isEmpty()) {
            projectId = getConfigService().getProjectId(); // Use getter from parent
            if (projectId == null || projectId.isEmpty()) {
                log.error("GCP Project ID not found in parameters or configuration.");
                return null;
            }
        }
        return projectId;
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

    private void publishResourceEvent(String projectId, BigQueryResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(CloudProvider.GCP.toString(), projectId, changeType, resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for BigQuery resource {}: {}", resource.getResourceId(), e.getMessage(), e);
        }
    }

    private Map<String, String> convertToStringMap(Map<String, Object> map) {
        Map<String, String> result = new HashMap<>();
        if (map != null) {
            map.forEach((key, value) -> {
                if (value != null) {
                    result.put(key, value.toString());
                }
            });
        }
        return result;
    }

    BigQueryResource createResourceFromDataset(Dataset dataset, String projectId, ResourceType rt) {
        DatasetId datasetId = dataset.getDatasetId();
        BigQueryResource bqResource = new BigQueryResource(rt, datasetId.toString(), datasetId.getDataset());

        bqResource.setAccountId(projectId);
        bqResource.setProjectId(projectId);
        // Canonical id (ADR-0047): bq://{project}/{dataset}. The dataset names its own project; the
        // connection's project is the fallback. Location is an attribute, not identity.
        CrawlerAssetIds.assignBuilt(bqResource, AssetKind.BIGQUERY_DATASET,
                firstNonBlank(datasetId.getProject(), projectId), datasetId.getDataset());
        bqResource.setLocation(dataset.getLocation());
        bqResource.setDescription(dataset.getDescription());
        bqResource.setLastDiscoveredAt(Instant.now());

        if (dataset.getCreationTime() != null) {
            bqResource.setCreatedAt(Instant.ofEpochMilli(dataset.getCreationTime()));
        }
        Long lastModifiedTime = dataset.getLastModified();
        if (lastModifiedTime != null) {
            bqResource.setUpdatedAt(Instant.ofEpochMilli(lastModifiedTime));
        } else {
            bqResource.setUpdatedAt(Instant.now());
        }

        if (dataset.getLabels() != null) {
            bqResource.setTags(dataset.getLabels());
        }

        return bqResource;
    }

    BigQueryResource createResourceFromTable(Table table, String projectId, ResourceType rt, String datasetLocation) {
        TableId tableId = table.getTableId();
        String resourceId = String.format("%s:%s.%s", tableId.getProject(), tableId.getDataset(), tableId.getTable());
        String tableName = String.format("%s.%s", tableId.getDataset(), tableId.getTable());
        BigQueryResource bqResource = new BigQueryResource(rt, resourceId, tableName);

        bqResource.setAccountId(projectId);
        bqResource.setProjectId(projectId);
        // Canonical id (ADR-0047): bq://{project}/{dataset}/{table}.
        CrawlerAssetIds.assignBuilt(bqResource, AssetKind.BIGQUERY_TABLE,
                firstNonBlank(tableId.getProject(), projectId), tableId.getDataset(), tableId.getTable());
        bqResource.setLocation(datasetLocation); // Use dataset's location
        bqResource.setDescription(table.getDescription());
        bqResource.setLastDiscoveredAt(Instant.now());

        if (table.getCreationTime() != null) {
            bqResource.setCreatedAt(Instant.ofEpochMilli(table.getCreationTime()));
        }
        Long lastModifiedTime = table.getLastModifiedTime();
        if (lastModifiedTime != null) {
            bqResource.setUpdatedAt(Instant.ofEpochMilli(lastModifiedTime));
        } else {
            bqResource.setUpdatedAt(Instant.now());
        }

        if (table.getLabels() != null) {
            bqResource.setTags(table.getLabels());
        }

        // Add table-specific metadata
        String tableType = table.getDefinition().getType() != null ? table.getDefinition().getType().toString() : "TABLE";
        bqResource.addMetadata("tableType", tableType);

        // Add table size information
        if (table.getNumBytes() != null) {
            bqResource.addMetadata("numBytes", table.getNumBytes().toString());
            // The bridge forwards the observed table size as size_bytes (ADR-0046).
            CrawlerAssetIds.recordSize(bqResource, table.getNumBytes());
        }
        if (table.getNumRows() != null) {
            bqResource.addMetadata("numRows", table.getNumRows().toString());
        }

        return bqResource;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    protected boolean matchesFilters(BigQueryResource resource, Map<String, String> filters) {
        if (resource == null) return false;
        if (filters == null || filters.isEmpty()) return true;

        return filters
            .entrySet()
            .stream()
            .allMatch(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                if (value == null) return true;

                return switch (key.toLowerCase()) {
                    case "name" -> resource.getName() != null && resource.getName().toLowerCase().contains(value.toLowerCase());
                    case "id" -> resource.getResourceId() != null && resource.getResourceId().equalsIgnoreCase(value);
                    case "location" -> resource.getLocation() != null && resource.getLocation().equalsIgnoreCase(value);
                    case "projectid" -> resource.getProjectId() != null && resource.getProjectId().equalsIgnoreCase(value);
                    case "tabletype" -> resource.getMetadata() != null && Objects.equals(resource.getMetadata().get("tableType"), value);
                    default -> {
                        if (key.startsWith("tag:")) {
                            String tagKey = key.substring(4);
                            yield resource.getTags() != null && Objects.equals(resource.getTags().get(tagKey), value);
                        } else {
                            log.trace("Unsupported filter key '{}' for BigQuery resource {}", key, resource.getResourceId());
                            yield true;
                        }
                    }
                };
            });
    }
}
