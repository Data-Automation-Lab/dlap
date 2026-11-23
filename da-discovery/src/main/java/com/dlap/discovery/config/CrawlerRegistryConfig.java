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

package com.dlap.discovery.config;

import java.util.List;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;

import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.crawler.service.AbstractResourceCrawler;

@Configuration
@DependsOn("cloudHierarchyRegistry")
public class CrawlerRegistryConfig {
    private static final Logger log = LoggerFactory.getLogger(CrawlerRegistryConfig.class);

    @Autowired
    private IResourceCrawlerRegistry crawlerRegistry;

    @Autowired
    private List<IResourceCrawler<?>> crawlers;

    @Autowired
    private CloudHierarchyRegistry hierarchyRegistry;

    @PostConstruct
    // @EventListener(ApplicationReadyEvent.class)
    public void registerCrawlers() {
        log.info("Starting crawler registration process. Found {} crawlers to register.", crawlers.size());
        
        // Log all available resource types from hierarchy registry
        log.info("Available resource types in hierarchy registry:");
        hierarchyRegistry.getAllResourceTypes().forEach(type -> 
            log.info("ResourceType: {} -> Service: {} -> Provider: {}", 
                type.id(), type.service().id(), type.service().provider())
        );
        
        // Add a small delay to ensure all crawlers are properly initialized
        try {
            Thread.sleep(2000); // Increased delay to ensure initialization
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        for (IResourceCrawler<?> crawler : crawlers) {
            String crawlerName = crawler.getClass().getSimpleName();
            log.info("Registering crawler: {}", crawlerName);
            
            // Force initialization of lazy beans
            if (crawler instanceof AbstractResourceCrawler) {
                try {
                    // This will trigger @PostConstruct if not already called
                    crawler.getSupportedResourceTypes();
                } catch (Exception e) {
                    log.error("Error initializing crawler {}: {}", crawlerName, e.getMessage());
                    continue;
                }
            }
            
            log.info("Crawler supported types: {}", crawler.getSupportedResourceTypes());

            // Do NOT skip here on empty types: a CGLIB-proxied crawler reports an
            // empty list, but registerCrawler falls back to the provider's hierarchy
            // types. Let the registry decide.
            boolean registered = crawlerRegistry.registerCrawler(crawler);
            if (registered) {
                log.info("Successfully registered crawler: {}", crawlerName);
            } else {
                log.warn("Crawler {} registered no types (no hierarchy entry for its provider).", crawlerName);
            }
        }
        
        log.info("Completed crawler registration process.");
    }
}