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

package com.dlap.discovery.log.service.gcp.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.ICheckpointService;
import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import com.dlap.discovery.log.service.gcp.config.GcpLogSourceType;
import com.dlap.discovery.log.service.gcp.persistence.entity.GcpLogExclusionFilter;
import com.dlap.discovery.log.service.gcp.persistence.entity.GcpLogSourceDetail;
import com.dlap.discovery.log.service.gcp.persistence.repository.IGcpLogExclusionFilterRepository;
import com.dlap.discovery.log.service.gcp.persistence.repository.IGcpLogSourceDetailRepository;
import com.dlap.discovery.log.service.gcp.service.IGcpLogConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Implementation of IGcpLogConfigService using Spring Data JPA.
 * Manages GCP log configuration data in the database.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class GcpLogConfigServiceImpl implements IGcpLogConfigService {

	private static final Logger log = LoggerFactory.getLogger(GcpLogConfigServiceImpl.class);

	private final ICheckpointService checkpointService;
	private final IGcpLogSourceDetailRepository sourceDetailRepository;
	private final IGcpLogExclusionFilterRepository exclusionFilterRepository;
	private final ObjectMapper objectMapper;

	/**
	 * Constructor for the service with required repositories.
	 * 
	 * @param checkpointService         Service for checkpoint management
	 * @param sourceDetailRepository    Repository for source detail entities
	 * @param exclusionFilterRepository Repository for exclusion filter entities
	 * @param objectMapper              Jackson ObjectMapper for JSON processing
	 */
	@Autowired
	public GcpLogConfigServiceImpl(
			ICheckpointService checkpointService,
			IGcpLogSourceDetailRepository sourceDetailRepository,
			IGcpLogExclusionFilterRepository exclusionFilterRepository,
			ObjectMapper objectMapper) {
		this.checkpointService = checkpointService;
		this.sourceDetailRepository = sourceDetailRepository;
		this.exclusionFilterRepository = exclusionFilterRepository;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional
	public boolean saveCheckpoint(String accountId, AnalysisOptions options, Instant checkpoint) {
		if (accountId == null || accountId.isBlank() || checkpoint == null) {
			return false;
		}

		try {
			String optionsHash = generateOptionsHash(options);
			return checkpointService.setCheckpoint(CloudProvider.GCP, accountId, checkpoint, optionsHash);
		} catch (Exception e) {
			log.error("Failed to save checkpoint for account {}: {}", accountId, e.getMessage(), e);
			return false;
		}
	}

	@Override
	public Instant loadCheckpoint(String accountId, AnalysisOptions options) {
		if (accountId == null || accountId.isBlank()) {
			return null;
		}

		try {
			String optionsHash = generateOptionsHash(options);
			Optional<Instant> checkpoint = checkpointService.getCheckpoint(CloudProvider.GCP, accountId, optionsHash);
			return checkpoint.orElse(null);
		} catch (Exception e) {
			log.error("Failed to load checkpoint for account {}: {}", accountId, e.getMessage(), e);
			return null;
		}
	}

	@Override
	@Transactional
	public boolean saveSourceDetails(String accountId, GcpLogSourceType sourceType, Map<String, String> details) {
		if (accountId == null || accountId.isBlank() || sourceType == null) {
			return false;
		}

		try {
			String detailsJson = objectMapper.writeValueAsString(details);

			// Find existing source details or create new one
			Optional<GcpLogSourceDetail> existingDetails = sourceDetailRepository
					.findByAccountIdAndSourceType(accountId, sourceType);

			GcpLogSourceDetail entity;
			if (existingDetails.isPresent()) {
				entity = existingDetails.get();
				entity.setDetailsJson(detailsJson);
			} else {
				entity = new GcpLogSourceDetail(accountId, sourceType, detailsJson);
			}

			sourceDetailRepository.save(entity);
			log.info("Saved source details for account {} and type {}", accountId, sourceType);
			return true;
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize source details for account {}: {}", accountId, e.getMessage(), e);
			return false;
		} catch (Exception e) {
			log.error("Failed to save source details for account {}: {}", accountId, e.getMessage(), e);
			return false;
		}
	}

	@Override
	public Map<String, String> loadSourceDetails(String accountId, GcpLogSourceType sourceType) {
		if (accountId == null || accountId.isBlank() || sourceType == null) {
			return new HashMap<>();
		}

		try {
			Optional<GcpLogSourceDetail> sourceDetail = sourceDetailRepository.findByAccountIdAndSourceType(accountId,
					sourceType);

			if (sourceDetail.isPresent() && sourceDetail.get().getDetailsJson() != null) {
				String detailsJson = sourceDetail.get().getDetailsJson();
				return objectMapper.readValue(detailsJson, new TypeReference<Map<String, String>>() {
				});
			}

			return new HashMap<>();
		} catch (JsonProcessingException e) {
			log.error("Failed to deserialize source details for account {}: {}", accountId, e.getMessage(), e);
			return new HashMap<>();
		} catch (Exception e) {
			log.error("Failed to load source details for account {}: {}", accountId, e.getMessage(), e);
			return new HashMap<>();
		}
	}

	@Override
	@Transactional
	public boolean saveExclusionFilters(String accountId, List<String> filters) {
		if (accountId == null || accountId.isBlank()) {
			return false;
		}

		try {
			String filtersJson = objectMapper.writeValueAsString(filters);

			// Find existing filters or create new one
			Optional<GcpLogExclusionFilter> existingFilters = exclusionFilterRepository.findByAccountId(accountId);

			GcpLogExclusionFilter entity;
			if (existingFilters.isPresent()) {
				entity = existingFilters.get();
				entity.setFiltersJson(filtersJson);
			} else {
				entity = new GcpLogExclusionFilter(accountId, filtersJson);
			}

			exclusionFilterRepository.save(entity);
			log.info("Saved exclusion filters for account {}", accountId);
			return true;
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize exclusion filters for account {}: {}", accountId, e.getMessage(), e);
			return false;
		} catch (Exception e) {
			log.error("Failed to save exclusion filters for account {}: {}", accountId, e.getMessage(), e);
			return false;
		}
	}

	@Override
	public List<String> loadExclusionFilters(String accountId) {
		if (accountId == null || accountId.isBlank()) {
			return new ArrayList<>();
		}

		try {
			Optional<GcpLogExclusionFilter> exclusionFilter = exclusionFilterRepository.findByAccountId(accountId);

			if (exclusionFilter.isPresent() && exclusionFilter.get().getFiltersJson() != null) {
				String filtersJson = exclusionFilter.get().getFiltersJson();
				return objectMapper.readValue(filtersJson, new TypeReference<List<String>>() {
				});
			}

			return new ArrayList<>();
		} catch (JsonProcessingException e) {
			log.error("Failed to deserialize exclusion filters for account {}: {}", accountId, e.getMessage(), e);
			return new ArrayList<>();
		} catch (Exception e) {
			log.error("Failed to load exclusion filters for account {}: {}", accountId, e.getMessage(), e);
			return new ArrayList<>();
		}
	}

	@Override
	public String generateOptionsHash(AnalysisOptions options) {
		if (options == null) {
			return "default";
		}

		StringBuilder hashInput = new StringBuilder();

		// Add resource types
		if (options.getResourceTypes() != null && !options.getResourceTypes().isEmpty()) {
			hashInput.append("rt:");
			for (ResourceType type : options.getResourceTypes()) {
				hashInput.append(type.id()).append(",");
			}
		}

		// Add change types
		if (options.getChangeTypes() != null && !options.getChangeTypes().isEmpty()) {
			hashInput.append("|ct:");
			for (String changeType : options.getChangeTypes()) {
				hashInput.append(changeType).append(",");
			}
		}

		// Add filters
		if (options.getFilters() != null && !options.getFilters().isEmpty()) {
			hashInput.append("|f:");
			for (Map.Entry<String, String> filter : options.getFilters().entrySet()) {
				hashInput.append(filter.getKey()).append("=").append(filter.getValue()).append(",");
			}
		}

		if (hashInput.length() == 0) {
			return "default";
		}

		// Generate a hash of the input
		try {
			byte[] bytes = hashInput.toString().getBytes();
			return Base64.getEncoder().encodeToString(
					java.security.MessageDigest.getInstance("MD5").digest(bytes));
		} catch (Exception e) {
			log.warn("Failed to generate hash for options, using fallback: {}", e.getMessage());
			return String.valueOf(hashInput.toString().hashCode());
		}
	}
}