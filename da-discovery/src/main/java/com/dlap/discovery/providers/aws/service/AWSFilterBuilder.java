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

package com.dlap.discovery.providers.aws.service;

import java.util.ArrayList;
import java.util.List;

import com.dlap.discovery.common.constants.AWSConstants;

import software.amazon.awssdk.services.ec2.model.Filter;

/**
 * Builder for creating AWS filter expressions for SDK client calls.
 * This class helps create standardized filters for EC2, S3, and other AWS
 * services.
 */
public class AWSFilterBuilder {
	private final List<Filter> filters = new ArrayList<>();

	/**
	 * Creates a new AWS filter builder.
	 */
	public AWSFilterBuilder() {
	}

	/**
	 * Adds a filter with a single value.
	 * 
	 * @param name  The filter name
	 * @param value The filter value
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withFilter(String name, String value) {
		filters.add(Filter.builder()
				.name(name)
				.values(value)
				.build());
		return this;
	}

	/**
	 * Adds a filter with multiple values.
	 * 
	 * @param name   The filter name
	 * @param values The filter values
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withFilter(String name, List<String> values) {
		filters.add(Filter.builder()
				.name(name)
				.values(values)
				.build());
		return this;
	}

	/**
	 * Adds an instance state filter.
	 * 
	 * @param state The instance state (e.g., "running", "stopped")
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withInstanceState(String state) {
		return withFilter(AWSConstants.FILTER_INSTANCE_STATE, state);
	}

	/**
	 * Adds a filter for running instances.
	 * 
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withRunningInstances() {
		return withInstanceState(AWSConstants.STATE_RUNNING);
	}

	/**
	 * Adds a filter for stopped instances.
	 * 
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withStoppedInstances() {
		return withInstanceState(AWSConstants.STATE_STOPPED);
	}

	/**
	 * Adds an instance type filter.
	 * 
	 * @param instanceType The EC2 instance type
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withInstanceType(String instanceType) {
		return withFilter(AWSConstants.FILTER_INSTANCE_TYPE, instanceType);
	}

	/**
	 * Adds a tag key filter.
	 * 
	 * @param tagKey The tag key to filter on
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withTagKey(String tagKey) {
		return withFilter(AWSConstants.FILTER_TAG_KEY, tagKey);
	}

	/**
	 * Adds a tag value filter.
	 * 
	 * @param tagValue The tag value to filter on
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withTagValue(String tagValue) {
		return withFilter(AWSConstants.FILTER_TAG_VALUE, tagValue);
	}

	/**
	 * Adds a filter for a specific tag key/value pair.
	 * 
	 * @param tagKey   The tag key
	 * @param tagValue The tag value
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withTag(String tagKey, String tagValue) {
		return withFilter(AWSConstants.TAG_PREFIX + tagKey, tagValue);
	}

	/**
	 * Adds a Name tag filter.
	 * 
	 * @param name The name to filter on
	 * @return This builder for method chaining
	 */
	public AWSFilterBuilder withNameTag(String name) {
		return withTag(AWSConstants.TAG_NAME, name);
	}

	/**
	 * Gets the built filters.
	 * 
	 * @return The list of built Filter objects
	 */
	public List<Filter> build() {
		return new ArrayList<>(filters);
	}
}