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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArchivalConfigServiceImplTest {

    @Mock
    private ArchivalConfigRepository configRepository;

    @Mock
    private ArchivalConfigMapper configMapper;

    @InjectMocks
    private ArchivalConfigServiceImpl configService;

    private ArchivalConfigEntity sampleEntity;
    private ArchivalConfigDTO sampleDTO;
    private static final Long GLOBAL_CONFIG_ID = 1L;

    @BeforeEach
    void setUp() {
        sampleEntity = new ArchivalConfigEntity(
                GLOBAL_CONFIG_ID, true, "COOL", 30L, true, true, new HashMap<>()
        );
        sampleDTO = ArchivalConfigDTO.builder()
                .enabled(true).defaultArchivalTier("COOL").defaultRetentionDays(30L)
                .requireApprovalForArchival(true).requireApprovalForRestore(true).providerConfigs(new HashMap<>()).build();
    }

    @Test
    void init_WhenConfigExists_ShouldNotSave() {
        when(configRepository.existsById(GLOBAL_CONFIG_ID)).thenReturn(true);
        configService.init();
        verify(configRepository, never()).save(any(ArchivalConfigEntity.class));
    }

    @Test
    void init_WhenConfigNotExists_ShouldSaveDefault() {
        when(configRepository.existsById(GLOBAL_CONFIG_ID)).thenReturn(false);
        when(configRepository.save(any(ArchivalConfigEntity.class))).thenReturn(sampleEntity); // Mock save
        configService.init();
        verify(configRepository, times(1)).save(any(ArchivalConfigEntity.class));
    }

    @Test
    void getArchivalConfig_ShouldReturnMappedDTO() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.of(sampleEntity));
        when(configMapper.toDto(sampleEntity)).thenReturn(sampleDTO);

        ArchivalConfigDTO result = configService.getArchivalConfig();

        assertNotNull(result);
        assertEquals(sampleDTO.getDefaultArchivalTier(), result.getDefaultArchivalTier());
        verify(configRepository, times(1)).findById(GLOBAL_CONFIG_ID);
        verify(configMapper, times(1)).toDto(sampleEntity);
    }

    @Test
    void updateArchivalConfig_ShouldUpdateAndSaveFolder() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.of(sampleEntity));
        // configMapper.updateEntityFromDto is void, so mock it if necessary (e.g. with doNothing() or verify interaction)
        doNothing().when(configMapper).updateEntityFromDto(eq(sampleDTO), eq(sampleEntity));
        when(configRepository.save(any(ArchivalConfigEntity.class))).thenReturn(sampleEntity);
        
        configService.updateArchivalConfig(sampleDTO);

        verify(configRepository, times(1)).findById(GLOBAL_CONFIG_ID);
        verify(configMapper, times(1)).updateEntityFromDto(sampleDTO, sampleEntity);
        verify(configRepository, times(1)).save(sampleEntity);
        assertEquals(GLOBAL_CONFIG_ID, sampleEntity.getId()); // Ensure ID is preserved
    }

    @Test
    void updateArchivalConfig_WhenDtoIsNull_ShouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> {
            configService.updateArchivalConfig(null);
        });
    }
    
    @Test
    void updateArchivalConfig_WhenEntityNotFound_ShouldThrowException() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> {
            configService.updateArchivalConfig(sampleDTO);
        });
    }

    @Test
    void init_DefaultTierIsAnArchiveClassOnEveryCloud() {
        when(configRepository.existsById(GLOBAL_CONFIG_ID)).thenReturn(false);
        org.mockito.ArgumentCaptor<ArchivalConfigEntity> saved = org.mockito.ArgumentCaptor.forClass(ArchivalConfigEntity.class);
        when(configRepository.save(saved.capture())).thenReturn(sampleEntity);

        configService.init();

        assertEquals("COLD", saved.getValue().getDefaultArchivalTier());
        for (String provider : new String[] {"AWS", "GCP", "AZURE"}) {
            assertNotNull(com.dlap.autoarchival.rule.TierResolver.resolve(provider, saved.getValue().getDefaultArchivalTier()));
        }
    }

    @Test
    void updateArchivalConfig_RefusesATierNoCloudHasAndWritesNothing() {
        for (String bad : new String[] {"STANDARD_ARCHIVE", "S3_GLACIER", "HOT", "ONEZONE_IA"}) {
            ArchivalConfigDTO dto = ArchivalConfigDTO.builder().enabled(true).defaultArchivalTier(bad)
                    .defaultRetentionDays(30L).requireApprovalForArchival(true).requireApprovalForRestore(true)
                    .providerConfigs(new HashMap<>()).build();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> configService.updateArchivalConfig(dto), bad);
            assertTrue(e.getMessage().contains("defaultArchivalTier"));
        }
        verify(configRepository, never()).save(any(ArchivalConfigEntity.class));
    }

    @Test
    void updateArchivalConfig_AcceptsNeutralTiersAndTheNamesThatMapOnEveryCloud() {
        when(configRepository.findById(GLOBAL_CONFIG_ID)).thenReturn(Optional.of(sampleEntity));
        when(configRepository.save(any(ArchivalConfigEntity.class))).thenReturn(sampleEntity);
        for (String ok : new String[] {"COOL", "COLD", "ARCHIVE", "GLACIER", "GLACIER_IR", "DEEP_ARCHIVE", "STANDARD_IA"}) {
            ArchivalConfigDTO dto = ArchivalConfigDTO.builder().enabled(true).defaultArchivalTier(ok)
                    .defaultRetentionDays(30L).requireApprovalForArchival(true).requireApprovalForRestore(true)
                    .providerConfigs(new HashMap<>()).build();
            configService.updateArchivalConfig(dto);
        }
        verify(configRepository, times(7)).save(any(ArchivalConfigEntity.class));
    }
}
