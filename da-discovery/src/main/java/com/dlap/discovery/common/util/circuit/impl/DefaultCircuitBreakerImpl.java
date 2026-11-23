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

package com.dlap.discovery.common.util.circuit.impl;

import com.dlap.discovery.common.util.circuit.CircuitBreaker;
import com.dlap.discovery.common.util.circuit.CircuitBreakerConfig;
import com.dlap.discovery.common.util.circuit.CircuitBreakerStatus;
import com.dlap.discovery.common.util.circuit.CircuitOpenException;
import com.dlap.discovery.crawler.service.event.IDiscoveryEventService;
import com.dlap.discovery.crawler.service.event.dto.DiscoveryEventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Default implementation of the CircuitBreaker.
 */
@Service
public class DefaultCircuitBreakerImpl implements CircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(DefaultCircuitBreakerImpl.class);
    
    private static class CircuitState {
        private CircuitBreakerStatus.State state;
        private int failureCount;
        private int successCount;
        private ZonedDateTime lastStateChange;
        private ZonedDateTime lastFailure;
        private CircuitBreakerConfig config;
        
        public CircuitState(CircuitBreakerConfig config) {
            this.state = CircuitBreakerStatus.State.CLOSED;
            this.failureCount = 0;
            this.successCount = 0;
            this.lastStateChange = ZonedDateTime.now();
            this.config = config != null ? config : new CircuitBreakerConfig();
        }
        
        public synchronized CircuitBreakerStatus getStatus(String serviceId) {
            return new CircuitBreakerStatus(
                serviceId, state, failureCount, successCount,
                lastStateChange, lastFailure
            );
        }
        
        public synchronized void recordSuccess() {
            if (state == CircuitBreakerStatus.State.HALF_OPEN) {
                successCount++;
                if (successCount >= config.getSuccessThreshold()) {
                    state = CircuitBreakerStatus.State.CLOSED;
                    failureCount = 0;
                    successCount = 0;
                    lastStateChange = ZonedDateTime.now();
                }
            } else if (state == CircuitBreakerStatus.State.CLOSED) {
                failureCount = 0;
            }
        }
        
        public synchronized void recordFailure() {
            lastFailure = ZonedDateTime.now();
            
            if (state == CircuitBreakerStatus.State.HALF_OPEN) {
                state = CircuitBreakerStatus.State.OPEN;
                lastStateChange = ZonedDateTime.now();
            } else if (state == CircuitBreakerStatus.State.CLOSED) {
                failureCount++;
                if (failureCount >= config.getFailureThreshold()) {
                    state = CircuitBreakerStatus.State.OPEN;
                    lastStateChange = ZonedDateTime.now();
                }
            }
        }
        
        public synchronized boolean allowRequest() {
            if (state == CircuitBreakerStatus.State.CLOSED) {
                return true;
            } else if (state == CircuitBreakerStatus.State.OPEN) {
                ZonedDateTime now = ZonedDateTime.now();
                if (lastStateChange.plus(config.getResetTimeout()).isBefore(now)) {
                    state = CircuitBreakerStatus.State.HALF_OPEN;
                    successCount = 0;
                    lastStateChange = now;
                    return true;
                }
                return false;
            } else { // HALF_OPEN
                return true;
            }
        }
        
        public synchronized void updateConfig(CircuitBreakerConfig config) {
            if (config != null) {
                this.config = config;
            }
        }
    }
    
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    private final IDiscoveryEventService eventService;
    
    public DefaultCircuitBreakerImpl(IDiscoveryEventService eventService) {
        this.eventService = eventService;
    }
    
    @Override
    public <T> T executeWithCircuitBreaker(String service, Supplier<T> operation) {
        if (service == null || operation == null) {
            throw new IllegalArgumentException("Service and operation must not be null");
        }
        
        CircuitState circuitState = getOrCreateCircuitState(service);
        
        if (!circuitState.allowRequest()) {
            log.debug("Circuit is open for service: {}", service);
            throw new CircuitOpenException(service);
        }
        
        try {
            T result = operation.get();
            circuitState.recordSuccess();
            return result;
        } catch (Throwable e) {
            CircuitBreakerConfig config = circuitState.config;
            if (config.isFailureException(e)) {
                circuitState.recordFailure();
                
                // Emit circuit breaker event if state changed
                CircuitBreakerStatus status = circuitState.getStatus(service);
                if (status.getState() == CircuitBreakerStatus.State.OPEN && 
                    status.getFailureCount() == config.getFailureThreshold()) {
                    emitCircuitOpenEvent(service, status);
                }
            }
            
            throw e;
        }
    }
    
    @Override
    public CircuitBreakerStatus getStatus(String service) {
        if (service == null) {
            throw new IllegalArgumentException("Service must not be null");
        }
        
        CircuitState circuitState = circuits.get(service);
        if (circuitState == null) {
            return new CircuitBreakerStatus(
                service, CircuitBreakerStatus.State.CLOSED, 0, 0,
                ZonedDateTime.now(), null
            );
        }
        
        return circuitState.getStatus(service);
    }
    
    @Override
    public void resetCircuit(String service) {
        if (service == null) {
            return;
        }
        
        log.info("Manually resetting circuit for service: {}", service);
        CircuitState oldState = circuits.remove(service);
        
        if (oldState != null) {
            // Keep the existing config
            CircuitState newState = new CircuitState(oldState.config);
            circuits.put(service, newState);
            
            // Emit circuit reset event
            emitCircuitResetEvent(service);
        }
    }
    
    /**
     * Configures a circuit breaker for a service.
     * @param service Service identifier
     * @param config Circuit breaker configuration
     */
    public void configureCircuitBreaker(String service, CircuitBreakerConfig config) {
        if (service == null || config == null) {
            return;
        }
        
        log.info("Configuring circuit breaker for service: {}", service);
        
        CircuitState circuitState = getOrCreateCircuitState(service);
        circuitState.updateConfig(config);
    }
    
    /**
     * Gets or creates a circuit state for a service.
     * @param service Service identifier
     * @return Circuit state
     */
    private CircuitState getOrCreateCircuitState(String service) {
        return circuits.computeIfAbsent(service, s -> new CircuitState(new CircuitBreakerConfig()));
    }
    
    /**
     * Emits an event when a circuit opens.
     * @param service Service identifier
     * @param status Circuit status
     */
    private void emitCircuitOpenEvent(String service, CircuitBreakerStatus status) {
        DiscoveryEventDTO event = new DiscoveryEventDTO();
        event.setEventType("circuit.open");
        event.setResourceId(service);
        event.setSeverity(DiscoveryEventDTO.EventSeverity.ERROR);
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("service", service);
        payload.put("failureCount", status.getFailureCount());
        payload.put("lastFailure", status.getLastFailure());
        event.setPayload(payload);
        
        eventService.publishEvent(event);
    }
    
    /**
     * Emits an event when a circuit is reset.
     * @param service Service identifier
     */
    private void emitCircuitResetEvent(String service) {
        DiscoveryEventDTO event = new DiscoveryEventDTO();
        event.setEventType("circuit.reset");
        event.setResourceId(service);
        event.setSeverity(DiscoveryEventDTO.EventSeverity.INFO);
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("service", service);
        payload.put("resetTime", ZonedDateTime.now());
        event.setPayload(payload);
        
        eventService.publishEvent(event);
    }
} 