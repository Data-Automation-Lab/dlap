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

package com.dlap.discovery.catalog.persistence;

import com.dlap.discovery.common.config.CloudHierarchyProperties;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Service
public class CloudHierarchyRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudHierarchyRegistry.class);

    private final CloudHierarchyProperties cloudHierarchyProperties;

    // Internal storage for the loaded hierarchy (made unmodifiable after init)
    private Map<String, CloudService> servicesById = Collections.emptyMap();
    private Map<String, ResourceType> resourceTypesById = Collections.emptyMap();
    private Map<CloudProvider, List<CloudService>> servicesByProvider = Collections.emptyMap();
    private Map<CloudService, List<ResourceType>> resourceTypesByService = Collections.emptyMap();

    public CloudHierarchyRegistry(CloudHierarchyProperties cloudHierarchyProperties) {
        LOGGER.info("Initializing Cloud Hierarchy Registry with properties: {}", cloudHierarchyProperties);
        this.cloudHierarchyProperties = Objects.requireNonNull(cloudHierarchyProperties, "cloudHierarchyProperties cannot be null");
    }

    @PostConstruct
    public void initialize() {
        LOGGER.info("Initializing Cloud Hierarchy Registry...");
        if (cloudHierarchyProperties == null || CollectionUtils.isEmpty(cloudHierarchyProperties.getProviders())) {
            LOGGER.warn("Cloud hierarchy configuration is empty or null. Registry will be empty.");
            // Keep maps empty
            return;
        }

        Map<String, CloudService> tempServicesById = new HashMap<>();
        Map<String, ResourceType> tempResourceTypesById = new HashMap<>();
        Map<CloudProvider, List<CloudService>> tempServicesByProvider = new HashMap<>();
        Map<CloudService, List<ResourceType>> tempResourceTypesByService = new HashMap<>();

        try {
            for (CloudHierarchyProperties.ProviderConfig providerConfig : cloudHierarchyProperties.getProviders()) {
                CloudProvider currentProvider = providerConfig.getProvider();
                if (currentProvider == null) {
                    throw new IllegalStateException("Configuration error: ProviderConfig missing 'provider' field.");
                }
                LOGGER.info("Processing provider: {}", currentProvider);
                List<CloudService> providerServices = new ArrayList<>();
                tempServicesByProvider.put(currentProvider, providerServices);

                if (CollectionUtils.isEmpty(providerConfig.getServices())) {
                    LOGGER.warn("No services configured for provider: {}", currentProvider);
                    continue;
                }

                for (CloudHierarchyProperties.ServiceConfig serviceConfig : providerConfig.getServices()) {
                    // Create and validate Service
                    CloudService service = new CloudService(serviceConfig.getId(), serviceConfig.getDisplayName(), currentProvider);
                    if (tempServicesById.containsKey(service.id())) {
                        throw new IllegalStateException("Duplicate service ID found in configuration: " + service.id());
                    }
                    LOGGER.info("Processing service: {} ({})", service.id(), service.displayName());
                    tempServicesById.put(service.id(), service);
                    providerServices.add(service); // Add to the list for the current provider

                    List<ResourceType> serviceResourceTypes = new ArrayList<>();
                    tempResourceTypesByService.put(service, serviceResourceTypes);

                    if (CollectionUtils.isEmpty(serviceConfig.getResourceTypes())) {
                        LOGGER.warn("No resource types configured for service: {} ({})", service.id(), service.displayName());
                        continue;
                    }

                    for (CloudHierarchyProperties.ResourceTypeConfig resourceTypeConfig : serviceConfig.getResourceTypes()) {
                        // Create and validate ResourceType
                        ResourceType resourceType = new ResourceType(
                            resourceTypeConfig.getId(),
                            resourceTypeConfig.getDisplayName(),
                            service
                        );
                        if (tempResourceTypesById.containsKey(resourceType.id())) {
                            throw new IllegalStateException("Duplicate resource type ID found in configuration: " + resourceType.id());
                        }
                        LOGGER.info("Processing resource type: {} ({})", resourceType.id(), resourceType.displayName());
                        tempResourceTypesById.put(resourceType.id(), resourceType);
                        serviceResourceTypes.add(resourceType); // Add to the list for the current service
                    }
                    // Make the list for this service unmodifiable
                    tempResourceTypesByService.put(service, Collections.unmodifiableList(new ArrayList<>(serviceResourceTypes)));
                }
                // Make the list for this provider unmodifiable
                tempServicesByProvider.put(currentProvider, Collections.unmodifiableList(new ArrayList<>(providerServices)));
            }

            // Make the main maps unmodifiable
            this.servicesById = Collections.unmodifiableMap(tempServicesById);
            this.resourceTypesById = Collections.unmodifiableMap(tempResourceTypesById);
            this.servicesByProvider = Collections.unmodifiableMap(tempServicesByProvider);
            this.resourceTypesByService = Collections.unmodifiableMap(tempResourceTypesByService);

            LOGGER.info(
                "Cloud Hierarchy Registry initialized successfully with {} services and {} resource types.",
                this.servicesById.size(),
                this.resourceTypesById.size()
            );

            // Log all registered resource types
            LOGGER.info("Registered resource types:");
            this.resourceTypesById.forEach((id, type) ->
                    LOGGER.info("ResourceType: {} -> Service: {} -> Provider: {}", id, type.service().id(), type.service().provider())
                );
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Cloud Hierarchy Registry from configuration: {}", e.getMessage(), e);
            // Prevent partial initialization - keep maps empty
            this.servicesById = Collections.emptyMap();
            this.resourceTypesById = Collections.emptyMap();
            this.servicesByProvider = Collections.emptyMap();
            this.resourceTypesByService = Collections.emptyMap();
            // Optionally re-throw as a runtime exception to halt application startup
            throw new RuntimeException("Failed to initialize Cloud Hierarchy Registry", e);
        }
    }

    // --- Public Accessor Methods ---

    public CloudService getService(String serviceId) {
        return servicesById.get(serviceId);
    }

    public ResourceType getResourceType(String resourceTypeId) {
        return resourceTypesById.get(resourceTypeId);
    }

    public List<CloudService> getServices(CloudProvider provider) {
        return servicesByProvider.getOrDefault(provider, Collections.emptyList());
    }

    public List<ResourceType> getResourceTypes(CloudService service) {
        return resourceTypesByService.getOrDefault(service, Collections.emptyList());
    }

    public List<ResourceType> getResourceTypes(CloudProvider provider) {
        return getServices(provider).stream().flatMap(service -> getResourceTypes(service).stream()).collect(Collectors.toList()); // Already unmodifiable lists from getResourceTypes(Service)
    }

    public Set<CloudService> getAllServices() {
        return Collections.unmodifiableSet(servicesById.values().stream().collect(Collectors.toSet()));
    }

    public Set<ResourceType> getAllResourceTypes() {
        return Collections.unmodifiableSet(resourceTypesById.values().stream().collect(Collectors.toSet()));
    }
}
