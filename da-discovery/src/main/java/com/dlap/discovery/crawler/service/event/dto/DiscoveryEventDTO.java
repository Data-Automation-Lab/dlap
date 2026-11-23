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

package com.dlap.discovery.crawler.service.event.dto;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object for crawler events.
 */
public class DiscoveryEventDTO {
    private UUID eventId;
    private String eventType;
    private ZonedDateTime timestamp;
    private String resourceId;
    private EventSeverity severity;
    private Map<String, Object> payload;
    
    /**
     * Severity levels for events.
     */
    public enum EventSeverity {
        INFO, WARN, ERROR, CRITICAL
    }
    
    /**
     * Creates a new event with default values.
     */
    public DiscoveryEventDTO() {
        this.eventId = UUID.randomUUID();
        this.timestamp = ZonedDateTime.now();
    }
    
    // Getters and setters
    public UUID getEventId() {
        return eventId;
    }
    
    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public ZonedDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public EventSeverity getSeverity() {
        return severity;
    }
    
    public void setSeverity(EventSeverity severity) {
        this.severity = severity;
    }
    
    public Map<String, Object> getPayload() {
        return payload;
    }
    
    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
    
    @Override
    public String toString() {
        return String.format("DiscoveryEventDTO{eventId='%s', eventType='%s', severity=%s}", 
            eventId, eventType, severity);
    }
} 