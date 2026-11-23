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

package com.dlap.discovery.catalog.persistence.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.IResourceCrawler;

/**
 * Default implementation of ResourceRegistry that stores Crawlers in memory,
 * mapped by the ResourceType they support.
 * 
 */
@org.springframework.stereotype.Service // Fully qualified annotation
public class DefaultResourceCrawlerRegistry implements IResourceCrawlerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultResourceCrawlerRegistry.class);

    // Registry containing the loaded Service/ResourceType hierarchy
    private final CloudHierarchyRegistry hierarchyRegistry;

    // Maps ResourceType record to the corresponding Crawler that handles it.
    // Assumes one crawler per resource type for simplicity. Use List<> if multiple
    // are allowed.
    private final Map<ResourceType, IResourceCrawler<?>> crawlersByResourceType = new ConcurrentHashMap<>();

    // Constructor injection
    public DefaultResourceCrawlerRegistry(CloudHierarchyRegistry hierarchyRegistry) {
        this.hierarchyRegistry = Objects.requireNonNull(hierarchyRegistry, "hierarchyRegistry cannot be null");
    }

    @Override
    public boolean registerCrawler(IResourceCrawler<?> resourceCrawler) {
        if (resourceCrawler == null) {
            LOGGER.warn("Attempted to register a null Crawler");
            return false;
        }

        List<ResourceType> supportedTypes = resourceCrawler.getSupportedResourceTypes();
        if (supportedTypes == null || supportedTypes.isEmpty()) {
            // A CGLIB-proxied crawler bean reports an empty supported-types list:
            // @PostConstruct populated the target instance, but the final
            // getSupportedResourceTypes() reads the proxy's own (empty) field and
            // can't delegate. Fall back to the provider's declared types from the
            // hierarchy so the crawler still registers (fixes every proxied crawler).
            supportedTypes = hierarchyRegistry.getResourceTypes(resourceCrawler.getProvider());
            if (supportedTypes == null || supportedTypes.isEmpty()) {
                LOGGER.warn("Crawler {} does not support any resource types. Skipping registration.",
                        resourceCrawler.getClass().getSimpleName());
                return false;
            }
            LOGGER.info("Crawler {} reported no types (proxy); resolved {} type(s) from the hierarchy for provider {}.",
                    resourceCrawler.getClass().getSimpleName(), supportedTypes.size(), resourceCrawler.getProvider());
        }

        LOGGER.info("Registering Crawler: {} for types: {}",
                resourceCrawler.getClass().getSimpleName(),
                supportedTypes.stream().map(ResourceType::id).collect(Collectors.joining(", ")));

        boolean registeredAny = false;
        for (ResourceType type : supportedTypes) {
            IResourceCrawler<?> previous = crawlersByResourceType.put(type, resourceCrawler);
            if (previous != null && previous != resourceCrawler) {
                LOGGER.warn(
                        "Crawler registration conflict: ResourceType '{}' was handled by {} but is now handled by {}.",
                        type.id(), previous.getClass().getSimpleName(), resourceCrawler.getClass().getSimpleName());
            }
            registeredAny = true;
        }
        return registeredAny;
    }

    @Override
    public boolean unregisterCrawler(IResourceCrawler<?> resourceCrawler) {
        if (resourceCrawler == null) {
            LOGGER.warn("Attempted to unregister a null Crawler");
            return false;
        }

        LOGGER.info("Unregistering Crawler: {}", resourceCrawler.getClass().getSimpleName());

        // Find all resource types handled by this crawler and remove them
        // This is less efficient but necessary if a crawler handles multiple types.
        // Consider a reverse map if performance becomes an issue.
        List<ResourceType> keysToRemove = crawlersByResourceType.entrySet().stream()
                .filter(entry -> entry.getValue() == resourceCrawler) // Use identity check
                .map(Map.Entry::getKey)
                .toList(); // Java 16+

        if (keysToRemove.isEmpty()) {
            LOGGER.warn("Crawler {} was not found in the registry.", resourceCrawler.getClass().getSimpleName());
            return false;
        }

        keysToRemove.forEach(crawlersByResourceType::remove);
        LOGGER.info("Unregistered crawler {} for types: {}",
                resourceCrawler.getClass().getSimpleName(),
                keysToRemove.stream().map(ResourceType::id).collect(Collectors.joining(", ")));
        return true;
    }

    @Override
    public IResourceCrawler<?> getCrawler(ResourceType resourceType) {
        if (resourceType == null) {
            return null;
        }
        return crawlersByResourceType.get(resourceType);
    }

    @Override
    public List<IResourceCrawler<?>> getCrawlers(CloudService service) {
        if (service == null) {
            return Collections.emptyList();
        }
        // Find all resource types for this service from the hierarchy registry
        return hierarchyRegistry.getResourceTypes(service).stream()
                .map(this::getCrawler) // Look up the crawler for each type
                .filter(Objects::nonNull) // Filter out types with no registered crawler
                .distinct() // Ensure each crawler instance appears only once
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<IResourceCrawler<?>> getCrawlers(CloudProvider provider) {
        if (provider == null) {
            return Collections.emptyList();
        }
        // Find all services for this provider, then get crawlers for each service
        return hierarchyRegistry.getServices(provider).stream()
                .flatMap(service -> getCrawlers(service).stream()) // Get crawlers for each service
                .distinct() // Ensure each crawler instance appears only once
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<IResourceCrawler<CloudResource>> getCrawlersForProvider(CloudProvider provider) {
        if (provider == null) {
            return Collections.emptyList();
        }

        // Cast to the required Collection type from our existing method
        @SuppressWarnings("unchecked")
        List<IResourceCrawler<CloudResource>> result = (List<IResourceCrawler<CloudResource>>) (List<?>) getCrawlers(
                provider);
        return result;
    }

    @Override
    public Collection<IResourceCrawler<CloudResource>> getCrawlersForTypes(CloudProvider provider,
            List<ResourceType> resourceTypes) {
        if (provider == null || resourceTypes == null || resourceTypes.isEmpty()) {
            return Collections.emptyList();
        }

        // Resolve from the registration map (authoritative) rather than each crawler's
        // getSupportedResourceTypes(): under CGLIB proxying the proxy instance's copy of
        // that list is null/empty, which would NPE here or silently drop the crawler.
        // The map was populated at registration from the cloud hierarchy, and the job's
        // ResourceTypes are resolved from the same CloudHierarchyRegistry, so they key in.
        List<IResourceCrawler<CloudResource>> result = new ArrayList<>();
        for (ResourceType type : resourceTypes) {
            IResourceCrawler<?> crawler = crawlersByResourceType.get(type);
            if (crawler != null && provider.equals(crawler.getProvider()) && !result.contains(crawler)) {
                @SuppressWarnings("unchecked")
                IResourceCrawler<CloudResource> typed = (IResourceCrawler<CloudResource>) crawler;
                result.add(typed);
            }
        }
        return result;
    }

    @Override
    public List<IResourceCrawler<?>> getAllCrawlers() {
        // Return a unique, unmodifiable list of all registered crawler instances
        return crawlersByResourceType.values().stream()
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    // Remove the old getCrawlersByProvider method as getCrawlers(CloudProvider)
    // replaces it
    /*
     * @Override
     * public List<IResourceCrawler<?>> getCrawlersByProvider(CloudProvider
     * cloudProvider) {
     * // ... old implementation ...
     * }
     */
}