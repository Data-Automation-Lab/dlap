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

package com.dlap.discovery.crawler.service.event.impl;

import com.dlap.discovery.crawler.service.event.DiscoveryEventListener;
import com.dlap.discovery.crawler.service.event.IDiscoveryEventService;
import com.dlap.discovery.crawler.service.event.dto.DiscoveryEventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Default implementation of IDiscoveryEventService using in-memory storage.
 * TODO: Modify publishEvent to send to Kafka.
 */
@Service
public class DefaultDiscoveryEventServiceImpl implements IDiscoveryEventService {
    private static final Logger log = LoggerFactory.getLogger(DefaultDiscoveryEventServiceImpl.class);
    
    // Map of event type to handlers
    private final Map<String, Map<String, Consumer<DiscoveryEventDTO>>> subscribers = new ConcurrentHashMap<>();
    
    // Event history for each event type
    private final Map<String, Deque<DiscoveryEventDTO>> eventHistory = new ConcurrentHashMap<>();
    
    // Executor for async event handling
    private final ExecutorService executorService;
    
    // Maximum history size
    @Value("${discovery.event.history.max-size:1000}")
    private int maxHistorySize;

    @Value("${discovery.event.history.max-age-minutes:1440}") // Default to 24 hours
    private long maxHistoryAgeMinutes;

    private final Object historyLock = new Object();
    
    private final List<DiscoveryEventListener> listeners = new CopyOnWriteArrayList<>();
    
    // --- Kafka Integration (Placeholder) ---
    @Autowired(required = false) // Make optional for environments without Kafka configured
    private KafkaTemplate<String, DiscoveryEventDTO> kafkaTemplate;

    @Value("${discovery.event.kafka.topic:discovery-events}") // Default topic name
    private String kafkaTopic;
    // --- End Kafka Integration Placeholder ---
    
    /**
     * Constructor creating a cached thread pool for async processing.
     */
    public DefaultDiscoveryEventServiceImpl() {
        this.executorService = Executors.newCachedThreadPool();
    }
    
    @Override
    public void publishEvent(DiscoveryEventDTO event) {
        if (event == null || event.getEventType() == null) {
            log.warn("Attempted to publish a null event or event with null type.");
            return;
        }
        
        String eventIdStr = event.getEventId().toString();
        log.debug("Publishing event: Type={}, ID={}, ResourceId={}", 
                event.getEventType(), eventIdStr, event.getResourceId());
        
        // --- Publish to Kafka --- 
        if (kafkaTemplate != null) {
            try {
                // Use resourceId or eventId as Kafka key? EventId seems more unique.
                kafkaTemplate.send(kafkaTopic, eventIdStr, event);
                 log.debug("Submitted event {} to Kafka topic {}", eventIdStr, kafkaTopic);
            } catch (Exception e) {
                // Log error but don't stop other processing (like internal listeners)
                log.error("Failed to publish event {} to Kafka topic {}: {}", 
                         eventIdStr, kafkaTopic, e.getMessage(), e);
            }
        } else {
             // Log only once or based on configuration to avoid flooding logs
             log.trace("KafkaTemplate not configured. Skipping Kafka publish for event {}", eventIdStr);
        }
        // --- End Kafka Publish ---

        // Store event in history (remains useful for recent event queries)
        storeEventInHistory(event);
        
        // Notify internal subscribers asynchronously (Keep for internal use?)
        notifyInternalSubscribers(event);

        // Notify global internal listeners asynchronously (Keep for internal use?)
        notifyGlobalListeners(event);
    }
    
