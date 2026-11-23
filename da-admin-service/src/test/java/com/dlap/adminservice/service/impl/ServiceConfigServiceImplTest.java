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

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.adminservice.dto.ServiceConfigDTO;
import com.dlap.adminservice.exception.NotFoundException;
import com.dlap.adminservice.mapper.ServiceConfigMapper;
import com.dlap.adminservice.model.ServiceConfigEntity;
import com.dlap.adminservice.repository.ServiceConfigRepository;

@ExtendWith(MockitoExtension.class)
class ServiceConfigServiceImplTest {

    @Mock
    private ServiceConfigRepository serviceConfigRepository;

    @Mock
    private ServiceConfigMapper serviceConfigMapper;

    @InjectMocks
    private ServiceConfigServiceImpl serviceConfigService;

    private ServiceConfigEntity serviceConfigEntity;
    private ServiceConfigDTO serviceConfigDTO;

    @BeforeEach
    void setUp() {
        serviceConfigEntity = new ServiceConfigEntity();
        serviceConfigEntity.setServiceId("test-service");
        serviceConfigEntity.setDisplayName("Test Service");
        serviceConfigEntity.setEnabled(true);

        serviceConfigDTO = ServiceConfigDTO.builder()
                .serviceId("test-service")
                .displayName("Test Service")
                .enabled(true)
                .build();
    }

    @Test
    void getAllServiceConfigs_shouldReturnDtoList() {
        when(serviceConfigRepository.findAll()).thenReturn(Collections.singletonList(serviceConfigEntity));
        when(serviceConfigMapper.toDtoList(anyList())).thenReturn(Collections.singletonList(serviceConfigDTO));

        List<ServiceConfigDTO> result = serviceConfigService.getAllServiceConfigs();

        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).getServiceId()).isEqualTo("test-service");
        verify(serviceConfigRepository).findAll();
        verify(serviceConfigMapper).toDtoList(anyList());
    }

    @Test
    void getServiceConfigById_whenFound_shouldReturnOptionalDto() {
        when(serviceConfigRepository.findByServiceId("test-service")).thenReturn(Optional.of(serviceConfigEntity));
        when(serviceConfigMapper.toDto(serviceConfigEntity)).thenReturn(serviceConfigDTO);

        Optional<ServiceConfigDTO> result = serviceConfigService.getServiceConfigById("test-service");

        assertThat(result).isPresent();
        assertThat(result.get().getDisplayName()).isEqualTo("Test Service");
        verify(serviceConfigRepository).findByServiceId("test-service");
    }

    @Test
    void getServiceConfigById_whenNotFound_shouldReturnEmptyOptional() {
        when(serviceConfigRepository.findByServiceId("unknown")).thenReturn(Optional.empty());

        Optional<ServiceConfigDTO> result = serviceConfigService.getServiceConfigById("unknown");

        assertThat(result).isNotPresent();
        verify(serviceConfigRepository).findByServiceId("unknown");
    }

    @Test
    void createServiceConfig_shouldCreateAndReturnDto() {
        when(serviceConfigMapper.toEntity(serviceConfigDTO)).thenReturn(serviceConfigEntity);
        when(serviceConfigRepository.save(serviceConfigEntity)).thenReturn(serviceConfigEntity);
        when(serviceConfigMapper.toDto(serviceConfigEntity)).thenReturn(serviceConfigDTO);

        ServiceConfigDTO result = serviceConfigService.createServiceConfig(serviceConfigDTO);

        assertThat(result).isNotNull();
        assertThat(result.getServiceId()).isEqualTo(serviceConfigDTO.getServiceId());
        verify(serviceConfigRepository).save(serviceConfigEntity);
    }

    @Test
    void updateServiceConfig_whenFound_shouldUpdateAndReturnDto() {
        ServiceConfigDTO updatedDto = ServiceConfigDTO.builder().serviceId("test-service").displayName("Updated Service").build();
        
        when(serviceConfigRepository.findByServiceId("test-service")).thenReturn(Optional.of(serviceConfigEntity));
        // serviceConfigMapper.updateEntityFromDto is void, so no when()... needed for it if just verifying call
        when(serviceConfigRepository.save(serviceConfigEntity)).thenReturn(serviceConfigEntity); // assume save returns updated entity
        when(serviceConfigMapper.toDto(serviceConfigEntity)).thenReturn(updatedDto); // mapper returns DTO of updated entity

        ServiceConfigDTO result = serviceConfigService.updateServiceConfig("test-service", updatedDto);

        assertThat(result).isNotNull();
        assertThat(result.getDisplayName()).isEqualTo("Updated Service");
        verify(serviceConfigMapper).updateEntityFromDto(updatedDto, serviceConfigEntity);
        verify(serviceConfigRepository).save(serviceConfigEntity);
    }

    @Test
    void updateServiceConfig_whenNotFound_shouldThrowNotFoundException() {
        ServiceConfigDTO updatedDto = ServiceConfigDTO.builder().serviceId("unknown").build();
        when(serviceConfigRepository.findByServiceId("unknown")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> {
            serviceConfigService.updateServiceConfig("unknown", updatedDto);
        });
        verify(serviceConfigRepository, never()).save(any());
    }
} 