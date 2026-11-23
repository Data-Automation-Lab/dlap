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

package com.dlap.discovery.crawler.service.oracle;

import com.dlap.discovery.common.auth.impl.oracle.OracleAuthenticationServiceImpl;
import com.dlap.discovery.common.config.cloud.impl.oracle.OracleConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.oracle.OracleResource;
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
 * Abstract base class for Oracle Cloud resource Crawlers.
 * Aligned with the event-driven architecture.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public abstract class OracleResourceCrawler<T extends OracleResource> extends AbstractResourceCrawler<T> {

    private static final Logger log = LoggerFactory.getLogger(OracleResourceCrawler.class);

    @Autowired
    protected OracleAuthenticationServiceImpl authService;

    @Autowired
    protected OracleConfigService configService;

    /**
     * Creates a new Oracle resource Crawler.
     *
     * @param supportedTypes List of resource types supported by this crawler.
     */
    protected OracleResourceCrawler(List<ResourceType> supportedTypes) {
        super(supportedTypes);
        log.info("Initialized Oracle Crawler {} for resource types: {}", getClass().getSimpleName(), supportedTypes);
    }

    /**
     * Prepare Oracle-specific discovery logic.
     *
     * @param job The discovery job
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing discovery for Oracle job: {}", job.getJobId());
        // Implement preparation logic using job details if necessary
        // Example: Set credentials, region, etc.
    }

    /**
     * Initiates the asynchronous discovery of Oracle resources.
     * Subclasses must implement this method to start their discovery process,
     * which should typically delegate to an @Async annotated method.
     *
     * @param accountId The Oracle Compartment ID.
     * @param context   The discovery context.
     */
    @Override
    public abstract void discoverResourcesAsync(String accountId, Map<String, Object> context);

    /**
     * Applies filters to a list of discovered resources.
     *
     * @param resources The resources to filter (use generic T)
     * @param filters   The filters to apply
     * @return The filtered list of resources
     */
    protected List<T> applyFilters(List<T> resources, Map<String, String> filters) {
        if (filters == null || filters.isEmpty() || resources == null || resources.isEmpty()) {
            return resources;
        }

        log.debug("Applying filters to {} resources: {}", resources.size(), filters);

        return resources.stream().filter(resource -> matchesFilters(resource, filters)).toList();
    }

    /**
     * Checks if a resource matches the specified filters.
     *
     * @param resource The resource to check (use generic T)
     * @param filters  The filters to apply
     * @return true if the resource matches all filters, false otherwise
     */
    protected boolean matchesFilters(T resource, Map<String, String> filters) {
        if (resource == null) return false;
        if (filters == null || filters.isEmpty()) return true;

        return filters
            .entrySet()
            .stream()
            .allMatch(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                if (value == null) return true;

                return switch (key.toLowerCase()) {
                    case "name" -> resource.getName() != null && resource.getName().toLowerCase().contains(value.toLowerCase());
                    case "id", "resourceid" -> resource.getResourceId() != null && resource.getResourceId().equalsIgnoreCase(value);
                    case "compartmentid" -> resource.getCompartmentId() != null && resource.getCompartmentId().equalsIgnoreCase(value);
                    case "region" -> resource.getRegion() != null && resource.getRegion().equalsIgnoreCase(value);
                    case "state", "lifecyclestate" -> resource.getLifecycleState() != null &&
                    resource.getLifecycleState().equalsIgnoreCase(value);
                    default -> {
                        if (key.startsWith("tag:")) {
                            String tagKey = key.substring(4);
                            yield resource.getTags() != null && Objects.equals(resource.getTags().get(tagKey), value);
                        } else if (key.startsWith("meta:")) {
                            String metaKey = key.substring(5);
                            yield resource.getOracleMetadata() != null && Objects.equals(resource.getOracleMetadata().get(metaKey), value);
                        } else {
                            log.trace("Unsupported filter key '{}' for resource {}", key, resource.getResourceId());
                            yield true;
                        }
                    }
                };
            });
    }

    public OracleAuthenticationServiceImpl getAuthService() {
        return authService;
    }

    public OracleConfigService getConfigService() {
        return configService;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.OCI;
    }
}
