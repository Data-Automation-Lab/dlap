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

package com.dlap.discovery.job.executable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.ICheckpointService;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;

/**
 * Callable task performing log analysis for a specific target
 * (account/provider).
 * Intended to be wrapped by JavaCallableExecutable for the job framework.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE) // New instance per job execution
public class LogAnalysisCallable implements Callable<Boolean> { // Returns true on success

	private static final Logger log = LoggerFactory.getLogger(LogAnalysisCallable.class);

	private final List<ILogAnalyzer> logAnalyzers; // Injected list of all analyzers
	private final ICatalogService catalogService;
	private final ICheckpointService checkpointService;

	// Parameters to be set for each job execution
	private String targetAccountId;
	private CloudProvider targetProvider;
	private Map<String, String> jobParameters; // General purpose parameters
	private String jobInstanceId; // Identifier for this specific job run/instance

	@Autowired
	public LogAnalysisCallable(List<ILogAnalyzer> logAnalyzers,
			ICatalogService catalogService,
			ICheckpointService checkpointService) {
		this.logAnalyzers = logAnalyzers;
		this.catalogService = catalogService;
		this.checkpointService = checkpointService;
	}

	// --- Setters for Job Parameters ---
	// These would be called by the job framework before execution

	public void setTargetAccountId(String targetAccountId) {
		this.targetAccountId = targetAccountId;
	}

	public void setTargetProvider(CloudProvider targetProvider) {
		this.targetProvider = targetProvider;
	}

	public void setJobParameters(Map<String, String> jobParameters) {
		this.jobParameters = jobParameters;
	}

	public void setJobInstanceId(String jobInstanceId) {
		this.jobInstanceId = jobInstanceId;
	}

	@Override
	public Boolean call() throws Exception {
		if (targetAccountId == null || targetProvider == null || jobInstanceId == null) {
			log.error("LogAnalysisCallable missing required parameters: accountId, provider, or jobInstanceId");
			return false;
		}

		log.info("Starting log analysis task for account: {}, provider: {}, jobInstanceId: {}",
				targetAccountId, targetProvider, jobInstanceId);

		try {
			// 1. Get Checkpoint using the checkpoint service
			Instant lastCheckpoint = checkpointService
					.getCheckpoint(targetProvider, targetAccountId, jobInstanceId)
					.orElse(null);

			if (lastCheckpoint == null) {
				// Default to 1 hour ago if no checkpoint exists
				lastCheckpoint = Instant.now().minus(Duration.ofHours(1));
				log.info(
						"No previous checkpoint found for provider: {}, account: {}, job: {}. Using default window starting at: {}",
						targetProvider, targetAccountId, jobInstanceId, lastCheckpoint);
			} else {
				log.info("Resuming from checkpoint: {} for provider: {}, account: {}, job: {}",
						lastCheckpoint, targetProvider, targetAccountId, jobInstanceId);
			}

			// 2. Determine Time Window
			Instant now = Instant.now();
			Instant startTime = lastCheckpoint;
			Instant endTime = now;

			if (startTime.isAfter(endTime) || startTime.equals(endTime)) {
				log.info("Start time ({}) is not before end time ({}) for job {}. Skipping analysis.",
						startTime, endTime, jobInstanceId);
				return true; // Nothing to process, considered success
			}

			ZonedDateTime startZoned = startTime.atZone(ZoneOffset.UTC);
			ZonedDateTime endZoned = endTime.atZone(ZoneOffset.UTC);

			// 3. Select Analyzer
			ILogAnalyzer analyzer = logAnalyzers.stream()
					.filter(a -> a.getProviderName().equalsIgnoreCase(targetProvider.name())) // Match provider name
					.findFirst()
					.orElse(null);

			if (analyzer == null) {
				log.error("No ILogAnalyzer implementation found for provider: {}", targetProvider);
				return false;
			}

			// 4. Prepare Analysis Options (can be customized based on jobParameters)
			AnalysisOptions options = AnalysisOptions.builder()
					// .resourceTypes(...) // Optional: Filter specific types
					// .changeTypes(...) // Optional: Filter specific changes
					.includeMetadata(true) // Ensure metadata is extracted by analyzer
					.build();

			// 5. Call Analyzer
			log.info("Analyzing logs for {} account {} between {} and {}",
					targetProvider, targetAccountId, startZoned, endZoned);

			// Trigger the analysis asynchronously
			analyzer.triggerLogAnalysisAsync(targetAccountId, startZoned, endZoned, options);

			// Use query end time as the new checkpoint
			Instant newCheckpoint = endTime;

			// 7. Update Checkpoint using the checkpoint service
			boolean checkpointSaved = checkpointService.setCheckpoint(
					targetProvider, targetAccountId, newCheckpoint, jobInstanceId);

			if (checkpointSaved) {
				log.info("Updated checkpoint to {} for provider: {}, account: {}, job: {}",
						newCheckpoint, targetProvider, targetAccountId, jobInstanceId);
			} else {
				log.warn("Failed to update checkpoint for provider: {}, account: {}, job: {}",
						targetProvider, targetAccountId, jobInstanceId);
			}

			return true; // Indicate success

		} catch (Exception e) {
			log.error("Log analysis task failed for account: {}, provider: {}, jobInstanceId: {} - Error: {}",
					targetAccountId, targetProvider, jobInstanceId, e.getMessage(), e);
			throw e; // Allow exception to propagate
		}
	}
}