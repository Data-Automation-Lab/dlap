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

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for managing DiscoveryJob entities.
 */
@Repository
public interface DiscoveryJobRepository extends JpaRepository<DiscoveryJob, UUID>, JpaSpecificationExecutor<DiscoveryJob> {
    /**
     * Finds all jobs for a specific account.
     *
     * @param accountId The account ID to filter by
     * @return List of jobs for the account
     */
    List<DiscoveryJob> findByAccountId(String accountId);

    /**
     * Finds all jobs for a specific cloud provider.
     *
     * @param provider The cloud provider to filter by
     * @return List of jobs for the provider
     */
    List<DiscoveryJob> findByCloudProvider(CloudProvider provider);

    /**
     * Finds all jobs of a specific type.
     *
     * @param jobType The job type to filter by
     * @return List of jobs of the specified type
     */
    List<DiscoveryJob> findByJobType(JobType jobType);

    /**
     * Finds all jobs with a specific status.
     *
     * @param status The job status to filter by
     * @return List of jobs with the specified status
     */
    List<DiscoveryJob> findByStatus(JobStatus status);

    /**
     * Finds all periodic jobs.
     *
     * @return List of periodic jobs
     */
    List<DiscoveryJob> findByIsPeriodicJobTrue();

    /**
     * Finds all one-time jobs.
     *
     * @return List of one-time jobs
     */
    List<DiscoveryJob> findByIsOneTimeJobTrue();

    /**
     * Finds all trigger jobs.
     *
     * @return List of trigger jobs
     */
    List<DiscoveryJob> findByIsTriggerJobTrue();

    /**
     * Finds all jobs for a specific account and provider.
     *
     * @param accountId The account ID to filter by
     * @param provider  The cloud provider to filter by
     * @return List of jobs matching both criteria
     */
    List<DiscoveryJob> findByAccountIdAndCloudProvider(String accountId, CloudProvider provider);

    /**
     * Finds all jobs for a specific account and status.
     *
     * @param accountId The account ID to filter by
     * @param status    The job status to filter by
     * @return List of jobs matching both criteria
     */
    List<DiscoveryJob> findByAccountIdAndStatus(String accountId, JobStatus status);

    /**
     * Finds a job by its last execution ID.
     *
     * @param lastExecutionId The last execution ID to search for
     * @return Optional containing the job if found
     */
    Optional<DiscoveryJob> findByLastExecutionId(String lastExecutionId);

    /**
     * Finds all jobs with schedule information.
     *
     * @return List of jobs that have schedule information
     */
    @Query("SELECT j FROM DiscoveryJob j WHERE j.scheduleInfo IS NOT NULL")
    List<DiscoveryJob> findAllScheduledJobs();

    /**
     * Finds all jobs for a specific account that were created after a given job.
     *
     * @param accountId      The account ID to filter by
     * @param referenceJobId The ID of the reference job
     * @return List of jobs matching the criteria
     */
    @Query(
        "SELECT j FROM DiscoveryJob j WHERE j.accountId = :accountId AND j.createdAt > (SELECT ref.createdAt FROM DiscoveryJob ref WHERE ref.jobId = :referenceJobId)"
    )
    List<DiscoveryJob> findJobsAfter(@Param("accountId") String accountId, @Param("referenceJobId") UUID referenceJobId);
}
