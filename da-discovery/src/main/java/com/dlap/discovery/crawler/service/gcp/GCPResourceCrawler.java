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

package com.dlap.discovery.crawler.service.gcp;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.dlap.discovery.providers.gcp.auth.GCPAuthenticationServiceImpl;
import com.dlap.discovery.providers.gcp.config.GCPConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.GcpResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;
import com.google.auth.Credentials;

/**
 * Abstract base class for GCP resource crawlers.
 * Aligned with the event-driven architecture.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public abstract class GCPResourceCrawler<T extends GcpResource> extends AbstractResourceCrawler<T> {

    private static final Logger log = LoggerFactory.getLogger(GCPResourceCrawler.class);

    @Autowired
    private GCPConfigService configService;

    // Use the more specific type if possible
    @Autowired
    private GCPAuthenticationServiceImpl authService;

    /**
     * Constructor for GCPResourceCrawler.
     *
     * @param supportedTypes List of resource types supported by the crawler.
     */
    protected GCPResourceCrawler(List<ResourceType> supportedTypes) {
        super(supportedTypes);
    }

    /**
     * Overrides credential validation for GCP.
     */
    @Override
    public boolean validateCredentials(String accountId, Map<String, String> parameters) {
        log.info("Validating GCP credentials for account: {}", accountId);
        try {
            Credentials credentials = authService.getCredentials();
            boolean isValid = (credentials != null);
            // TODO: Implement a more robust validation check
            log.info("GCP credential validation result for account {}: {}", accountId, isValid);
            return isValid;
        } catch (Exception e) {
            log.error("GCP credential validation failed for account {}: {}", accountId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Prepare GCP-specific discovery logic.
     *
     * @param job The discovery job
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing discovery for GCP job: {}", job.getJobId());
        // Implement preparation logic using job details if necessary
        // Example: Set credentials, project ID, etc.
    }

    /**
     * Initiates the asynchronous discovery of GCP resources.
     * Subclasses must implement this method to start their discovery process,
     * which should typically delegate to an @Async annotated method.
     *
     * @param accountId The GCP Project ID.
     * @param context   The discovery context.
     */
    @Override
    public abstract void discoverResourcesAsync(String accountId, Map<String, Object> context);

    // Removed prepareDiscoveryLogic and discoverResources overrides

    // --- Getters for injected services ---
    public CloudAuthenticationService getAuthService() {
        return authService;
    }

    public GCPConfigService getConfigService() {
        return configService;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.GCP;
    }
}