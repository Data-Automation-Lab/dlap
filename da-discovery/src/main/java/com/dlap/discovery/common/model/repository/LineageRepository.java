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

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.common.model.Lineage;

/**
 * Spring Data JPA repository for managing {@link Lineage} entities.
 */
@Repository
public interface LineageRepository extends JpaRepository<Lineage, UUID> {

	// --- Basic CRUD Methods (Provided by JpaRepository) ---
	// save(S entity)
	// saveAll(Iterable<S> entities)
	// findById(UUID id)
	// existsById(UUID id)
	// findAll()
	// findAllById(Iterable<UUID> ids)
	// count()
	// deleteById(UUID id)
	// delete(Lineage entity)
	// deleteAllById(Iterable<? extends UUID> ids)
	// deleteAll(Iterable<? extends Lineage> entities)
	// deleteAll()

	/**
	 * Finds all lineage records associated with a specific CloudResource database
	 * ID.
	 *
	 * @param resourceDbId The internal database ID (UUID) of the CloudResource.
	 * @return A list of associated lineage records.
	 */
	List<Lineage> findByCloudResource_Id(UUID resourceDbId);

	/**
	 * Finds lineage records based on the actor who performed the action.
	 *
	 * @param actorId The identifier of the actor.
	 * @return A list of lineage records performed by the specified actor.
	 */
	List<Lineage> findByActorId(String actorId);

	/**
	 * Finds lineage records where the specified resource ID appears as a source.
	 *
	 * @param sourceResourceId The UUID of the source CloudResource.
	 * @return A list of lineage records where the given resource was a source.
	 */
	List<Lineage> findBySourceResourceIdsContaining(UUID sourceResourceId);

	// Add other custom query methods as needed (e.g., find by action, timestamp
	// range)
}