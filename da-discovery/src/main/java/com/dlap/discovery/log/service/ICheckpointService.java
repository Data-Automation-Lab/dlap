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

package com.dlap.discovery.log.service;

import java.time.Instant;
import java.util.Optional;

import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Interface for managing checkpoints during log analysis.
 * Provides a cloud-agnostic way to store and retrieve timestamps
 * for incremental log processing.
 */
public interface ICheckpointService {

	/**
	 * Retrieves a checkpoint timestamp for a specific account and provider.
	 *
	 * @param provider  The cloud provider (GCP, AWS, etc.)
	 * @param accountId The account/project/tenant ID
	 * @param context   Optional additional context (e.g., job ID, resource type)
	 * @return The checkpoint timestamp if found, otherwise empty
	 */
	Optional<Instant> getCheckpoint(CloudProvider provider, String accountId, String context);

	/**
	 * Stores a checkpoint timestamp for a specific account and provider.
	 *
	 * @param provider  The cloud provider (GCP, AWS, etc.)
	 * @param accountId The account/project/tenant ID
	 * @param timestamp The timestamp to store
	 * @param context   Optional additional context (e.g., job ID, resource type)
	 * @return True if the operation was successful
	 */
	boolean setCheckpoint(CloudProvider provider, String accountId, Instant timestamp, String context);

	/**
	 * Deletes a checkpoint for a specific account and provider.
	 *
	 * @param provider  The cloud provider (GCP, AWS, etc.)
	 * @param accountId The account/project/tenant ID
	 * @param context   Optional additional context (e.g., job ID, resource type)
	 * @return True if the operation was successful
	 */
	boolean deleteCheckpoint(CloudProvider provider, String accountId, String context);

	/**
	 * Constructs a checkpoint key from provider, account, and context.
	 * Useful for consistent key generation across implementations.
	 *
	 * @param provider  The cloud provider
	 * @param accountId The account/project/tenant ID
	 * @param context   Optional additional context
	 * @return A string key for checkpoint storage/retrieval
	 */
	default String buildCheckpointKey(CloudProvider provider, String accountId, String context) {
		String baseKey = String.format("checkpoint_%s_%s", provider.name().toLowerCase(), accountId);
		if (context != null && !context.isEmpty()) {
			return baseKey + "_" + context;
		}
		return baseKey;
	}

	/**
	 * Gets the last checkpoint for a provider and account.
	 * 
	 * @param provider  The cloud provider
	 * @param accountId The account/project ID
	 * @return The last checkpoint, or a default if none exists
	 */
	Instant getLastCheckpoint(CloudProvider provider, String accountId);

	/**
	 * Updates the checkpoint for a provider and account.
	 * 
	 * @param provider   The cloud provider
	 * @param accountId  The account/project ID
	 * @param checkpoint The new checkpoint
	 * @return true if successful, false otherwise
	 */
	boolean updateCheckpoint(CloudProvider provider, String accountId, Instant checkpoint);
}