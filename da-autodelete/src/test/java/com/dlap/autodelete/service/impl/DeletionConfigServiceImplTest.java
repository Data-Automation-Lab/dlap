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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeletionConfigServiceImplTest {

    @Mock
    private DeletionConfigRepository configRepository;

    @Mock
    private DeletionConfigMapper configMapper;

    @InjectMocks
    private DeletionConfigServiceImpl configService;

    private DeletionConfigEntity sampleEntity;
    private DeletionConfigDTO sampleDTO;
    private static final Long GLOBAL_CONFIG_ID = 1L;

    @BeforeEach
    void setUp() {
        sampleEntity = new DeletionConfigEntity(
                GLOBAL_CONFIG_ID, true, true, 30L, true, false, new ArrayList<>()
        );
        sampleDTO = DeletionConfigDTO.builder()
                .enabled(true).softDeleteByDefault(true).defaultSoftDeleteRetentionDays(30L)
                .requireApprovalForHardDelete(true).requireApprovalForSoftDelete(false)
                .notificationEmailsOnError(new ArrayList<>()).build();
    }

    @Test
    void initDefaultConfig_WhenConfigExists_ShouldNotSave() {
        when(configRepository.existsById(GLOBAL_CONFIG_ID)).thenReturn(true);
        configService.initDefaultConfig();
        verify(configRepository, never()).save(any(DeletionConfigEntity.class));
    }

    @Test
    void initDefaultConfig_WhenConfigNotExists_ShouldSaveDefault() {
        when(configRepository.existsById(GLOBAL_CONFIG_ID)).thenReturn(false);
        when(configRepository.save(any(DeletionConfigEntity.class))).thenReturn(sampleEntity); // Mock save
        configService.initDefaultConfig();
        verify(configRepository, times(1)).save(any(DeletionConfigEntity.class));
    }

    @Test
    void getDeletionConfig_WhenEntityExists_ShouldReturnMappedDTO() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.of(sampleEntity));
        when(configMapper.toDto(sampleEntity)).thenReturn(sampleDTO);

        DeletionConfigDTO result = configService.getDeletionConfig();

        assertNotNull(result);
        assertEquals(sampleDTO.isSoftDeleteByDefault(), result.isSoftDeleteByDefault());
        verify(configRepository, times(1)).findById(GLOBAL_CONFIG_ID);
        verify(configMapper, times(1)).toDto(sampleEntity);
    }

    @Test
    void getDeletionConfig_WhenEntityNotExists_ShouldReturnEmptyDTO() {
        // This scenario tests the defensive elseGet in the service, though it should ideally not happen after init.
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.empty());
        
        DeletionConfigDTO result = configService.getDeletionConfig();

        assertNotNull(result); // Service returns new DTO(), not null
        // Assert default fields of new DeletionConfigDTO() if necessary, or that it's just not null
        assertNull(result.getEnabled()); // Example: Builder default for Boolean is null

        verify(configRepository, times(1)).findById(GLOBAL_CONFIG_ID);
        verify(configMapper, never()).toDto(any());
    }


    @Test
    void updateDeletionConfig_WhenEntityExists_ShouldUpdateAndSave() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.of(sampleEntity));
        doNothing().when(configMapper).updateEntityFromDto(eq(sampleDTO), eq(sampleEntity));
        when(configRepository.save(any(DeletionConfigEntity.class))).thenReturn(sampleEntity);
        
        configService.updateDeletionConfig(sampleDTO);

        verify(configRepository, times(1)).findById(GLOBAL_CONFIG_ID);
        verify(configMapper, times(1)).updateEntityFromDto(sampleDTO, sampleEntity);
        verify(configRepository, times(1)).save(sampleEntity);
        assertEquals(GLOBAL_CONFIG_ID, sampleEntity.getId()); // Ensure ID is preserved
    }

    @Test
    void updateDeletionConfig_WhenDtoIsNull_ShouldThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> {
            configService.updateDeletionConfig(null);
        });
    }
    
    @Test
    void updateDeletionConfig_WhenEntityNotFound_ShouldThrowIllegalStateException() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> {
            configService.updateDeletionConfig(sampleDTO);
        });
    }
} 