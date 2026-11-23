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

import com.dlap.discovery.log.service.gcp.config.GcpLogSourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Entity representing source details for GCP log processing.
 * Source details include configuration for different log sources (API,
 * BigQuery, GCS).
 */
@Entity
@Table(name = "gcp_log_source_details", uniqueConstraints = @UniqueConstraint(columnNames = { "account_id",
		"source_type" }))
public class GcpLogSourceDetail {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false)
	private String accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false)
	private GcpLogSourceType sourceType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "details_json")
	private String detailsJson;

	@CreationTimestamp
	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	/**
	 * Default constructor required by JPA.
	 */
	public GcpLogSourceDetail() {
	}

	/**
	 * Constructor with required fields.
	 *
	 * @param accountId   The GCP account/project ID
	 * @param sourceType  The type of log source
	 * @param detailsJson The JSON string containing source-specific details
	 */
	public GcpLogSourceDetail(String accountId, GcpLogSourceType sourceType, String detailsJson) {
		this.accountId = accountId;
		this.sourceType = sourceType;
		this.detailsJson = detailsJson;
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

	public GcpLogSourceType getSourceType() {
		return sourceType;
	}

	public void setSourceType(GcpLogSourceType sourceType) {
		this.sourceType = sourceType;
	}

	public String getDetailsJson() {
		return detailsJson;
	}

	public void setDetailsJson(String detailsJson) {
		this.detailsJson = detailsJson;
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