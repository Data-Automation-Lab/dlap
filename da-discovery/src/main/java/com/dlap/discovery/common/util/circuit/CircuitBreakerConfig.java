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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for circuit breaker behavior.
 */
public class CircuitBreakerConfig {
    private int failureThreshold;
    private Duration resetTimeout;
    private int successThreshold;
    private List<Class<? extends Throwable>> failureExceptions;
    
    /**
     * Creates a new CircuitBreakerConfig with default values.
     */
    public CircuitBreakerConfig() {
        this.failureThreshold = 5;
        this.resetTimeout = Duration.ofSeconds(30);
        this.successThreshold = 2;
        this.failureExceptions = List.of(Exception.class);
    }
    
    // Getters and setters with builder pattern
    public int getFailureThreshold() {
        return failureThreshold;
    }
    
    public CircuitBreakerConfig failureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
        return this;
    }
    
    public Duration getResetTimeout() {
        return resetTimeout;
    }
    
    public CircuitBreakerConfig resetTimeout(Duration resetTimeout) {
        this.resetTimeout = resetTimeout;
        return this;
    }
    
    public int getSuccessThreshold() {
        return successThreshold;
    }
    
    public CircuitBreakerConfig successThreshold(int successThreshold) {
        this.successThreshold = successThreshold;
        return this;
    }
    
    public List<Class<? extends Throwable>> getFailureExceptions() {
        return failureExceptions;
    }
    
    public CircuitBreakerConfig failureExceptions(Class<? extends Throwable>... exceptions) {
        this.failureExceptions = Arrays.asList(exceptions);
        return this;
    }
    
    /**
     * Checks if an exception counts as a failure.
     * @param exception Exception to check
     * @return true if the exception counts as a failure
     */
    public boolean isFailureException(Throwable exception) {
        if (exception == null) {
            return false;
        }
        
        for (Class<? extends Throwable> clazz : failureExceptions) {
            if (clazz.isInstance(exception)) {
                return true;
            }
        }
        
        return false;
    }
} 