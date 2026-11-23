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

package com.dlap.discovery.client.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.service.IDiscoveryJobService;

/**
 * Provides command-line interface commands for interacting with the Discovery
 * Service.
 */
@Component
public class DiscoveryCommands {

	private final IResourceCrawlerRegistry crawlerRegistry;
	private final IDiscoveryJobService jobService;

	@Autowired
	public DiscoveryCommands(IResourceCrawlerRegistry crawlerRegistry, IDiscoveryJobService jobService) {
		super();
		this.crawlerRegistry = crawlerRegistry;
		this.jobService = jobService;
	}

	/**
	 * Lists all registered resource crawlers.
	 * 
	 * @return A string listing the names of registered crawlers.
	 */
	@Command(name = "list-crawlers", description = "Lists all registered resource crawlers.")
	public String listCrawlers() {
		List<IResourceCrawler<?>> crawlers = crawlerRegistry.getAllCrawlers();
		if (crawlers == null || crawlers.isEmpty()) {
			return "No resource crawlers are currently registered.";
		}
		String crawlerNames = crawlers.stream()
				.map(crawler -> crawler.getClass().getSimpleName())
				.collect(Collectors.joining("\n - "));
		return "Registered Resource Crawlers:\n - " + crawlerNames;
	}

	/**
	 * Creates a new discovery job definition.
	 *
	 * @param provider    Cloud provider (e.g., gcp, aws, azure, oracle).
	 * @param accountId   Account ID for the provider.
	 * @param jobName     Optional name for the job.
	 * @param resourceIds Optional comma-separated list of resource type IDs to
	 *                    crawl.
	 * @return Confirmation message or error.
	 */
	@Command(name = "create-job", description = "Creates a new resource discovery job.")
	public String createJob(
			@Option(longName = "provider", shortName = 'p', description = "Cloud provider (gcp, aws, azure, oracle)", required = true) String provider,
			@Option(longName = "account", shortName = 'a', description = "Cloud account ID", required = true) String accountId,
			@Option(longName = "name", shortName = 'n', description = "Optional name for the job", defaultValue = "") String jobName,
			@Option(longName = "resources", shortName = 'r', description = "Comma-separated resource type IDs to crawl", defaultValue = "") String resourceIds) {
		CloudProvider cloudProvider;
		try {
			cloudProvider = CloudProvider.valueOf(provider.toUpperCase());
		} catch (IllegalArgumentException e) {
			return "Error: Invalid cloud provider specified: " + provider;
		}

		try {
			DiscoveryJob newJob = jobService.createJob(JobType.RESOURCE_CRAWLER,
					accountId,
					cloudProvider,
					jobName);

			if (resourceIds != null && !resourceIds.isBlank()) {
				List<String> resourceTypeList = Arrays.asList(resourceIds.split("\\s*,\\s*"));
				newJob.getParameters().put("resourceTypesToCrawl", resourceTypeList);
			}

			DiscoveryJob savedJob = jobService.saveJob(newJob);
			return "Successfully created job with ID: " + savedJob.getJobId();
		} catch (Exception e) {
			return "Error creating job: " + e.getMessage();
		}
	}

	/**
	 * Executes a configured discovery job.
	 *
	 * @param jobId The UUID of the job to execute.
	 * @return Confirmation message or error.
	 */
	@Command(name = "exec-job", description = "Executes a discovery job by its ID.")
	public String executeJob(
			@Option(longName = "id", shortName = 'i', description = "UUID of the job to execute", required = true) String jobId) {
		UUID jobUuid;
		try {
			jobUuid = UUID.fromString(jobId);
		} catch (IllegalArgumentException e) {
			return "Error: Invalid Job ID format. Please provide a valid UUID.";
		}

		try {
			DiscoveryJob job = jobService.getJob(jobUuid)
					.orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

			jobService.executeJob(job);
			return "Job " + jobId + " execution requested successfully.";
		} catch (Exception e) {
			return "Error executing job " + jobId + ": " + e.getMessage();
		}
	}

