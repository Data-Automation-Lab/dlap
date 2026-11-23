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

package com.dlap.autodelete.controller;

import com.dlap.autodelete.dto.DeletionConfigDTO;
import com.dlap.autodelete.service.IDeletionConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeletionConfigController.class)
class DeletionConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDeletionConfigService deletionConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    private DeletionConfigDTO sampleConfigDTO;

    @BeforeEach
    void setUp() {
        sampleConfigDTO = DeletionConfigDTO.builder()
                .enabled(true)
                .softDeleteByDefault(true)
                .defaultSoftDeleteRetentionDays(30L)
                .requireApprovalForHardDelete(true)
                .requireApprovalForSoftDelete(false)
                .notificationEmailsOnError(new ArrayList<>()) 
                .build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getDeletionConfiguration_AsAdmin_ShouldReturnConfig() throws Exception {
        when(deletionConfigService.getDeletionConfig()).thenReturn(sampleConfigDTO);

        mockMvc.perform(get("/api/v1/deletion/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.softDeleteByDefault").value(true));
    }

    @Test
    @WithMockUser(roles = "CDO")
    void getDeletionConfiguration_AsCDO_ShouldReturnConfig() throws Exception {
        when(deletionConfigService.getDeletionConfig()).thenReturn(sampleConfigDTO);

        mockMvc.perform(get("/api/v1/deletion/config"))
                .andExpect(status().isOk());
    }
    
    @Test
    @WithMockUser(roles = "VIEWER") // A regular user should not access this
    void getDeletionConfiguration_AsUser_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/deletion/config"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateDeletionConfiguration_AsAdmin_ShouldReturnOk() throws Exception {
        doNothing().when(deletionConfigService).updateDeletionConfig(any(DeletionConfigDTO.class));

        mockMvc.perform(put("/api/v1/deletion/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleConfigDTO)))
                .andExpect(status().isOk());

        verify(deletionConfigService, times(1)).updateDeletionConfig(any(DeletionConfigDTO.class));
    }

    @Test
    @WithMockUser(roles = "CDO")
    void updateDeletionConfiguration_AsCDO_ShouldBeForbidden() throws Exception {
        mockMvc.perform(put("/api/v1/deletion/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleConfigDTO)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateDeletionConfiguration_WithNullBody_AsAdmin_ShouldReturnBadRequest() throws Exception {
        // No request body at all: the controller cannot read one, which is a client error (400),
        // and the service must not be reached.
        mockMvc.perform(put("/api/v1/deletion/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deletionConfigService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateDeletionConfiguration_WithMalformedBody_AsAdmin_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(put("/api/v1/deletion/config")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": tru"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deletionConfigService);
    }
}
