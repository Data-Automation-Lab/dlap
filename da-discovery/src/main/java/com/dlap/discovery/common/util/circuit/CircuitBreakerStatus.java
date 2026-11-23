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

package com.dlap.discovery.common.util.circuit;

import java.time.ZonedDateTime;

/**
 * Status of a circuit breaker.
 */
public class CircuitBreakerStatus {
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }
    
    private final String service;
    private final State state;
    private final int failureCount;
    private final int successCount;
    private final ZonedDateTime lastStateChange;
    private final ZonedDateTime lastFailure;
    
    public CircuitBreakerStatus(
        String service, 
        State state, 
        int failureCount, 
        int successCount, 
        ZonedDateTime lastStateChange, 
        ZonedDateTime lastFailure
    ) {
        this.service = service;
        this.state = state;
        this.failureCount = failureCount;
        this.successCount = successCount;
        this.lastStateChange = lastStateChange;
        this.lastFailure = lastFailure;
    }
    
    // Constructor for simplified instantiation with minimal args
    public CircuitBreakerStatus(String service, State state, int failureCount) {
        this(service, state, failureCount, 0, ZonedDateTime.now(), null);
    }
    
    // Getters
    public String getService() {
        return service;
    }
    
    public State getState() {
        return state;
    }
    
    public int getFailureCount() {
        return failureCount;
    }
    
    public int getSuccessCount() {
        return successCount;
    }
    
    public ZonedDateTime getLastStateChange() {
        return lastStateChange;
    }
    
    public ZonedDateTime getLastFailure() {
        return lastFailure;
    }
    
    @Override
    public String toString() {
        return String.format(
            "CircuitBreakerStatus{service='%s', state=%s, failureCount=%d, successCount=%d}", 
            service, state, failureCount, successCount);
    }
} 