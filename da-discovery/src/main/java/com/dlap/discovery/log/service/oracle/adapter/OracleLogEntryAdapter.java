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

package com.dlap.discovery.log.service.oracle.adapter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter for Oracle Cloud Audit log entries to provide a simplified interface.
 * This decouples our application code from direct Oracle Cloud API
 * dependencies.
 */
public class OracleLogEntryAdapter {
	private final String eventId;
	private final String eventName;
	private final String eventType;
	private final Instant eventTime;
	private final String region;
	private final String sourceIpAddress;
	private final String userIdentity;
	private final String userAgent;
	private final String errorCode;
	private final String errorMessage;
	private final String compartmentId;
	private final String compartmentName;
	private final String tenancyId;
	private final String resourceType;
	private final String resourceId;
	private final String resourceName;
	private final Map<String, String> requestParameters;
	private final Map<String, String> responseElements;
	private final Map<String, Object> additionalEventData;

	/**
	 * Constructor for OracleLogEntryAdapter.
	 * 
	 * @param builder The builder to construct the adapter
	 */
	private OracleLogEntryAdapter(Builder builder) {
		this.eventId = builder.eventId;
		this.eventName = builder.eventName;
		this.eventType = builder.eventType;
		this.eventTime = builder.eventTime;
		this.region = builder.region;
		this.sourceIpAddress = builder.sourceIpAddress;
		this.userIdentity = builder.userIdentity;
		this.userAgent = builder.userAgent;
		this.errorCode = builder.errorCode;
		this.errorMessage = builder.errorMessage;
		this.compartmentId = builder.compartmentId;
		this.compartmentName = builder.compartmentName;
		this.tenancyId = builder.tenancyId;
		this.resourceType = builder.resourceType;
		this.resourceId = builder.resourceId;
		this.resourceName = builder.resourceName;
		this.requestParameters = builder.requestParameters;
		this.responseElements = builder.responseElements;
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
	 * Gets the event type.
	 * 
	 * @return The event type
	 */
	public String getEventType() {
		return eventType;
	}

	/**
	 * Gets the event timestamp.
	 * 
	 * @return The event timestamp
	 */
	public Instant getEventTime() {
		return eventTime;
	}

	/**
	 * Gets the region.
	 * 
	 * @return The region
	 */
	public String getRegion() {
		return region;
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
	 * Gets the compartment ID.
	 * 
	 * @return The compartment ID
	 */
	public String getCompartmentId() {
		return compartmentId;
	}

	/**
	 * Gets the compartment name.
	 * 
	 * @return The compartment name
	 */
	public String getCompartmentName() {
		return compartmentName;
	}

	/**
	 * Gets the tenancy ID.
	 * 
	 * @return The tenancy ID
	 */
	public String getTenancyId() {
		return tenancyId;
	}

	/**
	 * Gets the resource type.
	 * 
	 * @return The resource type
	 */
	public String getResourceType() {
		return resourceType;
	}

	/**
	 * Gets the resource ID.
	 * 
	 * @return The resource ID
	 */
	public String getResourceId() {
		return resourceId;
	}

	/**
	 * Gets the resource name.
	 * 
	 * @return The resource name
	 */
	public String getResourceName() {
		return resourceName;
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
	 * Gets additional event data.
	 * 
	 * @return The additional event data
	 */
	public Map<String, Object> getAdditionalEventData() {
		return additionalEventData;
	}

	/**
	 * Builder for OracleLogEntryAdapter.
	 */
	public static class Builder {
		private String eventId;
		private String eventName;
		private String eventType;
		private Instant eventTime;
		private String region;
		private String sourceIpAddress;
		private String userIdentity;
		private String userAgent;
		private String errorCode;
		private String errorMessage;
		private String compartmentId;
		private String compartmentName;
		private String tenancyId;
		private String resourceType;
		private String resourceId;
		private String resourceName;
		private Map<String, String> requestParameters = new HashMap<>();
		private Map<String, String> responseElements = new HashMap<>();
		private Map<String, Object> additionalEventData = new HashMap<>();

		/**
		 * Creates a new Builder.
		 */
		public Builder() {
		}

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
		 * Sets the region.
		 * 
		 * @param region The region
		 * @return This builder
		 */
		public Builder region(String region) {
			this.region = region;
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
		 * Sets the compartment ID.
		 * 
		 * @param compartmentId The compartment ID
		 * @return This builder
		 */
		public Builder compartmentId(String compartmentId) {
			this.compartmentId = compartmentId;
			return this;
		}

		/**
		 * Sets the compartment name.
		 * 
		 * @param compartmentName The compartment name
		 * @return This builder
		 */
		public Builder compartmentName(String compartmentName) {
			this.compartmentName = compartmentName;
			return this;
		}

		/**
		 * Sets the tenancy ID.
		 * 
		 * @param tenancyId The tenancy ID
		 * @return This builder
		 */
		public Builder tenancyId(String tenancyId) {
			this.tenancyId = tenancyId;
			return this;
		}

		/**
		 * Sets the resource type.
		 * 
		 * @param resourceType The resource type
		 * @return This builder
		 */
		public Builder resourceType(String resourceType) {
			this.resourceType = resourceType;
			return this;
		}

		/**
		 * Sets the resource ID.
		 * 
		 * @param resourceId The resource ID
		 * @return This builder
		 */
		public Builder resourceId(String resourceId) {
			this.resourceId = resourceId;
			return this;
		}

		/**
		 * Sets the resource name.
		 * 
		 * @param resourceName The resource name
		 * @return This builder
		 */
		public Builder resourceName(String resourceName) {
			this.resourceName = resourceName;
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
		 * Builds the OracleLogEntryAdapter.
		 * 
		 * @return The built OracleLogEntryAdapter
		 */
		public OracleLogEntryAdapter build() {
			return new OracleLogEntryAdapter(this);
		}
	}
}