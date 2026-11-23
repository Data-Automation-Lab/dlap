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

package com.dlap.discovery.common.model.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Spring Data JPA repository for managing {@link CloudResource} entities.
 * Provides standard CRUD operations and custom query methods.
 */
@Repository
public interface CloudResourceRepository extends JpaRepository<CloudResource, UUID>, JpaSpecificationExecutor<CloudResource> {

        // --- Basic CRUD Methods (Provided by JpaRepository) ---
        // save(S entity)
        // saveAll(Iterable<S> entities)
        // findById(UUID id) // Note: Takes UUID (DB Primary Key)
        // existsById(UUID id)
        // findAll()
        // findAllById(Iterable<UUID> ids)
        // count()
        // deleteById(UUID id)
        // delete(CloudResource entity)
        // deleteAllById(Iterable<? extends UUID> ids) // Corrected method signature
        // deleteAll(Iterable<? extends CloudResource> entities)
        // deleteAll()

        // --- Custom Query Methods (Examples using Query Derivation or @Query) ---

        /**
         * Finds a resource by its provider-specific resource ID, provider, and account
         * ID.
         * This combination often forms a natural business key.
         *
         * @param resourceId Provider-specific resource ID.
         * @param provider   Cloud provider enum.
         * @param accountId  Account/Subscription/Tenancy ID.
         * @return Optional containing the found resource.
         */
        Optional<CloudResource> findByResourceIdAndCloudProviderAndAccountId(String resourceId, CloudProvider provider,
                        String accountId);

        /**
         * Finds a cloud resource based on its provider-specific resource ID, cloud
         * provider, and type ID.
         * This combination often forms a unique business key for a resource.
         * Note: Assumes 'typeId' corresponds to the mapped column in CloudResource.
         *
         * @param resourceId The provider-specific resource identifier.
         * @param provider   The cloud provider enum.
         * @param typeId     The specific type identifier within the service.
         * @return An {@link Optional} containing the found {@link CloudResource} or
         *         empty if not found.
         */
        Optional<CloudResource> findByResourceIdAndCloudProviderAndTypeId(String resourceId, CloudProvider provider,
                        String typeId);

        /**
         * Finds resources by their provider-specific resource ID.
         * Note: This might return multiple resources if the ID is not unique across
         * providers/accounts.
         *
         * @param resourceId The provider-specific resource ID.
         * @return A list of resources matching the ID.
         */
        List<CloudResource> findByResourceId(String resourceId);

        /**
         * Finds resources by their assigned name.
         *
         * @param name The resource name.
         * @return A list of resources with the given name.
         */
        List<CloudResource> findByName(String name);

        /**
         * Finds resources by their mapped type components.
         *
         * @param provider  The cloud provider enum.
         * @param serviceId The service identifier.
         * @param typeId    The type identifier.
         * @return The resources of the specified type.
         */
        List<CloudResource> findByCloudProviderAndServiceIdAndTypeId(CloudProvider provider, String serviceId,
                        String typeId);

        /**
         * Finds resources by their mapped type components with pagination support.
         *
         * @param provider  The cloud provider enum.
         * @param serviceId The service identifier.
         * @param typeId    The type identifier.
         * @param pageable  The pagination information.
         * @return A Page of resources of the specified type.
         */
        Page<CloudResource> findByCloudProviderAndServiceIdAndTypeId(CloudProvider provider, String serviceId,
                        String typeId, Pageable pageable);

        /**
         * Finds resources by cloud provider.
         *
         * @param provider The cloud provider enum.
         * @return The resources from the specified provider.
         */
        List<CloudResource> findByCloudProvider(CloudProvider provider);

        /**
         * Finds resources by account ID (AWS Account, Azure Subscription, OCI Tenancy).
         *
         * @param accountId The account identifier.
         * @return The resources associated with the account.
         */
        List<CloudResource> findByAccountId(String accountId);

        /**
         * Finds resources by account ID (AWS Account, Azure Subscription, OCI Tenancy)
         * with pagination support.
         *
         * @param accountId The account identifier.
         * @param pageable  The pagination information.
         * @return A Page of resources associated with the account.
         */
        Page<CloudResource> findByAccountId(String accountId, Pageable pageable);

