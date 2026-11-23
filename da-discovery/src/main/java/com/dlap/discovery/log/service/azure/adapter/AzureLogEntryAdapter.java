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

package com.dlap.discovery.log.service.azure.adapter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter for Azure Activity Log entries to provide a simplified interface.
 * This decouples our application code from direct Azure API dependencies.
 */
public class AzureLogEntryAdapter {
	private final String eventId;
	private final String operationName;
	private final String operationId;
	private final String status;
	private final String resourceId;
	private final String resourceType;
	private final String resourceGroup;
	private final String subscriptionId;
	private final String tenantId;
	private final String category;
	private final Instant eventTimestamp;
	private final String caller;
	private final String callerIpAddress;
	private final String correlationId;
	private final String level;
	private final Map<String, String> properties;
	private final Map<String, Object> additionalProperties;

	/**
	 * Constructor for AzureLogEntryAdapter.
	 * 
	 * @param builder The builder to construct the adapter
	 */
	private AzureLogEntryAdapter(Builder builder) {
		this.eventId = builder.eventId;
		this.operationName = builder.operationName;
		this.operationId = builder.operationId;
		this.status = builder.status;
		this.resourceId = builder.resourceId;
		this.resourceType = builder.resourceType;
		this.resourceGroup = builder.resourceGroup;
		this.subscriptionId = builder.subscriptionId;
		this.tenantId = builder.tenantId;
		this.category = builder.category;
		this.eventTimestamp = builder.eventTimestamp;
		this.caller = builder.caller;
		this.callerIpAddress = builder.callerIpAddress;
		this.correlationId = builder.correlationId;
		this.level = builder.level;
		this.properties = builder.properties;
		this.additionalProperties = builder.additionalProperties;
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
	 * Gets the operation name.
	 * 
	 * @return The operation name
	 */
	public String getOperationName() {
		return operationName;
	}

	/**
	 * Gets the operation ID.
	 * 
	 * @return The operation ID
	 */
	public String getOperationId() {
		return operationId;
	}

	/**
	 * Gets the status.
	 * 
	 * @return The status
	 */
	public String getStatus() {
		return status;
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
	 * Gets the resource type.
	 * 
	 * @return The resource type
	 */
	public String getResourceType() {
		return resourceType;
	}

	/**
	 * Gets the resource group.
	 * 
	 * @return The resource group
	 */
	public String getResourceGroup() {
		return resourceGroup;
	}

	/**
	 * Gets the subscription ID.
	 * 
	 * @return The subscription ID
	 */
	public String getSubscriptionId() {
		return subscriptionId;
	}

	/**
	 * Gets the tenant ID.
	 * 
	 * @return The tenant ID
	 */
	public String getTenantId() {
		return tenantId;
	}

	/**
	 * Gets the category.
	 * 
	 * @return The category
	 */
	public String getCategory() {
		return category;
	}

	/**
	 * Gets the event timestamp.
	 * 
	 * @return The event timestamp
	 */
	public Instant getEventTimestamp() {
		return eventTimestamp;
	}

	/**
	 * Gets the caller.
	 * 
	 * @return The caller
	 */
	public String getCaller() {
		return caller;
	}

	/**
	 * Gets the caller IP address.
	 * 
	 * @return The caller IP address
	 */
	public String getCallerIpAddress() {
		return callerIpAddress;
	}

	/**
	 * Gets the correlation ID.
	 * 
	 * @return The correlation ID
	 */
	public String getCorrelationId() {
		return correlationId;
	}

	/**
	 * Gets the level.
	 * 
	 * @return The level
	 */
	public String getLevel() {
		return level;
	}

	/**
	 * Gets the properties.
	 * 
	 * @return The properties
	 */
	public Map<String, String> getProperties() {
		return properties;
	}

	/**
	 * Gets the additional properties.
	 * 
	 * @return The additional properties
	 */
	public Map<String, Object> getAdditionalProperties() {
		return additionalProperties;
	}

	/**
	 * Builder for AzureLogEntryAdapter.
	 */
	public static class Builder {
		private String eventId;
		private String operationName;
		private String operationId;
		private String status;
		private String resourceId;
		private String resourceType;
		private String resourceGroup;
		private String subscriptionId;
		private String tenantId;
		private String category;
		private Instant eventTimestamp;
		private String caller;
		private String callerIpAddress;
		private String correlationId;
		private String level;
		private Map<String, String> properties = new HashMap<>();
		private Map<String, Object> additionalProperties = new HashMap<>();

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
		 * Sets the operation name.
		 * 
		 * @param operationName The operation name
		 * @return This builder
		 */
		public Builder operationName(String operationName) {
			this.operationName = operationName;
			return this;
		}

		/**
		 * Sets the operation ID.
		 * 
		 * @param operationId The operation ID
		 * @return This builder
		 */
		public Builder operationId(String operationId) {
			this.operationId = operationId;
			return this;
		}

		/**
		 * Sets the status.
		 * 
		 * @param status The status
		 * @return This builder
		 */
		public Builder status(String status) {
			this.status = status;
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
		 * Sets the resource group.
		 * 
		 * @param resourceGroup The resource group
		 * @return This builder
		 */
		public Builder resourceGroup(String resourceGroup) {
			this.resourceGroup = resourceGroup;
			return this;
		}

		/**
		 * Sets the subscription ID.
		 * 
		 * @param subscriptionId The subscription ID
		 * @return This builder
		 */
		public Builder subscriptionId(String subscriptionId) {
			this.subscriptionId = subscriptionId;
			return this;
		}

		/**
		 * Sets the tenant ID.
		 * 
		 * @param tenantId The tenant ID
		 * @return This builder
		 */
		public Builder tenantId(String tenantId) {
			this.tenantId = tenantId;
			return this;
		}

		/**
		 * Sets the category.
		 * 
		 * @param category The category
		 * @return This builder
		 */
		public Builder category(String category) {
			this.category = category;
			return this;
		}

		/**
		 * Sets the event timestamp.
		 * 
		 * @param eventTimestamp The event timestamp
		 * @return This builder
		 */
		public Builder eventTimestamp(Instant eventTimestamp) {
			this.eventTimestamp = eventTimestamp;
			return this;
		}

		/**
		 * Sets the caller.
		 * 
		 * @param caller The caller
		 * @return This builder
		 */
		public Builder caller(String caller) {
			this.caller = caller;
			return this;
		}

		/**
		 * Sets the caller IP address.
		 * 
		 * @param callerIpAddress The caller IP address
		 * @return This builder
		 */
		public Builder callerIpAddress(String callerIpAddress) {
			this.callerIpAddress = callerIpAddress;
			return this;
		}

		/**
		 * Sets the correlation ID.
		 * 
		 * @param correlationId The correlation ID
		 * @return This builder
		 */
		public Builder correlationId(String correlationId) {
			this.correlationId = correlationId;
			return this;
		}

		/**
		 * Sets the level.
		 * 
		 * @param level The level
		 * @return This builder
		 */
		public Builder level(String level) {
			this.level = level;
			return this;
		}

		/**
		 * Sets the properties.
		 * 
		 * @param properties The properties
		 * @return This builder
		 */
		public Builder properties(Map<String, String> properties) {
			this.properties = properties;
			return this;
		}

		/**
		 * Sets the additional properties.
		 * 
		 * @param additionalProperties The additional properties
		 * @return This builder
		 */
		public Builder additionalProperties(Map<String, Object> additionalProperties) {
			this.additionalProperties = additionalProperties;
			return this;
		}

		/**
		 * Builds the AzureLogEntryAdapter.
		 * 
		 * @return The built AzureLogEntryAdapter
		 */
		public AzureLogEntryAdapter build() {
			return new AzureLogEntryAdapter(this);
		}
	}
}