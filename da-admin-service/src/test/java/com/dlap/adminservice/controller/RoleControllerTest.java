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

import com.dlap.adminservice.config.TestSecurityConfiguration;
import com.dlap.adminservice.dto.RoleDTO;
import com.dlap.adminservice.service.IRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;

@WebMvcTest(RoleController.class)
@Import(TestSecurityConfiguration.class)
@WithMockUser(roles = "ADMIN")
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IRoleService roleService;

    @Autowired
    private ObjectMapper objectMapper;

    private RoleDTO roleDTO;

    @BeforeEach
    void setUp() {
        roleDTO = RoleDTO.builder()
                .id("role-id-1")
                .name("VIEWER")
                .description("Viewer role")
                .build();
    }

    @Test
    void getAllRealmRoles_shouldReturnListOfRoles() throws Exception {
        given(roleService.getAllRealmRoles()).willReturn(Collections.singletonList(roleDTO));

        mockMvc.perform(get("/api/v1/admin/roles")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("VIEWER"));
    }
    
    @Test
    @WithMockUser(roles = "USER") // Non-admin
    void getAllRealmRoles_whenUnauthorized_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/roles")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
