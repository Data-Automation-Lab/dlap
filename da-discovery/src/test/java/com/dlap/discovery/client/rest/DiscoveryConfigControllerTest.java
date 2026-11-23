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

package com.dlap.discovery.client.rest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*; // Restored
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dlap.discovery.config.dto.ConnectionDiscoveryConfigDTO;
import com.dlap.discovery.config.dto.GlobalDiscoveryConfigDTO;
import com.dlap.discovery.config.service.IDiscoveryConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import; // Restored for TestApp
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser; // Restored
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = DiscoveryConfigController.class,
    excludeAutoConfiguration = { OAuth2ClientAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscoveryConfigControllerTest {

    @SpringBootConfiguration
    @Import({ TestWebSecurityConfiguration.class, DiscoveryConfigController.class }) // Added DiscoveryConfigController.class
    static class TestApp {}

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDiscoveryConfigService configService;

    @Autowired
    private ObjectMapper objectMapper;

    private GlobalDiscoveryConfigDTO globalConfigDTO;
    private ConnectionDiscoveryConfigDTO connectionConfigDTO;
    private final String testConnectionId = "conn-123";

    @BeforeEach
    void setUp() {
        globalConfigDTO = new GlobalDiscoveryConfigDTO();
        globalConfigDTO.setDefaultScanIntervalMinutes(60);
        globalConfigDTO.setEnableAutoRemediation(false);
        // globalConfigDTO.setDefaultResourceTypesToExclude(List.of("AWS::IAM::Role")); // Example if needed
        // globalConfigDTO.setGlobalCrawlerProperties(Map.of("key", "value")); // Example if needed

        connectionConfigDTO = new ConnectionDiscoveryConfigDTO();
        connectionConfigDTO.setCloudConnectionId(testConnectionId);
        connectionConfigDTO.setIsEnabled(true);
        connectionConfigDTO.setScanIntervalHours(2);
        // connectionConfigDTO.setResourceTypesToInclude(List.of("AWS::S3::Bucket")); // Example if needed
    }

    // Global Config Tests
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN") // Restored
    void getGlobalConfig_AsAdmin_ShouldReturnConfig() throws Exception {
        when(configService.getGlobalDiscoveryConfig()).thenReturn(globalConfigDTO);
        mockMvc
            .perform(get("/api/v1/discovery/config/global"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultScanIntervalMinutes").value(60))
            .andExpect(jsonPath("$.enableAutoRemediation").value(false));
    }

    @Test
    @WithMockUser(authorities = "ROLE_USER") // Restored
    void getGlobalConfig_AsUser_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/discovery/config/global")).andExpect(status().isForbidden()); // Restored original expectation
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN") // Restored
    void updateGlobalConfig_AsAdmin_ShouldSucceed() throws Exception {
        doNothing().when(configService).saveGlobalDiscoveryConfig(any(GlobalDiscoveryConfigDTO.class));
        mockMvc
            .perform(
                put("/api/v1/discovery/config/global")
                    .with(csrf()) // Restored csrf
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(globalConfigDTO))
            )
            .andExpect(status().isOk());
    }

    // Connection Config Tests
    @Test
    @WithMockUser(authorities = "ROLE_ADMIN") // Restored
    void getConnectionConfig_AsAdmin_ShouldReturnConfig() throws Exception {
        when(configService.getConnectionDiscoveryConfig(testConnectionId)).thenReturn(Optional.of(connectionConfigDTO));
        mockMvc
            .perform(get("/api/v1/discovery/config/connections/{connectionId}", testConnectionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isEnabled").value(true))
            .andExpect(jsonPath("$.scanIntervalHours").value(2));
    }

    @Test
    @WithMockUser(authorities = "ROLE_USER") // Restored
    void getConnectionConfig_AsUser_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/discovery/config/connections/{connectionId}", testConnectionId)).andExpect(status().isForbidden()); // Restored original expectation
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN") // Restored
    void updateConnectionConfig_AsAdmin_ShouldSucceed() throws Exception {
        when(configService.saveConnectionDiscoveryConfig(eq(testConnectionId), any(ConnectionDiscoveryConfigDTO.class)))
            .thenReturn(connectionConfigDTO);
        mockMvc
            .perform(
                put("/api/v1/discovery/config/connections/{connectionId}", testConnectionId)
                    .with(csrf()) // Restored csrf
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(connectionConfigDTO))
            )
            .andExpect(status().isOk());
    }
}
