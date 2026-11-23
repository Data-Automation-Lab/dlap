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

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.entity.MlGlobalConfigEntity;
import com.dlap.autolabel.repository.MlGlobalConfigRepository;
import com.dlap.autolabel.service.IMLConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists ML configuration in PostgreSQL (singleton row).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JpaMlConfigService implements IMLConfigService {

    private final MlGlobalConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void updateMlConfig(MLConfigRequest mlConfigRequest) {
        if (mlConfigRequest == null) {
            throw new IllegalArgumentException("ML configuration request cannot be null");
        }
        try {
            String json = objectMapper.writeValueAsString(mlConfigRequest);
            MlGlobalConfigEntity entity = repository.findById(MlGlobalConfigEntity.SINGLETON_ID)
                    .orElse(MlGlobalConfigEntity.builder()
                            .id(MlGlobalConfigEntity.SINGLETON_ID)
                            .configJson(json)
                            .build());
            entity.setConfigJson(json);
            repository.save(entity);
            log.info("ML configuration persisted for provider: {}", mlConfigRequest.getProviderType());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ML configuration", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MLConfigRequest> getMlConfig() {
        Optional<MlGlobalConfigEntity> stored = repository.findById(MlGlobalConfigEntity.SINGLETON_ID);
        if (stored.isEmpty()) {
            log.warn("ML configuration has not been set up yet.");
            return Optional.empty();
        }
        try {
            MLConfigRequest deserialized = objectMapper.readValue(
                    stored.get().getConfigJson(), MLConfigRequest.class);
            return Optional.ofNullable(deserialized);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize stored ML configuration: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
