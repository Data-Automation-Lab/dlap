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
 * Constants related to database fields, tables, and schemas.
 * This class centralizes string literals used in database operations.
 */
public final class DatabaseConstants {
	private DatabaseConstants() {
		// Prevent instantiation
	}

	// Table names
	public static final String RESOURCES_TABLE = "resources";
	public static final String RESOURCE_CHANGES_TABLE = "resource_changes";

	// Common resource field names
	public static final String FIELD_RESOURCE_ID = "resource_id";
	public static final String FIELD_RESOURCE_TYPE_ID = "resource_type_id";
	public static final String FIELD_NAME = "name";
	public static final String FIELD_PROJECT_ID = "project_id";
	public static final String FIELD_ACCOUNT_ID = "account_id";
	public static final String FIELD_REGION = "region";
	public static final String FIELD_ZONE = "zone";
	public static final String FIELD_LOCATION = "location";
	public static final String FIELD_CREATED_AT = "created_at";
	public static final String FIELD_UPDATED_AT = "updated_at";
	public static final String FIELD_LAST_DISCOVERED_AT = "last_discovered_at";
	public static final String FIELD_TAGS = "tags";
	public static final String FIELD_PROPERTIES = "properties";
	public static final String FIELD_RAW_JSON = "raw_json";

	// Tag field names
	public static final String FIELD_TAG_KEY = "key";
	public static final String FIELD_TAG_VALUE = "value";

	// Change field names
	public static final String FIELD_CHANGE_ID = "change_id";
	public static final String FIELD_CHANGE_TYPE = "change_type";
	public static final String FIELD_TIMESTAMP = "timestamp";
	public static final String FIELD_ACTOR = "actor";
	public static final String FIELD_ACTOR_EMAIL = "actor_email";
	public static final String FIELD_DETAILS = "details";

	// EC2 specific tag fields
	public static final String EC2_NAME_TAG = "Name";
}