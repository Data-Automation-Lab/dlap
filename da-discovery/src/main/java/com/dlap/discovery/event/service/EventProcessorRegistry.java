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

package com.dlap.discovery.event.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;

import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.event.service.type.ResourceEvent;

import jakarta.annotation.PostConstruct;

/**
 * Registry for event processors.
 * Automatically collects all IEventProcessor beans from the Spring context.
 */
@Service
public class EventProcessorRegistry {
    private static final Logger log = LoggerFactory.getLogger(EventProcessorRegistry.class);

    private final ApplicationContext applicationContext;
    private final List<IEventSubscriber<ResourceEvent>> resourceEventProcessors = new ArrayList<>();
    private final List<IEventSubscriber<LogEvent>> logEventProcessors = new ArrayList<>();

    @Autowired
    public EventProcessorRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Initialize the registry by collecting all IEventProcessor beans.
     */
    @PostConstruct
    public void init() {
        // Find ResourceEvent processors
        ResolvableType resourceEventType = ResolvableType.forClassWithGenerics(IEventSubscriber.class,
                ResourceEvent.class);
        String[] resourceProcessorNames = applicationContext.getBeanNamesForType(resourceEventType);
        for (String name : resourceProcessorNames) {
            @SuppressWarnings("unchecked") // Cast is safe due to getBeanNamesForType check
            IEventSubscriber<ResourceEvent> processor = (IEventSubscriber<ResourceEvent>) applicationContext
                    .getBean(name);
            resourceEventProcessors.add(processor);
            log.info("Registered ResourceEvent processor: {}", processor.getName());
        }

        // Find LogEvent processors
        ResolvableType logEventType = ResolvableType.forClassWithGenerics(IEventSubscriber.class, LogEvent.class);
        String[] logProcessorNames = applicationContext.getBeanNamesForType(logEventType);
        for (String name : logProcessorNames) {
            @SuppressWarnings("unchecked") // Cast is safe due to getBeanNamesForType check
            IEventSubscriber<LogEvent> processor = (IEventSubscriber<LogEvent>) applicationContext.getBean(name);
            logEventProcessors.add(processor);
            log.info("Registered LogEvent processor: {}", processor.getName());
        }

        // Sort processors by priority
        resourceEventProcessors.sort(Comparator.comparingInt(IEventSubscriber::getPriority));
        logEventProcessors.sort(Comparator.comparingInt(IEventSubscriber::getPriority));

        log.info("Initialized EventProcessorRegistry with {} ResourceEvent processors and {} LogEvent processors",
                resourceEventProcessors.size(), logEventProcessors.size());
    }

    /**
     * Gets all processors that can handle the given resource event.
     * 
     * @param event The event to process
     * @return List of matching processors, sorted by priority
     */
    public List<IEventSubscriber<ResourceEvent>> getProcessorsForEvent(ResourceEvent event) {
        return resourceEventProcessors.stream()
                .filter(processor -> processor.canProcess(event))
                .collect(Collectors.toList());
    }

    /**
     * Gets all processors that can handle the given log event.
     * 
     * @param event The event to process
     * @return List of matching processors, sorted by priority
     */
    public List<IEventSubscriber<LogEvent>> getProcessorsForEvent(LogEvent event) {
        return logEventProcessors.stream()
                .filter(processor -> processor.canProcess(event))
                .collect(Collectors.toList());
    }
}