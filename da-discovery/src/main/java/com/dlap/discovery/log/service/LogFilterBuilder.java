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

package com.dlap.discovery.log.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.dlap.discovery.common.constants.LoggingConstants;

/**
 * Builder class for constructing log filter queries.
 * This builder supports creating complex filter strings for cloud provider log
 * APIs.
 */
public class LogFilterBuilder {
	private final StringBuilder filterBuilder = new StringBuilder();
	private boolean hasCondition = false;
	private final List<String> conditions = new ArrayList<>();
	private DateTimeFormatter timestampFormatter;

	/**
	 * Creates a new LogFilterBuilder instance.
	 */
	public LogFilterBuilder() {
	}

	/**
	 * Sets the timestamp formatter to use for formatting dates in the filter.
	 * 
	 * @param formatter The DateTimeFormatter to use
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withTimestampFormatter(DateTimeFormatter formatter) {
		this.timestampFormatter = formatter;
		return this;
	}

	/**
	 * Adds a GCP log name filter for the specified project and log.
	 * 
	 * @param projectId The GCP project ID
	 * @param logName   The log name to filter for
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withGcpLogName(String projectId, String logName) {
		filterBuilder.append("logName=")
				.append(String.format(LoggingConstants.GCP_LOG_NAME_FORMAT, projectId, logName));
		hasCondition = true;
		return this;
	}

	/**
	 * Adds a GCP log name filter for the specified project and the GCP audit log.
	 * 
	 * @param projectId The GCP project ID
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withGcpAuditLog(String projectId) {
		return withGcpLogName(projectId, LoggingConstants.GCP_AUDIT_LOG_NAME);
	}

	/**
	 * Adds a timestamp range filter.
	 * 
	 * @param startTime The start time for the filter
	 * @param endTime   The end time for the filter
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withTimeRange(Instant startTime, Instant endTime) {
		if (timestampFormatter == null) {
			throw new IllegalStateException("Timestamp formatter must be set before using time filters");
		}

		addAndOperatorIfNeeded();
		filterBuilder.append("timestamp>=")
				.append("\"").append(timestampFormatter.format(startTime)).append("\"")
				.append(" AND timestamp<=")
				.append("\"").append(timestampFormatter.format(endTime)).append("\"");
		hasCondition = true;
		return this;
	}

	/**
	 * Adds a GCP resource type filter.
	 * 
	 * @param resourceType The GCP resource type
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withGcpResourceType(String resourceType) {
		conditions.add(String.format(LoggingConstants.GCP_RESOURCE_TYPE_FILTER_FORMAT, resourceType));
		return this;
	}

	/**
	 * Adds a GCP method name filter.
	 * 
	 * @param methodName The method name to filter for
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withGcpMethodName(String methodName) {
		conditions.add(String.format(LoggingConstants.GCP_METHOD_NAME_FILTER_FORMAT, methodName));
		return this;
	}

	/**
	 * Adds multiple GCP resource type filters.
	 * These will be combined using OR logic when build() is called after grouping.
	 * 
	 * @param resourceTypes List of GCP resource types
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withGcpResourceTypes(List<String> resourceTypes) {
		if (resourceTypes != null) {
			resourceTypes.forEach(this::withGcpResourceType);
		}
		return this;
	}

	/**
	 * Adds multiple GCP method name filters (often patterns).
	 * These will be combined using OR logic when build() is called after grouping.
	 * 
	 * @param methodNames List of GCP method names or patterns
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withGcpMethodNames(List<String> methodNames) {
		if (methodNames != null) {
			methodNames.forEach(this::withGcpMethodName);
		}
		return this;
	}

	/**
	 * Adds a custom key-value filter.
	 * 
	 * @param key   The filter key
	 * @param value The filter value
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withFilter(String key, String value) {
		addAndOperatorIfNeeded();
		filterBuilder.append(key).append("=\"").append(value).append("\"");
		hasCondition = true;
		return this;
	}

	/**
	 * Adds all resource type conditions as an OR group.
	 * 
	 * @return This builder for method chaining
	 */
	public LogFilterBuilder withResourceTypeOrGroup() {
		if (!conditions.isEmpty()) {
			addAndOperatorIfNeeded();
			filterBuilder.append(LoggingConstants.FILTER_GROUP_START);

			for (int i = 0; i < conditions.size(); i++) {
				if (i > 0) {
					filterBuilder.append(LoggingConstants.FILTER_OR);
				}
				filterBuilder.append(conditions.get(i));
			}

			filterBuilder.append(LoggingConstants.FILTER_GROUP_END);
			hasCondition = true;
			conditions.clear();
		} else {
			// If no conditions, add a false condition
			addAndOperatorIfNeeded();
			filterBuilder.append(LoggingConstants.FILTER_GROUP_START)
					.append("false")
					.append(LoggingConstants.FILTER_GROUP_END);
			hasCondition = true;
		}

		return this;
	}

	/**
	 * Builds the final filter string.
	 * 
	 * @return The constructed filter string
	 */
	public String build() {
		return filterBuilder.toString();
	}

	private void addAndOperatorIfNeeded() {
		if (hasCondition) {
			filterBuilder.append(LoggingConstants.FILTER_AND);
		}
	}
}