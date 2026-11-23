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

package com.dlap.discovery.client.rest.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object for creating a new discovery job via REST API.
 */
public class CreateJobRequest {

	/** Longest name, account id, resource type id or parameter key accepted. */
	public static final int MAX_TEXT_LENGTH = 255;

	/** Longest parameter value accepted. */
	public static final int MAX_PARAMETER_VALUE_LENGTH = 2048;

	/** Most resource types or parameters one job may carry. */
	public static final int MAX_ENTRIES = 100;

	@Size(max = MAX_TEXT_LENGTH)
	private String jobName;

	@NotBlank
	@Size(max = MAX_TEXT_LENGTH)
	private String accountId;

	@Size(max = MAX_ENTRIES)
	private List<@NotBlank @Size(max = MAX_TEXT_LENGTH) String> resourceTypeIds;

	@Size(max = MAX_ENTRIES)
	private Map<@NotBlank @Size(max = MAX_TEXT_LENGTH) String, @Size(max = MAX_PARAMETER_VALUE_LENGTH) String> parameters;
	// Add ExecutionMode and potentially scheduleInfo if needed for creation
	// private String executionMode;
	// private String scheduleInfo;

	// Getters and Setters
	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}

	public List<String> getResourceTypeIds() {
		return resourceTypeIds;
	}

	public void setResourceTypeIds(List<String> resourceTypeIds) {
		this.resourceTypeIds = resourceTypeIds;
	}

	public Map<String, String> getParameters() {
		return parameters;
	}

	public void setParameters(Map<String, String> parameters) {
		this.parameters = parameters;
	}
}