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

import com.dlap.autoarchival.model.ArchivalMetricsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ArchivalMetricsEntity with custom query methods for archival overview data.
 */
@Repository
public interface ArchivalMetricsRepository extends JpaRepository<ArchivalMetricsEntity, Long> {

    /**
     * Find the most recent metrics entry.
     *
     * @return Optional containing the most recent metrics
     */
    @Query("SELECT m FROM ArchivalMetricsEntity m ORDER BY m.metricDate DESC")
    Optional<ArchivalMetricsEntity> findMostRecentMetrics();

    /**
     * Find metrics for a specific date range.
     *
     * @param startDate Start date for the range
     * @param endDate End date for the range
     * @return List of metrics within the date range
     */
    @Query("SELECT m FROM ArchivalMetricsEntity m WHERE m.metricDate BETWEEN :startDate AND :endDate ORDER BY m.metricDate DESC")
    List<ArchivalMetricsEntity> findMetricsByDateRange(@Param("startDate") LocalDateTime startDate, 
                                                       @Param("endDate") LocalDateTime endDate);

    /**
     * Find metrics for the last N days.
     *
     * @param days Number of days to look back
     * @return List of metrics for the specified number of days
     */
    @Query("SELECT m FROM ArchivalMetricsEntity m WHERE m.metricDate >= :startDate ORDER BY m.metricDate DESC")
    List<ArchivalMetricsEntity> findMetricsForLastDays(@Param("startDate") LocalDateTime startDate);
} 