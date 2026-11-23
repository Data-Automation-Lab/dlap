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

package com.dlap.autolabel.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.entity.MlGlobalConfigEntity;
import com.dlap.autolabel.repository.MlGlobalConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class JpaMlConfigServiceTest {

    @Mock
    private MlGlobalConfigRepository repository;

    private ObjectMapper objectMapper;
    private JpaMlConfigService mlConfigService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mlConfigService = new JpaMlConfigService(repository, objectMapper);
    }

    @Test
    void getMlConfig_whenMissing_returnsEmptyOptional() {
        when(repository.findById(MlGlobalConfigEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        assertTrue(mlConfigService.getMlConfig().isEmpty());
    }

    @Test
    void updateMlConfig_persistsJson() throws Exception {
        MLConfigRequest newConfig = MLConfigRequest.builder()
                .providerType("OPENAI")
                .modelName("gpt-4")
                .apiKey("sk-123")
                .build();

        when(repository.findById(MlGlobalConfigEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        mlConfigService.updateMlConfig(newConfig);

        ArgumentCaptor<MlGlobalConfigEntity> captor = ArgumentCaptor.forClass(MlGlobalConfigEntity.class);
        verify(repository).save(captor.capture());
        MlGlobalConfigEntity saved = captor.getValue();
        assertEquals(MlGlobalConfigEntity.SINGLETON_ID, saved.getId());
        MLConfigRequest roundTrip = objectMapper.readValue(saved.getConfigJson(), MLConfigRequest.class);
        assertEquals("OPENAI", roundTrip.getProviderType());
        assertEquals("gpt-4", roundTrip.getModelName());
    }

    @Test
    void updateMlConfig_withNull_throws() {
        assertThrows(IllegalArgumentException.class, () -> mlConfigService.updateMlConfig(null));
    }

    @Test
    void getMlConfig_afterSave_returnsConfig() throws Exception {
        MLConfigRequest stored = MLConfigRequest.builder().modelName("m1").build();
        String json = objectMapper.writeValueAsString(stored);
        when(repository.findById(MlGlobalConfigEntity.SINGLETON_ID))
                .thenReturn(Optional.of(MlGlobalConfigEntity.builder()
                        .id(MlGlobalConfigEntity.SINGLETON_ID)
                        .configJson(json)
                        .build()));

        Optional<MLConfigRequest> fetched = mlConfigService.getMlConfig();
        assertTrue(fetched.isPresent());
        assertNotNull(fetched.get());
        assertEquals("m1", fetched.get().getModelName());
    }
}
