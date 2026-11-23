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

package com.dlap.discovery.common.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dlap.discovery.common.constants.DatabaseConstants;

/**
 * Builder for constructing database queries with consistent field names.
 * This class helps create standardized SQL statements and field mappings.
 */
public class DatabaseQueryBuilder {
	private final StringBuilder queryBuilder = new StringBuilder();
	private final Map<String, Object> parameters = new HashMap<>();
	private final List<String> selectedFields = new ArrayList<>();
	private final List<String> conditions = new ArrayList<>();
	private String tableName;

	/**
	 * Creates a new DatabaseQueryBuilder.
	 */
	public DatabaseQueryBuilder() {
	}

	/**
	 * Sets the table to query.
	 * 
	 * @param tableName The table name
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withTable(String tableName) {
		this.tableName = tableName;
		return this;
	}

	/**
	 * Uses the resources table.
	 * 
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withResourcesTable() {
		return withTable(DatabaseConstants.RESOURCES_TABLE);
	}

	/**
	 * Uses the resource changes table.
	 * 
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withResourceChangesTable() {
		return withTable(DatabaseConstants.RESOURCE_CHANGES_TABLE);
	}

	/**
	 * Adds a field to select.
	 * 
	 * @param fieldName The field name to select
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder selectField(String fieldName) {
		selectedFields.add(fieldName);
		return this;
	}

	/**
	 * Adds common resource fields to select.
	 * 
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder selectResourceFields() {
		selectField(DatabaseConstants.FIELD_RESOURCE_ID);
		selectField(DatabaseConstants.FIELD_RESOURCE_TYPE_ID);
		selectField(DatabaseConstants.FIELD_NAME);
		selectField(DatabaseConstants.FIELD_PROJECT_ID);
		selectField(DatabaseConstants.FIELD_ACCOUNT_ID);
		selectField(DatabaseConstants.FIELD_REGION);
		selectField(DatabaseConstants.FIELD_ZONE);
		selectField(DatabaseConstants.FIELD_CREATED_AT);
		selectField(DatabaseConstants.FIELD_UPDATED_AT);
		selectField(DatabaseConstants.FIELD_LAST_DISCOVERED_AT);
		selectField(DatabaseConstants.FIELD_TAGS);
		selectField(DatabaseConstants.FIELD_PROPERTIES);
		return this;
	}

	/**
	 * Adds common change fields to select.
	 * 
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder selectChangeFields() {
		selectField(DatabaseConstants.FIELD_CHANGE_ID);
		selectField(DatabaseConstants.FIELD_RESOURCE_ID);
		selectField(DatabaseConstants.FIELD_RESOURCE_TYPE_ID);
		selectField(DatabaseConstants.FIELD_PROJECT_ID);
		selectField(DatabaseConstants.FIELD_CHANGE_TYPE);
		selectField(DatabaseConstants.FIELD_TIMESTAMP);
		selectField(DatabaseConstants.FIELD_ACTOR);
		selectField(DatabaseConstants.FIELD_DETAILS);
		return this;
	}

	/**
	 * Adds a WHERE condition with a parameter.
	 * 
	 * @param fieldName      The field name to filter on
	 * @param operator       The SQL operator to use (e.g., "=", ">", "LIKE")
	 * @param parameterName  The parameter name
	 * @param parameterValue The parameter value
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withCondition(String fieldName, String operator, String parameterName,
			Object parameterValue) {
		conditions.add(fieldName + " " + operator + " :" + parameterName);
		parameters.put(parameterName, parameterValue);
		return this;
	}

	/**
	 * Adds a WHERE condition for resource ID.
	 * 
	 * @param resourceId The resource ID to filter for
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withResourceId(String resourceId) {
		return withCondition(DatabaseConstants.FIELD_RESOURCE_ID, "=", "res_id", resourceId);
	}

	/**
	 * Adds a WHERE condition for resource type ID.
	 * 
	 * @param resourceTypeId The resource type ID to filter for
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withResourceTypeId(String resourceTypeId) {
		return withCondition(DatabaseConstants.FIELD_RESOURCE_TYPE_ID, "=", "res_type_id", resourceTypeId);
	}

	/**
	 * Adds a WHERE condition for project ID.
	 * 
	 * @param projectId The project ID to filter for
	 * @return This builder for method chaining
	 */
	public DatabaseQueryBuilder withProjectId(String projectId) {
		return withCondition(DatabaseConstants.FIELD_PROJECT_ID, "=", "project_id", projectId);
	}

	/**
	 * Builds a SELECT query.
	 * 
	 * @return The SQL query string
	 */
	public String buildSelectQuery() {
		if (tableName == null) {
			throw new IllegalStateException("Table name must be set");
		}

		if (selectedFields.isEmpty()) {
			throw new IllegalStateException("At least one field must be selected");
		}

		queryBuilder.setLength(0);
		queryBuilder.append("SELECT ");
		for (int i = 0; i < selectedFields.size(); i++) {
			if (i > 0) {
				queryBuilder.append(", ");
			}
			queryBuilder.append(selectedFields.get(i));
		}

		queryBuilder.append(" FROM ").append(tableName);

		if (!conditions.isEmpty()) {
			queryBuilder.append(" WHERE ");
			for (int i = 0; i < conditions.size(); i++) {
				if (i > 0) {
					queryBuilder.append(" AND ");
				}
				queryBuilder.append(conditions.get(i));
			}
		}

		return queryBuilder.toString();
	}

	/**
	 * Gets the parameters for the query.
	 * 
	 * @return Map of parameter names to values
	 */
	public Map<String, Object> getParameters() {
		return new HashMap<>(parameters);
	}
}