	/**
	 * Gets the details of a specific discovery job.
	 *
	 * @param jobId The UUID of the job to retrieve.
	 * @return Job details string or error message.
	 */
	@Command(name = "get-job", description = "Gets details of a discovery job by its ID.")
	public String getJob(
			@Option(longName = "id", shortName = 'i', description = "UUID of the job to retrieve", required = true) String jobId) {
		UUID jobUuid;
		try {
			jobUuid = UUID.fromString(jobId);
		} catch (IllegalArgumentException e) {
			return "Error: Invalid Job ID format. Please provide a valid UUID.";
		}

		return jobService.getJob(jobUuid)
				.map(DiscoveryCommands::formatJobDetails)
				.orElse("Job not found with ID: " + jobId);
	}

	/**
	 * Lists discovery jobs, optionally filtering by provider or status.
	 *
	 * @param provider Optional cloud provider to filter by.
	 * @param status   Optional job status to filter by.
	 * @return List of jobs string or error message.
	 */
	@Command(name = "list-jobs", description = "Lists discovery jobs, optionally filtering by provider or status.")
	public String listJobs(
			@Option(longName = "provider", shortName = 'p', description = "Filter by cloud provider (gcp, aws, azure, oracle)", defaultValue = "") String provider,
			@Option(longName = "status", shortName = 's', description = "Filter by job status (PENDING, RUNNING, COMPLETED, FAILED, CANCELED)", defaultValue = "") String status) {
		try {
			CloudProvider cloudProvider = null;
			if (provider != null && !provider.isEmpty()) {
				cloudProvider = CloudProvider.valueOf(provider.toUpperCase());
			}

			JobStatus jobStatus = null;
			if (status != null && !status.isEmpty()) {
				jobStatus = JobStatus.valueOf(status.toUpperCase());
			}

			List<DiscoveryJob> jobs;
			if (cloudProvider != null && jobStatus != null) {
				final JobStatus finalJobStatus = jobStatus;
				jobs = jobService.getJobsByProvider(cloudProvider).stream()
						.filter(j -> j.getStatus() == finalJobStatus).toList();
			} else if (cloudProvider != null) {
				jobs = jobService.getJobsByProvider(cloudProvider);
			} else if (jobStatus != null) {
				jobs = jobService.getJobsByStatus(jobStatus);
			} else {
				jobs = jobService.getAllJobs();
			}

			if (jobs.isEmpty()) {
				return "No jobs found matching the criteria.";
			}

			return jobs.stream()
					.map(DiscoveryCommands::formatJobSummary)
					.collect(Collectors.joining("\n"));

		} catch (IllegalArgumentException e) {
			return "Error: Invalid provider or status value.";
		} catch (Exception e) {
			return "Error listing jobs: " + e.getMessage();
		}
	}

	// --- Helper Methods for Formatting --- //

	private static String formatJobSummary(DiscoveryJob job) {
		return String.format("ID: %s | Name: %s | Provider: %s | Account: %s | Status: %s | Type: %s",
				job.getJobId(),
				job.getJobName() != null ? job.getJobName() : "N/A",
				job.getCloudProvider(),
				job.getAccountId(),
				job.getStatus(),
				job.getJobType());
	}

	private static String formatJobDetails(DiscoveryJob job) {
		StringBuilder details = new StringBuilder();
		details.append("--- Job Details ---\n");
		details.append("ID:         ").append(job.getJobId()).append("\n");
		details.append("Name:       ").append(job.getJobName() != null ? job.getJobName() : "N/A").append("\n");
		details.append("Type:       ").append(job.getJobType()).append("\n");
		details.append("Provider:   ").append(job.getCloudProvider()).append("\n");
		details.append("Account ID: ").append(job.getAccountId()).append("\n");
		details.append("Status:     ").append(job.getStatus()).append("\n");
		details.append("Created At: ").append(job.getCreatedAt()).append("\n");
		details.append("Updated At: ").append(job.getUpdatedAt()).append("\n");
		details.append("Parameters: ").append(formatParameters(job.getParameters())).append("\n");
		return details.toString();
	}

	private static String formatParameters(Map<String, Object> params) {
		if (params == null || params.isEmpty()) {
			return "None";
		}
		return params.entrySet().stream()
				.map(entry -> entry.getKey() + "=" + entry.getValue())
				.collect(Collectors.joining(", "));
	}

}    