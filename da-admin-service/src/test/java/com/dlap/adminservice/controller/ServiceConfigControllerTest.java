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

package com.dlap.adminservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.adminservice.config.TestSecurityConfiguration;
import com.dlap.adminservice.dto.ServiceConfigDTO;
import com.dlap.adminservice.service.IServiceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(ServiceConfigController.class)
@Import(TestSecurityConfiguration.class)
class ServiceConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IServiceConfigService serviceConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    private ServiceConfigDTO serviceConfigDTO;

    @BeforeEach
    void setUp() {
        serviceConfigDTO = ServiceConfigDTO.builder()
                .serviceId("test-service")
                .displayName("Test Service")
                .endpoint("http://localhost:1234")
                .enabled(true)
                .build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllServiceConfigs_shouldReturnListOfConfigs() throws Exception {
        given(serviceConfigService.getAllServiceConfigs()).willReturn(Collections.singletonList(serviceConfigDTO));

        mockMvc.perform(get("/api/v1/admin/config/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceId").value("test-service"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getServiceConfigById_whenExists_shouldReturnConfig() throws Exception {
        given(serviceConfigService.getServiceConfigById("test-service")).willReturn(Optional.of(serviceConfigDTO));

        mockMvc.perform(get("/api/v1/admin/config/services/test-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Test Service"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getServiceConfigById_whenNotExists_shouldReturnNotFound() throws Exception {
        given(serviceConfigService.getServiceConfigById("unknown-service")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/config/services/unknown-service"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createServiceConfig_shouldReturnCreatedConfig() throws Exception {
        given(serviceConfigService.createServiceConfig(any(ServiceConfigDTO.class))).willReturn(serviceConfigDTO);

        mockMvc.perform(post("/api/v1/admin/config/services").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(serviceConfigDTO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serviceId").value("test-service"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateServiceConfig_shouldReturnUpdatedConfig() throws Exception {
        ServiceConfigDTO updatedDTO = ServiceConfigDTO.builder().serviceId("test-service").displayName("Updated Name").build();
        given(serviceConfigService.updateServiceConfig(eq("test-service"), any(ServiceConfigDTO.class))).willReturn(updatedDTO);

        mockMvc.perform(put("/api/v1/admin/config/services/test-service").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"));
    }
    
    @Test
    @WithMockUser(roles = "USER") // Non-admin user
    void createServiceConfig_whenUnauthorized_shouldReturnForbidden() throws Exception {
         mockMvc.perform(post("/api/v1/admin/config/services").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(serviceConfigDTO)))
                .andExpect(status().isForbidden());
    }
} 