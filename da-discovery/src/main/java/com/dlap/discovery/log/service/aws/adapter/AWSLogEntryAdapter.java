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

package com.dlap.discovery.log.service.aws.adapter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter for AWS CloudTrail log entries to provide a simplified interface.
 * This decouples our application code from direct AWS API dependencies.
 */
public class AWSLogEntryAdapter {
	private final String eventId;
	private final String eventName;
	private final String eventSource;
	private final String eventType;
	private final Instant eventTime;
	private final String awsRegion;
	private final String sourceIpAddress;
	private final String userIdentity;
	private final String userAgent;
	private final String errorCode;
	private final String errorMessage;
	private final Map<String, String> requestParameters;
	private final Map<String, String> responseElements;
	private final Map<String, String> resources;
	private final Map<String, Object> additionalEventData;

	/**
	 * Constructor for AWSLogEntryAdapter.
	 * 
	 * @param builder The builder to construct the adapter
	 */
	private AWSLogEntryAdapter(Builder builder) {
		this.eventId = builder.eventId;
		this.eventName = builder.eventName;
		this.eventSource = builder.eventSource;
		this.eventType = builder.eventType;
		this.eventTime = builder.eventTime;
		this.awsRegion = builder.awsRegion;
		this.sourceIpAddress = builder.sourceIpAddress;
		this.userIdentity = builder.userIdentity;
		this.userAgent = builder.userAgent;
		this.errorCode = builder.errorCode;
		this.errorMessage = builder.errorMessage;
		this.requestParameters = builder.requestParameters;
		this.responseElements = builder.responseElements;
		this.resources = builder.resources;
		this.additionalEventData = builder.additionalEventData;
	}

	/**
	 * Gets the event ID.
	 * 
	 * @return The event ID
	 */
	public String getEventId() {
		return eventId;
	}

	/**
	 * Gets the event name.
	 * 
	 * @return The event name
	 */
	public String getEventName() {
		return eventName;
	}

	/**
	 * Gets the event source.
	 * 
	 * @return The event source
	 */
	public String getEventSource() {
		return eventSource;
	}

	/**
	 * Gets the event type.
	 * 
	 * @return The event type
	 */
	public String getEventType() {
		return eventType;
	}

	/**
	 * Gets the event time.
	 * 
	 * @return The event time
	 */
	public Instant getEventTime() {
		return eventTime;
	}

	/**
	 * Gets the AWS region.
	 * 
	 * @return The AWS region
	 */
	public String getAwsRegion() {
		return awsRegion;
	}

	/**
	 * Gets the source IP address.
	 * 
	 * @return The source IP address
	 */
	public String getSourceIpAddress() {
		return sourceIpAddress;
	}

	/**
	 * Gets the user identity.
	 * 
	 * @return The user identity
	 */
	public String getUserIdentity() {
		return userIdentity;
	}

	/**
	 * Gets the user agent.
	 * 
	 * @return The user agent
	 */
	public String getUserAgent() {
		return userAgent;
	}

	/**
	 * Gets the error code.
	 * 
	 * @return The error code
	 */
	public String getErrorCode() {
		return errorCode;
	}

	/**
	 * Gets the error message.
	 * 
	 * @return The error message
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * Gets the request parameters.
	 * 
	 * @return The request parameters
	 */
	public Map<String, String> getRequestParameters() {
		return requestParameters;
	}

	/**
	 * Gets the response elements.
	 * 
	 * @return The response elements
	 */
	public Map<String, String> getResponseElements() {
		return responseElements;
	}

	/**
	 * Gets the resources.
	 * 
	 * @return The resources
	 */
	public Map<String, String> getResources() {
		return resources;
	}

	/**
	 * Gets the additional event data.
	 * 
	 * @return The additional event data
	 */
	public Map<String, Object> getAdditionalEventData() {
		return additionalEventData;
	}

