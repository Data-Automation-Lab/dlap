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

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.IEventPublisher;

/**
 * Interface for resource crawler implementations.
 * Resource crawlers discover cloud resources of specific types asynchronously
 * and publish events.
 *
 * @param <T> The type of resource this crawler handles
 */
public interface IResourceCrawler<T extends CloudResource> extends IEventPublisher {

    /**
     * Gets the resource types this crawler can handle.
     *
     * @return List of supported resource types
     */
    List<ResourceType> getSupportedResourceTypes();

    /**
     * Prepares the crawler for discovery based on the job configuration.
     * This might involve setting up context, validating parameters, etc.
     * This method might be removed if configuration is handled elsewhere.
     *
     * @param job The DiscoveryJob entity providing context and parameters.
     */
    void prepareDiscovery(DiscoveryJob job);

    /**
     * Initiates the asynchronous discovery of resources for the specified
     * account/project.
     * Implementations should perform discovery in the background and publish events
     * (e.g., to Kafka) for found resources.
     *
     * @param accountId The account/project ID
     * @param context   The discovery context (potentially prepared by
     *                  prepareDiscovery)
     */
    void discoverResourcesAsync(String accountId, Map<String, Object> context);

    /**
     * Gets the cloud provider this crawler supports.
     * 
     * @return The CloudProvider enum.
     */
    CloudProvider getProvider();
}