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

package com.dlap.discovery.log.service.gcp.persistence.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Entity representing exclusion filters for GCP log processing.
 * Exclusion filters allow filtering out specific log entries based on Cloud
 * Logging filter syntax.
 */
@Entity
@Table(name = "gcp_log_exclusion_filters", uniqueConstraints = @UniqueConstraint(columnNames = { "account_id" }))
public class GcpLogExclusionFilter {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false)
	private String accountId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "filters_json")
	private String filtersJson;

	@CreationTimestamp
	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	/**
	 * Default constructor required by JPA.
	 */
	public GcpLogExclusionFilter() {
	}

	/**
	 * Constructor with required fields.
	 *
	 * @param accountId   The GCP account/project ID
	 * @param filtersJson The JSON array of exclusion filter strings
	 */
	public GcpLogExclusionFilter(String accountId, String filtersJson) {
		this.accountId = accountId;
		this.filtersJson = filtersJson;
	}

	// Getters and Setters

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}

	public String getFiltersJson() {
		return filtersJson;
	}

	public void setFiltersJson(String filtersJson) {
		this.filtersJson = filtersJson;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	/**
	 * Updates the updatedAt timestamp before any update operation.
	 */
	@PreUpdate
	public void preUpdate() {
		this.updatedAt = java.time.LocalDateTime.now();
	}
}