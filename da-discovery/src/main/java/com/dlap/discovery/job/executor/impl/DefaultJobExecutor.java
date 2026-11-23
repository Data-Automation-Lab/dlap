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

package com.dlap.discovery.job.executor.impl;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.context.ApplicationContext; // Needed to get beans/factories
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.model.DiscoveryJob;
// import com.dlap.discovery.job.repository.IDiscoveryJobRepository; // Needed for persistence
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.executable.CrawlerJavaExecutable;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.service.IDiscoveryJobService;

/**
 * Default implementation of IJobExecutor that handles DEFAULT execution mode.
 * Uses a Java ExecutorService to run jobs locally.
 * This executor is responsible for triggering the job by submitting the
 * pre-configured Callable found in the DiscoveryJob's Executable.
 * It does NOT manage job status updates; that responsibility lies elsewhere
 * (e.g., the Callable itself, event listeners, or the calling service).
 */
@Service
public class DefaultJobExecutor implements IJobExecutor {
	private static final Logger log = LoggerFactory.getLogger(DefaultJobExecutor.class);
	// Removed TRIGGER_TIMEOUT_SECONDS as handleTriggerResult is removed

	private final ExecutorService executorService;
	private final IDiscoveryJobService jobService; // Add job service for status updates
	// ApplicationContext and Repository might be needed by Callables,
	// but not directly by this simplified executor.

	@Autowired
	public DefaultJobExecutor(ExecutorService executorService, IDiscoveryJobService jobService) {
		this.executorService = executorService;
		this.jobService = jobService;
	}

	@Override
	public Future<?> execute(DiscoveryJob job) {
		if (job == null) {
			log.error("Attempted to execute a null job.");
			throw new IllegalStateException("Job must not be null");
		}

		if (!supportsExecutionMode(job.getExecutionMode())) {
			log.error("Executor {} cannot handle execution mode {} for job {}",
					this.getClass().getSimpleName(), job.getExecutionMode(), job.getJobId());
			throw new IllegalStateException(
					"This executor only supports execution mode: " + getExecutionMode());
		}

		// Ensure the executable is the correct type for this executor
		if (!(job.getExecutable() instanceof CrawlerJavaExecutable<?>)) {
			log.error("Incorrect executable type for job {} in DEFAULT mode. Expected CrawlerJavaExecutable, found {}.",
					job.getJobId(), (job.getExecutable() != null ? job.getExecutable().getClass().getName() : "null"));
			throw new IllegalStateException("Job executable must be CrawlerJavaExecutable for DEFAULT mode. Found: "
					+ (job.getExecutable() != null ? job.getExecutable().getClass().getName() : "null"));
		}

		@SuppressWarnings("unchecked") // Type checked above
		CrawlerJavaExecutable<Object> javaExecutable = (CrawlerJavaExecutable<Object>) job.getExecutable();
		Callable<?> callable = javaExecutable.getCallable(); // Use wildcard

		if (callable == null) {
			log.error("Callable inside CrawlerJavaExecutable is null for job: {}", job.getJobId());
			throw new IllegalStateException(
					"Callable inside CrawlerJavaExecutable cannot be null for job: " + job.getJobId());
		}

		log.info("Submitting job {} for DEFAULT execution.", job.getJobId());

		// Update job status to PENDING before execution
		job.setStatus(JobStatus.PENDING);
		job.setProgressPercentage(0);
		job.setProgressMessage("Job queued for execution");
		jobService.saveJob(job);

		// Wrap the callable to handle status updates
		Callable<?> wrappedCallable = () -> {
			try {
				// Update status to RUNNING
				job.setStatus(JobStatus.RUNNING);
				job.setProgressPercentage(10);
				job.setProgressMessage("Job execution started");
				jobService.saveJob(job);

				// Execute the original callable
				Object result = callable.call();

				// Update status to COMPLETED
				job.setStatus(JobStatus.COMPLETED);
				job.setProgressPercentage(100);
				job.setProgressMessage("Job execution completed successfully");
				jobService.saveJob(job);

				return result;
			} catch (Exception e) {
				// Update status to FAILED
				job.setStatus(JobStatus.FAILED);
				job.setErrorMessage(e.getMessage());
				job.setProgressMessage("Job execution failed: " + e.getMessage());
				jobService.saveJob(job);
				throw e;
			}
		};

		// Execute the wrapped callable asynchronously
		return executorService.submit(wrappedCallable);
	}

	@Override
	public boolean supportsExecutionMode(ExecutionMode mode) {
		return mode == ExecutionMode.DEFAULT;
	}

	@Override
	public ExecutionMode getExecutionMode() {
		return ExecutionMode.DEFAULT;
	}

	@Override
	public boolean cancelJob(UUID jobId) {
		// Default executor submits Callables. Cancellation depends on the Callable
		// implementation and the ExecutorService's handling of Future.cancel().
		// This executor doesn't maintain a map of Futures to Job IDs, so direct
		// cancellation isn't feasible here. Cancellation logic should be managed
		// where the Future is stored (e.g., the scheduler or service layer).
		log.warn(
				"cancelJob called on DefaultJobExecutor for job {}. Cancellation must be managed externally via the returned Future.",
				jobId);
		return false; // Indicate cancellation isn't directly supported here
	}

	@Override
	public boolean pauseJob(UUID jobId) {
		log.warn("pauseJob not supported in DEFAULT execution mode for job {}", jobId);
		return false;
	}

	@Override
	public boolean resumeJob(UUID jobId) {
		log.warn("resumeJob not supported in DEFAULT execution mode for job {}", jobId);
		return false;
	}

	@Override
	public JobStatus getJobStatus(UUID jobId) {
		// As stated before, this executor does not track status.
		log.warn("getJobStatus called on DefaultJobExecutor for job {}. Status must be retrieved from the repository.",
				jobId);
		return JobStatus.UNKNOWN;
	}

	// Removed createCallableForJob method - responsibility moved

	// Removed handleTriggerResult method - responsibility moved
}