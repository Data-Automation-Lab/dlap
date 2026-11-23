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

package com.dlap.discovery.common.notification;

import com.dlap.discovery.common.notification.dto.NotificationConfigDTO;
import com.dlap.discovery.common.notification.dto.NotificationDTO;

/**
 * Service for sending notifications.
 */
public interface INotificationService {

    enum NotificationChannel {
        EMAIL,
        SLACK,
        TEAMS
    }

    /**
     * Sends a notification through configured channels.
     * @param notification The notification to send
     * @return true if the notification was sent successfully
     */
    boolean sendNotification(NotificationDTO notification, NotificationChannel channel);
    
    /**
     * Configures notification channels.
     * @param config Configuration for notification channels
     */
    void configureNotificationChannels(NotificationConfigDTO config);
    
    /**
     * Checks if a notification channel is enabled.
     * @param channelType Type of notification channel
     * @return true if the channel is enabled
     */
    boolean isChannelEnabled(NotificationChannel channelType);
} 