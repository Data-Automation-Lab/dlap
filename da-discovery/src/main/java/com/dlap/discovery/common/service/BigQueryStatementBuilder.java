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
 * Builder class for constructing BigQuery SQL statements.
 * This class helps create standardized queries with named parameters for
 * BigQuery.
 */
public class BigQueryStatementBuilder {
	private final StringBuilder queryBuilder = new StringBuilder();
	private final Map<String, Object> parameters = new HashMap<>();
	private final List<String> selectFields = new ArrayList<>();
	private final List<String> whereConditions = new ArrayList<>();
	private final List<String> orderByFields = new ArrayList<>();
	private String tableName;
	private String limitClause;

	/**
	 * Creates a new BigQueryStatementBuilder.
	 */
	public BigQueryStatementBuilder() {
	}

	/**
	 * Sets the table to query.
	 * 
	 * @param tableName The table name
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder from(String tableName) {
		this.tableName = tableName;
		return this;
	}

	/**
	 * Sets the query to use the resources table.
	 * 
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder fromResources() {
		return from(DatabaseConstants.RESOURCES_TABLE);
	}

	/**
	 * Sets the query to use the resource changes table.
	 * 
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder fromResourceChanges() {
		return from(DatabaseConstants.RESOURCE_CHANGES_TABLE);
	}

	/**
	 * Adds a field to the SELECT clause.
	 * 
	 * @param fieldName The field name to select
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder select(String fieldName) {
		selectFields.add(fieldName);
		return this;
	}

	/**
	 * Adds all standard resource fields to the SELECT clause.
	 * 
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder selectResourceFields() {
		select(DatabaseConstants.FIELD_RESOURCE_ID);
		select(DatabaseConstants.FIELD_RESOURCE_TYPE_ID);
		select(DatabaseConstants.FIELD_NAME);
		select(DatabaseConstants.FIELD_PROJECT_ID);
		select(DatabaseConstants.FIELD_ACCOUNT_ID);
		select(DatabaseConstants.FIELD_REGION);
		select(DatabaseConstants.FIELD_ZONE);
		select(DatabaseConstants.FIELD_LOCATION);
		select(DatabaseConstants.FIELD_CREATED_AT);
		select(DatabaseConstants.FIELD_UPDATED_AT);
		select(DatabaseConstants.FIELD_LAST_DISCOVERED_AT);
		select(DatabaseConstants.FIELD_TAGS);
		select(DatabaseConstants.FIELD_PROPERTIES);
		select(DatabaseConstants.FIELD_RAW_JSON);
		return this;
	}

	/**
	 * Adds all standard change fields to the SELECT clause.
	 * 
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder selectChangeFields() {
		select(DatabaseConstants.FIELD_CHANGE_ID);
		select(DatabaseConstants.FIELD_RESOURCE_ID);
		select(DatabaseConstants.FIELD_RESOURCE_TYPE_ID);
		select(DatabaseConstants.FIELD_PROJECT_ID);
		select(DatabaseConstants.FIELD_CHANGE_TYPE);
		select(DatabaseConstants.FIELD_TIMESTAMP);
		select(DatabaseConstants.FIELD_ACTOR);
		select(DatabaseConstants.FIELD_DETAILS);
		return this;
	}

	/**
	 * Adds a WHERE condition with a named parameter.
	 * 
	 * @param fieldName The field name
	 * @param operator  The SQL operator (=, <, >, LIKE, etc.)
	 * @param paramName The parameter name
	 * @param value     The parameter value
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder where(String fieldName, String operator, String paramName, Object value) {
		whereConditions.add(String.format("%s %s @%s", fieldName, operator, paramName));
		parameters.put(paramName, value);
		return this;
	}

	/**
	 * Adds a WHERE condition for resource ID.
	 * 
	 * @param resourceId The resource ID to filter for
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder whereResourceId(String resourceId) {
		return where(DatabaseConstants.FIELD_RESOURCE_ID, "=", "resourceId", resourceId);
	}

	/**
	 * Adds a WHERE condition for resource type ID.
	 * 
	 * @param resourceTypeId The resource type ID to filter for
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder whereResourceTypeId(String resourceTypeId) {
		return where(DatabaseConstants.FIELD_RESOURCE_TYPE_ID, "=", "resourceTypeId", resourceTypeId);
	}

	/**
	 * Adds a WHERE condition for project ID.
	 * 
	 * @param projectId The project ID to filter for
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder whereProjectId(String projectId) {
		return where(DatabaseConstants.FIELD_PROJECT_ID, "=", "projectId", projectId);
	}

	/**
	 * Adds an ORDER BY clause.
	 * 
	 * @param fieldName The field to order by
	 * @param direction The sort direction ("ASC" or "DESC")
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder orderBy(String fieldName, String direction) {
		orderByFields.add(fieldName + " " + direction);
		return this;
	}

	/**
	 * Adds a LIMIT clause.
	 * 
	 * @param limit The maximum number of rows to return
	 * @return This builder for method chaining
	 */
	public BigQueryStatementBuilder limit(int limit) {
		this.limitClause = "LIMIT " + limit;
		return this;
	}

	/**
	 * Builds the final SQL query statement.
	 * 
	 * @return The constructed SQL query
	 */
	public String buildQuery() {
		if (tableName == null || tableName.isEmpty()) {
			throw new IllegalStateException("Table name must be specified using from()");
		}

		if (selectFields.isEmpty()) {
			throw new IllegalStateException("At least one field must be selected using select()");
		}

		queryBuilder.setLength(0);
		queryBuilder.append("SELECT ");

		// Append SELECT fields
		for (int i = 0; i < selectFields.size(); i++) {
			if (i > 0) {
				queryBuilder.append(", ");
			}
			queryBuilder.append(selectFields.get(i));
		}

		// Append FROM clause
		queryBuilder.append(" FROM ").append(tableName);

		// Append WHERE clause if conditions exist
		if (!whereConditions.isEmpty()) {
			queryBuilder.append(" WHERE ");

			for (int i = 0; i < whereConditions.size(); i++) {
				if (i > 0) {
					queryBuilder.append(" AND ");
				}
				queryBuilder.append(whereConditions.get(i));
			}
		}

		// Append ORDER BY clause if fields exist
		if (!orderByFields.isEmpty()) {
			queryBuilder.append(" ORDER BY ");

			for (int i = 0; i < orderByFields.size(); i++) {
				if (i > 0) {
					queryBuilder.append(", ");
				}
				queryBuilder.append(orderByFields.get(i));
			}
		}

		// Append LIMIT clause if specified
		if (limitClause != null && !limitClause.isEmpty()) {
			queryBuilder.append(" ").append(limitClause);
		}

		return queryBuilder.toString();
	}

	/**
	 * Gets the query parameters.
	 * 
	 * @return A map of parameter names to values
	 */
	public Map<String, Object> getParameters() {
		return new HashMap<>(parameters);
	}
}