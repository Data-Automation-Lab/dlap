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

package com.dlap.discovery.job.callable;

import java.time.Instant;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.ICheckpointService;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.ILogAnalyzerRegistry;

/**
 * Callable for executing log analyzer jobs.
 * This class is responsible for finding and executing log analyzers for a given
 * job.
 */
public class LogAnalyzerCallable implements Callable<Boolean> {
	private static final Logger log = LoggerFactory.getLogger(LogAnalyzerCallable.class);

	private final DiscoveryJob job;
	private final ILogAnalyzerRegistry analyzerRegistry;
	private final ICheckpointService checkpointService;
	private final ICatalogService catalogService;

	/**
	 * Constructor with dependencies.
	 * 
	 * @param job               The discovery job entity to execute
	 * @param analyzerRegistry  Registry for finding log analyzers
	 * @param checkpointService Service for managing checkpoints
	 * @param catalogService    Service for updating the resource catalog
	 */
	public LogAnalyzerCallable(DiscoveryJob job,
			ILogAnalyzerRegistry analyzerRegistry,
			ICheckpointService checkpointService,
			ICatalogService catalogService) {
		this.job = job;
		this.analyzerRegistry = analyzerRegistry;
		this.checkpointService = checkpointService;
		this.catalogService = catalogService;
	}

	@Override
	public Boolean call() throws Exception {
		log.info("Executing log analyzer job: {}", job.getJobId());

		// Extract parameters
		CloudProvider provider = job.getCloudProvider();
		String accountId = job.getAccountId();

		if (provider == null) {
			throw new IllegalArgumentException("Cloud provider is required");
		}

		if (accountId == null || accountId.isEmpty()) {
			throw new IllegalArgumentException("Account ID is required");
		}

		// Get log analyzer for the provider
		ILogAnalyzer analyzer = analyzerRegistry.getAnalyzer(provider);
		if (analyzer == null) {
			throw new IllegalStateException("No log analyzer found for provider: " + provider);
		}

		log.info("Using log analyzer: {}", analyzer.getClass().getSimpleName());

		// Get last checkpoint
		Instant lastCheckpoint = checkpointService.getLastCheckpoint(provider, accountId);
		Instant currentCheckpoint = Instant.now();

		// Check if there are new logs
		if (!analyzer.hasNewLogs(accountId, lastCheckpoint)) {
			log.info("No new logs found since last checkpoint: {}", lastCheckpoint);
			return true; // Return true assuming no changes
		}

		/*
		 * // TODO: Refactor this callable for async/event-driven log analysis
		 * // Analyze logs (This method is removed/changed in ILogAnalyzer)
		 * List<ResourceChange> changes = analyzer.analyzeLogs(accountId,
		 * lastCheckpoint, currentCheckpoint);
		 * log.info("Found {} resource changes in logs", changes.size());
		 * 
		 * // Process resource changes (This should happen in event
		 * listeners/processors)
		 * List<ResourceChange> processedChanges = new ArrayList<>();
		 * for (ResourceChange change : changes) {
		 * try {
		 * // Update catalog
		 * catalogService.processResourceChange(change);
		 * processedChanges.add(change);
		 * } catch (Exception e) {
		 * log.error("Error processing resource change: {}", change, e);
		 * }
		 * }
		 * 
		 * // Update checkpoint (Checkpoint logic might need review in async model)
		 * checkpointService.updateCheckpoint(provider, accountId, currentCheckpoint);
		 * 
		 * log.info("Completed log analyzer job: {}, processed {} changes",
		 * job.getJobId(), processedChanges.size());
		 * 
		 * return new AnalysisResult(processedChanges, lastCheckpoint,
		 * currentCheckpoint, Map.of());
		 */

		// Trigger the asynchronous analysis instead
		log.info("Triggering asynchronous log analysis for job: {}", job.getJobId());
		try {
			analyzer.triggerLogAnalysisAsync(accountId,
					lastCheckpoint.atZone(java.time.ZoneId.systemDefault()), // Convert Instant to ZonedDateTime if
																				// needed by API
					currentCheckpoint.atZone(java.time.ZoneId.systemDefault()),
					null // Pass null or default AnalysisOptions
			);

			// In async model, the callable returns immediately after triggering
			log.info("Successfully triggered async log analysis for job: {}", job.getJobId());

			// Consider moving checkpoint logic? For now, update after triggering.
			try {
				checkpointService.updateCheckpoint(provider, accountId, currentCheckpoint);
			} catch (Exception e) {
				log.error("Failed to update checkpoint for job {} after triggering analysis", job.getJobId(), e);
				// Decide if this should cause the trigger to be marked as failed
				// return false; // Or maybe just log it?
			}

			return true; // Return true assuming trigger itself succeeded
		} catch (Exception e) {
			log.error("Failed to trigger log analysis for job {}: {}", job.getJobId(), e.getMessage(), e);
			return false; // Indicate trigger failure
		}
	}
}