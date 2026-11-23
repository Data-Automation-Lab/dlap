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

package com.dlap.adminservice.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.adminservice.dto.ServiceConfigDTO;
import com.dlap.adminservice.exception.NotFoundException;
import com.dlap.adminservice.mapper.ServiceConfigMapper;
import com.dlap.adminservice.model.ServiceConfigEntity;
import com.dlap.adminservice.repository.ServiceConfigRepository;
import com.dlap.adminservice.service.IServiceConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service implementation for managing service configurations
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ServiceConfigServiceImpl implements IServiceConfigService {

    private final ServiceConfigRepository serviceConfigRepository;
    private final ServiceConfigMapper serviceConfigMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ServiceConfigDTO> getAllServiceConfigs() {
        log.debug("Retrieving all service configurations");
        List<ServiceConfigEntity> entities = serviceConfigRepository.findAll();
        return serviceConfigMapper.toDtoList(entities);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServiceConfigDTO> getServiceConfigById(String serviceId) {
        log.debug("Retrieving service configuration for service: {}", serviceId);
        return serviceConfigRepository.findByServiceId(serviceId)
                .map(serviceConfigMapper::toDto);
    }

    @Override
    public ServiceConfigDTO createServiceConfig(ServiceConfigDTO serviceConfigDTO) {
        log.debug("Creating new service configuration for service: {}", serviceConfigDTO.getServiceId());
        ServiceConfigEntity entity = serviceConfigMapper.toEntity(serviceConfigDTO);
        ServiceConfigEntity savedEntity = serviceConfigRepository.save(entity);
        return serviceConfigMapper.toDto(savedEntity);
    }

    @Override
    public ServiceConfigDTO updateServiceConfig(String serviceId, ServiceConfigDTO serviceConfigDTO) {
        log.debug("Updating service configuration for service: {}", serviceId);
        ServiceConfigEntity existingEntity = serviceConfigRepository.findByServiceId(serviceId)
                .orElseThrow(() -> new NotFoundException("Service configuration not found for service: " + serviceId));
        
        serviceConfigMapper.updateEntityFromDto(serviceConfigDTO, existingEntity);
        ServiceConfigEntity updatedEntity = serviceConfigRepository.save(existingEntity);
        return serviceConfigMapper.toDto(updatedEntity);
    }
} 