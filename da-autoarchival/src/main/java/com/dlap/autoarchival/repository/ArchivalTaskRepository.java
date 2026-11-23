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

import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.model.ArchivalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ArchivalTaskEntity with custom query methods for archival tasks.
 */
@Repository
public interface ArchivalTaskRepository extends JpaRepository<ArchivalTaskEntity, String> {
    
    /**
     * Loads a task with a row lock, so the executor, the proof listener and the approval listener
     * never overwrite each other's changes to the same task (RET-03).
     *
     * @param taskId the task id
     * @return the locked task
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ArchivalTaskEntity t WHERE t.taskId = :taskId")
    Optional<ArchivalTaskEntity> findForUpdate(@Param("taskId") String taskId);

    /**
     * Find tasks by status.
     *
     * @param status Status to filter by
     * @return List of tasks with the specified status
     */
    List<ArchivalTaskEntity> findByStatusOrderBySubmittedAtDesc(ArchivalStatus status);
    
    /**
     * Find tasks by triggered by user.
     *
     * @param triggeredBy User who triggered the task
     * @return List of tasks triggered by the specified user
     */
    List<ArchivalTaskEntity> findByTriggeredByOrderBySubmittedAtDesc(String triggeredBy);
    
    /**
     * Find tasks submitted within a date range.
     *
     * @param startDate Start date for the range
     * @param endDate End date for the range
     * @return List of tasks submitted within the date range
     */
    @Query("SELECT t FROM ArchivalTaskEntity t WHERE t.submittedAt BETWEEN :startDate AND :endDate ORDER BY t.submittedAt DESC")
    List<ArchivalTaskEntity> findTasksBySubmissionDateRange(@Param("startDate") LocalDateTime startDate, 
                                                            @Param("endDate") LocalDateTime endDate);
    
    /**
     * Count tasks by status.
     *
     * @param status Status to count
     * @return Count of tasks with the specified status
     */
    long countByStatus(ArchivalStatus status);
    
    /**
     * Find the most recent task.
     *
     * @return The most recent task by submission date
     */
    @Query("SELECT t FROM ArchivalTaskEntity t ORDER BY t.submittedAt DESC")
    List<ArchivalTaskEntity> findMostRecentTasks();
    
    /**
     * Find tasks that completed successfully.
     *
     * @return List of successfully completed tasks
     */
    @Query("SELECT t FROM ArchivalTaskEntity t WHERE t.status = 'COMPLETED' AND t.completedAt IS NOT NULL ORDER BY t.completedAt DESC")
    List<ArchivalTaskEntity> findCompletedTasks();
    
    /**
     * Find tasks that failed.
     *
     * @return List of failed tasks
     */
    @Query("SELECT t FROM ArchivalTaskEntity t WHERE t.status = 'FAILED' ORDER BY t.submittedAt DESC")
    List<ArchivalTaskEntity> findFailedTasks();
} 