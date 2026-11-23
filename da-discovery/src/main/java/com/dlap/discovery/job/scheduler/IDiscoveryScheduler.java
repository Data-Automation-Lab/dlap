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

package com.dlap.discovery.job.scheduler;

import java.util.List;
import java.util.concurrent.Future;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.executor.IJobExecutor;

/**
 * Interface for scheduling and executing discovery jobs.
 * Handles both immediate and scheduled job execution, as well as executor
 * management.
 */
public interface IDiscoveryScheduler {
    /**
     * Schedules a job for periodic execution.
     * 
     * @param job The job to schedule
     * @return true if scheduling was successful, false otherwise
     */
    boolean scheduleJob(DiscoveryJob job);

    /**
     * Executes a job immediately using the appropriate executor.
     * 
     * @param job The job to execute
     * @return A Future representing the job execution result
     * @throws com.dlap.discovery.sd.exception.ExecutorNotFoundException if no
     *                                                                    suitable
     *                                                                    executor
     *                                                                    is found
     * @throws com.dlap.discovery.sd.exception.JobExecutionException     if
     *                                                                    execution
     *                                                                    fails
     */
    Future<?> executeJob(DiscoveryJob job);

    /**
     * Unschedules a job.
     * 
     * @param jobId The ID of the job to unschedule
     * @return true if unscheduling was successful, false otherwise
     */
    boolean unscheduleJob(String jobId);

    /**
     * Gets all scheduled jobs.
     * 
     * @return List of scheduled jobs
     */
    List<DiscoveryJob> getScheduledJobs();

    /**
     * Gets a specific scheduled job.
     * 
     * @param jobId The ID of the job to get
     * @return The scheduled job, or null if not found
     */
    DiscoveryJob getScheduledJob(String jobId);

    /**
     * Updates the schedule for a job.
     * 
     * @param jobId        The ID of the job to update
     * @param scheduleInfo The new schedule information
     * @return true if update was successful, false otherwise
     */
    boolean updateSchedule(String jobId, String scheduleInfo);

    /**
     * Registers a job executor for a specific execution mode.
     * 
     * @param executionMode The execution mode the executor handles
     * @param executor      The executor implementation
     */
    void registerExecutor(ExecutionMode executionMode, IJobExecutor executor);

    /**
     * Unregisters a job executor for a specific execution mode.
     * 
     * @param executionMode The execution mode to unregister the executor for
     * @return true if unregistration was successful, false otherwise
     */
    boolean unregisterExecutor(ExecutionMode executionMode);

    /**
     * Gets the executor for a specific execution mode.
     * 
     * @param executionMode The execution mode to get the executor for
     * @return The registered executor, or null if none is registered
     */
    IJobExecutor getExecutor(ExecutionMode executionMode);
}