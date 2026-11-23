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
 * Constants related to AWS discovery and resources.
 * This class centralizes string literals used across AWS services and
 * resources.
 */
public final class AWSConstants {
	private AWSConstants() {
		// Prevent instantiation
	}

	// Resource types (should match IDs in configuration)
	public static final String RESOURCE_TYPE_EC2_INSTANCE = "aws_ec2_instance";
	public static final String RESOURCE_TYPE_S3_BUCKET = "aws_s3_bucket";
	public static final String RESOURCE_TYPE_RDS_INSTANCE = "aws_rds_instance";
	public static final String RESOURCE_TYPE_LAMBDA_FUNCTION = "aws_lambda_function";
	public static final String RESOURCE_TYPE_DYNAMO_TABLE = "aws_dynamodb_table";
	public static final String RESOURCE_TYPE_GLUE_TABLE = "aws_glue_table";

	// Service names
	public static final String SERVICE_EC2 = "Amazon EC2";
	public static final String SERVICE_S3 = "Amazon S3";
	public static final String SERVICE_RDS = "Amazon RDS";
	public static final String SERVICE_LAMBDA = "AWS Lambda";
	public static final String SERVICE_DYNAMODB = "Amazon DynamoDB";
	public static final String SERVICE_GLUE = "AWS Glue";

	// Tag keys
	public static final String TAG_NAME = "Name";
	public static final String TAG_ENVIRONMENT = "Environment";
	public static final String TAG_OWNER = "Owner";
	public static final String TAG_PREFIX = "tag:";

	// Resource states
	public static final String STATE_RUNNING = "running";
	public static final String STATE_STOPPED = "stopped";
	public static final String STATE_TERMINATED = "terminated";
	public static final String STATE_PENDING = "pending";

	// AWS API filter constants
	public static final String FILTER_INSTANCE_STATE = "instance-state-name";
	public static final String FILTER_INSTANCE_TYPE = "instance-type";
	public static final String FILTER_TAG_KEY = "tag-key";
	public static final String FILTER_TAG_VALUE = "tag-value";

	// AWS SDK error messages
	public static final String ERROR_CREDENTIALS = "Failed to validate AWS credentials";
	public static final String ERROR_EC2_CLIENT = "Failed to create EC2 client";
	public static final String ERROR_S3_CLIENT = "Failed to create S3 client";
	public static final String ERROR_RDS_CLIENT = "Failed to create RDS client";
}