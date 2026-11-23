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
 * Constants related to logging and log analysis across all cloud providers.
 * This class centralizes string literals used in log analysis services.
 */
public final class LoggingConstants {
	private LoggingConstants() {
		// Prevent instantiation
	}

	// Common logging constants
	public static final String TIMESTAMP_FILTER_FORMAT = "timestamp>=%s";
	public static final String TIMESTAMP_RANGE_FILTER_FORMAT = "timestamp>=%s AND timestamp<=%s";

	// Provider names
	public static final String GCP_PROVIDER_NAME = "GCP";
	public static final String AWS_PROVIDER_NAME = "AWS";
	public static final String AZURE_PROVIDER_NAME = "AZURE";
	public static final String ORACLE_PROVIDER_NAME = "OCI";

	// Event field names
	public static final String EVENT_ID = "eventId";
	public static final String EVENT_NAME = "eventName";
	public static final String EVENT_SOURCE = "eventSource";
	public static final String EVENT_TIME = "eventTime";
	public static final String USERNAME = "username";
	public static final String EVENT_CATEGORY = "eventCategory";

	// GCP specific logging constants
	public static final String GCP_AUDIT_LOG_NAME = "cloudaudit.googleapis.com/activity";
	public static final String GCP_LOG_NAME_FORMAT = "projects/%s/logs/%s";
	public static final String GCP_LOG_FILTER_TEMPLATE = "logName=" + GCP_LOG_NAME_FORMAT + " AND "
			+ TIMESTAMP_RANGE_FILTER_FORMAT;
	public static final String GCP_RESOURCE_TYPE_FILTER_FORMAT = "resource.type=\"%s\"";
	public static final String GCP_METHOD_NAME_FILTER_FORMAT = "protoPayload.methodName: \"%s\"";

	// AWS specific event sources
	public static final String AWS_EC2_EVENT_SOURCE = "ec2.amazonaws.com";
	public static final String AWS_S3_EVENT_SOURCE = "s3.amazonaws.com";
	public static final String AWS_RDS_EVENT_SOURCE = "rds.amazonaws.com";
	public static final String AWS_LAMBDA_EVENT_SOURCE = "lambda.amazonaws.com";
	public static final String AWS_DYNAMODB_EVENT_SOURCE = "dynamodb.amazonaws.com";

	// Log filter operators
	public static final String FILTER_AND = " AND ";
	public static final String FILTER_OR = " OR ";
	public static final String FILTER_GROUP_START = "(";
	public static final String FILTER_GROUP_END = ")";
}