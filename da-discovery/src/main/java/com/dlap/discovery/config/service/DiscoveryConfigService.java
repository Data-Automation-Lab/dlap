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

package com.dlap.discovery.config.service;

import com.dlap.discovery.common.service.ResourceNotFoundException; // Assuming a common exception
import com.dlap.discovery.config.dto.ConnectionDiscoveryConfigDTO;
import com.dlap.discovery.config.dto.GlobalDiscoveryConfigDTO;
import com.dlap.discovery.config.model.ConnectionDiscoveryConfig;
import com.dlap.discovery.config.model.GlobalDiscoveryConfig;
import com.dlap.discovery.config.repository.ConnectionDiscoveryConfigRepository;
import com.dlap.discovery.config.repository.GlobalDiscoveryConfigRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DiscoveryConfigService implements IDiscoveryConfigService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryConfigService.class);
    private static final String GLOBAL_CONFIG_KEY = "GLOBAL_CONFIG_SINGLETON";

    private final GlobalDiscoveryConfigRepository globalConfigRepository;
    private final ConnectionDiscoveryConfigRepository connectionConfigRepository;

    @Autowired
    public DiscoveryConfigService(
        GlobalDiscoveryConfigRepository globalConfigRepository,
        ConnectionDiscoveryConfigRepository connectionConfigRepository
    ) {
        this.globalConfigRepository = globalConfigRepository;
        this.connectionConfigRepository = connectionConfigRepository;
    }

    // --- Global Config --- //

    @Override
    @Transactional(readOnly = true)
    public GlobalDiscoveryConfigDTO getGlobalDiscoveryConfig() {
        GlobalDiscoveryConfig entity = globalConfigRepository
            .findById(GLOBAL_CONFIG_KEY)
            .orElseGet(() -> {
                log.info("No global config found, creating default with key: {}", GLOBAL_CONFIG_KEY);
                GlobalDiscoveryConfig defaultConfig = new GlobalDiscoveryConfig(GLOBAL_CONFIG_KEY);
                // TODO: Populate with sensible defaults from application properties or constants
                return globalConfigRepository.save(defaultConfig);
            });
        return mapToGlobalDto(entity);
    }

    @Override
    public void saveGlobalDiscoveryConfig(GlobalDiscoveryConfigDTO globalConfigDto) {
        GlobalDiscoveryConfig entity = globalConfigRepository
            .findById(GLOBAL_CONFIG_KEY)
            .orElseGet(() -> new GlobalDiscoveryConfig(GLOBAL_CONFIG_KEY));

        mapToGlobalEntity(globalConfigDto, entity);
        GlobalDiscoveryConfig updatedEntity = globalConfigRepository.save(entity);
        log.info("Global discovery configuration updated.");
    }

    // --- Connection Config --- //

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectionDiscoveryConfigDTO> getConnectionDiscoveryConfig(String cloudConnectionId) {
        return connectionConfigRepository.findByCloudConnectionId(cloudConnectionId).map(this::mapToConnectionDto);
    }

    @Override
    public ConnectionDiscoveryConfigDTO saveConnectionDiscoveryConfig(
        String cloudConnectionId,
        ConnectionDiscoveryConfigDTO connectionConfigDto
    ) {
        ConnectionDiscoveryConfig entity = connectionConfigRepository
            .findByCloudConnectionId(cloudConnectionId)
            .orElseGet(() -> {
                ConnectionDiscoveryConfig newEntity = new ConnectionDiscoveryConfig();
                newEntity.setCloudConnectionId(cloudConnectionId);
                return newEntity;
            });

        mapToConnectionEntity(connectionConfigDto, entity);
        ConnectionDiscoveryConfig updatedEntity = connectionConfigRepository.save(entity);
        log.info("Connection-specific discovery configuration updated for connectionId: {}", cloudConnectionId);
        return mapToConnectionDto(updatedEntity);
    }

    @Override
    public void recordScheduledScanStarted(String cloudConnectionId, Instant startedAt) {
        connectionConfigRepository.findByCloudConnectionId(cloudConnectionId).ifPresent(entity -> {
            entity.setLastScanStartedAt(startedAt);
            connectionConfigRepository.save(entity);
        });
    }

    @Override
    public void deleteConnectionDiscoveryConfig(String cloudConnectionId) {
        ConnectionDiscoveryConfig entity = connectionConfigRepository
            .findByCloudConnectionId(cloudConnectionId)
            .orElseThrow(() ->
                new ResourceNotFoundException(
                    "ConnectionDiscoveryConfig",
                    cloudConnectionId,
                    new Throwable("Cannot delete non-existent config for cloudConnectionId: " + cloudConnectionId)
                )
            );
        connectionConfigRepository.delete(entity);
        log.info("Connection-specific discovery configuration deleted for connectionId: {}", cloudConnectionId);
    }

    // --- Mappers --- //
    private GlobalDiscoveryConfigDTO mapToGlobalDto(GlobalDiscoveryConfig entity) {
        if (entity == null) return null;
        // Map entity fields to DTO - adjust based on actual entity structure
        return GlobalDiscoveryConfigDTO
            .builder()
            .defaultScanIntervalMinutes(entity.getDefaultScanIntervalHours() != null ? entity.getDefaultScanIntervalHours() * 60 : null)
            .enableAutoRemediation(entity.getEnableAutoRemediation())
            .defaultResourceTypesToExclude(entity.getDefaultResourceTypesToExclude())
            .globalCrawlerProperties(entity.getGlobalCrawlerProperties())
            .defaultOutputLocation(entity.getDefaultOutputLocation())
            .build();
    }

    private void mapToGlobalEntity(GlobalDiscoveryConfigDTO dto, GlobalDiscoveryConfig entity) {
        if (dto == null || entity == null) return;
        entity.setDefaultScanIntervalHours(dto.getDefaultScanIntervalMinutes() != null ? dto.getDefaultScanIntervalMinutes() / 60 : null);
        entity.setEnableAutoRemediation(dto.getEnableAutoRemediation());
        entity.setDefaultResourceTypesToExclude(dto.getDefaultResourceTypesToExclude());
        entity.setGlobalCrawlerProperties(dto.getGlobalCrawlerProperties());
        entity.setDefaultOutputLocation(dto.getDefaultOutputLocation());
    }

    private ConnectionDiscoveryConfigDTO mapToConnectionDto(ConnectionDiscoveryConfig entity) {
        if (entity == null) return null;
        // Map entity fields to DTO - field names now match exactly
        return ConnectionDiscoveryConfigDTO
            .builder()
            .cloudConnectionId(entity.getCloudConnectionId())
            .isEnabled(entity.getIsEnabled())
            .scanIntervalHours(entity.getScanIntervalHours()) // Now matches exactly
            .resourceTypesToInclude(entity.getResourceTypesToInclude())
            .resourceTypesToExclude(entity.getResourceTypesToExclude())
            .specificRegions(entity.getSpecificRegions())
            .customParameters(entity.getCustomParameters()) // Now matches exactly
            .lastScanStartedAt(entity.getLastScanStartedAt())
            .build();
    }

    private void mapToConnectionEntity(ConnectionDiscoveryConfigDTO dto, ConnectionDiscoveryConfig entity) {
        if (dto == null || entity == null) return;
        entity.setIsEnabled(dto.getIsEnabled());
        entity.setScanIntervalHours(dto.getScanIntervalHours()); // Now matches exactly
        entity.setResourceTypesToInclude(dto.getResourceTypesToInclude());
        entity.setResourceTypesToExclude(dto.getResourceTypesToExclude());
        entity.setSpecificRegions(dto.getSpecificRegions());
        entity.setCustomParameters(dto.getCustomParameters()); // Now matches exactly
    }
}
