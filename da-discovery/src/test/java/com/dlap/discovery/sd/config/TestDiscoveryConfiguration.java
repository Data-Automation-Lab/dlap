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

package com.dlap.discovery.sd.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.CloudResourceDTO;
import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.service.JobStatisticsDTO;

/**
 * Mock configuration for discovery services in tests.
 * This resolves the dependency injection issue where multiple discovery service
 * beans are found (AWS, Azure, GCP) but DefaultDiscoveryCoordinatorServiceImpl
 * requires a single one.
 */
@Configuration
public class TestDiscoveryConfiguration {

	/**
	 * Primary mock discovery service for tests.
	 * This bean will be injected when a non-specific IDiscoveryService is
	 * requested.
	 */
	@Primary
	@Bean
	public IDiscoveryService<CloudResource> primaryDiscoveryService() {
		return new MockDiscoveryService();
	}

	/**
	 * Mock implementation of IDiscoveryService for testing
	 */
	private static class MockDiscoveryService implements IDiscoveryService<CloudResource> {
		// Implement methods with stub implementations
		// We're just providing this to satisfy Spring's dependency injection
		@Override
		public String getCloudProvider() {
			return "mock";
		}

		@Override
		public CloudProvider getProvider() {
			return CloudProvider.UNKNOWN;
		}

		// IHealthCheckService methods
		@Override
		public boolean isServiceHealthy(String serviceName) {
			return true;
		}

		@Override
		public HealthStatus checkServiceHealth(String serviceName) {
			return HealthStatus.up(serviceName);
		}

		@Override
		public List<HealthStatus> getSystemStatus() {
			return Collections.singletonList(HealthStatus.up("mock-system"));
		}

		@Override
		public void registerHealthCheck(String name, String displayName, Supplier<HealthStatus> checker) {
			// No-op for testing
		}

		// Other methods can return default/empty values
		// Just implement the minimum required for tests to run
		@Override
		public List<ResourceType> getSupportedResourceTypes() {
			return Collections.emptyList();
		}

		@Override
		public boolean isResourceTypeSupported(ResourceType resourceType) {
			return false;
		}

		@Override
		public DiscoveryJob createDiscoveryCrawlerJob(String accountId,
				List<String> resourceTypeIds, Map<String, String> parameters, String jobName) {
			return null;
		}

		// Keep this for backward compatibility
		public DiscoveryJob createDiscoveryJob(String accountId,
				List<String> resourceTypeIds, Map<String, String> parameters) {
			return createDiscoveryCrawlerJob(accountId, resourceTypeIds, parameters, "DefaultJob");
		}

		@Override
		public CompletableFuture<DiscoveryJob> startDiscoveryJobAsync(UUID jobId) {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public DiscoveryJob startDiscoveryJob(UUID jobId, Map<String, String> parameters) {
			return null;
		}

		@Override
		public Optional<DiscoveryJob> getDiscoveryJob(String jobId) {
			return Optional.empty();
		}

		@Override
		public List<DiscoveryJob> getAllDiscoveryJobs() {
			return Collections.emptyList();
		}

		@Override
		public List<DiscoveryJob> getDiscoveryJobsByAccount(String accountId) {
			return Collections.emptyList();
		}

		@Override
		public boolean cancelDiscoveryJob(UUID jobId) {
			return false;
		}

		@Override
		public boolean pauseDiscoveryJob(UUID jobId) {
			return false;
		}

		@Override
		public boolean resumeDiscoveryJob(UUID jobId) {
			return false;
		}

		// Keep these for backward compatibility
		public boolean cancelDiscoveryJob(String jobId) {
			try {
				return cancelDiscoveryJob(UUID.fromString(jobId));
			} catch (IllegalArgumentException e) {
				return false;
			}
		}

		public boolean pauseDiscoveryJob(String jobId) {
			try {
				return pauseDiscoveryJob(UUID.fromString(jobId));
			} catch (IllegalArgumentException e) {
				return false;
			}
		}

		public boolean resumeDiscoveryJob(String jobId) {
			try {
				return resumeDiscoveryJob(UUID.fromString(jobId));
			} catch (IllegalArgumentException e) {
				return false;
			}
		}

		@Override
		public List<IResourceCrawler<CloudResource>> getCrawlers() {
			return Collections.emptyList();
		}

		@Override
		public List<IResourceCrawler<CloudResource>> getCrawlersByProvider(
				String cloudProvider) {
			return Collections.emptyList();
		}

		@Override
		public void registerCrawler(IResourceCrawler<CloudResource> crawler) {
			// No-op
		}

		@Override
		public boolean unregisterCrawler(String crawlerName) {
			return false;
		}

		@Override
		public java.util.Optional<CloudResourceDTO> getResource(
				String resourceId) {
			return java.util.Optional.empty();
		}

		@Override
		public boolean deleteResource(String resourceId) {
			return false;
		}

		@Override
		public java.util.List<CloudResourceDTO> discoverResources(String accountId,
				String resourceType) {
			return java.util.Collections.emptyList();
		}

		@Override
		public CloudResourceDTO updateResourceProperties(String resourceId,
				java.util.Map<String, Object> properties) {
			return null;
		}

		@Override
		public java.util.List<CloudResourceDTO> getResourcesByRegion(
				String region) {
			return java.util.Collections.emptyList();
		}

		@Override
		public java.util.List<CloudResourceDTO> getResourcesByType(
				String resourceType) {
			return java.util.Collections.emptyList();
		}

		@Override
		public JobStatisticsDTO getJobStatistics() {
			return new JobStatisticsDTO.Builder()
					.resourcesDiscovered(0)
					.resourcesAdded(0)
					.resourcesSkipped(0)
					.resourcesUpdated(0)
					.resourcesFailed(0)
					.resources(java.util.Collections.emptyList())
					.build();
		}

		@Override
		public java.util.List<CloudResourceDTO> getResourcesByTags(
				java.util.Map<String, String> tags) {
			return java.util.Collections.emptyList();
		}

		@Override
		public CloudResourceDTO updateResourceTags(String resourceId,
				java.util.Map<String, String> tags) {
			return null;
		}
	}
}