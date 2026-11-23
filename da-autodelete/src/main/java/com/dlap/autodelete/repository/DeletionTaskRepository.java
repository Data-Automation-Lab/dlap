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

package com.dlap.autodelete.repository;

import com.dlap.autodelete.model.DeletionTaskEntity;
import com.dlap.autodelete.model.DeletionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DeletionTaskRepository extends JpaRepository<DeletionTaskEntity, String> {
    
    // Find tasks by status
    List<DeletionTaskEntity> findByStatus(DeletionStatus status);
    
    // Find tasks by triggered by user
    List<DeletionTaskEntity> findByTriggeredBy(String triggeredBy);
    
    // Find tasks created in the last 24 hours
    List<DeletionTaskEntity> findBySubmittedAtAfter(LocalDateTime after);
    
    // Find tasks completed in the last 24 hours
    List<DeletionTaskEntity> findByCompletedAtAfter(LocalDateTime after);
    
    // Find tasks by status and submitted date range
    List<DeletionTaskEntity> findByStatusAndSubmittedAtBetween(
        DeletionStatus status, LocalDateTime start, LocalDateTime end);
    
    // Count tasks by status
    long countByStatus(DeletionStatus status);
    
    // Count tasks submitted in the last 24 hours
    long countBySubmittedAtAfter(LocalDateTime after);
    
    // Count tasks completed in the last 24 hours
    long countByCompletedAtAfter(LocalDateTime after);
    
    // Find recent tasks ordered by submitted date (top 10)
    List<DeletionTaskEntity> findTop10ByOrderBySubmittedAtDesc();
    
    // Find tasks with pagination ordered by submitted date
    List<DeletionTaskEntity> findAllByOrderBySubmittedAtDesc(Pageable pageable);
    
    // Custom query to get tasks for recent activities
    @Query("SELECT t FROM DeletionTaskEntity t WHERE t.submittedAt >= :since ORDER BY t.submittedAt DESC")
    List<DeletionTaskEntity> findRecentTasks(@Param("since") LocalDateTime since);
    
    // Custom query to get tasks for risk assets (pending approval or queued)
    @Query("SELECT t FROM DeletionTaskEntity t WHERE t.status IN ('PENDING_APPROVAL', 'QUEUED', 'SUBMITTED') ORDER BY t.submittedAt ASC")
    List<DeletionTaskEntity> findPendingTasks();
    
    // Custom query to get deletion statistics by policy type (based on task name patterns)
    @Query("SELECT t.taskName, COUNT(t) FROM DeletionTaskEntity t WHERE t.status = 'COMPLETED' GROUP BY t.taskName")
    List<Object[]> getDeletionStatsByTaskName();
    
    // Custom query to get storage reclaimed trends (based on completed tasks)
    @Query("SELECT DATE(t.completedAt), SUM(t.totalAssetsInScope) FROM DeletionTaskEntity t " +
           "WHERE t.status = 'COMPLETED' AND t.completedAt >= :since " +
           "GROUP BY DATE(t.completedAt) ORDER BY DATE(t.completedAt)")
    List<Object[]> getStorageReclaimedTrends(@Param("since") LocalDateTime since);

    /**
     * RET-02: claims the next task whose approvals the sweeper has to read, and locks it until the
     * transaction ends; rows another transaction holds are skipped ({@code SKIP LOCKED}, Q-548).
     *
     * @param now the sweep time
     * @return the claimed task, or empty
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM dlap_autodelete.dlap_deletion_tasks"
            + " WHERE status = 'PENDING_APPROVAL' AND (next_check_at IS NULL OR next_check_at <= :now)"
            + " ORDER BY submitted_at, task_id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    java.util.Optional<DeletionTaskEntity> claimNextPendingApproval(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
