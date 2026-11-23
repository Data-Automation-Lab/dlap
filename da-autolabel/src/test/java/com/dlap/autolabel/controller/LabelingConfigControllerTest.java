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

package com.dlap.autolabel.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.service.IMLConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(LabelingConfigController.class)
class LabelingConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IMLConfigService mlConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    private MLConfigRequest mlConfigRequest;

    @BeforeEach
    void setUp() {
        mlConfigRequest = MLConfigRequest.builder()
                .providerType("TEST_PROVIDER")
                .modelName("test-model")
                .baseUrl("http://localhost/test")
                .apiKey("test-key")
                .build();
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void updateMlConfiguration_AdminRole_ShouldSucceed() throws Exception {
        doNothing().when(mlConfigService).updateMlConfig(any(MLConfigRequest.class));

        mockMvc.perform(put("/api/v1/labeling/config/ml")
                        .with(csrf()) // Add CSRF token for PUT requests if CSRF is enabled
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mlConfigRequest)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ROLE_USER") // Non-admin role
    void updateMlConfiguration_UserRole_ShouldBeForbidden() throws Exception {
        mockMvc.perform(put("/api/v1/labeling/config/ml")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mlConfigRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void getMlConfiguration_AdminRole_ShouldReturnConfig() throws Exception {
        when(mlConfigService.getMlConfig()).thenReturn(Optional.of(mlConfigRequest));

        mockMvc.perform(get("/api/v1/labeling/config/ml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerType").value("TEST_PROVIDER"))
                .andExpect(jsonPath("$.modelName").value("test-model"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void getMlConfiguration_AdminRole_NoConfigFound_ShouldReturnNotFound() throws Exception {
        when(mlConfigService.getMlConfig()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/labeling/config/ml"))
                .andExpect(status().isNotFound());
    }
    
    @Test
    @WithMockUser(authorities = "ROLE_USER") // Non-admin role
    void getMlConfiguration_UserRole_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/labeling/config/ml"))
                .andExpect(status().isForbidden());
    }
} 