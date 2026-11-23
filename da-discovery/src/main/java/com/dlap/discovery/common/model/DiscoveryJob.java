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

package com.dlap.discovery.common.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.executable.Executable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * JPA Entity representing a discovery job.
 * This class holds the state and configuration of a job.
 */
@Entity
@Table(name = "discovery_job")
public class DiscoveryJob {

	@Id
	private UUID jobId;

	@Column(nullable = false)
	private String jobName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JobType jobType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CloudProvider cloudProvider;

	@Column(nullable = false)
	private String accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ExecutionMode executionMode = ExecutionMode.DEFAULT;

	@Column
	private String scheduleInfo;

	@Column(columnDefinition = "jsonb")
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> parameters = new HashMap<>();

	@Column(columnDefinition = "jsonb")
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, String> context = new HashMap<>();

	@Column
	private String executorId;

	@Column
	private String lastExecutionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JobStatus status = JobStatus.PENDING;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Column
	private Instant startedAt;

	@Column
	private Instant completedAt;

	@Column
	private String errorMessage;

	@Column
	private int progressPercentage;

	@Column
	private String progressMessage;

	@Column(nullable = false)
	private boolean isPeriodicJob;

	@Column(nullable = false)
	private boolean isOneTimeJob = true;

	@Column(nullable = false)
	private boolean isTriggerJob;

	@Transient
	private DiscoveryJob.JobStatistics statistics = new DiscoveryJob.JobStatistics.Builder().build();

	// This field should not be persisted
	@Transient
	private Executable executable;

