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
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Spring Data JPA repository for managing {@link ResourceChange} entities.
 */
@Repository
public interface ResourceChangeRepository extends JpaRepository<ResourceChange, UUID> {

	// --- Basic CRUD Methods (Provided by JpaRepository) ---
	// save(S entity)
	// saveAll(Iterable<S> entities)
	// findById(UUID id)
	// existsById(UUID id)
	// findAll()
	// findAllById(Iterable<UUID> ids)
	// count()
	// deleteById(UUID id)
	// delete(ResourceChange entity)
	// deleteAllById(Iterable<? extends UUID> ids)
	// deleteAll(Iterable<? extends ResourceChange> entities)
	// deleteAll()

	/**
	 * Finds resource change records by the provider-specific ID of the resource
	 * that was changed.
	 *
	 * @param resourceId The provider-specific resource ID.
	 * @return A list of change records for that resource ID.
	 */
	List<ResourceChange> findByResourceIdOrderByTimestampDesc(String resourceId);

	/**
	 * Finds resource change records by the provider-specific ID of the resource
	 * that was changed, with pagination support.
	 *
	 * @param resourceId The provider-specific resource ID.
	 * @param pageable   The pagination information.
	 * @return A page of change records for that resource ID.
	 */
	Page<ResourceChange> findByResourceIdOrderByTimestampDesc(String resourceId, Pageable pageable);

	/**
	 * Finds resource change records by the provider-specific ID and the cloud
	 * provider.
	 *
	 * @param resourceId The provider-specific resource ID.
	 * @param provider   The cloud provider.
	 * @return A list of change records for that resource ID within the specified
	 *         provider.
	 */
	List<ResourceChange> findByResourceIdAndCloudProviderOrderByTimestampDesc(String resourceId,
			CloudProvider provider);

	/**
	 * Finds resource change records by the actor who performed the change.
	 *
	 * @param actorId The identifier of the actor.
	 * @return A list of change records performed by the actor.
	 */
	List<ResourceChange> findByActorOrderByTimestampDesc(String actorId);

	/**
	 * Finds resource change records by the type of change.
	 *
	 * @param changeType The type of change.
	 * @return A list of change records of the specified type.
	 */
	List<ResourceChange> findByChangeTypeOrderByTimestampDesc(ChangeType changeType);

	/**
	 * Finds resource change records that occurred after a specific timestamp.
	 *
	 * @param timestamp The timestamp threshold.
	 * @return A list of change records occurring after the timestamp.
	 */
	List<ResourceChange> findByTimestampAfterOrderByTimestampDesc(Instant timestamp);

	/**
	 * Finds resource change records within a specific time range.
	 *
	 * @param startTime The start time (inclusive).
	 * @param endTime   The end time (exclusive).
	 * @return A list of change records within the time range.
	 */
	List<ResourceChange> findByTimestampBetweenOrderByTimestampDesc(Instant startTime, Instant endTime);

	/**
	 * Finds resource change records within a specific time range with pagination
	 * support.
	 *
	 * @param startTime The start time (inclusive).
	 * @param endTime   The end time (exclusive).
	 * @param pageable  The pagination information.
	 * @return A page of change records within the time range.
	 */
	Page<ResourceChange> findByTimestampBetweenOrderByTimestampDesc(Instant startTime, Instant endTime,
			Pageable pageable);

	// Add other custom query methods as needed (e.g., find by project ID, actor
	// email)
}