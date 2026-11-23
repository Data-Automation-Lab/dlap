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

package com.dlap.autolabel.repository;

import com.dlap.autolabel.entity.LabelingJobEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for the {@link LabelingJobEntity} entity.
 */
@Repository
public interface LabelingJobRepository extends JpaRepository<LabelingJobEntity, String> {
    
    /**
     * Find all jobs with a specific status.
     *
     * @param status the job status to filter by
     * @return list of jobs with the specified status
     */
    List<LabelingJobEntity> findByStatus(LabelingJobEntity.JobStatus status);
    
    /**
     * Find all completed jobs.
     *
     * @return list of completed jobs
     */
    @Query("SELECT j FROM LabelingJobEntity j WHERE j.status = 'COMPLETED'")
    List<LabelingJobEntity> findCompletedJobs();
    
    /**
     * Find all jobs completed after a specific date.
     *
     * @param date the date to filter from
     * @return list of jobs completed after the specified date
     */
    @Query("SELECT j FROM LabelingJobEntity j WHERE j.completedAt >= :date AND j.status = 'COMPLETED'")
    List<LabelingJobEntity> findJobsCompletedAfter(@Param("date") LocalDateTime date);
    
    /**
     * Find all jobs completed in the last 24 hours.
     *
     * @return list of jobs completed in the last 24 hours
     */
    @Query("SELECT j FROM LabelingJobEntity j WHERE j.completedAt >= :last24h AND j.status = 'COMPLETED'")
    List<LabelingJobEntity> findJobsCompletedInLast24Hours(@Param("last24h") LocalDateTime last24h);
    
    /**
     * Count total assets processed across all completed jobs.
     *
     * @return total number of assets processed
     */
    @Query("SELECT COALESCE(SUM(j.assetsProcessed), 0) FROM LabelingJobEntity j WHERE j.status = 'COMPLETED'")
    Integer countTotalAssetsProcessed();
    
    /**
     * Count total assets successfully labeled across all completed jobs.
     *
     * @return total number of assets successfully labeled
     */
    @Query("SELECT COALESCE(SUM(j.assetsSuccessfullyLabeled), 0) FROM LabelingJobEntity j WHERE j.status = 'COMPLETED'")
    Integer countTotalAssetsSuccessfullyLabeled();
    
    /**
     * Count total assets that failed labeling across all completed jobs.
     *
     * @return total number of assets that failed labeling
     */
    @Query("SELECT COALESCE(SUM(j.assetsFailed), 0) FROM LabelingJobEntity j WHERE j.status = 'COMPLETED'")
    Integer countTotalAssetsFailed();
    
    /**
     * Find jobs by the user who triggered them.
     *
     * @param userId the user ID who triggered the jobs
     * @return list of jobs triggered by the specified user
     */
    List<LabelingJobEntity> findByTriggeredByUserId(String userId);

    /**
     * Find the job a policy fire already created.
     *
     * @param fireId the fire id of the policy action
     * @return the job, or empty when the fire was not applied before
     */
    java.util.Optional<LabelingJobEntity> findByFireId(String fireId);
    
    /**
     * Find the most recent completed jobs (limit via {@link Pageable#getPageSize()}).
     */
    @Query("SELECT j FROM LabelingJobEntity j WHERE j.status = 'COMPLETED' ORDER BY j.completedAt DESC")
    List<LabelingJobEntity> findMostRecentCompletedJobs(Pageable pageable);
} 