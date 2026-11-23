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

package com.dlap.discovery.crawler.service.azure;

import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.azure.AzureVMResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Abstract base class for Azure Cloud resource Crawlers.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public abstract class AbstractAzureResourceCrawler<T extends AzureVMResource> extends AbstractResourceCrawler<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractAzureResourceCrawler.class);

    // Make authService final and use constructor injection if possible
    @Autowired
    protected AzureAuthenticationService authService;

    /**
     * Creates a new Azure resource Crawler.
     *
     * @param supportedTypes List of resource types supported by this crawler.
     */
    protected AbstractAzureResourceCrawler(List<ResourceType> supportedTypes) {
        // Pass registry and type IDs up to AbstractResourceCrawler
        super(supportedTypes);
        log.info("Initialized Azure Crawler {} for resource types: {}", getClass().getSimpleName(), supportedTypes);
    }

    /**
     * Prepare Azure-specific discovery logic.
     * Subclasses can override to add more specific context if needed.
     *
     * @param job The discovery job
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing discovery for Azure job: {}", job.getJobId());
        // Subclasses can override if specific preparation is needed
    }

    /**
     * Initiates the asynchronous discovery of Azure resources.
     * Subclasses must implement this method to start their discovery process,
     * which should typically delegate to an @Async annotated method.
     *
     * @param accountId The Azure Subscription ID
     * @param context   The discovery context
     */
    @Override
    public abstract void discoverResourcesAsync(String accountId, Map<String, Object> context);

    /**
     * Applies filters to a list of discovered resources.
     *
     * @param resources The resources to filter
     * @param filters   The filters to apply
     * @return The filtered list of resources
     */
    protected List<T> applyFilters(List<T> resources, Map<String, String> filters) { // Use generic type T
        if (filters == null || filters.isEmpty() || resources == null || resources.isEmpty()) {
            return resources; // Return original list if no filtering needed or possible
        }

        log.debug("Applying filters to {} resources: {}", resources.size(), filters);

        // Filter resources based on criteria
        return resources.stream().filter(resource -> matchesFilters(resource, filters)).toList(); // Java 16+
    }

    /**
     * Checks if a resource matches the specified filters.
     *
     * @param resource The resource to check (using generic type T)
     * @param filters  The filters to apply
     * @return true if the resource matches all filters, false otherwise
     */
    protected boolean matchesFilters(T resource, Map<String, String> filters) {
        // Base check
        if (resource == null) return false;

        // Ensure filters are provided
        if (filters == null || filters.isEmpty()) return true;

        return filters
            .entrySet()
            .stream()
            .allMatch(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                if (value == null) return true; // Skip null filter values

                // Common Azure Resource fields (adjust based on AzureVMResource fields)
                return switch (key.toLowerCase()) {
                    case "name" -> resource.getName() != null && resource.getName().toLowerCase().contains(value.toLowerCase());
                    case "id", "resourceid" -> resource.getResourceId() != null && resource.getResourceId().equalsIgnoreCase(value);
                    case "resourcegroup" -> resource.getResourceGroup() != null && resource.getResourceGroup().equalsIgnoreCase(value);
                    case "region", "location" -> resource.getRegion() != null && resource.getRegion().equalsIgnoreCase(value);
                    case "state", "status", "provisioningstate" -> resource.getProvisioningState() != null &&
                    resource.getProvisioningState().equalsIgnoreCase(value);
                    case "subscriptionid" -> resource.getSubscriptionId() != null && resource.getSubscriptionId().equalsIgnoreCase(value);
                    // Generic tag handling
                    default -> {
                        if (key.startsWith("tag:")) {
                            String tagKey = key.substring(4);
                            yield resource.getTags() != null && Objects.equals(resource.getTags().get(tagKey), value);
                        } else {
                            log.trace("Unsupported filter key '{}' for resource {}", key, resource.getResourceId());
                            yield true;
                        }
                    }
                };
            });
    }

    // --- Getter for auth service ---
    public AzureAuthenticationService getAuthService() {
        return authService;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AZURE;
    }
}
