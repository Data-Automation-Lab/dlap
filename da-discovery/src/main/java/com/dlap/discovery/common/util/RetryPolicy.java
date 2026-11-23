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

package com.dlap.discovery.common.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for retry behavior.
 */
public class RetryPolicy {
    private int maxAttempts;
    private Duration initialDelay;
    private double backoffMultiplier;
    private Duration maxDelay;
    private List<Class<? extends Throwable>> retryableExceptions;
    
    /**
     * Creates a new RetryPolicy with default values.
     */
    public RetryPolicy() {
        this.maxAttempts = 3;
        this.initialDelay = Duration.ofMillis(100);
        this.backoffMultiplier = 2.0;
        this.maxDelay = Duration.ofSeconds(30);
        this.retryableExceptions = List.of(Exception.class);
    }
    
    // Getters and setters
    public int getMaxAttempts() {
        return maxAttempts;
    }
    
    public RetryPolicy maxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }
    
    public Duration getInitialDelay() {
        return initialDelay;
    }
    
    public RetryPolicy initialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
        return this;
    }
    
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }
    
    public RetryPolicy backoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
        return this;
    }
    
    public Duration getMaxDelay() {
        return maxDelay;
    }
    
    public RetryPolicy maxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
        return this;
    }
    
    public List<Class<? extends Throwable>> getRetryableExceptions() {
        return retryableExceptions;
    }
    
    public RetryPolicy retryableExceptions(Class<? extends Throwable>... exceptions) {
        this.retryableExceptions = Arrays.asList(exceptions);
        return this;
    }
    
    /**
     * Calculates the delay for a specific retry attempt.
     * @param attempt Current attempt number (0-based)
     * @return Delay duration
     */
    public Duration calculateDelay(int attempt) {
        long delayMillis = (long) (initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt));
        return Duration.ofMillis(Math.min(delayMillis, maxDelay.toMillis()));
    }
    
    /**
     * Checks if an exception is retryable according to this policy.
     * @param exception Exception to check
     * @return true if the exception is retryable
     */
    public boolean isRetryableException(Throwable exception) {
        if (exception == null) {
            return false;
        }
        
        for (Class<? extends Throwable> clazz : retryableExceptions) {
            if (clazz.isInstance(exception)) {
                return true;
            }
        }
        
        return false;
    }
} 