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

package com.dlap.autoarchival.controller;

import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ArchivalConfigController.class)
class ArchivalConfigControllerTest {

    // The authorities the controller's @PreAuthorize expressions name (DlapConstants.UserRole).
    // ROLE_DATA_STEWARD and ROLE_USER are not DLAP roles and grant nothing.
    private static final String POLICY_MANAGER = "ROLE_" + DlapConstants.UserRole.POLICY_MANAGER;
    private static final String DIRECTOR = "ROLE_" + DlapConstants.UserRole.DIRECTOR;
    private static final String DATA_ANALYST = "ROLE_" + DlapConstants.UserRole.DATA_ANALYST;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IArchivalConfigService archivalConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    private ArchivalConfigDTO sampleConfigDTO;

    @BeforeEach
    void setUp() {
        sampleConfigDTO = ArchivalConfigDTO.builder()
                .enabled(true)
                .defaultArchivalTier("S3_GLACIER")
                .defaultRetentionDays(365L)
                .requireApprovalForArchival(true)
                .requireApprovalForRestore(false)
                .providerConfigs(new HashMap<>()) 
                .build();
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void getArchivalConfiguration_AsAdmin_ShouldReturnConfig() throws Exception {
        when(archivalConfigService.getArchivalConfig()).thenReturn(sampleConfigDTO);

        mockMvc.perform(get("/api/v1/archival/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultArchivalTier").value("S3_GLACIER"));
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void getArchivalConfiguration_AsPolicyManager_ShouldReturnConfig() throws Exception {
        when(archivalConfigService.getArchivalConfig()).thenReturn(sampleConfigDTO);

        mockMvc.perform(get("/api/v1/archival/config"))
                .andExpect(status().isOk());
    }
    
    @Test
    @WithMockUser(authorities = DIRECTOR)
    void getArchivalConfiguration_AsDirector_ShouldReturnConfig() throws Exception {
        when(archivalConfigService.getArchivalConfig()).thenReturn(sampleConfigDTO);

        mockMvc.perform(get("/api/v1/archival/config"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getArchivalConfiguration_AsDataAnalyst_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/archival/config"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void updateArchivalConfiguration_AsAdmin_ShouldReturnOk() throws Exception {
        doNothing().when(archivalConfigService).updateArchivalConfig(any(ArchivalConfigDTO.class));

        mockMvc.perform(put("/api/v1/archival/config")
                        .with(csrf()) // Add CSRF token for PUT if Spring Security CSRF is enabled
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleConfigDTO)))
                .andExpect(status().isOk());

        verify(archivalConfigService, times(1)).updateArchivalConfig(any(ArchivalConfigDTO.class));
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void updateArchivalConfiguration_AsPolicyManager_ShouldBeForbidden() throws Exception {
        mockMvc.perform(put("/api/v1/archival/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleConfigDTO)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void updateArchivalConfiguration_WithATierNoCloudHas_ShouldBeBadRequestNotServerError() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("defaultArchivalTier: 'STANDARD_ARCHIVE' is not an archive tier"))
                .when(archivalConfigService).updateArchivalConfig(org.mockito.ArgumentMatchers.any());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/archival/config")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleConfigDTO)))
                .andExpect(status().isBadRequest());
    }
}
