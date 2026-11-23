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

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dlap.discovery.common.model.MetricEntity;

/**
 * Spring Data JPA repository for the {@link MetricEntity} entity.
 * Provides standard CRUD operations and potentially custom query methods
 * for accessing persisted metric data.
 */
@Repository
public interface IMetricsRepository extends JpaRepository<MetricEntity, UUID> {
	// Add custom query methods here if needed, for example:
	// List<MetricEntity> findByMetricNameAndTimestampBetween(String metricName,
	// Instant start, Instant end);
	// Page<MetricEntity> findByTagsContaining(String key, String value, Pageable
	// pageable);
}