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

package com.dlap.autoarchival.repository;

import com.dlap.autoarchival.model.StorageConsumerEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for StorageConsumerEntity with custom query methods for storage consumers.
 */
@Repository
public interface StorageConsumerRepository extends JpaRepository<StorageConsumerEntity, Long> {

    /**
     * Find top storage consumers by size with a limit.
     *
     * @param pageable Pageable object for pagination
     * @return List of top storage consumers ordered by size
     */
    @Query("SELECT s FROM StorageConsumerEntity s ORDER BY s.currentSizeTb DESC")
    List<StorageConsumerEntity> findTopStorageConsumers(Pageable pageable);

    /**
     * Find storage consumers by priority.
     *
     * @param priority Priority to filter by
     * @return List of storage consumers with the specified priority
     */
    List<StorageConsumerEntity> findByPriorityOrderByCurrentSizeTbDesc(String priority);

    /**
     * Find storage consumers by recommendation.
     *
     * @param recommendation Recommendation to filter by
     * @return List of storage consumers with the specified recommendation
     */
    List<StorageConsumerEntity> findByRecommendationOrderByCurrentSizeTbDesc(String recommendation);

    /**
     * Find storage consumers by type.
     *
     * @param consumerType Consumer type to filter by
     * @return List of storage consumers with the specified type
     */
    List<StorageConsumerEntity> findByConsumerTypeOrderByCurrentSizeTbDesc(String consumerType);

    /**
     * Find storage consumers with high estimated savings.
     *
     * @param minSavings Minimum estimated savings threshold
     * @return List of storage consumers with high estimated savings
     */
    @Query("SELECT s FROM StorageConsumerEntity s WHERE s.estimatedSavings >= :minSavings ORDER BY s.estimatedSavings DESC")
    List<StorageConsumerEntity> findHighSavingsConsumers(@Param("minSavings") Double minSavings);

    /**
     * Find storage consumers that haven't been accessed recently.
     *
     * @param cutoffDate Cutoff date for last access
     * @return List of storage consumers not accessed since the cutoff date
     */
    @Query("SELECT s FROM StorageConsumerEntity s WHERE s.lastAccessed < :cutoffDate ORDER BY s.currentSizeTb DESC")
    List<StorageConsumerEntity> findInactiveConsumers(@Param("cutoffDate") java.time.LocalDateTime cutoffDate);
} 