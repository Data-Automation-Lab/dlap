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

package com.dlap.discovery.crawler.service.aws;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.AwsResource;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;

/**
 * Abstract base class for AWS resource crawlers.
 * Aligned with the event-driven architecture.
 * 
 * @param <T> The specific AWS resource type (subclass of AwsResource)
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public abstract class AWSResourceCrawler<T extends AwsResource> extends AbstractResourceCrawler<T> {

    private static final Logger log = LoggerFactory.getLogger(AWSResourceCrawler.class);

    // authenticationService is inherited from AbstractResourceCrawler

    @Autowired
    protected AWSConfigService configService;

    /**
     * Constructor.
     *
     * @param supportedTypes The list of ResourceType records this crawler supports.
     */
    protected AWSResourceCrawler(List<ResourceType> supportedTypes) {
        super(supportedTypes);
    }

    /**
     * Prepare AWS-specific discovery logic.
     * 
     * @param job The discovery job
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing discovery for AWS job: {}", job.getJobId());
        // Implement preparation logic using job details if necessary
        // Example: Set credentials, region, etc.
    }

    /**
     * Initiates the asynchronous discovery of AWS resources.
     * Subclasses must implement this method to start their discovery process,
     * which should typically delegate to an @Async annotated method.
     *
     * @param accountId The AWS account ID.
     * @param context   The discovery context (e.g., containing region).
     */
    @Override
    public abstract void discoverResourcesAsync(String accountId, Map<String, Object> context);

    // Removed prepareDiscoveryLogic and discoverResources overrides

    // Removed getCloudProvider() - handled by hierarchy
    // Removed validateCredentials() and estimateResourceCount() - uses
    // implementation from AbstractResourceCrawler

    // --- Getters for injected services (if needed by subclasses) ---
    public AWSConfigService getConfigService() {
        return configService;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }
}