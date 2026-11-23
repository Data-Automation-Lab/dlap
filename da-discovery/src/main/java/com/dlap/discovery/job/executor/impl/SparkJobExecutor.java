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
import com.dlap.discovery.job.executable.SparkExecutable;
import com.dlap.discovery.job.executable.SparkJobConfiguration;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.integration.spark.SparkClient;

/**
 * Implementation of IJobExecutor that handles Spark-based job execution.
 * Submits jobs to an Apache Spark cluster for processing.
 */
@Service
@ConditionalOnProperty(value = "discovery.job.executor.spark.enabled", havingValue = "true", matchIfMissing = false)
public class SparkJobExecutor implements IJobExecutor {
	private static final Logger log = LoggerFactory.getLogger(SparkJobExecutor.class);

	private final SparkClient sparkClient;

	@Autowired
	public SparkJobExecutor(SparkClient sparkClient) {
		this.sparkClient = sparkClient;
	}

	@Override
	public Future<?> execute(DiscoveryJob job) {
		if (job == null) {
			throw new IllegalStateException("Job must not be null");
		}

		if (!supportsExecutionMode(job.getExecutionMode())) {
			throw new IllegalStateException(
					"This executor does not support execution mode: " + job.getExecutionMode());
		}

		if (!(job.getExecutable() instanceof SparkExecutable)) {
			throw new IllegalStateException(
					"Job executable must be SparkExecutable for SPARK mode");
		}

		SparkExecutable sparkExecutable = (SparkExecutable) job.getExecutable();
		SparkJobConfiguration config = sparkExecutable.getJobConfiguration();

		log.info("Submitting job {} to Spark cluster", job.getJobId());

		CompletableFuture<String> future = new CompletableFuture<>();
		try {
			String applicationId = sparkClient.submitJob(config);
			future.complete(applicationId);
			log.info("Successfully submitted job {} to Spark application {}", job.getJobId(), applicationId);
		} catch (Exception e) {
			log.error("Failed to submit job {} to Spark cluster", job.getJobId(), e);
			future.completeExceptionally(e);
		}

		return future;
	}

	@Override
	public boolean supportsExecutionMode(ExecutionMode mode) {
		return mode == ExecutionMode.SPARK;
	}

	@Override
	public ExecutionMode getExecutionMode() {
		return ExecutionMode.SPARK;
	}

	@Override
	public boolean cancelJob(UUID jobId) {
		try {
			return sparkClient.killApplication(jobId.toString());
		} catch (Exception e) {
			log.error("Failed to cancel Spark job {}", jobId, e);
			return false;
		}
	}

	@Override
	public boolean pauseJob(UUID jobId) {
		log.warn("Job pausing not supported in SPARK mode for job {}", jobId);
		return false;
	}

	@Override
	public boolean resumeJob(UUID jobId) {
		log.warn("Job resuming not supported in SPARK mode for job {}", jobId);
		return false;
	}

	@Override
	public JobStatus getJobStatus(UUID jobId) {
		try {
			String sparkStatus = sparkClient.getApplicationStatus(jobId.toString());
			return mapSparkStatusToJobStatus(sparkStatus);
		} catch (Exception e) {
			log.error("Failed to get status for Spark job {}", jobId, e);
			return JobStatus.UNKNOWN;
		}
	}

	private JobStatus mapSparkStatusToJobStatus(String sparkStatus) {
		if (sparkStatus == null) {
			return JobStatus.UNKNOWN;
		}

		return switch (sparkStatus.toUpperCase()) {
			case "SUBMITTED", "ACCEPTED" -> JobStatus.PENDING;
			case "RUNNING" -> JobStatus.RUNNING;
			case "FINISHED", "SUCCEEDED" -> JobStatus.COMPLETED;
			case "FAILED", "KILLED" -> JobStatus.FAILED;
			default -> JobStatus.UNKNOWN;
		};
	}
}