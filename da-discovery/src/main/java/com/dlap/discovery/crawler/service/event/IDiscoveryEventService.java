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

package com.dlap.discovery.crawler.service.event;

import java.util.List;
import java.util.function.Consumer;

import com.dlap.discovery.crawler.service.event.dto.DiscoveryEventDTO;

/**
 * Interface for publishing discovery-related events.
 */
public interface IDiscoveryEventService {
    /**
     * Publishes a discovery event.
     *
     * @param event The event to publish
     */
    void publishEvent(DiscoveryEventDTO event);
    
    /**
     * Subscribes to events of a specific type.
     * @param eventType Type of events to subscribe to
     * @param handler Handler for processing events
     * @return Subscription ID
     */
    String subscribeToEvents(String eventType, Consumer<DiscoveryEventDTO> handler);
    
    /**
     * Unsubscribes from events.
     * @param subscriptionId Subscription ID to unsubscribe
     */
    void unsubscribe(String subscriptionId);
    
    /**
     * Gets all recent events of a specific type.
     * @param eventType Type of events to retrieve
     * @param limit Maximum number of events to return
     * @return List of events
     */
    List<DiscoveryEventDTO> getRecentEvents(String eventType, int limit);
    
    /**
     * Subscribes a listener to all events.
     * 
     * @param listener The listener to subscribe
     */
    void subscribe(DiscoveryEventListener listener);
    
    /**
     * Unsubscribes a listener from all events.
     * 
     * @param listener The listener to unsubscribe
     */
    void unsubscribe(DiscoveryEventListener listener);
} 