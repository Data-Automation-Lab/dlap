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

package com.dlap.discovery.common.constants;

/**
 * Constants related to notification messages and templates.
 * This class centralizes string literals used in notification services.
 */
public final class NotificationConstants {
	private NotificationConstants() {
		// Prevent instantiation
	}

	// Notification templates
	public static final String EVENT_DETAILS_TEMPLATE = "Event Details:\nType: %s\nResource ID: %s\nTimestamp: %s\n";
	public static final String EVENT_PAYLOAD_TEMPLATE = "Payload: %s\n";

	public static final String NOTIFICATION_METADATA_TEMPLATE = "\n\nTimestamp: %s\nPriority: %s\nNotification ID: %s\nType: %s";

	// Field names
	public static final String FIELD_EVENT_TYPE = "Type";
	public static final String FIELD_RESOURCE_ID = "Resource ID";
	public static final String FIELD_TIMESTAMP = "Timestamp";
	public static final String FIELD_PAYLOAD = "Payload";
	public static final String FIELD_PRIORITY = "Priority";
	public static final String FIELD_NOTIFICATION_ID = "Notification ID";
	public static final String FIELD_TYPE = "Type";

	// Message components
	public static final String LINE_BREAK = "\n";
	public static final String FIELD_SEPARATOR = ": ";
	public static final String DOUBLE_LINE_BREAK = "\n\n";
}