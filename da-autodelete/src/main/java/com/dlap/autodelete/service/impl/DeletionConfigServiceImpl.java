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

package com.dlap.autodelete.service.impl;

import com.dlap.autodelete.dto.DeletionConfigDTO;
import com.dlap.autodelete.mapper.DeletionConfigMapper;
import com.dlap.autodelete.model.DeletionConfigEntity;
import com.dlap.autodelete.repository.DeletionConfigRepository;
import com.dlap.autodelete.service.IDeletionConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Optional;

@Service("deletionConfigService") // Explicit bean name to distinguish from potential in-memory version
@RequiredArgsConstructor
@Slf4j
public class DeletionConfigServiceImpl implements IDeletionConfigService {

    private final DeletionConfigRepository configRepository;
    private final DeletionConfigMapper configMapper;
    @Autowired
    private ApplicationContext applicationContext;
    private static final Long GLOBAL_CONFIG_ID = 1L;

    /**
     * PostConstruct hook. Deliberately NOT annotated with {@code @Transactional}
     * because {@code @PostConstruct} runs before the Spring transaction aspect is
     * fully woven, and the combined annotation silently fails (no transaction is
     * ever opened). We therefore delegate to a sibling bean method through the
     * proxy so the {@code @Transactional} annotation on {@link #initDefaultConfig()}
     * actually takes effect.
     */
    @PostConstruct
    public void init() {
        log.debug("Running post-construct deletion config initialiser.");
        try {
            // Re-resolve the proxy from the context so that @Transactional is honoured.
            IDeletionConfigService self = applicationContext.getBean(IDeletionConfigService.class);
            self.initDefaultConfig();
        } catch (RuntimeException ex) {
            // Never throw from @PostConstruct; log loudly so the config issue is visible.
            log.error("Failed to initialise global deletion configuration", ex);
        }
    }

    @Override
    @Transactional
    public void initDefaultConfig() {
        // Ensure a default config exists if the DB is empty for this service
        if (!configRepository.existsById(GLOBAL_CONFIG_ID)) {
            log.info("No global deletion configuration found. Initializing with default values.");
            DeletionConfigEntity defaultConfig = new DeletionConfigEntity(
                    GLOBAL_CONFIG_ID,
                    false, // enabled
                    true,  // softDeleteByDefault
                    30L,   // defaultSoftDeleteRetentionDays
                    true,  // requireApprovalForHardDelete
                    false, // requireApprovalForSoftDelete
                    new ArrayList<>() // notificationEmailsOnError
            );
            configRepository.save(defaultConfig);
            log.info("Default global deletion configuration saved with ID: {}", GLOBAL_CONFIG_ID);
        } else {
            log.info("Global deletion configuration already exists with ID: {}", GLOBAL_CONFIG_ID);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DeletionConfigDTO getDeletionConfig() {
        log.debug("Fetching global deletion configuration.");
        Optional<DeletionConfigEntity> entityOptional = configRepository.findById(GLOBAL_CONFIG_ID);
        
        return entityOptional.map(configMapper::toDto)
                .orElseGet(() -> {
                    log.warn("Global deletion configuration (ID: {}) not found, returning empty DTO. This might indicate an issue if init() failed or was bypassed.", GLOBAL_CONFIG_ID);
                    // Consider if throwing an exception or returning a default non-persistent DTO is better here.
                    // For now, returning an empty DTO as per previous in-memory behavior for safety, but this is unusual for JPA.
                    return new DeletionConfigDTO(); 
                });
    }

    @Override
    @Transactional
    public void updateDeletionConfig(DeletionConfigDTO deletionConfigDTO) {
        if (deletionConfigDTO == null) {
            log.warn("Attempted to update deletion configuration with null DTO.");
            throw new IllegalArgumentException("Deletion configuration DTO cannot be null.");
        }
        log.info("Updating global deletion configuration.");
        DeletionConfigEntity entity = configRepository.findById(GLOBAL_CONFIG_ID)
                .orElseThrow(() -> {
                    log.error("Global deletion config (ID: {}) not found for update. This is unexpected post-initialization.", GLOBAL_CONFIG_ID);
                    // This state (config not found during update) should ideally not happen if PostConstruct works.
                    return new IllegalStateException("Global deletion configuration not found for update. Initialize first.");
                });
        
        configMapper.updateEntityFromDto(deletionConfigDTO, entity);
        // Ensure ID is not accidentally changed by the mapper if DTO were to carry it
        entity.setId(GLOBAL_CONFIG_ID); 
        configRepository.save(entity);
        log.info("Global deletion configuration updated successfully for ID: {}.", GLOBAL_CONFIG_ID);
    }
} 