	public DiscoveryJob() {
		this.jobId = UUID.randomUUID();
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public DiscoveryJob(UUID jobId) {
		this.jobId = jobId;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public UUID getJobId() {
		return jobId;
	}

	public UUID getId() {
		return jobId;
	}

	public String getJobName() {
		return jobName;
	}

	public JobType getJobType() {
		return jobType;
	}

	public CloudProvider getCloudProvider() {
		return cloudProvider;
	}

	public String getAccountId() {
		return accountId;
	}

	public ExecutionMode getExecutionMode() {
		return executionMode;
	}

	public Optional<String> getScheduleInfo() {
		return Optional.ofNullable(scheduleInfo);
	}

	public Map<String, Object> getParameters() {
		return parameters;
	}

	public Map<String, String> getContext() {
		return context;
	}

	public Map<String, String> getExecutionConfig() {
		return new HashMap<>();
	}

	public Optional<String> getExecutorId() {
		return Optional.ofNullable(executorId);
	}

	public JobStatus getStatus() {
		return status;
	}

	public Executable getExecutable() {
		return executable;
	}

	public Optional<String> getLastExecutionId() {
		return Optional.ofNullable(lastExecutionId);
	}

	public Optional<Instant> getStartedAt() {
		return Optional.ofNullable(startedAt);
	}

	public Optional<Instant> getCompletedAt() {
		return Optional.ofNullable(completedAt);
	}

	public Optional<String> getErrorMessage() {
		return Optional.ofNullable(errorMessage);
	}

	public int getProgressPercentage() {
		return progressPercentage;
	}

	public String getProgressMessage() {
		return progressMessage;
	}

	public boolean isPeriodicJob() {
		return isPeriodicJob;
	}

	public boolean isOneTimeJob() {
		return isOneTimeJob;
	}

	public boolean isTriggerJob() {
		return isTriggerJob;
	}

	public DiscoveryJob.JobStatistics getStatistics() {
		return statistics;
	}

	public void setJobId(UUID jobId) {
		this.jobId = jobId;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
		this.updatedAt = Instant.now();
	}

	public void setJobType(JobType jobType) {
		this.jobType = jobType;
		this.updatedAt = Instant.now();
	}

	public void setCloudProvider(CloudProvider provider) {
		this.cloudProvider = provider;
		this.updatedAt = Instant.now();
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
		this.updatedAt = Instant.now();
	}

	public void setExecutionMode(ExecutionMode mode) {
		this.executionMode = mode;
		this.updatedAt = Instant.now();
	}

	public void setScheduleInfo(String scheduleInfo) {
		// Clear schedule info if it's null or empty
		if (scheduleInfo == null || scheduleInfo.trim().isEmpty()) {
			this.scheduleInfo = null;
			this.isPeriodicJob = false;
			this.isOneTimeJob = true;
		} else {
			this.scheduleInfo = scheduleInfo;
			this.isPeriodicJob = true;
			this.isOneTimeJob = false;
		}

		// Reset trigger flag when schedule changes
		this.isTriggerJob = false;
		this.updatedAt = Instant.now();
	}

	public void setParameters(Map<String, Object> parameters) {
		this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
		this.updatedAt = Instant.now();
	}

	public void setContext(Map<String, String> context) {
		this.context = context != null ? new HashMap<>(context) : new HashMap<>();
		this.updatedAt = Instant.now();
	}

	public void setExecutorId(String executorId) {
		this.executorId = executorId;
		this.updatedAt = Instant.now();
	}

	public void setStatus(JobStatus status) {
		// Validate status transition
		if (this.status == JobStatus.COMPLETED || this.status == JobStatus.FAILED || this.status == JobStatus.CANCELLED) {
			throw new IllegalStateException("Cannot change status of a completed/failed/cancelled job");
		}

		this.status = status;
		this.updatedAt = Instant.now();

		// Update timing fields based on status
		switch (status) {
			case RUNNING:
				if (this.startedAt == null) {
					this.startedAt = Instant.now();
				}
				break;
			case COMPLETED:
			case FAILED:
			case CANCELLED:
				if (this.startedAt == null) {
					this.startedAt = Instant.now().minusMillis(1);
				}
				if (this.completedAt == null) {
					this.completedAt = Instant.now();
				}
				break;
			case PENDING:
				// Reset progress when entering pending state
				this.progressPercentage = 0;
				this.progressMessage = "Job queued for execution";
				break;
			default:
				break;
		}
	}

	public void setExecutable(Executable executable) {
		if (executable != null && executable.getMode() != this.executionMode) {
			throw new IllegalArgumentException(
					"Executable mode " + executable.getMode() + " does not match job execution mode "
							+ this.executionMode);
		}
		this.executable = executable;
		this.updatedAt = Instant.now();
	}

	public void setLastExecutionId(String lastExecutionId) {
		this.lastExecutionId = lastExecutionId;
		this.updatedAt = Instant.now();
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
		this.updatedAt = Instant.now();
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
		this.updatedAt = Instant.now();
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		this.updatedAt = Instant.now();
	}

	public void setProgressPercentage(int progressPercentage) {
		this.progressPercentage = Math.max(0, Math.min(100, progressPercentage));
		this.updatedAt = Instant.now();
		
		// Update progress message based on percentage
		if (this.status == JobStatus.RUNNING) {
			if (progressPercentage == 0) {
				this.progressMessage = "Starting job execution";
			} else if (progressPercentage == 100) {
				this.progressMessage = "Job execution completed";
			} else {
				this.progressMessage = "Job execution in progress: " + progressPercentage + "%";
			}
		}
	}

	public void setProgressMessage(String progressMessage) {
		this.progressMessage = progressMessage;
		this.updatedAt = Instant.now();
	}

	public void setPeriodicJob(boolean isPeriodicJob) {
		this.isPeriodicJob = isPeriodicJob;
	}

	public void setOneTimeJob(boolean isOneTimeJob) {
		this.isOneTimeJob = isOneTimeJob;
	}

	public void setTriggerJob(boolean isTriggerJob) {
		this.isTriggerJob = isTriggerJob;
	}

	public void setStatistics(DiscoveryJob.JobStatistics statistics) {
		this.statistics = statistics;
		this.updatedAt = Instant.now();
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/**
	 * Updates the updatedAt timestamp before any update operation.
	 */
	@PreUpdate
	public void preUpdate() {
		this.updatedAt = Instant.now();
	}

	/**
	 * Statistics collected during job execution.
	 */
	public static final class JobStatistics {
		private final int resourcesDiscovered;
		private final int resourcesAdded;
		private final int resourcesSkipped;
		private final int resourcesUpdated;
		private final int resourcesFailed;
		private final List<?> resources; // Consider making this List<CloudResource> if possible

		private JobStatistics(Builder builder) {
			this.resourcesDiscovered = builder.resourcesDiscovered;
			this.resourcesAdded = builder.resourcesAdded;
			this.resourcesSkipped = builder.resourcesSkipped;
			this.resourcesUpdated = builder.resourcesUpdated;
			this.resourcesFailed = builder.resourcesFailed;
			// Ensure defensive copy if resources list is mutable
			this.resources = builder.resources != null ? List.copyOf(builder.resources) : List.of();
		}

		// Getters for statistics fields
		public int getResourcesDiscovered() {
			return resourcesDiscovered;
		}

		public int getResourcesAdded() {
			return resourcesAdded;
		}

		public int getResourcesSkipped() {
			return resourcesSkipped;
		}

		public int getResourcesUpdated() {
			return resourcesUpdated;
		}

		public int getResourcesFailed() {
			return resourcesFailed;
		}

		public List<?> getResources() {
			return resources;
		} // Returns unmodifiable list

		// Builder pattern for creating JobStatistics
		public static class Builder {
			private int resourcesDiscovered;
			private int resourcesAdded;
			private int resourcesSkipped;
			private int resourcesUpdated;
			private int resourcesFailed;
			private List<?> resources;

			public Builder resourcesDiscovered(int count) {
				this.resourcesDiscovered = count;
				return this;
			}

			public Builder resourcesAdded(int count) {
				this.resourcesAdded = count;
				return this;
			}

			public Builder resourcesSkipped(int count) {
				this.resourcesSkipped = count;
				return this;
			}

			public Builder resourcesUpdated(int count) {
				this.resourcesUpdated = count;
				return this;
			}

			public Builder resourcesFailed(int count) {
				this.resourcesFailed = count;
				return this;
			}

			public Builder resources(List<?> resources) {
				this.resources = resources;
				return this;
			}

			public JobStatistics build() {
				return new JobStatistics(this);
			}
		}
	}
}