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
 * Constants related to the discovery service and job operations.
 * This class centralizes string literals used across discovery services.
 */
public final class DiscoveryConstants {
	private DiscoveryConstants() {
		// Prevent instantiation
	}

	// Service identity constants
	public static final String AWS_DISCOVERY_SERVICE_ID = "AWS Discovery Service";
	public static final String GCP_DISCOVERY_SERVICE_ID = "GCP Discovery Service";
	public static final String AZURE_DISCOVERY_SERVICE_ID = "Azure Discovery Service";
	public static final String ORACLE_DISCOVERY_SERVICE_ID = "Oracle Discovery Service";

	// Job templates
	public static final String AWS_JOB_NAME_TEMPLATE = "AWS Job %s";
	public static final String GCP_JOB_NAME_TEMPLATE = "GCP Job %s";
	public static final String AZURE_JOB_NAME_TEMPLATE = "Azure Job %s";
	public static final String ORACLE_JOB_NAME_TEMPLATE = "Oracle Job %s";

	// Job ID format
	public static final int JOB_ID_PREFIX_LENGTH = 8;

	// Context parameter keys
	public static final String CONTEXT_PARAM_REGION = "region";
	public static final String CONTEXT_PARAM_PROJECT_ID = "projectId";
	public static final String CONTEXT_PARAM_RESOURCE_ID = "resourceId";
	public static final String CONTEXT_PARAM_RESOURCE_TYPE = "resourceType";
	public static final String CONTEXT_PARAM_ACCOUNT_ID = "accountId";
	public static final String CONTEXT_PARAM_TAG_KEY = "tagKey";
	public static final String CONTEXT_PARAM_TAG_VALUE = "tagValue";

	// Job status messages
	public static final String JOB_STATUS_CREATED = "Job created";
	public static final String JOB_STATUS_STARTING = "Job starting";
	public static final String JOB_STATUS_RUNNING = "Job running";
	public static final String JOB_STATUS_PAUSED = "Job paused";
	public static final String JOB_STATUS_COMPLETED = "Job completed";
	public static final String JOB_STATUS_FAILED = "Job failed";
	public static final String JOB_STATUS_CANCELED = "Job canceled";

	// Resource crawler status messages
	public static final String CRAWLER_STATUS_INITIALIZED = "Crawler initialized";
	public static final String CRAWLER_STATUS_RUNNING = "Crawler running";
	public static final String CRAWLER_STATUS_COMPLETED = "Crawler completed";
	public static final String CRAWLER_STATUS_FAILED = "Crawler failed";

	// Log message templates
	public static final String LOG_DISCOVERY_STARTING = "Starting {} discovery in region: {} for job {}";
	public static final String LOG_DISCOVERY_COMPLETED = "Completed {} discovery in region: {}, found {} resources";
	public static final String LOG_DISCOVERY_FAILED = "Failed {} discovery in region: {}: {}";
	public static final String LOG_CLIENT_INITIALIZED = "{} client initialized successfully for region: {}";
}