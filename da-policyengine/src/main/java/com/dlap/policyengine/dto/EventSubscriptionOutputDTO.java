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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;

/**
 * DTO for returning event subscription information.
 */
public class EventSubscriptionOutputDTO {

    private UUID id;
    private String name;
    private String description;
    private UUID userId;
    private EventSubscriptionStatus status;
    private List<EventType> eventTypes;
    private List<EventSeverity> severities;
    private List<String> sourceServices;
    private List<String> assetPrefixes;
    private List<String> labels;
    /** Retired MVEL rules (SEC-04, ADR-0057): always empty. */
    private List<EventRuleOutputDTO> rules;
    private Map<String, Object> notificationConfig;
    private Map<String, Object> actionConfig;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID createdByUserId;
    private UUID updatedByUserId;

    // Statistics
    private Long totalRulesCount;
    private Long enabledRulesCount;
    private Long eventsMatchedCount;
    private Instant lastEventMatchedAt;

    // Constructors
    public EventSubscriptionOutputDTO() {}

    public EventSubscriptionOutputDTO(UUID id, String name, UUID userId, EventSubscriptionStatus status) {
        this.id = id;
        this.name = name;
        this.userId = userId;
        this.status = status;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public EventSubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(EventSubscriptionStatus status) {
        this.status = status;
    }

    public List<EventType> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<EventType> eventTypes) {
        this.eventTypes = eventTypes;
    }

    public List<EventSeverity> getSeverities() {
        return severities;
    }

    public void setSeverities(List<EventSeverity> severities) {
        this.severities = severities;
    }

    public List<String> getSourceServices() {
        return sourceServices;
    }

    public void setSourceServices(List<String> sourceServices) {
        this.sourceServices = sourceServices;
    }

    /**
     * Asset prefix filter.
     *
     * @return the canonical asset URI prefixes, sorted
     */
    public List<String> getAssetPrefixes() {
        return assetPrefixes;
    }

    /**
     * Sets the asset prefix filter.
     *
     * @param assetPrefixes canonical asset URI prefixes
     */
    public void setAssetPrefixes(List<String> assetPrefixes) {
        this.assetPrefixes = assetPrefixes;
    }

    /**
     * Label filter.
     *
     * @return the label names, sorted
     */
    public List<String> getLabels() {
        return labels;
    }

    /**
     * Sets the label filter.
     *
     * @param labels label names
     */
    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    /**
     * Retired MVEL rules (ADR-0057).
     *
     * @return always an empty list on a migrated database
     */
    public List<EventRuleOutputDTO> getRules() {
        return rules;
    }

    public void setRules(List<EventRuleOutputDTO> rules) {
        this.rules = rules;
    }

    public Map<String, Object> getNotificationConfig() {
        return notificationConfig;
    }

    public void setNotificationConfig(Map<String, Object> notificationConfig) {
        this.notificationConfig = notificationConfig;
    }

    public Map<String, Object> getActionConfig() {
        return actionConfig;
    }

    public void setActionConfig(Map<String, Object> actionConfig) {
        this.actionConfig = actionConfig;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public void setUpdatedByUserId(UUID updatedByUserId) {
        this.updatedByUserId = updatedByUserId;
    }

    public Long getTotalRulesCount() {
        return totalRulesCount;
    }

    public void setTotalRulesCount(Long totalRulesCount) {
        this.totalRulesCount = totalRulesCount;
    }

    public Long getEnabledRulesCount() {
        return enabledRulesCount;
    }

    public void setEnabledRulesCount(Long enabledRulesCount) {
        this.enabledRulesCount = enabledRulesCount;
    }

    public Long getEventsMatchedCount() {
        return eventsMatchedCount;
    }

    public void setEventsMatchedCount(Long eventsMatchedCount) {
        this.eventsMatchedCount = eventsMatchedCount;
    }

    public Instant getLastEventMatchedAt() {
        return lastEventMatchedAt;
    }

    public void setLastEventMatchedAt(Instant lastEventMatchedAt) {
        this.lastEventMatchedAt = lastEventMatchedAt;
    }

    @Override
    public String toString() {
        return "EventSubscriptionOutputDTO{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", userId=" + userId +
                ", status=" + status +
                ", eventTypes=" + eventTypes +
                ", severities=" + severities +
                ", rulesCount=" + totalRulesCount +
                ", eventsMatchedCount=" + eventsMatchedCount +
                '}';
    }

    /**
     * A retired subscription rule (SEC-04, ADR-0057), kept for wire compatibility.
     */
    public static class EventRuleOutputDTO {
        private UUID id;
        private String name;
        private String description;
        private String condition;
        private Integer priority;
        private Boolean enabled;
        private Map<String, Object> parameters;
        private Instant createdAt;
        private Instant updatedAt;
        private UUID createdByUserId;
        private UUID updatedByUserId;

        // Constructors
        public EventRuleOutputDTO() {}

        public EventRuleOutputDTO(UUID id, String name, String condition) {
            this.id = id;
            this.name = name;
            this.condition = condition;
        }

        // Getters and Setters

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public Integer getPriority() {
            return priority;
        }

        public void setPriority(Integer priority) {
            this.priority = priority;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public UUID getCreatedByUserId() {
            return createdByUserId;
        }

        public void setCreatedByUserId(UUID createdByUserId) {
            this.createdByUserId = createdByUserId;
        }

        public UUID getUpdatedByUserId() {
            return updatedByUserId;
        }

        public void setUpdatedByUserId(UUID updatedByUserId) {
            this.updatedByUserId = updatedByUserId;
        }

        @Override
        public String toString() {
            return "EventRuleOutputDTO{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", condition='" + condition + '\'' +
                    ", priority=" + priority +
                    ", enabled=" + enabled +
                    '}';
        }
    }
} 