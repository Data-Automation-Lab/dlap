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

package com.dlap.discovery.common.notification.dto;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Data Transfer Object for notifications.
 */
public class NotificationDTO {
    private String notificationId;
    private String title;
    private String message;
    private NotificationType type;
    private ZonedDateTime timestamp;
    private List<String> targetChannels = new ArrayList<>();
    private int priority;
    
    /**
     * Types of notifications.
     */
    public enum NotificationType {
        INFO, WARNING, ERROR, CRITICAL
    }
    
    /**
     * Creates a new notification with default values.
     */
    public NotificationDTO() {
        this.notificationId = UUID.randomUUID().toString();
        this.timestamp = ZonedDateTime.now();
        this.type = NotificationType.INFO;
        this.priority = 0;
    }
    
    // Getters and setters
    public String getNotificationId() {
        return notificationId;
    }
    
    public void setNotificationId(String notificationId) {
        this.notificationId = notificationId;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public NotificationType getType() {
        return type;
    }
    
    public void setType(NotificationType type) {
        this.type = type;
    }
    
    public ZonedDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public List<String> getTargetChannels() {
        return targetChannels;
    }
    
    public void setTargetChannels(List<String> targetChannels) {
        this.targetChannels = targetChannels != null ? targetChannels : new ArrayList<>();
    }
    
    public int getPriority() {
        return priority;
    }
    
    public void setPriority(int priority) {
        this.priority = priority;
    }
    
    @Override
    public String toString() {
        return String.format("NotificationDTO{id='%s', title='%s', type=%s}", 
            notificationId, title, type);
    }
} 