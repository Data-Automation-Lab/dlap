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

package com.dlap.discovery.catalog.client.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.dlap.discovery.catalog.client.CatalogClient;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;

/**
 * Default implementation of the CatalogClient interface.
 * This implementation uses REST API calls to interact with an external catalog
 * service.
 */
@Service
@ConditionalOnProperty(name = "catalog.external.enabled", havingValue = "true", matchIfMissing = false)
public class DefaultCatalogClient implements CatalogClient {
    private static final Logger logger = LoggerFactory.getLogger(DefaultCatalogClient.class);

    private final RestTemplate restTemplate;
    private final String catalogBaseUrl;
    private final String apiKey;

    public DefaultCatalogClient(
            RestTemplate restTemplate,
            @Value("${catalog.external.baseUrl}") String catalogBaseUrl,
            @Value("${catalog.external.apiKey:#{null}}") String apiKey) {
        this.restTemplate = restTemplate;
        this.catalogBaseUrl = catalogBaseUrl;
        this.apiKey = apiKey;
        logger.info("Initialized DefaultCatalogClient with base URL: {}", catalogBaseUrl);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("X-API-Key", apiKey);
        }
        return headers;
    }

    @Override
    public String registerResource(CloudResource resource) {
        try {
            HttpEntity<CloudResource> entity = new HttpEntity<>(resource, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("catalogId");
            }
            logger.warn("Failed to register resource: {}", resource.getId());
            return null;
        } catch (Exception e) {
            logger.error("Error registering resource: " + resource.getId(), e);
            return null;
        }
    }

    @Override
    public Map<String, String> registerResources(List<CloudResource> resources) {
        try {
            HttpEntity<List<CloudResource>> entity = new HttpEntity<>(resources, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/batch",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, String> result = new HashMap<>();
                Map<String, String> responseMap = (Map<String, String>) response.getBody().get("catalogIds");
                if (responseMap != null) {
                    return responseMap;
                }
            }
            logger.warn("Failed to batch register resources, count: {}", resources.size());
            return Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Error batch registering resources", e);
            return Collections.emptyMap();
        }
    }

    @Override
    public boolean updateResource(CloudResource resource) {
        try {
            HttpEntity<CloudResource> entity = new HttpEntity<>(resource, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/" + resource.getId(),
                    HttpMethod.PUT,
                    entity,
                    Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error updating resource: " + resource.getId(), e);
            return false;
        }
    }

    @Override
    public Optional<CloudResource> getResource(String resourceId, ResourceType resourceType) {
        if (resourceId == null || resourceType == null) {
            logger.warn("Attempted to get resource with null ID or type.");
            return Optional.empty();
        }
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            String url = catalogBaseUrl + "/resources/" + resourceId + "?type=" + resourceType.id();
            ResponseEntity<CloudResource> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    CloudResource.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(response.getBody());
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting resource: " + resourceId + " with type " + resourceType.id(), e);
            return Optional.empty();
        }
    }

    @Override
    public List<CloudResource> searchResources(String query, Map<String, Object> parameters) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);
            requestBody.put("parameters", parameters);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/search",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> resources = (List<Map<String, Object>>) response.getBody().get("resources");
                if (resources != null) {
                    // This would need a proper mapper from Map to CloudResource
                    return resources.stream()
                            .map(this::mapToCloudResource)
                            .collect(Collectors.toList());
                }
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error searching resources", e);
            return Collections.emptyList();
        }
    }

    // This is a placeholder method - would need proper implementation
    private CloudResource mapToCloudResource(Map<String, Object> resourceMap) {
        // In a real implementation, would convert the map to a CloudResource object
        return null;
    }

    @Override
    public boolean deleteResource(String resourceId, ResourceType resourceType) {
        if (resourceId == null || resourceType == null) {
            logger.warn("Attempted to delete resource with null ID or type.");
            return false;
        }
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            String url = catalogBaseUrl + "/resources/" + resourceId + "?type=" + resourceType.id();
            ResponseEntity<Void> response = restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    entity,
                    Void.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error deleting resource: " + resourceId + " with type " + resourceType.id(), e);
            return false;
        }
    }

    @Override
    public String registerSchema(String resourceId, Object schema) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("resourceId", resourceId);
            requestBody.put("schema", schema);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/schemas",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("schemaId");
            }
            return null;
        } catch (Exception e) {
            logger.error("Error registering schema for resource: " + resourceId, e);
            return null;
        }
    }

    @Override
    public Optional<Object> getSchema(String resourceId) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/schemas/" + resourceId,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.ofNullable(response.getBody().get("schema"));
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting schema for resource: " + resourceId, e);
            return Optional.empty();
        }
    }

    @Override
    public String recordLineage(String sourceResourceId, String targetResourceId,
            String lineageType, Map<String, Object> metadata) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("sourceResourceId", sourceResourceId);
            requestBody.put("targetResourceId", targetResourceId);
            requestBody.put("lineageType", lineageType);
            requestBody.put("metadata", metadata);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/lineage",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("lineageId");
            }
            return null;
        } catch (Exception e) {
            logger.error("Error recording lineage", e);
            return null;
        }
    }

    @Override
    public Map<String, Object> getLineage(String resourceId, String direction, int depth) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/lineage/" + resourceId +
                            "?direction=" + direction + "&depth=" + depth,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Error getting lineage for resource: " + resourceId, e);
            return Collections.emptyMap();
        }
    }

    @Override
    public boolean addTags(String resourceId, Map<String, String> tags) {
        try {
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(tags, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/" + resourceId + "/tags",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error adding tags to resource: " + resourceId, e);
            return false;
        }
    }

    @Override
    public Map<String, String> getTags(String resourceId) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/" + resourceId + "/tags",
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (Map<String, String>) response.getBody().get("tags");
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Error getting tags for resource: " + resourceId, e);
            return Collections.emptyMap();
        }
    }

    @Override
    public boolean recordAccess(String resourceId, String userId, String accessType, Long timestamp) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("userId", userId);
            requestBody.put("accessType", accessType);
            if (timestamp != null) {
                requestBody.put("timestamp", timestamp);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/" + resourceId + "/access",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error recording access for resource: " + resourceId, e);
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> getAccessHistory(String resourceId, int limit) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/" + resourceId + "/access?limit=" + limit,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (List<Map<String, Object>>) response.getBody().get("accessRecords");
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error getting access history for resource: " + resourceId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public String recordChange(ResourceChange change) {
        try {
            HttpEntity<ResourceChange> entity = new HttpEntity<>(change, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/changes",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("changeId");
            }
            return null;
        } catch (Exception e) {
            logger.error("Error recording change", e);
            return null;
        }
    }

    @Override
    public List<ResourceChange> getChangeHistory(String resourceId, int limit) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/resources/" + resourceId + "/changes?limit=" + limit,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> changes = (List<Map<String, Object>>) response.getBody().get("changes");
                // This would need a proper mapper from Map to ResourceChange
                return Collections.emptyList(); // Placeholder
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error getting change history for resource: " + resourceId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isConnected() {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/health",
                    HttpMethod.GET,
                    entity,
                    Map.class);

            return response.getStatusCode().is2xxSuccessful() &&
                    response.getBody() != null &&
                    "UP".equals(response.getBody().get("status"));
        } catch (Exception e) {
            logger.error("Error checking catalog connection", e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getCatalogInfo() {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/info",
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Error getting catalog info", e);
            return Collections.emptyMap();
        }
    }

    @Override
    public List<ResourceType> getAllResourceTypes() {
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<ResourceType[]> response = restTemplate.exchange(
                    catalogBaseUrl + "/resourceTypes",
                    HttpMethod.GET,
                    entity,
                    ResourceType[].class);

            ResourceType[] resourceTypes = response.getBody();
            if (resourceTypes != null) {
                return List.of(resourceTypes);
            }
        } catch (RestClientException e) {
            logger.error("Failed to get resource types from catalog: {}", e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    @Override
    public List<CloudService> getAllCloudServices() {
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<CloudService[]> response = restTemplate.exchange(
                    catalogBaseUrl + "/services",
                    HttpMethod.GET,
                    entity,
                    CloudService[].class);

            CloudService[] services = response.getBody();
            if (services != null) {
                return List.of(services);
            }
        } catch (RestClientException e) {
            logger.error("Failed to get cloud services from catalog: {}", e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    @Override
    public ResourceType getResourceType(String typeId) {
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<ResourceType> response = restTemplate.exchange(
                    catalogBaseUrl + "/resourceTypes/" + typeId,
                    HttpMethod.GET,
                    entity,
                    ResourceType.class);

            return response.getBody();
        } catch (RestClientException e) {
            logger.error("Failed to get resource type {} from catalog: {}", typeId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public CloudService getCloudService(String serviceId) {
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<CloudService> response = restTemplate.exchange(
                    catalogBaseUrl + "/services/" + serviceId,
                    HttpMethod.GET,
                    entity,
                    CloudService.class);

            return response.getBody();
        } catch (RestClientException e) {
            logger.error("Failed to get cloud service {} from catalog: {}", serviceId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Map<String, Object> getServiceMappings() {
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + "/mappings",
                    HttpMethod.GET,
                    entity,
                    Map.class);

            return response.getBody();
        } catch (RestClientException e) {
            logger.error("Failed to get service mappings from catalog: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
}