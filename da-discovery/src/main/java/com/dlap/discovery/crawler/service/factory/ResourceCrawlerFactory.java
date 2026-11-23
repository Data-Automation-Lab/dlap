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

package com.dlap.discovery.crawler.service.factory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;

/**
 * Factory for creating ResourceCrawler instances based on cloud provider and
 * resource type.
 * This class supports the refactoring of the resource discovery system to
 * support
 * multi-cloud discovery in a more modular way.
 */
@Component
public class ResourceCrawlerFactory {

    private static final Logger log = LoggerFactory.getLogger(ResourceCrawlerFactory.class);

    @Autowired
    private IResourceCrawlerRegistry crawlerRegistry;

    /**
     * Gets all supported resource types for a given cloud provider.
     * 
     * @param cloudProvider The cloud provider to check for supported resource types
     * @return List of supported resource types for this provider
     */
    public List<ResourceType> getSupportedResourceTypes(CloudProvider cloudProvider) {
        if (cloudProvider == null) {
            log.error("Cloud provider is null");
            return List.of();
        }

        // Get all crawlers for this provider
        Collection<IResourceCrawler<CloudResource>> crawlers = crawlerRegistry.getCrawlersForProvider(cloudProvider);
        if (crawlers.isEmpty()) {
            log.warn("No crawlers found for cloud provider: {}", cloudProvider);
            return List.of();
        }

        // Collect all supported resource types from all crawlers
        Set<ResourceType> supportedTypes = new HashSet<>();
        for (IResourceCrawler<?> crawler : crawlers) {
            supportedTypes.addAll(crawler.getSupportedResourceTypes());
        }

        return new ArrayList<>(supportedTypes);
    }

    /**
     * Finds an appropriate ResourceCrawler for the specified cloud provider and
     * resource type.
     * 
     * @param <T>           The type of cloud resources
     * @param cloudProvider The cloud provider (e.g., "aws", "gcp", "azure")
     * @param resourceType  The type of resource to find
     * @return An Optional containing the ResourceCrawler if found, or empty if not
     */
    @SuppressWarnings("unchecked")
    public <T extends CloudResource> Optional<IResourceCrawler<T>> getCrawler(CloudProvider cloudProvider,
            ResourceType resourceType) {
        if (cloudProvider == null || resourceType == null) {
            log.error("Cloud provider or resource type is null");
            return Optional.empty();
        }

        // Get crawlers for the cloud provider
        Collection<IResourceCrawler<CloudResource>> crawlers = crawlerRegistry.getCrawlersForProvider(cloudProvider);
        if (crawlers.isEmpty()) {
            log.error("No crawlers found for cloud provider: {}", cloudProvider);
            return Optional.empty();
        }

        // Find a crawler that supports the requested resource type
        for (IResourceCrawler<?> crawler : crawlers) {
            if (crawler.getSupportedResourceTypes().contains(resourceType)) {
                log.info("Found crawler for resource type {} and cloud provider {}: {}",
                        resourceType, cloudProvider, crawler.getClass().getSimpleName());
                return Optional.of((IResourceCrawler<T>) crawler);
            }
        }

        log.error("No crawler found for resource type {} and cloud provider {}", resourceType, cloudProvider);
        return Optional.empty();
    }

    /**
     * Finds an appropriate ResourceCrawler for the specified job details.
     * 
     * @param <T>           The type of cloud resources
     * @param cloudProvider The cloud provider (e.g., "aws", "gcp", "azure")
     * @param resourceTypes The list of resource types to discover
     * @return An Optional containing the ResourceCrawler if found, or empty if not
     */
    public <T extends CloudResource> Optional<IResourceCrawler<T>> getCrawler(CloudProvider cloudProvider,
            List<ResourceType> resourceTypes) {
        if (cloudProvider == null || resourceTypes == null || resourceTypes.isEmpty()) {
            log.error("Cloud provider or resource types list is null or empty");
            return Optional.empty();
        }

        // Try each resource type in order until we find a matcher
        for (ResourceType resourceType : resourceTypes) {
            Optional<IResourceCrawler<T>> crawler = getCrawler(cloudProvider, resourceType);
            if (crawler.isPresent()) {
                return crawler;
            }
        }

        log.error("No crawler found for any of the resource types {} and cloud provider {}", resourceTypes,
                cloudProvider);
        return Optional.empty();
    }
}