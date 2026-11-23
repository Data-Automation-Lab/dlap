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

package com.dlap.policyengine.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;

/**
 * DTO for streaming events in real-time to the Event Center UI.
 */
public class EventStreamDTO {

    private UUID eventId;
    private EventType eventType;
    private EventSeverity severity;
    private String sourceService;
    private String title;
    private String description;
    private UUID assetId;
    private String assetName;
    private UUID policyId;
    private String policyName;
    private Map<String, Object> eventData;
    private Map<String, Object> metadata;
    private Instant timestamp;
    private UUID userId;
    private String userAction;

    // Event processing information
    private Boolean isProcessed;
    private Integer matchingSubscriptionsCount;
    private Boolean notificationSent;
    private Boolean actionTriggered;

    // Constructors
    public EventStreamDTO() {}

    public EventStreamDTO(UUID eventId, EventType eventType, EventSeverity severity, String sourceService) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.severity = severity;
        this.sourceService = sourceService;
        this.timestamp = Instant.now();
    }

    // Getters and Setters

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public EventSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(EventSeverity severity) {
        this.severity = severity;
    }

    public String getSourceService() {
        return sourceService;
    }

    public void setSourceService(String sourceService) {
        this.sourceService = sourceService;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public void setPolicyId(UUID policyId) {
        this.policyId = policyId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public Map<String, Object> getEventData() {
        return eventData;
    }

    public void setEventData(Map<String, Object> eventData) {
        this.eventData = eventData;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getUserAction() {
        return userAction;
    }

    public void setUserAction(String userAction) {
        this.userAction = userAction;
    }

    public Boolean getIsProcessed() {
        return isProcessed;
    }

    public void setIsProcessed(Boolean isProcessed) {
        this.isProcessed = isProcessed;
    }

    public Integer getMatchingSubscriptionsCount() {
        return matchingSubscriptionsCount;
    }

    public void setMatchingSubscriptionsCount(Integer matchingSubscriptionsCount) {
        this.matchingSubscriptionsCount = matchingSubscriptionsCount;
    }

    public Boolean getNotificationSent() {
        return notificationSent;
    }

    public void setNotificationSent(Boolean notificationSent) {
        this.notificationSent = notificationSent;
    }

    public Boolean getActionTriggered() {
        return actionTriggered;
    }

    public void setActionTriggered(Boolean actionTriggered) {
        this.actionTriggered = actionTriggered;
    }

    @Override
    public String toString() {
        return "EventStreamDTO{" +
                "eventId=" + eventId +
                ", eventType=" + eventType +
                ", severity=" + severity +
                ", sourceService='" + sourceService + '\'' +
                ", title='" + title + '\'' +
                ", assetId=" + assetId +
                ", policyId=" + policyId +
                ", timestamp=" + timestamp +
                ", isProcessed=" + isProcessed +
                '}';
    }
} 