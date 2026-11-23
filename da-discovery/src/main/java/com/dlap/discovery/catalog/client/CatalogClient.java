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

package com.dlap.discovery.catalog.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;

/**
 * Client interface for interacting with external catalog services.
 * This provides a common interface for different catalog services,
 * such as data catalogs, metadata stores, or asset inventory systems.
 */
public interface CatalogClient {

    /**
     * Registers a resource in the catalog.
     *
     * @param resource The resource to register
     * @return The ID of the registered resource in the catalog
     */
    String registerResource(CloudResource resource);

    /**
     * Registers multiple resources in the catalog.
     *
     * @param resources The resources to register
     * @return A map of resource IDs to their catalog IDs
     */
    Map<String, String> registerResources(List<CloudResource> resources);

    /**
     * Updates a resource in the catalog.
     *
     * @param resource The resource to update
     * @return true if the resource was updated, false otherwise
     */
    boolean updateResource(CloudResource resource);

    /**
     * Gets a resource from the catalog.
     *
     * @param resourceId   The resource ID (usually the unique ID like OCID, ARN,
     *                     etc.)
     * @param resourceType The resource type record (contains ID, service, provider
     *                     info)
     * @return The resource, if found
     */
    Optional<CloudResource> getResource(String resourceId, ResourceType resourceType);

    /**
     * Searches for resources in the catalog.
     *
     * @param query      The search query
     * @param parameters Additional search parameters
     * @return The resources matching the search
     */
    List<CloudResource> searchResources(String query, Map<String, Object> parameters);

    /**
     * Deletes a resource from the catalog.
     *
     * @param resourceId   The resource ID
     * @param resourceType The resource type record
     * @return true if the resource was deleted, false otherwise
     */
    boolean deleteResource(String resourceId, ResourceType resourceType);

    /**
     * Registers a schema for a resource.
     *
     * @param resourceId The resource ID
     * @param schema     The schema definition
     * @return The ID of the registered schema
     */
    String registerSchema(String resourceId, Object schema);

    /**
     * Gets the schema for a resource.
     *
     * @param resourceId The resource ID
     * @return The schema, if found
     */
    Optional<Object> getSchema(String resourceId);

    /**
     * Records lineage information.
     *
     * @param sourceResourceId The source resource ID
     * @param targetResourceId The target resource ID
     * @param lineageType      The type of lineage (e.g., "COPY", "DERIVE",
     *                         "TRANSFORM")
     * @param metadata         Additional lineage metadata
     * @return The ID of the lineage record
     */
    String recordLineage(String sourceResourceId, String targetResourceId,
            String lineageType, Map<String, Object> metadata);

    /**
     * Gets lineage information for a resource.
     *
     * @param resourceId The resource ID
     * @param direction  The lineage direction ("UPSTREAM", "DOWNSTREAM", or "BOTH")
     * @param depth      The maximum depth to traverse
     * @return The lineage information
     */
    Map<String, Object> getLineage(String resourceId, String direction, int depth);

    /**
     * Adds tags to a resource.
     *
     * @param resourceId The resource ID
     * @param tags       The tags to add
     * @return true if the tags were added, false otherwise
     */
    boolean addTags(String resourceId, Map<String, String> tags);

    /**
     * Gets tags for a resource.
     *
     * @param resourceId The resource ID
     * @return The tags for the resource
     */
    Map<String, String> getTags(String resourceId);

    /**
     * Records an access event for a resource.
     *
     * @param resourceId The resource ID
     * @param userId     The user ID
     * @param accessType The type of access (e.g., "READ", "WRITE")
     * @param timestamp  The time of access (null for current time)
     * @return true if the access was recorded, false otherwise
     */
    boolean recordAccess(String resourceId, String userId, String accessType, Long timestamp);

    /**
     * Gets access history for a resource.
     *
     * @param resourceId The resource ID
     * @param limit      The maximum number of records to return
     * @return The access history
     */
    List<Map<String, Object>> getAccessHistory(String resourceId, int limit);

    /**
     * Records a change to a resource.
     *
     * @param change The resource change
     * @return The ID of the change record
     */
    String recordChange(ResourceChange change);

    /**
     * Gets change history for a resource.
     *
     * @param resourceId The resource ID
     * @param limit      The maximum number of records to return
     * @return The change history
     */
    List<ResourceChange> getChangeHistory(String resourceId, int limit);

    /**
     * Gets the catalog connection status.
     *
     * @return true if connected, false otherwise
     */
    boolean isConnected();

    /**
     * Gets information about the catalog service.
     *
     * @return Information about the catalog service
     */
    Map<String, Object> getCatalogInfo();

    /**
     * Gets all resource types from the catalog.
     *
     * @return List of all available resource types
     */
    List<ResourceType> getAllResourceTypes();

    /**
     * Gets all cloud services from the catalog.
     *
     * @return List of all available cloud services
     */
    List<CloudService> getAllCloudServices();

    /**
     * Gets a specific resource type by its ID.
     *
     * @param typeId The resource type ID
     * @return The resource type if found, null otherwise
     */
    ResourceType getResourceType(String typeId);

    /**
     * Gets a specific cloud service by its ID.
     *
     * @param serviceId The service ID
     * @return The cloud service if found, null otherwise
     */
    CloudService getCloudService(String serviceId);

    /**
     * Gets service mappings from the catalog.
     * These mappings help translate between different cloud provider service
     * identifiers.
     *
     * @return Map of service mappings
     */
    Map<String, Object> getServiceMappings();
}