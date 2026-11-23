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

package com.dlap.discovery.job.executor;

import java.util.UUID;
import java.util.concurrent.Future;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;

/**
 * Interface for executing discovery jobs in different modes (DEFAULT, NIFI,
 * SPARK).
 * Implementations handle the specific execution strategy for each mode.
 */
public interface IJobExecutor {
    /**
     * Executes a discovery job according to its configuration.
     *
     * @param job The job to execute
     * @return A Future representing the pending result of the job execution
     * @throws IllegalArgumentException if job is null or has invalid configuration
     * @throws IllegalStateException    if the executor cannot handle the job's
     *                                  execution mode
     */
    Future<?> execute(DiscoveryJob job);

    /**
     * Checks if this executor can handle the given execution mode.
     *
     * @param mode The execution mode to check
     * @return true if this executor can handle the mode, false otherwise
     */
    boolean supportsExecutionMode(ExecutionMode mode);

    /**
     * Gets the execution mode this executor handles.
     *
     * @return The execution mode
     */
    ExecutionMode getExecutionMode();

    /**
     * Cancels a running job with the specified ID.
     * 
     * @param jobId The ID of the job to cancel
     * @return true if cancellation was successful, false otherwise
     */
    boolean cancelJob(UUID jobId);

    /**
     * Pauses a running job with the specified ID.
     * Not all executor implementations will support pausing.
     * 
     * @param jobId The ID of the job to pause
     * @return true if pausing was successful, false otherwise
     */
    boolean pauseJob(UUID jobId);

    /**
     * Resumes a paused job with the specified ID.
     * Not all executor implementations will support resuming.
     * 
     * @param jobId The ID of the job to resume
     * @return true if resuming was successful, false otherwise
     */
    boolean resumeJob(UUID jobId);

    /**
     * Gets the status of a job with the specified ID.
     * 
     * @param jobId The ID of the job to check
     * @return The current job status
     */
    JobStatus getJobStatus(UUID jobId);
}