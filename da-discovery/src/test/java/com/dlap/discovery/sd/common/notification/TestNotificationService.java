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

package com.dlap.discovery.sd.common.notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.notification.INotificationService;
import com.dlap.discovery.common.notification.dto.NotificationConfigDTO;
import com.dlap.discovery.common.notification.dto.NotificationDTO;

/**
 * Test implementation of the notification service.
 */
@Service
@Primary
@Profile("test")
public class TestNotificationService implements INotificationService {

	private final List<NotificationDTO> sentNotifications = new ArrayList<>();
	private final Map<NotificationChannel, Boolean> enabledChannels = new HashMap<>();

	public TestNotificationService() {
		// Enable all channels by default
		for (NotificationChannel channel : NotificationChannel.values()) {
			enabledChannels.put(channel, true);
		}
	}

	@Override
	public boolean sendNotification(NotificationDTO notification, NotificationChannel channel) {
		if (isChannelEnabled(channel)) {
			sentNotifications.add(notification);
			return true;
		}
		return false;
	}

	@Override
	public void configureNotificationChannels(NotificationConfigDTO config) {
		// Process channel configurations
		for (NotificationConfigDTO.ChannelConfig channelConfig : config.getChannels()) {
			try {
				NotificationChannel channelType = NotificationChannel.valueOf(channelConfig.getChannelType());
				enabledChannels.put(channelType, channelConfig.isEnabled());
			} catch (IllegalArgumentException e) {
				// Ignore invalid channel types
			}
		}
	}

	@Override
	public boolean isChannelEnabled(NotificationChannel channelType) {
		return enabledChannels.getOrDefault(channelType, false);
	}

	/**
	 * Get all notifications that were sent during the test.
	 */
	public List<NotificationDTO> getSentNotifications() {
		return new ArrayList<>(sentNotifications);
	}

	/**
	 * Clear all sent notifications.
	 */
	public void clearNotifications() {
		sentNotifications.clear();
	}
}