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

package com.dlap.reporting.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "user_notification_preferences",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "notification_type_key", "channel"})
    })
public class UserNotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private UUID userId; // The ID of the user (from Keycloak or central user management)

    @NotBlank
    @Size(max = 100)
    @Column(name = "notification_type_key", nullable = false)
    private String notificationTypeKey; // e.g., "REPORT_COMPLETED_COST_OPTIMIZATION", "POLICY_ALERT_HIGH_SEVERITY"

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel; // EMAIL, SLACK

    private boolean enabled = true;

    // Channel-specific details, e.g., for SLACK, it could be { "channelId": "C123", "userId": "U456" }
    // For EMAIL, it might be overridden by a central user profile email but could allow specific addresses here.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> channelConfiguration; // e.g., Slack channel ID, specific email address

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getNotificationTypeKey() {
        return notificationTypeKey;
    }

    public void setNotificationTypeKey(String notificationTypeKey) {
        this.notificationTypeKey = notificationTypeKey;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getChannelConfiguration() {
        return channelConfiguration;
    }

    public void setChannelConfiguration(Map<String, String> channelConfiguration) {
        this.channelConfiguration = channelConfiguration;
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

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
} 