    @Override
    public void subscribe(DiscoveryEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            log.info("Global internal listener subscribed: {}", listener.getClass().getSimpleName());
            listeners.add(listener);
        }
    }
    
    @Override
    public void unsubscribe(DiscoveryEventListener listener) {
        if (listener != null) {
            boolean removed = listeners.remove(listener);
            if (removed) {
                log.info("Global internal listener unsubscribed: {}", listener.getClass().getSimpleName());
            } else {
                 log.warn("Attempted to unsubscribe a non-subscribed global internal listener: {}", listener.getClass().getSimpleName());
            }
        }
    }
    
    @Override
    public String subscribeToEvents(String eventType, Consumer<DiscoveryEventDTO> handler) {
        if (eventType == null || eventType.isBlank() || handler == null) {
            throw new IllegalArgumentException("Event type and handler cannot be null or blank.");
        }
        
        String subscriptionId = UUID.randomUUID().toString();
        subscribers.computeIfAbsent(eventType, k -> new ConcurrentHashMap<>()).put(subscriptionId, handler);
        log.info("Internal subscription {} created for event type {}", subscriptionId, eventType);
        return subscriptionId;
    }
    
    @Override
    public void unsubscribe(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return;
        }
        
        boolean removed = false;
        for (Map<String, Consumer<DiscoveryEventDTO>> typeSubscribers : subscribers.values()) {
            if (typeSubscribers.remove(subscriptionId) != null) {
                removed = true;
                break;
            }
        }
        if (removed) {
            log.info("Internal subscription unsubscribed ID: {}", subscriptionId);
        } else {
            log.warn("Attempted to unsubscribe non-existent internal subscription ID: {}", subscriptionId);
        }
    }
    
    @Override
    public List<DiscoveryEventDTO> getRecentEvents(String eventType, int limit) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Event type cannot be null or blank.");
        }
        
        int actualLimit = Math.max(1, Math.min(limit, maxHistorySize));
        
        synchronized (historyLock) {
            Deque<DiscoveryEventDTO> history = eventHistory.getOrDefault(eventType, new LinkedList<>());
            if (history.isEmpty()) {
                return Collections.emptyList();
            }
            
            List<DiscoveryEventDTO> result = new ArrayList<>(actualLimit);
            Iterator<DiscoveryEventDTO> iterator = history.iterator();
            int count = 0;
            while (iterator.hasNext() && count < actualLimit) {
                result.add(iterator.next());
                count++;
            }
            return result;
        }
    }
    
    /**
     * Gets all event types that have been published.
     * 
     * @return Set of event types
     */
    public Set<String> getAvailableEventTypes() {
        return new HashSet<>(eventHistory.keySet());
    }
    
    /**
     * Clears history for a specific event type.
     * 
     * @param eventType Event type to clear history for
     */
    public void clearEventHistory(String eventType) {
        if (eventType != null) {
            eventHistory.remove(eventType);
            log.debug("Cleared event history for type: {}", eventType);
        }
    }
    
    /**
     * Clears all event history.
     */
    public void clearAllEventHistory() {
        eventHistory.clear();
        log.debug("Cleared all event history");
    }
    
    /**
     * Stores an event in the history queue.
     * @param event Event to store
     */
    private void storeEventInHistory(DiscoveryEventDTO event) {
        String eventType = event.getEventType();
        
        synchronized (historyLock) {
            Deque<DiscoveryEventDTO> history = eventHistory.computeIfAbsent(
                    eventType, 
                    k -> new LinkedBlockingDeque<>(maxHistorySize)
            );
            
            if (!history.offerFirst(event)) {
                history.pollLast();
                history.offerFirst(event);
            }
        }
    }
    
    // Method to notify internal specific subscribers
    private void notifyInternalSubscribers(DiscoveryEventDTO event) {
        Map<String, Consumer<DiscoveryEventDTO>> typeSubscribers = subscribers.get(event.getEventType());
        if (typeSubscribers != null && !typeSubscribers.isEmpty()) {
            log.debug("Notifying {} specific internal subscribers asynchronously for event type {}", typeSubscribers.size(), event.getEventType());
            for (Map.Entry<String, Consumer<DiscoveryEventDTO>> entry : typeSubscribers.entrySet()) {
                final String subscriptionId = entry.getKey();
                final Consumer<DiscoveryEventDTO> handler = entry.getValue();
                executorService.submit(() -> {
                     try {
                        handler.accept(event);
                    } catch (Exception e) {
                        log.error("Error notifying internal subscriber {} for event type {}: {}", 
                                subscriptionId, event.getEventType(), e.getMessage(), e);
                    }
                });
            }
        }
    }
    
    // Method to notify internal global listeners
    private void notifyGlobalListeners(DiscoveryEventDTO event) {
         if (!listeners.isEmpty()) {
            log.debug("Notifying {} global internal listeners asynchronously", listeners.size());
            for (DiscoveryEventListener listener : listeners) {
                 executorService.submit(() -> {
                    try {
                        listener.onEvent(event);
                    } catch (Exception e) {
                        log.error("Error notifying global internal listener {}: {}", 
                                listener.getClass().getSimpleName(), e.getMessage(), e);
                    }
                });
            }
        }
    }
    
    /**
     * Shuts down the event service and its executor service.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down event service executor...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Event service executor shut down.");
    }
} 