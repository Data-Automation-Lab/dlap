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

package com.dlap.discovery.event.service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Map;

import com.dlap.discovery.common.constants.NotificationConstants;

/**
 * Builder class for constructing notification messages.
 * This class helps create standardized notification messages from various event
 * sources.
 */
public class NotificationMessageBuilder {
	private final StringBuilder messageBuilder;
	private DateTimeFormatter dateFormatter;

	/**
	 * Creates a new notification message builder.
	 */
	public NotificationMessageBuilder() {
		this.messageBuilder = new StringBuilder();
	}

	/**
	 * Sets the date formatter to use for formatting timestamps.
	 * 
	 * @param formatter The date formatter
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withDateFormatter(DateTimeFormatter formatter) {
		this.dateFormatter = formatter;
		return this;
	}

	/**
	 * Adds a field with a value to the message.
	 * 
	 * @param fieldName The field name
	 * @param value     The field value
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withField(String fieldName, String value) {
		if (value != null) {
			messageBuilder.append(fieldName)
					.append(": ")
					.append(value)
					.append("\n\n");
		}
		return this;
	}

	/**
	 * Adds event details to the message.
	 * 
	 * @param eventType  The event type
	 * @param resourceId The resource ID
	 * @param timestamp  The timestamp as an Instant
	 * @param payload    The event payload as a string
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withEventDetails(String eventType, String resourceId, Instant timestamp,
			String payload) {
		messageBuilder.append(String.format(NotificationConstants.EVENT_DETAILS_TEMPLATE,
				eventType, resourceId, formatTimestamp(timestamp)));

		if (payload != null) {
			messageBuilder.append(String.format(NotificationConstants.EVENT_PAYLOAD_TEMPLATE, payload));
		}

		return this;
	}

	/**
	 * Adds event details to the message with a ZonedDateTime timestamp.
	 * 
	 * @param eventType  The event type
	 * @param resourceId The resource ID
	 * @param timestamp  The timestamp as a ZonedDateTime
	 * @param payload    The event payload
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withEventDetails(String eventType, String resourceId, ZonedDateTime timestamp,
			Object payload) {
		// Convert ZonedDateTime to Instant
		Instant instant = timestamp != null ? timestamp.toInstant() : null;
		String payloadStr = payload != null ? payload.toString() : null;

		return withEventDetails(eventType, resourceId, instant, payloadStr);
	}

	/**
	 * Adds notification metadata to the message.
	 * 
	 * @param notificationId The notification ID
	 * @param type           The notification type
	 * @param timestamp      The timestamp as an Instant
	 * @param priority       The notification priority
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withNotificationMetadata(String notificationId, String type, Instant timestamp,
			String priority) {
		messageBuilder.append(String.format(NotificationConstants.NOTIFICATION_METADATA_TEMPLATE,
				formatTimestamp(timestamp), priority, notificationId, type));
		return this;
	}

	/**
	 * Adds notification metadata to the message with a ZonedDateTime timestamp.
	 * 
	 * @param notificationId The notification ID
	 * @param type           The notification type
	 * @param timestamp      The timestamp as a ZonedDateTime
	 * @param priority       The notification priority
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withNotificationMetadata(String notificationId, String type,
			ZonedDateTime timestamp, String priority) {
		// Convert ZonedDateTime to Instant
		Instant instant = timestamp != null ? timestamp.toInstant() : null;
		return withNotificationMetadata(notificationId, type, instant, priority);
	}

	/**
	 * Formats a timestamp using the configured formatter, or ISO-8601 if none is
	 * set.
	 * 
	 * @param timestamp The timestamp to format
	 * @return The formatted timestamp string
	 */
	private String formatTimestamp(TemporalAccessor timestamp) {
		if (timestamp == null) {
			return "N/A";
		}
		return dateFormatter != null ? dateFormatter.format(timestamp) : timestamp.toString();
	}

	/**
	 * Adds multiple fields from a map.
	 * 
	 * @param fields The map of field names to values
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withFields(Map<String, String> fields) {
		if (fields != null) {
			for (Map.Entry<String, String> entry : fields.entrySet()) {
				withField(entry.getKey(), entry.getValue());
			}
		}
		return this;
	}

	/**
	 * Adds a line break to the message.
	 * 
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withLineBreak() {
		messageBuilder.append(NotificationConstants.LINE_BREAK);
		return this;
	}

	/**
	 * Adds a double line break to the message.
	 * 
	 * @return This builder for method chaining
	 */
	public NotificationMessageBuilder withDoubleLineBreak() {
		messageBuilder.append(NotificationConstants.DOUBLE_LINE_BREAK);
		return this;
	}

	/**
	 * Builds the final notification message.
	 * 
	 * @return The built message
	 */
	public String build() {
		return messageBuilder.toString();
	}
}