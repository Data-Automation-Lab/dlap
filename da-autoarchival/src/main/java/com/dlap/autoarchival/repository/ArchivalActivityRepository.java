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

import com.dlap.autoarchival.model.ArchivalActivityEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for ArchivalActivityEntity with custom query methods for recent activities.
 */
@Repository
public interface ArchivalActivityRepository extends JpaRepository<ArchivalActivityEntity, Long> {

    /**
     * Find recent activities with a limit.
     *
     * @param pageable Pageable object for pagination
     * @return List of recent activities ordered by timestamp
     */
    @Query("SELECT a FROM ArchivalActivityEntity a ORDER BY a.activityTimestamp DESC")
    List<ArchivalActivityEntity> findRecentActivities(Pageable pageable);

    /**
     * Find activities by status.
     *
     * @param status Status to filter by
     * @return List of activities with the specified status
     */
    List<ArchivalActivityEntity> findByStatusOrderByActivityTimestampDesc(String status);

    /**
     * Find activities by event type.
     *
     * @param eventType Event type to filter by
     * @return List of activities with the specified event type
     */
    List<ArchivalActivityEntity> findByEventTypeOrderByActivityTimestampDesc(String eventType);

    /**
     * Find activities for a specific task.
     *
     * @param taskId Task ID to filter by
     * @return List of activities for the specified task
     */
    List<ArchivalActivityEntity> findByTaskIdOrderByActivityTimestampDesc(String taskId);

    /**
     * Find activities for a specific asset.
     *
     * @param assetId Asset ID to filter by
     * @return List of activities for the specified asset
     */
    List<ArchivalActivityEntity> findByAssetIdOrderByActivityTimestampDesc(String assetId);

    /**
     * Find activities within a date range.
     *
     * @param startDate Start date for the range
     * @param endDate End date for the range
     * @return List of activities within the date range
     */
    @Query("SELECT a FROM ArchivalActivityEntity a WHERE a.activityTimestamp BETWEEN :startDate AND :endDate ORDER BY a.activityTimestamp DESC")
    List<ArchivalActivityEntity> findActivitiesByDateRange(@Param("startDate") LocalDateTime startDate, 
                                                           @Param("endDate") LocalDateTime endDate);
} 