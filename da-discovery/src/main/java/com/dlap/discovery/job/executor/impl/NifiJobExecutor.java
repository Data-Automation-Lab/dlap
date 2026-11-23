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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.executable.NiFiExecutable;
import com.dlap.discovery.job.executable.NiFiTaskConfiguration;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.integration.nifi.NiFiClient;

/**
 * Implementation of IJobExecutor that handles NiFi-based job execution.
 * Submits jobs to an Apache NiFi workflow for processing.
 */
@Service
@ConditionalOnProperty(value = "discovery.job.executor.nifi.enabled", havingValue = "true", matchIfMissing = false)
public class NifiJobExecutor implements IJobExecutor {
	private static final Logger log = LoggerFactory.getLogger(NifiJobExecutor.class);

	private final NiFiClient nifiClient;

	@Autowired
	public NifiJobExecutor(NiFiClient nifiClient) {
		this.nifiClient = nifiClient;
	}

	@Override
	public Future<?> execute(DiscoveryJob job) {
		if (job == null) {
			throw new IllegalArgumentException("Job must not be null");
		}

		if (!supportsExecutionMode(job.getExecutionMode())) {
			throw new IllegalStateException(
					"This executor does not support execution mode: " + job.getExecutionMode());
		}

		if (!(job.getExecutable() instanceof NiFiExecutable)) {
			throw new IllegalStateException(
					"Job executable must be NiFiExecutable for NIFI mode");
		}

		NiFiExecutable nifiExecutable = (NiFiExecutable) job.getExecutable();
		NiFiTaskConfiguration config = nifiExecutable.getTaskConfiguration();

		log.info("Submitting job {} to NiFi cluster", job.getJobId());

		CompletableFuture<String> future = new CompletableFuture<>();
		try {
			String processorId = nifiClient.submitTask(config);
			future.complete(processorId);
			log.info("Successfully submitted job {} to NiFi processor {}", job.getJobId(), processorId);
		} catch (Exception e) {
			log.error("Failed to submit job {} to NiFi cluster", job.getJobId(), e);
			future.completeExceptionally(e);
		}

		return future;
	}

	@Override
	public boolean supportsExecutionMode(ExecutionMode mode) {
		return mode == ExecutionMode.NIFI;
	}

	@Override
	public ExecutionMode getExecutionMode() {
		return ExecutionMode.NIFI;
	}

	@Override
	public boolean cancelJob(UUID jobId) {
		try {
			return nifiClient.stopProcessor(jobId.toString());
		} catch (Exception e) {
			log.error("Failed to cancel NiFi job {}", jobId, e);
			return false;
		}
	}

	@Override
	public boolean pauseJob(UUID jobId) {
		try {
			return nifiClient.pauseProcessor(jobId.toString());
		} catch (Exception e) {
			log.error("Failed to pause NiFi job {}", jobId, e);
			return false;
		}
	}

	@Override
	public boolean resumeJob(UUID jobId) {
		try {
			return nifiClient.startProcessor(jobId.toString());
		} catch (Exception e) {
			log.error("Failed to resume NiFi job {}", jobId, e);
			return false;
		}
	}

	@Override
	public JobStatus getJobStatus(UUID jobId) {
		try {
			String status = nifiClient.getProcessorStatus(jobId.toString());
			return mapNiFiStatusToJobStatus(status);
		} catch (Exception e) {
			log.error("Failed to get status for NiFi job {}", jobId, e);
			return JobStatus.UNKNOWN;
		}
	}

	private JobStatus mapNiFiStatusToJobStatus(String nifiStatus) {
		if (nifiStatus == null) {
			return JobStatus.UNKNOWN;
		}

		return switch (nifiStatus.toUpperCase()) {
			case "RUNNING" -> JobStatus.RUNNING;
			case "STOPPED" -> JobStatus.COMPLETED;
			case "DISABLED" -> JobStatus.PAUSED;
			case "INVALID" -> JobStatus.FAILED;
			default -> JobStatus.UNKNOWN;
		};
	}
}