        /**
         * Finds resources by GCP project ID.
         *
         * @param projectId The GCP project ID.
         * @return The resources within the specified project.
         */
        List<CloudResource> findByProjectId(String projectId);

        /**
         * Finds resources by region.
         *
         * @param region The region identifier.
         * @return The resources located in the specified region.
         */
        List<CloudResource> findByRegion(String region);

        /**
         * Example using @Query to find resources containing a specific tag key.
         * Note: Querying map collections can be database-specific.
         * This example uses JPQL suitable for basic key existence.
         *
         * @param tagKey The tag key to search for.
         * @return List of resources having the specified tag key.
         */
        @Query("SELECT cr FROM CloudResource cr JOIN cr.tags t WHERE KEY(t) = :tagKey")
        List<CloudResource> findByTagKey(@Param("tagKey") String tagKey);

        /**
         * Counts resources discovered after a specific timestamp.
         *
         * @param timestamp The timestamp threshold
         * @return Count of resources discovered after the timestamp
         */
        long countByLastDiscoveredAtAfter(Instant timestamp);

        List<CloudResource> findByLastDiscoveredAtAfter(Instant instant);

        /**
         * Counts the number of resources for a specific cloud provider.
         *
         * @param provider The cloud provider to count resources for
         * @return The number of resources for the provider
         */
        long countByCloudProvider(CloudProvider provider);

    

        /**
         * Counts the number of resources discovered before a specific time.
         *
         * @param instant The time to count resources before
         * @return The number of resources discovered before the given time
         */
        long countByLastDiscoveredAtBefore(Instant instant);

        /**
         * Counts the number of resources discovered between two times.
         *
         * @param start The start time (inclusive)
         * @param end The end time (exclusive)
         * @return The number of resources discovered between the given times
         */
        long countByLastDiscoveredAtBetween(Instant start, Instant end);
        // --- Methods from the old interface are removed or commented below for
        // reference ---
        // They need careful reimplementation using JPA features (derived queries,
        // @Query, Specifications) or removal.

        /*
         * // findById(String resourceId) -> Changed to use UUID primary key:
         * findById(UUID id)
         * // findAllById(List<String> resourceIds) -> Changed to use UUID primary key:
         * findAllById(Iterable<UUID> ids)
         * 
         * // findByType(ResourceType resourceType) -> Replaced by
         * findByCloudProviderAndServiceIdAndTypeId
         * 
         * // findBySourceId(String sourceId) -> Ambiguous. Replace with findByAccountId
         * or findByProjectId?
         * 
         * // findByTypeAndSourceId(ResourceType resourceType, String sourceId) ->
         * Replace with combination of type/account/project finders
         * 
         * // findByCriteria(Map<String, String> criteria) -> Requires custom
         * implementation (e.g., Specifications)
         * 
         * // findByLastUpdatedTimeAfter(ZonedDateTime updatedAfter) -> Replace with
         * derived query on 'updatedAt' (Instant)
         * // List<CloudResource> findByUpdatedAtAfter(Instant updatedAfter);
         * 
         * // findByLastUpdatedTimeBefore(ZonedDateTime updatedBefore) -> Replace with
         * derived query on 'updatedAt' (Instant)
         * // List<CloudResource> findByUpdatedAtBefore(Instant updatedBefore);
         * 
         * // deleteById(String resourceId) -> Changed to use UUID primary key:
         * deleteById(UUID id)
         * 
         * // deleteAllById(List<String> resourceIds) -> Changed to use UUID primary
         * key: deleteAllById(Iterable<UUID> ids)
         * 
         * // updateMetadata(...) -> Requires custom query/service logic for partial
         * updates.
         * 
         * // updateTags(...) -> Requires custom query/service logic for partial
         * updates.
         * 
         * // updateStatus(...) -> Status is not a mapped field. Requires entity change
         * or custom logic.
         * 
         * // countByType(ResourceType resourceType) -> Replaced by
         * countByCloudProviderAndServiceIdAndTypeId
         * // long countByCloudProviderAndServiceIdAndTypeId(CloudProvider provider,
         * String serviceId, String typeId);
         * 
         * // countBySourceId(String sourceId) -> Replace with countByAccountId or
         * countByProjectId
         * 
         * // countByStatus(String status) -> Status is not a mapped field.
         */

}