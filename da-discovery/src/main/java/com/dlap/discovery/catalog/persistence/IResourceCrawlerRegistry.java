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

import java.util.Collection;
import java.util.List;

import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Registry for managing and retrieving resource crawlers.
 */
public interface IResourceCrawlerRegistry {

    /**
     * Gets crawlers for a specific cloud provider.
     * 
     * @param provider The cloud provider
     * @return Collection of resource crawlers for the provider
     */
    Collection<IResourceCrawler<CloudResource>> getCrawlersForProvider(CloudProvider provider);

    /**
     * Gets crawlers for specific resource types and cloud provider.
     * 
     * @param provider      The cloud provider
     * @param resourceTypes The resource types to get crawlers for
     * @return Collection of resource crawlers for the specified types
     */
    Collection<IResourceCrawler<CloudResource>> getCrawlersForTypes(CloudProvider provider,
            List<ResourceType> resourceTypes);

    /**
     * Registers a new resource crawler.
     *
     * @param resourceCrawler The crawler to register
     * @return true if registration was successful
     */
    boolean registerCrawler(IResourceCrawler<?> resourceCrawler);

    /**
     * Unregisters an existing resource crawler.
     *
     * @param resourceCrawler The crawler to unregister
     * @return true if unregistration was successful
     */
    boolean unregisterCrawler(IResourceCrawler<?> resourceCrawler);

    /**
     * Gets a crawler for a specific resource type.
     *
     * @param resourceType The resource type
     * @return The crawler that can handle the type, or null if none found
     */
    IResourceCrawler<?> getCrawler(ResourceType resourceType);

    /**
     * Gets crawlers for a specific cloud service.
     *
     * @param service The cloud service
     * @return List of resource crawlers for the service
     */
    List<IResourceCrawler<?>> getCrawlers(CloudService service);

    /**
     * Gets crawlers for a specific cloud provider (legacy method).
     *
     * @param provider The cloud provider
     * @return List of resource crawlers for the provider
     */
    List<IResourceCrawler<?>> getCrawlers(CloudProvider provider);

    /**
     * Gets all registered crawlers.
     *
     * @return List of all resource crawlers
     */
    List<IResourceCrawler<?>> getAllCrawlers();
}