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
import com.dlap.discovery.crawler.model.CrawlerAssetIds;
import com.dlap.discovery.crawler.model.gcp.CloudSQLResource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.InstancesListResponse;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import java.io.IOException;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Discovers GCP Cloud SQL instances asynchronously using the Cloud SQL Admin
 * API
 * and publishes events to Kafka.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class CloudSQLInstanceCrawler extends GCPResourceCrawler<CloudSQLResource> {

    private static final Logger log = LoggerFactory.getLogger(CloudSQLInstanceCrawler.class);
    private static final String APPLICATION_NAME = "DG-CloudSQL-Discovery";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String RESOURCE_TYPE_ID = "gcp_cloudsql_instance"; // Example ID

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public CloudSQLInstanceCrawler(CloudHierarchyRegistry hierarchyRegistry, KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        super(new ArrayList<>());
        this.hierarchyRegistry = hierarchyRegistry;
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");

        ResourceType rt = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        if (rt != null) {
            getSupportedResourceTypes().add(rt);
            log.info("CloudSQLInstanceCrawler initialized for type: {}", RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found for ID: {}. CloudSQLInstanceCrawler may not function.", RESOURCE_TYPE_ID);
        }
    }

    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous Cloud SQL instance discovery in project: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        String projectId = getProjectId(accountId, context);
        if (projectId == null) return; // Error logged in helper
        log.info("Starting async Cloud SQL instance discovery in project: {}", projectId);
        int discoveredCount = 0;

        SQLAdmin sqlAdmin = null;
        try {
            Credentials credentials = getAuthService().getCredentials();
            HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credentials);
            HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            sqlAdmin = new SQLAdmin.Builder(httpTransport, JSON_FACTORY, requestInitializer).setApplicationName(APPLICATION_NAME).build();

            log.debug("Listing Cloud SQL instances for project {}...", projectId);
            SQLAdmin.Instances.List request = sqlAdmin.instances().list(projectId);
            InstancesListResponse response = request.execute();

            ResourceType rt = findSupportedType(RESOURCE_TYPE_ID);
            if (rt == null) {
                log.error("ResourceType record not found for ID: {}. Cannot create CloudSQLResource.", RESOURCE_TYPE_ID);
                return;
            }

            if (response.getItems() != null) {
                Map<String, String> stringContext = convertToStringMap(context);
                for (DatabaseInstance instance : response.getItems()) {
                    CloudSQLResource sqlResource = createResourceFromInstance(instance, projectId, rt);
                    if (matchesFilters(sqlResource, stringContext)) {
                        publishResourceEvent(projectId, sqlResource, ChangeType.CREATE);
                        discoveredCount++;
                        log.debug("Published event for Cloud SQL instance: {}", sqlResource.getResourceId());
                    }
                }
            }

            log.info("Finished async Cloud SQL instance discovery. Published {} events for project {}.", discoveredCount, projectId);
        } catch (IOException e) {
            log.error("IOException during async Cloud SQL discovery for project {}: {}", projectId, e.getMessage(), e);
            // Consider publishing error event
        } catch (Exception e) {
            log.error("Unexpected error during async Cloud SQL discovery for project {}: {}", projectId, e.getMessage(), e);
            // Consider publishing error event
        }
    }

    /**
     * Helper to get projectId, prioritizing context, then accountId, then config.
     */
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

    /**
     * Helper to find the ResourceType record based on ID.
     */
    private ResourceType findSupportedType(String typeId) {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (typeId.equals(type.id())) {
                return type;
            }
        }
        log.warn("ResourceType not found in supported types: {}", typeId);
        return null;
    }

    /**
     * Helper method to publish resource events to Kafka.
     */
    private void publishResourceEvent(String projectId, CloudSQLResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(CloudProvider.GCP.toString(), projectId, changeType, resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event);
        } catch (Exception e) {
            log.error("Error publishing resource event for Cloud SQL instance {}: {}", resource.getResourceId(), e.getMessage(), e);
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

    CloudSQLResource createResourceFromInstance(DatabaseInstance instance, String projectId, ResourceType rt) {
        // rt validated by caller
        String instanceId = instance.getName();

        CloudSQLResource resource = new CloudSQLResource(rt, instanceId, instanceId);
        // Canonical id (ADR-0047): //cloudsql.googleapis.com/projects/{project}/instances/{instance}.
        // The region is an attribute, not identity.
        CrawlerAssetIds.assignBuilt(resource, AssetKind.CLOUD_SQL_INSTANCE, projectId, instanceId);
        // Set common fields
        resource.setProvider(CloudProvider.GCP.name()); // Deprecated, use setResourceType
        resource.setAccountId(projectId);
        resource.setProjectId(projectId);
        resource.setRegion(instance.getRegion());
        resource.setLocation(instance.getGceZone());
        resource.setLastDiscoveredAt(Instant.now());

        if (instance.getCreateTime() != null) {
            try {
                resource.setCreatedAt(Instant.parse(instance.getCreateTime()));
            } catch (Exception e) {
                log.warn("Could not parse createTime '{}' for instance {}", instance.getCreateTime(), instanceId);
            }
        }
        resource.setUpdatedAt(Instant.now()); // API doesn't seem to provide last updated time easily

        // Set metadata/properties
        resource.addMetadata("databaseVersion", instance.getDatabaseVersion());
        if (instance.getSettings() != null) {
            resource.addMetadata("tier", instance.getSettings().getTier());
            // Map labels to tags
            if (instance.getSettings().getUserLabels() != null) {
                resource.setTags(instance.getSettings().getUserLabels());
                resource.setDescription(instance.getSettings().getUserLabels().getOrDefault("description", null));
            }
        }
        resource.addMetadata("connectionName", instance.getConnectionName());
        resource.addMetadata("state", instance.getState());
        resource.addMetadata("status", instance.getState()); // Alias for state

        if (instance.getSelfLink() != null) {
            resource.addMetadata("selfLink", instance.getSelfLink());
        }

        return resource;
    }

    // matchesFilters remains largely the same but checks CloudSQLResource
    // fields/metadata
    private boolean matchesFilters(CloudSQLResource resource, Map<String, String> filters) {
        if (resource == null) return false;
        if (filters == null || filters.isEmpty()) return true;

        return filters
            .entrySet()
            .stream()
            .allMatch(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                if (value == null) return true;
                String lowerCaseKey = key.toLowerCase();

                // Use the transient getProperties() map which addMetadata populates
                Map<String, Object> properties = resource.getProperties() != null ? resource.getProperties() : Map.of();

                // Use consistent arrow syntax
                return switch (lowerCaseKey) {
                    case "name" -> resource.getName() != null && resource.getName().toLowerCase().contains(value.toLowerCase());
                    case "id" -> resource.getResourceId() != null && resource.getResourceId().equalsIgnoreCase(value);
                    case "projectid" -> resource.getProjectId() != null && resource.getProjectId().equalsIgnoreCase(value);
                    case "region" -> resource.getRegion() != null && resource.getRegion().equalsIgnoreCase(value);
                    case "location" -> resource.getLocation() != null && resource.getLocation().equalsIgnoreCase(value); // Zone for Cloud SQL
                    case "databaseversion" -> String
                        .valueOf(properties.getOrDefault("databaseVersion", ""))
                        .toLowerCase()
                        .contains(value.toLowerCase());
                    case "status", "state" -> { // Check both status and state metadata keys
                        String state = String.valueOf(
                            properties.getOrDefault("state", String.valueOf(properties.getOrDefault("status", "")))
                        );
                        yield state.equalsIgnoreCase(value);
                    }
                    case "tier" -> String.valueOf(properties.getOrDefault("tier", "")).equalsIgnoreCase(value);
                    // Handle tag filtering (mapped from labels) and direct property filtering
                    default -> {
                        if (key.startsWith("tag:")) {
                            String tagKey = key.substring(4);
                            yield resource.getTags() != null && Objects.equals(resource.getTags().get(tagKey), value);
                        } else if (key.startsWith("prop:")) {
                            String propKey = key.substring(5);
                            yield properties.containsKey(propKey) && Objects.equals(String.valueOf(properties.get(propKey)), value);
                        } else {
                            log.trace("Unsupported filter key '{}' for Cloud SQL resource {}", key, resource.getResourceId());
                            yield true;
                        }
                    }
                };
            });
    }
}
