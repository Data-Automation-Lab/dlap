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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.common.model.CloudResourceUsageStats;

/**
 * Spring Data JPA repository for managing {@link CloudResourceUsageStats}
 * entities.
 */
@Repository
public interface CloudResourceUsageStatsRepository extends JpaRepository<CloudResourceUsageStats, UUID> {

	// --- Basic CRUD Methods (Provided by JpaRepository) ---
	// save(S entity)
	// saveAll(Iterable<S> entities)
	// findById(UUID id)
	// existsById(UUID id)
	// findAll()
	// findAllById(Iterable<UUID> ids)
	// count()
	// deleteById(UUID id)
	// delete(CloudResourceUsageStats entity)
	// deleteAllById(Iterable<? extends UUID> ids)
	// deleteAll(Iterable<? extends CloudResourceUsageStats> entities)
	// deleteAll()

	/**
	 * Finds the usage statistics record associated with a specific CloudResource
	 * database ID.
	 * Since it's a OneToOne relationship, this should return at most one record.
	 *
	 * @param resourceDbId The internal database ID (UUID) of the CloudResource.
	 * @return An Optional containing the usage stats, if found.
	 */
	Optional<CloudResourceUsageStats> findByCloudResource_Id(UUID resourceDbId);

	/**
	 * Finds usage statistics records where the aggregation time window overlaps
	 * with the given Instant.
	 *
	 * @param timestamp The Instant to check for overlap.
	 * @return A list of usage stats records whose time window includes the
	 *         timestamp.
	 */
	List<CloudResourceUsageStats> findByTimeWindowStartLessThanEqualAndTimeWindowEndGreaterThanEqual(Instant timestamp,
			Instant timestamp2);

	/**
	 * Finds usage statistics records with a read count greater than a specified
	 * value.
	 *
	 * @param count The minimum read count (exclusive).
	 * @return A list of usage stats records exceeding the read count.
	 */
	List<CloudResourceUsageStats> findByReadCountGreaterThan(long count);

	// Add other custom query methods as needed (e.g., find by write count, last
	// accessed time range)
}