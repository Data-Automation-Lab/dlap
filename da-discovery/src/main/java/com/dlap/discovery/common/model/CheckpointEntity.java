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

package com.dlap.discovery.common.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entity for storing log analysis checkpoints in the database.
 */
@Entity
@Table(name = "log_analysis_checkpoints")
public class CheckpointEntity {

	@Id
	@Column(name = "checkpoint_key", length = 255, nullable = false)
	private String id;

	@Column(name = "timestamp", nullable = false)
	private Instant timestamp;

	@Column(name = "provider", length = 50)
	private String provider;

	@Column(name = "account_id", length = 255)
	private String accountId;

	@Column(name = "context", length = 255)
	private String context;

	@Column(name = "last_updated")
	private Instant lastUpdated;

	/**
	 * Default constructor for JPA.
	 */
	protected CheckpointEntity() {
	}

	/**
	 * Constructor with checkpoint ID.
	 * 
	 * @param id The checkpoint key
	 */
	public CheckpointEntity(String id) {
		this.id = id;
		this.lastUpdated = Instant.now();
	}

	/**
	 * Full constructor.
	 * 
	 * @param id        The checkpoint key
	 * @param timestamp The checkpoint timestamp
	 * @param provider  The cloud provider
	 * @param accountId The account ID
	 * @param context   Additional context
	 */
	public CheckpointEntity(String id, Instant timestamp, String provider, String accountId, String context) {
		this.id = id;
		this.timestamp = timestamp;
		this.provider = provider;
		this.accountId = accountId;
		this.context = context;
		this.lastUpdated = Instant.now();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}

	public String getContext() {
		return context;
	}

	public void setContext(String context) {
		this.context = context;
	}

	public Instant getLastUpdated() {
		return lastUpdated;
	}

	public void setLastUpdated(Instant lastUpdated) {
		this.lastUpdated = lastUpdated;
	}

	@Override
	public String toString() {
		return "CheckpointEntity{" +
				"id='" + id + '\'' +
				", timestamp=" + timestamp +
				", provider='" + provider + '\'' +
				", accountId='" + accountId + '\'' +
				", context='" + context + '\'' +
				", lastUpdated=" + lastUpdated +
				'}';
	}
}