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

package com.dlap.discovery.log.service.impl;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.discovery.common.model.CheckpointEntity;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.ICheckpointRepository;
import com.dlap.discovery.log.service.ICheckpointService;

/**
 * Default implementation of the ICheckpointService.
 * Provides database persistence of checkpoints with memory caching.
 */
@Service
public class DefaultCheckpointService implements ICheckpointService {

	private static final Logger log = LoggerFactory.getLogger(DefaultCheckpointService.class);

	private final ICheckpointRepository checkpointRepository;

	// Memory cache to reduce database queries
	private final ConcurrentMap<String, Instant> checkpointCache = new ConcurrentHashMap<>();

	@Autowired
	public DefaultCheckpointService(ICheckpointRepository checkpointRepository) {
		this.checkpointRepository = checkpointRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> getCheckpoint(CloudProvider provider, String accountId, String context) {
		String key = buildCheckpointKey(provider, accountId, context);

		// Check cache first
		Instant cachedValue = checkpointCache.get(key);
		if (cachedValue != null) {
			log.debug("Checkpoint cache hit for key: {}", key);
			return Optional.of(cachedValue);
		}

		// Otherwise check database
		log.debug("Retrieving checkpoint from database for key: {}", key);
		Optional<CheckpointEntity> entity = checkpointRepository.findById(key);

		if (entity.isPresent()) {
			Instant timestamp = entity.get().getTimestamp();
			// Update cache
			checkpointCache.put(key, timestamp);
			return Optional.of(timestamp);
		}

		return Optional.empty();
	}

	@Override
	@Transactional
	public boolean setCheckpoint(CloudProvider provider, String accountId, Instant timestamp, String context) {
		String key = buildCheckpointKey(provider, accountId, context);

		try {
			log.debug("Storing checkpoint with key: {}, timestamp: {}", key, timestamp);

			// Update or create entity
			CheckpointEntity entity = checkpointRepository.findById(key)
					.orElse(new CheckpointEntity(key));

			entity.setTimestamp(timestamp);
			entity.setProvider(provider.name());
			entity.setAccountId(accountId);
			entity.setContext(context);
			entity.setLastUpdated(Instant.now());

			checkpointRepository.save(entity);

			// Update cache
			checkpointCache.put(key, timestamp);

			return true;
		} catch (Exception e) {
			log.error("Failed to store checkpoint with key: {}", key, e);
			return false;
		}
	}

	@Override
	@Transactional
	public boolean deleteCheckpoint(CloudProvider provider, String accountId, String context) {
		String key = buildCheckpointKey(provider, accountId, context);

		try {
			log.debug("Deleting checkpoint with key: {}", key);

			checkpointRepository.deleteById(key);

			// Remove from cache
			checkpointCache.remove(key);

			return true;
		} catch (Exception e) {
			log.error("Failed to delete checkpoint with key: {}", key, e);
			return false;
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Instant getLastCheckpoint(CloudProvider provider, String accountId) {
		try {
			log.debug("Getting last checkpoint for provider: {}, accountId: {}", provider, accountId);

			// Use the default context for last checkpoint
			String key = buildCheckpointKey(provider, accountId, "last");

			// Check cache first
			Instant cachedValue = checkpointCache.get(key);
			if (cachedValue != null) {
				return cachedValue;
			}

			// Otherwise check database
			Optional<CheckpointEntity> entity = checkpointRepository.findById(key);
			return entity.map(CheckpointEntity::getTimestamp).orElse(Instant.EPOCH);
		} catch (Exception e) {
			log.error("Failed to get last checkpoint for provider: {}, accountId: {}", provider, accountId, e);
			return Instant.EPOCH;
		}
	}

	@Override
	@Transactional
	public boolean updateCheckpoint(CloudProvider provider, String accountId, Instant checkpoint) {
		try {
			log.debug("Updating checkpoint for provider: {}, accountId: {}", provider, accountId);

			// Use the default context for updates
			return setCheckpoint(provider, accountId, checkpoint, "last");
		} catch (Exception e) {
			log.error("Failed to update checkpoint for provider: {}, accountId: {}", provider, accountId, e);
			return false;
		}
	}

	/**
	 * Clears the memory cache.
	 * Useful for testing or when memory pressure is high.
	 */
	public void clearCache() {
		log.info("Clearing checkpoint cache");
		checkpointCache.clear();
	}
}
