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

package com.dlap.discovery.common.util.health;

import java.util.List;
import java.util.function.Supplier;

/**
 * Service interface for managing and performing health checks.
 */
public interface IHealthCheckService {
    /**
     * Checks if a specific service is healthy.
     * @param serviceId ID of the service to check
     * @return true if the service is healthy
     */
    boolean isServiceHealthy(String serviceId);
    
    /**
     * Gets the health status of all registered services.
     * @return List of health status results
     */
    List<HealthStatus> getSystemStatus();
    
    /**
     * Registers a health check for a service.
     * @param serviceId ID of the service
     * @param displayName User-friendly name of the service
     * @param checker Function that performs the health check
     */
    void registerHealthCheck(String serviceId, String displayName, Supplier<HealthStatus> checker);
    
    /**
     * Checks the health of a specified service.
     * 
     * @param serviceName the name of the service to check
     * @return the health status of the service
     */
    HealthStatus checkServiceHealth(String serviceName);
} 