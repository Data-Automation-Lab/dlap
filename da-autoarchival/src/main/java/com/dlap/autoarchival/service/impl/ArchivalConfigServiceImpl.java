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

package com.dlap.autoarchival.service.impl;

import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.mapper.ArchivalConfigMapper;
import com.dlap.autoarchival.model.ArchivalConfigEntity;
import com.dlap.autoarchival.repository.ArchivalConfigRepository;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.TierResolver;
import com.dlap.autoarchival.service.IArchivalConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Optional;

@Service("archivalConfigService") // Specify bean name to avoid conflict if InMemory still exists
@RequiredArgsConstructor
@Slf4j
public class ArchivalConfigServiceImpl implements IArchivalConfigService {

    private final ArchivalConfigRepository configRepository;
    private final ArchivalConfigMapper configMapper;
    private static final Long GLOBAL_CONFIG_ID = 1L;

    @PostConstruct
    public void init() {
        // Ensure a default config exists if the DB is empty
        if (!configRepository.existsById(GLOBAL_CONFIG_ID)) {
            log.info("No global archival configuration found. Initializing with default values.");
            ArchivalConfigEntity defaultConfig = new ArchivalConfigEntity(
                    GLOBAL_CONFIG_ID,
                    false, // enabled
                    TierResolver.DEFAULT_TIER, // defaultArchivalTier: COLD = S3 GLACIER_IR, GCS COLDLINE, Azure Cold
                    3650L, // defaultRetentionDays (10 years)
                    true, // requireApprovalForArchival
                    true,  // requireApprovalForRestore
                    new HashMap<>() // providerConfigs
            );
            configRepository.save(defaultConfig);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ArchivalConfigDTO getArchivalConfig() {
        log.debug("Fetching global archival configuration.");
        Optional<ArchivalConfigEntity> entityOptional = configRepository.findById(GLOBAL_CONFIG_ID);
        // Should always exist due to PostConstruct, but handle defensively
        return entityOptional.map(configMapper::toDto)
                .orElseGet(() -> {
                    log.warn("Global archival configuration (ID: {}) not found, returning empty DTO. This should not happen after initialization.", GLOBAL_CONFIG_ID);
                    return new ArchivalConfigDTO(); // Or throw exception
                });
    }

    @Override
    @Transactional
    public void updateArchivalConfig(ArchivalConfigDTO archivalConfigDTO) {
        if (archivalConfigDTO == null) {
            throw new IllegalArgumentException("Archival configuration DTO cannot be null.");
        }
        requireValidTier(archivalConfigDTO.getDefaultArchivalTier());
        log.info("Updating global archival configuration.");
        ArchivalConfigEntity entity = configRepository.findById(GLOBAL_CONFIG_ID)
                .orElseThrow(() -> {
                    log.error("Global archival config (ID: {}) not found for update. This is unexpected.", GLOBAL_CONFIG_ID);
                    return new IllegalStateException("Global archival configuration not found for update.");
                });
        
        configMapper.updateEntityFromDto(archivalConfigDTO, entity);
        // Ensure ID is not changed by mapper if DTO carries it over
        entity.setId(GLOBAL_CONFIG_ID); 
        configRepository.save(entity);
        log.info("Global archival configuration updated successfully.");
    }

    /**
     * Refuses a default tier that some cloud cannot use. The shipped default used to be
     * {@code STANDARD_ARCHIVE}, which no cloud has, so every archive that fell back to it failed at the
     * provider (RET-03); a tier that does not resolve on S3, GCS and Azure is now refused when it is saved.
     */
    private static void requireValidTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return;
        }
        for (String provider : new String[] {"AWS", "GCP", "AZURE"}) {
            try {
                TierResolver.resolve(provider, tier);
            } catch (RuleException e) {
                throw new IllegalArgumentException("defaultArchivalTier: " + e.getMessage(), e);
            }
        }
    }
}
