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

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.dlap.policyengine.filter.RetiredRules;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;

/**
 * DTO for creating or updating event subscriptions.
 *
 * <p>Events are selected with the typed filter (SEC-04, ADR-0057): {@code eventTypes},
 * {@code severities}, {@code sourceServices}, {@code assetPrefixes} and {@code labels}.
 * An empty or absent list does not constrain; all present lists must hold.
 */
public class EventSubscriptionInputDTO {

    @NotBlank(message = "Subscription name is required")
    @Size(max = 255, message = "Subscription name must not exceed 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    /**
     * Event types to subscribe to
     */
    private List<EventType> eventTypes;

    /**
     * Event severity levels to include
     */
    private List<EventSeverity> severities;

    /**
     * Source services to monitor
     */
    private List<String> sourceServices;

    /**
     * Canonical asset URI prefixes (docs/specs/asset-id.md), for example
     * {@code s3://finance-bucket/} or {@code bq://my-project/sales}. An event matches when one
     * of its asset URIs starts with one of these, as plain string prefixes.
     */
    private List<String> assetPrefixes;

    /**
     * Label names; an event matches when it names at least one of them (compared without case).
     */
    private List<String> labels;

    /**
     * Retired MVEL rule conditions (SEC-04, ADR-0057). Kept on the wire contract only so
     * that a client that still sends rules gets a clear HTTP 400 (bean validation runs
     * before the handler's {@code @PreAuthorize}, so every role gets the same answer).
     * Null or empty is accepted.
     */
    @Size(max = 0, message = RetiredRules.SUBSCRIPTION_RULES_MESSAGE)
    private List<EventRuleInputDTO> rules;

    /**
     * Notification configuration
     * Example: { "email": true, "slack": { "channel": "#alerts", "webhook": "https://..." } }
     */
    private Map<String, Object> notificationConfig;

    /**
     * Action configuration for automated responses
     * Example: { "autoQuarantine": true, "escalateTo": "admin", "maxRetries": 3 }
     */
    private Map<String, Object> actionConfig;

    // Constructors
    public EventSubscriptionInputDTO() {}

    public EventSubscriptionInputDTO(String name) {
        this.name = name;
    }

    // Getters and Setters

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
     * @return the canonical asset URI prefixes, or {@code null}
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
     * @return the label names, or {@code null}
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
     * Retired MVEL rules (ADR-0057); any entry is refused with HTTP 400.
     *
     * @return the rules sent by the client, or {@code null}
     */
    public List<EventRuleInputDTO> getRules() {
        return rules;
    }

    public void setRules(List<EventRuleInputDTO> rules) {
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

    @Override
    public String toString() {
        return "EventSubscriptionInputDTO{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", eventTypes=" + eventTypes +
                ", severities=" + severities +
                ", sourceServices=" + sourceServices +
                ", assetPrefixes=" + assetPrefixes +
                ", labels=" + labels +
                ", rulesCount=" + (rules != null ? rules.size() : 0) +
                '}';
    }

    /**
     * A retired subscription rule (SEC-04, ADR-0057). Its condition was MVEL, which is
     * executable code. Kept so that old clients deserialize and get HTTP 400, not a
     * silently ignored field.
     */
    public static class EventRuleInputDTO {
        @NotBlank(message = "Rule name is required")
        @Size(max = 255, message = "Rule name must not exceed 255 characters")
        private String name;

        @Size(max = 500, message = "Rule description must not exceed 500 characters")
        private String description;

        @NotBlank(message = "Rule condition is required")
        private String condition;

        private Integer priority = 1;

        private Boolean enabled = true;

        private Map<String, Object> parameters;

        // Constructors
        public EventRuleInputDTO() {}

        public EventRuleInputDTO(String name, String condition) {
            this.name = name;
            this.condition = condition;
        }

        // Getters and Setters

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

        @Override
        public String toString() {
            return "EventRuleInputDTO{" +
                    "name='" + name + '\'' +
                    ", condition='" + condition + '\'' +
                    ", priority=" + priority +
                    ", enabled=" + enabled +
                    '}';
        }
    }
} 