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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.common.model.CloudProviderHandler;

/**
 * Callable for executing resource crawler jobs.
 * This class is responsible for finding and executing resource crawlers for a
 * given job.
 */
public class ResourceCrawlerCallable implements Callable<Boolean> {
    private static final Logger log = LoggerFactory.getLogger(ResourceCrawlerCallable.class);

    private final DiscoveryJob job;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final ICatalogService catalogService;
    private final CloudHierarchyRegistry cloudHierarchyRegistry;
    private final List<CloudProviderHandler> cloudProviderHandlers; 
                 
    /**
     * Constructor with dependencies.
     *
     * @param job                   The discovery job entity to execute
     * @param crawlerRegistry       Registry for finding resource crawlers
     * @param catalogService        Service for updating the resource catalog
     * @param cloudHierarchyRegistry Registry for canonical ResourceType lookup
     * @param folderManagementService Service for managing folder operations
     * @param cloudProviderDispatcher Dispatcher for provider-specific folder logic
     */
    public ResourceCrawlerCallable(DiscoveryJob job, IResourceCrawlerRegistry crawlerRegistry,
                                   ICatalogService catalogService, CloudHierarchyRegistry cloudHierarchyRegistry,
                                   List<CloudProviderHandler> cloudProviderHandlers ) {
        this.job = job;
        this.crawlerRegistry = crawlerRegistry;
        this.catalogService = catalogService;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
         this.cloudProviderHandlers = cloudProviderHandlers;
    }

    @Override
    public Boolean call() throws Exception {
        log.info("Executing resource crawler job: {}", job.getJobId());

        // Extract parameters
        CloudProvider provider = job.getCloudProvider();
        String accountId = job.getAccountId();

        if (provider == null) {
            throw new IllegalArgumentException("Cloud provider is required");
        }

        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("Account ID is required");
        }

        // Get resource types to crawl from parameters
        List<ResourceType> resourceTypes = null;
        if (job.getParameters() != null) {
            Object typesParam = job.getParameters().get("resourceTypesToCrawl");
            if (typesParam instanceof List) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> castedList = (List<String>) typesParam;
                    resourceTypes = castedList.stream()
                        .map(cloudHierarchyRegistry::getResourceType)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                } catch (ClassCastException e) {
                    log.error("Parameter 'resourceTypesToCrawl' is not a List<String> for job {}", job.getJobId(), e);
                    throw new IllegalArgumentException("Invalid format for 'resourceTypesToCrawl' parameter.", e);
                }
            }
        }
        
        // Find appropriate crawlers
        Collection<IResourceCrawler<CloudResource>> crawlers;
        if (resourceTypes != null && !resourceTypes.isEmpty()) {
            crawlers = crawlerRegistry.getCrawlersForTypes(provider, resourceTypes);
        } else {
            crawlers = crawlerRegistry.getCrawlersForProvider(provider);
        }

        if (crawlers.isEmpty()) {
            log.error("No crawlers found for provider {} and resource types {}", provider, resourceTypes);
            List<IResourceCrawler<?>> allCrawlers = crawlerRegistry.getAllCrawlers();
            log.error("Registered crawlers and their supported resource types:");
            for (IResourceCrawler<?> registeredCrawler : allCrawlers) {
                log.error("Crawler: {}, Provider: {}, Supported Types: {}",
                    registeredCrawler.getClass().getSimpleName(),
                    registeredCrawler.getProvider(),
                    // Null-safe: a CGLIB-proxied crawler's field-backed types are null.
                    registeredCrawler.getSupportedResourceTypes() == null
                        ? "<none/proxy>" : registeredCrawler.getSupportedResourceTypes());
            }
            return false;
        }  

        final Map<String, Object> context = job.getParameters() != null ? job.getParameters() : Map.of();
        boolean triggeredSuccessfully = false;

        // Check if this is a folder-based job
        String idType = (String) context.get("idType");
        if ("folder".equalsIgnoreCase(idType)) {
            log.info("Processing folder-based job for folder ID: {}", accountId);
            // Use CloudProviderDispatcher to handle provider-specific folder logic
            // Find the correct handler for the provider
            CloudProviderHandler handler = cloudProviderHandlers.stream()
                .filter(h -> h.supports(provider)) 
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No handler found for provider: " + provider));

            handler.process(job);
            triggeredSuccessfully = true;
        } else {
            // Process as a regular project-based job
            for (IResourceCrawler<?> crawler : crawlers) {
                try {
                    log.info("Preparing and triggering async crawler: {}", crawler.getClass().getSimpleName());
                    crawler.discoverResourcesAsync(accountId, context);
                    triggeredSuccessfully = true;
                } catch (Exception e) {
                    log.error("Error triggering crawler: {}", crawler.getClass().getSimpleName(), e);
                }
            }
        }

        log.info("Completed triggering resource crawler job: {}", job.getJobId());

        return triggeredSuccessfully;
    }
}     