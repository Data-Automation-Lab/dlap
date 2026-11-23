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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.adminservice.config.TestSecurityConfiguration;
import com.dlap.adminservice.dto.UserDTO;
import com.dlap.adminservice.service.IUserService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(UserController.class)
@Import(TestSecurityConfiguration.class)
@WithMockUser(roles = "ADMIN") // Apply to all tests in this class for brevity, can be overridden
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IUserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserDTO userDTO;

    @BeforeEach
    void setUp() {
        userDTO = UserDTO.builder()
                .id(UUID.randomUUID().toString())
                .username("testuser")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .enabled(true)
                .roles(List.of("USER"))
                .build();
    }

    @Test
    void getAllUsers_shouldReturnListOfUsers() throws Exception {
        given(userService.getAllUsers(null, null)).willReturn(Collections.singletonList(userDTO));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].username").value("testuser"));
    }

    @Test
    void getUserById_whenExists_shouldReturnUser() throws Exception {
        given(userService.getUserById(userDTO.getId())).willReturn(Optional.of(userDTO));

        mockMvc.perform(get("/api/v1/admin/users/" + userDTO.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    void getUserById_whenNotExists_shouldReturnNotFound() throws Exception {
        given(userService.getUserById("unknown-id")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/users/unknown-id"))
                .andExpect(status().isNotFound());
    }
    
    @Test
    void getUserByUsername_whenExists_shouldReturnUser() throws Exception {
        given(userService.getUserByUsername("testuser")).willReturn(Optional.of(userDTO));

        mockMvc.perform(get("/api/v1/admin/users/username/testuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userDTO.getId()));
    }

    @Test
    void createUser_shouldReturnCreatedUser() throws Exception {
        given(userService.createUser(any(UserDTO.class))).willReturn(userDTO);

        mockMvc.perform(post("/api/v1/admin/users").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userDTO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    void updateUser_shouldReturnUpdatedUser() throws Exception {
        UserDTO updatedDto = UserDTO.builder().username("updateduser").build();
        given(userService.updateUser(eq(userDTO.getId()), any(UserDTO.class))).willReturn(updatedDto);

        mockMvc.perform(put("/api/v1/admin/users/" + userDTO.getId()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("updateduser"));
    }

    @Test
    void deleteUser_shouldReturnNoContent() throws Exception {
        doNothing().when(userService).deleteUser(userDTO.getId());

        mockMvc.perform(delete("/api/v1/admin/users/" + userDTO.getId()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void getUserRealmRoles_shouldReturnRoles() throws Exception {
        RoleRepresentation roleRep = new RoleRepresentation("USER", "User role", false);
        given(userService.getUserRealmRoles(userDTO.getId())).willReturn(Collections.singletonList(roleRep));

        mockMvc.perform(get("/api/v1/admin/users/" + userDTO.getId() + "/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("USER"));
    }

    @Test
    void assignRealmRolesToUser_shouldReturnNoContent() throws Exception {
        List<String> rolesToAssign = List.of("USER", "EDITOR");
        doNothing().when(userService).assignRealmRolesToUser(userDTO.getId(), rolesToAssign);

        mockMvc.perform(post("/api/v1/admin/users/" + userDTO.getId() + "/roles").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rolesToAssign)))
                .andExpect(status().isNoContent());
    }
    
    @Test
    @WithMockUser(roles = "VIEWER") // Non-admin user for a modification endpoint
    void createUser_whenUnauthorized_shouldReturnForbidden() throws Exception {
         mockMvc.perform(post("/api/v1/admin/users").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userDTO)))
                .andExpect(status().isForbidden());
    }
} 