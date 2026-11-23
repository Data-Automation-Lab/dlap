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
 * Constants related to resource change operations and types.
 * This class centralizes string literals used in change tracking.
 */
public final class ResourceChangeConstants {
	private ResourceChangeConstants() {
		// Prevent instantiation
	}

	// Change type constants
	public static final String CHANGE_TYPE_CREATE = "CREATE";
	public static final String CHANGE_TYPE_UPDATE = "UPDATE";
	public static final String CHANGE_TYPE_DELETE = "DELETE";
	public static final String CHANGE_TYPE_ACCESS = "ACCESS";
	public static final String CHANGE_TYPE_PERMISSION = "PERMISSION";
	public static final String CHANGE_TYPE_UNKNOWN = "UNKNOWN";

	// Operation keywords for change detection
	public static final String OPERATION_CREATE = "create";
	public static final String OPERATION_INSERT = "insert";
	public static final String OPERATION_UPDATE = "update";
	public static final String OPERATION_PATCH = "patch";
	public static final String OPERATION_DELETE = "delete";
	public static final String OPERATION_GET = "get";
	public static final String OPERATION_LIST = "list";
	public static final String OPERATION_READ = "read";
	public static final String OPERATION_IAM = "iam";
	public static final String OPERATION_SETIAM = "setiam";

	// Method name patterns for log filtering
	public static final String METHOD_PATTERN_CREATE = "create*";
	public static final String METHOD_PATTERN_UPDATE = "update*";
	public static final String METHOD_PATTERN_DELETE = "delete*";
	public static final String METHOD_PATTERN_GET = "get*";
	public static final String METHOD_PATTERN_IAM = "iam*";
	public static final String METHOD_PATTERN_ANY = "*";

	// Detail field names
	public static final String DETAIL_RESOURCE_PATH = "resourcePath";
	public static final String DETAIL_RESOURCE_NAME = "resourceName";
	public static final String DETAIL_SOURCE_IP = "sourceIp";

	// Actor defaults
	public static final String ACTOR_SYSTEM = "system";
}