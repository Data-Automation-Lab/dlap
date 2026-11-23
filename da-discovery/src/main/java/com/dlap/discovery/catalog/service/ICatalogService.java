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

package com.dlap.discovery.catalog.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Consolidated service interface for catalog operations.
 * Works with the com.dlap.discovery.common.models (CloudResource,
 * ResourceChange).
 */
public interface ICatalogService {

	// --- Resource Query Methods ---

	/**
	 * Retrieves a resource by its internal database primary key.
	 *
	 * @param resourceDbId The database UUID.
	 * @return Optional containing the resource if found.
	 */
	Optional<CloudResource> getResource(UUID resourceDbId);

	/**
	 * Retrieves a resource by its business key (resource ID, provider, type ID).
	 *
	 * @param resourceId The provider-specific resource ID.
	 * @param provider   The cloud provider.
	 * @param typeId     The resource type ID.
	 * @return Optional containing the resource if found.
	 */
	Optional<CloudResource> getResourceByKey(String resourceId, CloudProvider provider, String typeId);

	/**
	 * Retrieves resources by type (using ResourceType record) and provider.
	 * Provides pagination support.
	 *
	 * @param type     The ResourceType record.
	 * @param provider The cloud provider.
	 * @param pageable Pagination information.
	 * @return A Page of matching resources.
	 */
	Page<CloudResource> getResourcesByType(ResourceType type, CloudProvider provider, Pageable pageable);

	/**
	 * Retrieves resources by Account ID (e.g., AWS Account, Azure Subscription, GCP
	 * Project).
	 * Provides pagination support.
	 *
	 * @param accountId The account identifier.
	 * @param pageable  Pagination information.
	 * @return A Page of resources associated with the account.
	 */
	Page<CloudResource> getResourcesByAccount(String accountId, Pageable pageable);

	// --- Change Processing Method ---

	/**
	 * Updates resources in the catalog.
	 * 
	 * @param resources The resources to update
	 */
	void updateResources(List<CloudResource> resources);

	/**
	 * Processes a resource change.
	 * 
	 * @param change The resource change to process
	 */
	void processResourceChange(ResourceChange change);

	/**
	 * Processes multiple resource changes.
	 * 
	 * @param changes The resource changes to process
	 */
	default void processResourceChanges(List<ResourceChange> changes) {
		if (changes != null) {
			changes.forEach(this::processResourceChange);
		}
	}

	// --- Change History Query Methods ---

	/**
	 * Retrieves change history for a specific resource (identified by DB ID).
	 * Provides pagination support.
	 *
	 * @param resourceDbId The database UUID of the CloudResource.
	 * @param pageable     Pagination information.
	 * @return A Page of ResourceChange records for the resource.
	 */
	Page<ResourceChange> getResourceChanges(UUID resourceDbId, Pageable pageable);

	/**
	 * Retrieves change history within a specific time range.
	 * Provides pagination support.
	 *
	 * @param start    Start time (inclusive).
	 * @param end      End time (exclusive).
	 * @param pageable Pagination information.
	 * @return A Page of ResourceChange records within the time range.
	 */
	Page<ResourceChange> getChangesByTimeRange(Instant start, Instant end, Pageable pageable);

	// --- Potentially useful methods adapted from ICatalogUpdater ---
	// These might need adjustment or implementation based on requirements

	/**
	 * Updates tags for a specific resource.
	 *
	 * @param resourceDbId The database UUID of the CloudResource.
	 * @param tags         The complete set of tags to apply.
	 * @return The updated CloudResource, or empty if not found.
	 */
	Optional<CloudResource> updateResourceTags(UUID resourceDbId, Map<String, String> tags);

	/**
	 * Updates technical metadata for a specific resource.
	 *
	 * @param resourceDbId The database UUID of the CloudResource.
	 * @param metadata     The complete set of technical metadata to apply.
	 * @return The updated CloudResource, or empty if not found.
	 */
	Optional<CloudResource> updateTechnicalMetadata(UUID resourceDbId, Map<String, String> metadata);

	/**
	 * Updates business metadata for a specific resource.
	 *
	 * @param resourceDbId The database UUID of the CloudResource.
	 * @param metadata     The complete set of business metadata to apply.
	 * @return The updated CloudResource, or empty if not found.
	 */
	Optional<CloudResource> updateBusinessMetadata(UUID resourceDbId, Map<String, String> metadata);

	// markResourceDeleted might be handled by processResourceChanges(DELETE)
	// updateResourceProperties merged into metadata methods?
}