	/**
	 * Builder for AWSLogEntryAdapter.
	 */
	public static class Builder {
		private String eventId;
		private String eventName;
		private String eventSource;
		private String eventType;
		private Instant eventTime;
		private String awsRegion;
		private String sourceIpAddress;
		private String userIdentity;
		private String userAgent;
		private String errorCode;
		private String errorMessage;
		private Map<String, String> requestParameters = new HashMap<>();
		private Map<String, String> responseElements = new HashMap<>();
		private Map<String, String> resources = new HashMap<>();
		private Map<String, Object> additionalEventData = new HashMap<>();

		/**
		 * Sets the event ID.
		 * 
		 * @param eventId The event ID
		 * @return This builder
		 */
		public Builder eventId(String eventId) {
			this.eventId = eventId;
			return this;
		}

		/**
		 * Sets the event name.
		 * 
		 * @param eventName The event name
		 * @return This builder
		 */
		public Builder eventName(String eventName) {
			this.eventName = eventName;
			return this;
		}

		/**
		 * Sets the event source.
		 * 
		 * @param eventSource The event source
		 * @return This builder
		 */
		public Builder eventSource(String eventSource) {
			this.eventSource = eventSource;
			return this;
		}

		/**
		 * Sets the event type.
		 * 
		 * @param eventType The event type
		 * @return This builder
		 */
		public Builder eventType(String eventType) {
			this.eventType = eventType;
			return this;
		}

		/**
		 * Sets the event time.
		 * 
		 * @param eventTime The event time
		 * @return This builder
		 */
		public Builder eventTime(Instant eventTime) {
			this.eventTime = eventTime;
			return this;
		}

		/**
		 * Sets the AWS region.
		 * 
		 * @param awsRegion The AWS region
		 * @return This builder
		 */
		public Builder awsRegion(String awsRegion) {
			this.awsRegion = awsRegion;
			return this;
		}

		/**
		 * Sets the source IP address.
		 * 
		 * @param sourceIpAddress The source IP address
		 * @return This builder
		 */
		public Builder sourceIpAddress(String sourceIpAddress) {
			this.sourceIpAddress = sourceIpAddress;
			return this;
		}

		/**
		 * Sets the user identity.
		 * 
		 * @param userIdentity The user identity
		 * @return This builder
		 */
		public Builder userIdentity(String userIdentity) {
			this.userIdentity = userIdentity;
			return this;
		}

		/**
		 * Sets the user agent.
		 * 
		 * @param userAgent The user agent
		 * @return This builder
		 */
		public Builder userAgent(String userAgent) {
			this.userAgent = userAgent;
			return this;
		}

		/**
		 * Sets the error code.
		 * 
		 * @param errorCode The error code
		 * @return This builder
		 */
		public Builder errorCode(String errorCode) {
			this.errorCode = errorCode;
			return this;
		}

		/**
		 * Sets the error message.
		 * 
		 * @param errorMessage The error message
		 * @return This builder
		 */
		public Builder errorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
			return this;
		}

		/**
		 * Sets the request parameters.
		 * 
		 * @param requestParameters The request parameters
		 * @return This builder
		 */
		public Builder requestParameters(Map<String, String> requestParameters) {
			this.requestParameters = requestParameters;
			return this;
		}

		/**
		 * Sets the response elements.
		 * 
		 * @param responseElements The response elements
		 * @return This builder
		 */
		public Builder responseElements(Map<String, String> responseElements) {
			this.responseElements = responseElements;
			return this;
		}

		/**
		 * Sets the resources.
		 * 
		 * @param resources The resources
		 * @return This builder
		 */
		public Builder resources(Map<String, String> resources) {
			this.resources = resources;
			return this;
		}

		/**
		 * Sets the additional event data.
		 * 
		 * @param additionalEventData The additional event data
		 * @return This builder
		 */
		public Builder additionalEventData(Map<String, Object> additionalEventData) {
			this.additionalEventData = additionalEventData;
			return this;
		}

		/**
		 * Builds the AWSLogEntryAdapter.
		 * 
		 * @return The built AWSLogEntryAdapter
		 */
		public AWSLogEntryAdapter build() {
			return new AWSLogEntryAdapter(this);
		}
	}
}