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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;

/**
 * Abstract base class for resource crawlers.
 * Provides common functionality and enforces basic contracts for the
 * event-driven model.
 *
 * @param <T> The type of cloud resource this crawler handles.
 */
public abstract class AbstractResourceCrawler<T extends CloudResource>
        implements IResourceCrawler<T> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected CloudHierarchyRegistry hierarchyRegistry;

    @Autowired
    protected CloudAuthenticationService authenticationService;

    private final List<ResourceType> supportedTypes;

    /**
     * Constructor for AbstractResourceCrawler.
     * 
     * @param supportedTypes List of resource types this crawler supports.
     */
    protected AbstractResourceCrawler(List<ResourceType> supportedTypes) {
        // Create a mutable ArrayList from the passed list (defensive copy)
        this.supportedTypes = new ArrayList<>(supportedTypes);
    }

    /**
     * Gets the resource types this Crawler supports.
     *
     * @return List of supported ResourceType records.
     */
    @Override
    public final List<ResourceType> getSupportedResourceTypes() {
        return supportedTypes;
    }

    /**
     * Adds a resource type to the supported types list.
     * This method should be called in the @PostConstruct method of implementing classes.
     *
     * @param resourceType The resource type to add
     */
    protected void addSupportedType(ResourceType resourceType) {
        if (resourceType != null) {
            supportedTypes.add(resourceType);
            log.info("Added supported type: {} to crawler: {}", resourceType.id(), getClass().getSimpleName());
        }
    }

    /**
     * Validates credentials for the given account and parameters.
     * Uses the injected CloudAuthenticationService.
     *
     * @param accountId  The account ID.
     * @param parameters Additional parameters (may contain credential info).
     * @return true if credentials are valid, false otherwise.
     */
    public boolean validateCredentials(String accountId, Map<String, String> parameters) {
        if (authenticationService == null) {
            log.error("Authentication service is not initialized.");
            return false;
        }
        try {
            return authenticationService.validateCredentials(accountId, parameters);
        } catch (Exception e) {
            log.error("Credential validation failed for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }

}
