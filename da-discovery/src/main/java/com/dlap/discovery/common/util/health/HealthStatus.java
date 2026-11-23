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

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Class representing the health status of a service.
 */
public class HealthStatus {
    public enum Status {
        UP, DOWN, DEGRADED, UNKNOWN
    }
    
    private String serviceId;
    private String displayName;
    private Status status;
    private ZonedDateTime timestamp;
    private String message;
    private Map<String, Object> details = new HashMap<>();
    
    // Constructor
    public HealthStatus(String serviceId, Status status) {
        this.serviceId = serviceId;
        this.status = status;
        this.timestamp = ZonedDateTime.now();
    }
    
    // Getters and setters
    public String getServiceId() {
        return serviceId;
    }
    
    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
    
    public ZonedDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public Map<String, Object> getDetails() {
        return details;
    }
    
    public void setDetails(Map<String, Object> details) {
        this.details = details != null ? details : new HashMap<>();
    }
    
    /**
     * Adds a detail to the health status.
     * @param key Detail key
     * @param value Detail value
     * @return This HealthStatus for method chaining
     */
    public HealthStatus withDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }
    
    /**
     * Sets the message for this health status.
     * @param message Status message
     * @return This HealthStatus for method chaining
     */
    public HealthStatus withMessage(String message) {
        this.message = message;
        return this;
    }
    
    /**
     * Sets the display name for this health status.
     * @param displayName Display name
     * @return This HealthStatus for method chaining
     */
    public HealthStatus withDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }
    
    /**
     * Updates the details with a map of values.
     * @param details Details to add
     * @return This HealthStatus for method chaining
     */
    public HealthStatus withDetails(Map<String, Object> details) {
        if (details != null) {
            this.details.putAll(details);
        }
        return this;
    }
    
    // Factory methods
    public static HealthStatus up(String serviceId) {
        return new HealthStatus(serviceId, Status.UP);
    }
    
    public static HealthStatus down(String serviceId) {
        return new HealthStatus(serviceId, Status.DOWN);
    }
    
    public static HealthStatus degraded(String serviceId) {
        return new HealthStatus(serviceId, Status.DEGRADED);
    }
    
    public static HealthStatus unknown(String serviceId) {
        return new HealthStatus(serviceId, Status.UNKNOWN);
    }
} 