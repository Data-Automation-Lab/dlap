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

package com.dlap.discovery.common.util.health.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.common.util.health.IHealthCheckService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default implementation of the {@link IHealthCheckService} interface.
 */
@Service
public class DefaultHealthCheckServiceImpl implements IHealthCheckService {
    private static final Logger log = LoggerFactory.getLogger(DefaultHealthCheckServiceImpl.class);
    
    /**
     * Record representing a health check registration.
     */
    private record HealthCheck(String displayName, Supplier<HealthStatus> checkSupplier) {}
    
    private final Map<String, HealthCheck> healthChecks = new ConcurrentHashMap<>();
    
    @Override
    public void registerHealthCheck(String serviceName, String displayName, Supplier<HealthStatus> checkSupplier) {
        if (serviceName == null || displayName == null || checkSupplier == null) {
            throw new IllegalArgumentException("Service name, display name, and check supplier must not be null");
        }
        log.info("Registering health check: {} ({})", displayName, serviceName);
        healthChecks.put(serviceName, new HealthCheck(displayName, checkSupplier));
    }
    
    @Override
    public boolean isServiceHealthy(String serviceName) {
        HealthCheck check = healthChecks.get(serviceName);
        if (check == null) {
            return false; // Or throw exception?
        }
        try {
            return check.checkSupplier().get().getStatus() == HealthStatus.Status.UP;
        } catch (Exception e) {
            log.error("Error executing health check for service {}: {}", serviceName, e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public HealthStatus checkServiceHealth(String serviceName) {
        HealthCheck check = healthChecks.get(serviceName);
        if (check == null) {
            return HealthStatus.unknown(serviceName).withMessage("Health check not registered.");
        }
        try {
            return check.checkSupplier().get();
        } catch (Exception e) {
            log.error("Error executing health check for service {}: {}", serviceName, e.getMessage(), e);
            return HealthStatus.down(serviceName)
                               .withDisplayName(check.displayName())
                               .withMessage("Error during check: " + e.getMessage());
        }
    }
    
    @Override
    public List<HealthStatus> getSystemStatus() {
        return healthChecks.entrySet().stream()
            .map(entry -> checkServiceHealth(entry.getKey()))
            .collect(Collectors.toList());
    }
} 