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

package com.dlap.discovery.job.service;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.config.JobConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
// Added import for Page and Pageable
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for managing the lifecycle and execution of DiscoveryJob
 * entities.
 */
public interface IDiscoveryJobService {
    /**
     * Creates a new DiscoveryJob entity with basic information.
     *
     * @param type      The type of the job (e.g., RESOURCE_CRAWLER).
     * @param accountId The target cloud account ID.
     * @param provider  The target cloud provider.
     * @param jobName   A descriptive name for the job.
     * @return The newly created and persisted DiscoveryJob entity.
     */
    DiscoveryJob createJob(JobType type, String accountId, CloudProvider provider, String jobName);

    /**
     * Returns a JobConfiguration builder for the given job, allowing setup of
     * execution mode and executable.
     *
     * @param job The DiscoveryJob entity to configure.
     * @return A JobConfiguration builder instance.
     */
    JobConfiguration configureJob(DiscoveryJob job);

    /**
     * Submits a configured job for execution via the IDiscoveryScheduler.
     *
     * @param job The configured DiscoveryJob entity (must have execution details
     *            set).
     * @return A Future representing the asynchronous execution.
     */
    Future<?> executeJob(DiscoveryJob job);

    /**
     * Saves changes to a DiscoveryJob entity.
     *
     * @param job The job entity to save.
     * @return The saved job entity.
     */
    DiscoveryJob saveJob(DiscoveryJob job);

    /**
     * Deletes a job by its ID.
     *
     * @param jobId The UUID of the job to delete.
     */
    void deleteJob(UUID jobId);

    /**
     * Retrieves a job by its ID.
     *
     * @param jobId The UUID of the job.
     * @return An Optional containing the job if found, otherwise empty.
     */
    Optional<DiscoveryJob> getJob(UUID jobId);

    /**
     * Retrieves all discovery jobs.
     *
     * @return A list of all DiscoveryJob entities.
     */
    List<DiscoveryJob> getAllJobs();

    /**
     * Finds jobs by their status.
     *
     * @param status The status to filter by.
     * @return A list of jobs matching the status.
     */
    List<DiscoveryJob> getJobsByStatus(JobStatus status);

    /**
     * Finds jobs by their type.
     *
     * @param jobType The type to filter by.
     * @return A list of jobs matching the type.
     */
    List<DiscoveryJob> getJobsByType(JobType jobType);

    /**
     * Finds jobs for a specific cloud account.
     *
     * @param accountId The account ID to filter by.
     * @return A list of jobs for the account.
     */
    List<DiscoveryJob> getJobsByAccount(String accountId);

    /**
     * Finds jobs for a specific cloud provider.
     *
     * @param provider The cloud provider to filter by.
     * @return A list of jobs for the provider.
     */
    List<DiscoveryJob> getJobsByProvider(CloudProvider provider);

    /**
     * Finds all jobs matching the given criteria with pagination.
     *
     * @param cloudConnectionId Optional filter by cloud connection ID (stored in parameters).
     * @param status Optional filter by job status string.
     * @param type Optional filter by job type string.
     * @param pageable Pagination information.
     * @return A page of DiscoveryJob entities.
     */
    Page<DiscoveryJob> findAll(String cloudConnectionId, String status, String type, Pageable pageable);
    // Add other query methods as needed (e.g., getPeriodicJobs,
    // getJobByLastExecutionId)
}
