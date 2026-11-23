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

package com.dlap.policyengine.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Entity representing an event subscription configuration.
 * Users can subscribe to specific types of events and configure
 * notification preferences and action triggers.
 *
 * <p>Which events match is decided by the typed filter (SEC-04, ADR-0057): event types,
 * severities, source services, asset prefixes and labels; see
 * {@link com.dlap.policyengine.filter.SubscriptionFilter}.
 */
@Entity
@Table(name = "event_subscriptions")
public class EventSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @Size(max = 1000)
    private String description;

    @NotNull
    @Column(columnDefinition = "UUID", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventSubscriptionStatus status = EventSubscriptionStatus.ACTIVE;

    /**
     * Event types to subscribe to (e.g., POLICY_VIOLATION, ASSET_DISCOVERED, COMPLIANCE_ISSUE)
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_subscription_types", joinColumns = @JoinColumn(name = "subscription_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type")
    private List<EventType> eventTypes = new ArrayList<>();

    /**
     * Event severity levels to include (e.g., HIGH, MEDIUM, LOW)
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_subscription_severities", joinColumns = @JoinColumn(name = "subscription_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private List<EventSeverity> severities = new ArrayList<>();

    /**
     * Source services to monitor (e.g., da-catalog, da-discovery, da-compliance)
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_subscription_sources", joinColumns = @JoinColumn(name = "subscription_id"))
    @Column(name = "source_service")
    private List<String> sourceServices = new ArrayList<>();

    /**
     * Typed filter (SEC-04, ADR-0057): canonical asset URI prefixes an event's asset must
     * start with. Empty means any asset. Loaded with its own select so it never multiplies
     * the rows of the other eager collections.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "event_subscription_asset_prefixes", joinColumns = @JoinColumn(name = "subscription_id"))
    @Column(name = "asset_prefix", length = 2048, nullable = false)
    private Set<String> assetPrefixes = new LinkedHashSet<>();

    /**
     * Typed filter (SEC-04, ADR-0057): label names, one of which the event must carry
     * (compared without case). Empty means any labels.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "event_subscription_labels", joinColumns = @JoinColumn(name = "subscription_id"))
    @Column(name = "label", length = 255, nullable = false)
    private Set<String> labels = new LinkedHashSet<>();

    /**
     * Retired MVEL rule conditions (SEC-04, ADR-0057). Flyway V5 archived every row to
     * {@code retired_mvel_rules} and emptied {@code event_rules}, which now refuses new
     * rows. Kept mapped, read-only, until the table is dropped; nothing evaluates it.
     */
    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<EventRule> rules = new ArrayList<>();

    /**
     * Notification preferences and action configurations
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> notificationConfig; // e.g., { "email": true, "slack": { "channel": "#alerts" } }

    /**
     * Action configurations to trigger when events match
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> actionConfig; // e.g., { "autoQuarantine": true, "escalateTo": "admin" }

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Column(columnDefinition = "UUID")
    private UUID createdByUserId;

    @Column(columnDefinition = "UUID")
    private UUID updatedByUserId;

    // Constructors
    public EventSubscription() {}

    public EventSubscription(String name, UUID userId) {
        this.name = name;
        this.userId = userId;
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
        this.eventTypes = eventTypes != null ? eventTypes : new ArrayList<>();
    }

    public void addEventType(EventType eventType) {
        if (this.eventTypes == null) {
            this.eventTypes = new ArrayList<>();
        }
        this.eventTypes.add(eventType);
    }

    public void removeEventType(EventType eventType) {
        if (this.eventTypes != null) {
            this.eventTypes.remove(eventType);
        }
    }

    public List<EventSeverity> getSeverities() {
        return severities;
    }

    public void setSeverities(List<EventSeverity> severities) {
        this.severities = severities != null ? severities : new ArrayList<>();
    }

    public void addSeverity(EventSeverity severity) {
        if (this.severities == null) {
            this.severities = new ArrayList<>();
        }
        this.severities.add(severity);
    }

    public void removeSeverity(EventSeverity severity) {
        if (this.severities != null) {
            this.severities.remove(severity);
        }
    }

    public List<String> getSourceServices() {
        return sourceServices;
    }

    public void setSourceServices(List<String> sourceServices) {
        this.sourceServices = sourceServices != null ? sourceServices : new ArrayList<>();
    }

    public void addSourceService(String sourceService) {
        if (this.sourceServices == null) {
            this.sourceServices = new ArrayList<>();
        }
        this.sourceServices.add(sourceService);
    }

    public void removeSourceService(String sourceService) {
        if (this.sourceServices != null) {
            this.sourceServices.remove(sourceService);
        }
    }

    /**
     * Asset prefix filter values.
     *
     * @return the canonical asset URI prefixes; never {@code null}
     */
    public Set<String> getAssetPrefixes() {
        return assetPrefixes;
    }

    /**
     * Replaces the asset prefix filter values.
     *
     * @param assetPrefixes the prefixes; {@code null} clears the filter
     */
    public void setAssetPrefixes(Collection<String> assetPrefixes) {
        if (this.assetPrefixes == null) {
            this.assetPrefixes = new LinkedHashSet<>();
        }
        this.assetPrefixes.clear();
        if (assetPrefixes != null) {
            this.assetPrefixes.addAll(assetPrefixes);
        }
    }

    /**
     * Label filter values.
     *
     * @return the label names; never {@code null}
     */
    public Set<String> getLabels() {
        return labels;
    }

    /**
     * Replaces the label filter values.
     *
     * @param labels the label names; {@code null} clears the filter
     */
    public void setLabels(Collection<String> labels) {
        if (this.labels == null) {
            this.labels = new LinkedHashSet<>();
        }
        this.labels.clear();
        if (labels != null) {
            this.labels.addAll(labels);
        }
    }

    /**
     * Retired MVEL rules (ADR-0057); empty after Flyway V5.
     *
     * @return the legacy rules, always empty on a migrated database
     */
    public List<EventRule> getRules() {
        return rules;
    }

    public void setRules(List<EventRule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
        this.rules.forEach(rule -> rule.setSubscription(this));
    }

    public void addRule(EventRule rule) {
        if (this.rules == null) {
            this.rules = new ArrayList<>();
        }
        this.rules.add(rule);
        rule.setSubscription(this);
    }

    public void removeRule(EventRule rule) {
        if (this.rules != null) {
            this.rules.remove(rule);
            rule.setSubscription(null);
        }
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

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventSubscription)) return false;
        EventSubscription that = (EventSubscription) o;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "EventSubscription{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", userId=" + userId +
                ", status=" + status +
                ", eventTypes=" + eventTypes +
                ", severities=" + severities +
                ", assetPrefixes=" + assetPrefixes +
                ", labels=" + labels +
                '}';
    }
} 