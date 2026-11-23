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

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Collections;
import java.util.Map;
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
import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.dto.CloudConnectionTestResultDTO;
import com.dlap.adminservice.model.enums.CloudProviderType;
import com.dlap.adminservice.service.ICloudConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(CloudConnectionController.class)
@Import(TestSecurityConfiguration.class)
@WithMockUser(roles = "ADMIN")
class CloudConnectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ICloudConnectionService cloudConnectionService;

    @Autowired
    private ObjectMapper objectMapper;

    private CloudConnectionDTO cloudConnectionDTO;
    private String connectionId;

    @BeforeEach
    void setUp() {
        connectionId = "test-connection-id";
        cloudConnectionDTO = CloudConnectionDTO.builder()
                .id(connectionId)
                .name("Test GCP Connection")
                .providerType(CloudProviderType.GCP)
                .connectionParameters(Map.of("projectId", "test-project"))
                .sensitiveCredentials("{\"type\": \"service_account\"}") // Example, this won't be returned
                .enabled(true)
                .build();
    }

    @Test
    void getAllCloudConnections_shouldReturnListOfConnections() throws Exception {
        given(cloudConnectionService.getAllCloudConnections()).willReturn(Collections.singletonList(cloudConnectionDTO));

        mockMvc.perform(get("/api/v1/admin/cloud-connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Test GCP Connection"));
    }

    @Test
    void getCloudConnectionById_whenExists_shouldReturnConnection() throws Exception {
        given(cloudConnectionService.getCloudConnectionById(connectionId)).willReturn(Optional.of(cloudConnectionDTO));

        mockMvc.perform(get("/api/v1/admin/cloud-connections/" + connectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test GCP Connection"));
    }

    @Test
    void getCloudConnectionById_whenNotExists_shouldReturnNotFound() throws Exception {
        given(cloudConnectionService.getCloudConnectionById("unknown-id")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/cloud-connections/unknown-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createCloudConnection_shouldReturnCreatedConnection() throws Exception {
        given(cloudConnectionService.createCloudConnection(any(CloudConnectionDTO.class))).willReturn(cloudConnectionDTO);

        mockMvc.perform(post("/api/v1/admin/cloud-connections").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cloudConnectionDTO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(connectionId));
    }

    @Test
    void updateCloudConnection_shouldReturnUpdatedConnection() throws Exception {
        CloudConnectionDTO updatedDto = CloudConnectionDTO.builder()
                .id(connectionId)
                .name("Updated GCP Connection")
                .providerType(CloudProviderType.GCP)
                .connectionParameters(Map.of("projectId", "updated-project"))
                .enabled(true)
                .build();
        given(cloudConnectionService.updateCloudConnection(eq(connectionId), any(CloudConnectionDTO.class))).willReturn(updatedDto);

        CloudConnectionDTO requestDto = CloudConnectionDTO.builder()
                .name("Updated GCP Connection")
                .providerType(CloudProviderType.GCP)
                .build();
        mockMvc.perform(put("/api/v1/admin/cloud-connections/" + connectionId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated GCP Connection"));
    }

    @Test
    void deleteCloudConnection_shouldReturnNoContent() throws Exception {
        doNothing().when(cloudConnectionService).deleteCloudConnection(connectionId);

        mockMvc.perform(delete("/api/v1/admin/cloud-connections/" + connectionId).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void testCloudConnection_shouldReturnTestResult() throws Exception {
        CloudConnectionTestResultDTO testResult = CloudConnectionTestResultDTO.builder()
                .success(true)
                .message("Connection successful")
                .build();
        given(cloudConnectionService.testCloudConnection(connectionId)).willReturn(testResult);

        mockMvc.perform(post("/api/v1/admin/cloud-connections/" + connectionId + "/test").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Connection successful"));
    }
    
    @Test
    @WithMockUser(roles = "VIEWER") // Non-admin
    void createCloudConnection_whenUnauthorized_shouldReturnForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/cloud-connections").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cloudConnectionDTO)))
                .andExpect(status().isForbidden());
    }
} 