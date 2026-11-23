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

package com.dlap.discovery.common.service;

import java.time.Duration;

/**
 * Service interface for rate limiting and throttling operations.
 */
public interface ThrottlingService {
    /**
     * Acquires a permit for the specified service type.
     * @param serviceType Type of service requiring throttling
     * @throws ThrottlingException if permit cannot be acquired
     */
    void acquirePermit(String serviceType);
    
    /**
     * Checks if a service is currently rate limited.
     * @param serviceType Type of service to check
     * @return true if rate limited
     */
    boolean isRateLimited(String serviceType);
    
    /**
     * Releases a previously acquired permit.
     * @param serviceType Type of service
     */
    void releasePermit(String serviceType);
    
    /**
     * Configures rate limits for a service type.
     * @param serviceType Type of service
     * @param maxRequests Maximum requests allowed
     * @param duration Time window for the rate limit
     */
    void configureRateLimit(String serviceType, int maxRequests, Duration duration);
} 