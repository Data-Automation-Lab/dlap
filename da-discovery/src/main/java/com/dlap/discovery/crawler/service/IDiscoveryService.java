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

package com.dlap.discovery.crawler.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.CloudResourceDTO;
import com.dlap.discovery.common.util.health.IHealthCheckService;
import com.dlap.discovery.job.service.JobStatisticsDTO;

/**
 * Service interface for managing and executing resource discovery jobs.
 * Extends IHealthCheckService to provide health status.
 * Implementations of IDiscoveryService are responsible for discovering
 * resources of a specific type in a specific cloud provider. They will use
 * IResourceCrawler & IDiscoveryJob implementations to discover resources
 * and track the discovery process.
 *
 * @param <T> The type of cloud resource this service works with
 */
public interface IDiscoveryService<T extends CloudResource> extends IHealthCheckService {

        /**
         * Gets the cloud provider this service works with.
         * 
         * @return The cloud provider name
         */
        String getCloudProvider();

        /**
         * Gets the resource types supported by this service.
         * 
         * @return List of supported resource types (records)
         */
        List<ResourceType> getSupportedResourceTypes();

        /**
         * Checks if a resource type is supported by this service.
         * 
         * @param resourceType The resource type record to check
         * @return True if supported, false otherwise
         */
        boolean isResourceTypeSupported(ResourceType resourceType);

        /**
         * Creates a discovery job definition for crawling resources.
         * The returned job is typically not yet configured for execution.
         *
         * @param accountId       The target cloud account ID.
         * @param resourceTypeIds List of specific resource type IDs to crawl
         *                        (optional).
         * @param parameters      Additional parameters for the job.
         * @param jobName         Optional descriptive name for the job.
         * @return The created DiscoveryJob entity.
         */
        DiscoveryJob createDiscoveryCrawlerJob(String accountId, List<String> resourceTypeIds,
                        Map<String, String> parameters, String jobName);

        /**
         * Starts the execution of a previously created and configured discovery job
         * asynchronously.
         *
         * @param jobId The UUID of the configured DiscoveryJob to start.
         * @return A CompletableFuture containing the DiscoveryJob after it has
         *         completed or failed.
         */
        CompletableFuture<DiscoveryJob> startDiscoveryJobAsync(UUID jobId);

        /**
         * Starts the execution of a previously created and configured discovery job
         * synchronously (blocks until completion or failure).
         *
         * @param jobId      The UUID of the configured DiscoveryJob to start.
         * @param parameters Additional parameters (potentially overriding job params).
         * @return The DiscoveryJob after it has completed or failed.
         */
        DiscoveryJob startDiscoveryJob(UUID jobId, Map<String, String> parameters);

        /**
         * Retrieves the current state of a discovery job by its ID.
         *
         * @param jobId The UUID string of the job.
         * @return An Optional containing the DiscoveryJob if found.
         */
        Optional<DiscoveryJob> getDiscoveryJob(String jobId);

        /**
         * Retrieves all discovery jobs associated with this service/provider.
         *
         * @return A list of DiscoveryJob entities.
         */
        List<DiscoveryJob> getAllDiscoveryJobs();

        /**
         * Retrieves all discovery jobs for a specific account ID associated with this
         * service/provider.
         *
         * @param accountId The target cloud account ID.
         * @return A list of DiscoveryJob entities for the account.
         */
        List<DiscoveryJob> getDiscoveryJobsByAccount(String accountId);

        /**
         * Cancels a running discovery job.
         *
         * @param jobId The ID of the job to cancel
         * @return true if the job was cancelled, false if it couldn't be cancelled
         */
        boolean cancelDiscoveryJob(UUID jobId);

        /**
         * Pauses a running discovery job.
         *
         * @param jobId The ID of the job to pause
         * @return true if the job was paused, false if it couldn't be paused
         */
        boolean pauseDiscoveryJob(UUID jobId);

        /**
         * Resumes a paused discovery job.
         *
         * @param jobId The ID of the job to resume
         * @return true if the job was resumed, false if it couldn't be resumed
         */
        boolean resumeDiscoveryJob(UUID jobId);

        /**
         * Gets all Crawlers registered with this service.
         *
         * @return List of resource Crawlers
         */
        List<IResourceCrawler<T>> getCrawlers();

        /**
         * Gets all Crawlers for a specific cloud provider.
         *
         * @param cloudProvider The cloud provider to filter by
         * @return List of resource Crawlers for the specified provider
         */
        List<IResourceCrawler<T>> getCrawlersByProvider(String cloudProvider);

        /**
         * Registers a new resource Crawler with this service.
         *
         * @param crawler The Crawler to register
         */
        void registerCrawler(IResourceCrawler<T> crawler);

        /**
         * Unregisters a resource Crawler from this service.
         *
         * @param crawlerName The name of the Crawler to unregister
         * @return true if the Crawler was unregistered, false if it wasn't found
         */
        boolean unregisterCrawler(String crawlerName);

        /**
         * Discovers resources of a specific type in a given region.
         *
         * @param resourceType The type of resources to discover
         * @param region       The region to discover resources in
         * @return A list of discovered resources
         */
        List<CloudResourceDTO> discoverResources(String resourceType, String region);

        /**
         * Gets a resource by its ID.
         *
         * @param resourceId The ID of the resource to get
         * @return An Optional containing the resource if found
         */
        Optional<CloudResourceDTO> getResource(String resourceId);

        /**
         * Gets all resources of a specific type.
         *
         * @param resourceType The type of resources to get
         * @return A list of resources of the specified type
         */
        List<CloudResourceDTO> getResourcesByType(String resourceType);

        /**
         * Gets all resources in a specific region.
         *
         * @param region The region to get resources from
         * @return A list of resources in the specified region
         */
        List<CloudResourceDTO> getResourcesByRegion(String region);

        /**
         * Gets all resources with specific tags.
         *
         * @param tags The tags to filter resources by
         * @return A list of resources with the specified tags
         */
        List<CloudResourceDTO> getResourcesByTags(Map<String, String> tags);

        /**
         * Updates a resource's properties.
         *
         * @param resourceId The ID of the resource to update
         * @param properties The new properties to set
         * @return The updated resource
         */
        CloudResourceDTO updateResourceProperties(String resourceId, Map<String, Object> properties);

        /**
         * Updates a resource's tags.
         *
         * @param resourceId The ID of the resource to update
         * @param tags       The new tags to set
         * @return The updated resource
         */
        CloudResourceDTO updateResourceTags(String resourceId, Map<String, String> tags);

        /**
         * Deletes a resource.
         *
         * @param resourceId The ID of the resource to delete
         * @return true if the resource was deleted, false otherwise
         */
        boolean deleteResource(String resourceId);

        /**
         * Gets the discovery job statistics.
         *
         * @return The discovery job statistics
         */
        JobStatisticsDTO getJobStatistics();

        /**
         * Gets the cloud provider this service implementation supports.
         * 
         * @return The supported CloudProvider enum.
         */
        CloudProvider getProvider();
}