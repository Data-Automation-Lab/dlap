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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.adminservice.config.SecurityConfiguration;
import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.model.enums.CloudProviderType;
import com.dlap.adminservice.service.ICloudConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the ADR-0034 allow-list on {@link CloudConnectionController} (Q-108).
 *
 * <p>da-discovery reads cloud connections with the {@code dlap-backend} service-account token
 * ({@code HttpAdminServiceClient}). Only the two GET endpoints it calls accept
 * {@code ROLE_SERVICE_ACCOUNT}; every write and the connection test stay ADMIN only.
 *
 * <p>Unlike {@link CloudConnectionControllerTest}, which uses a permissive URL rule that hides
 * the method-level check, this slice imports the real {@link SecurityConfiguration} and sends a
 * bearer token whose {@code realm_access.roles} claim goes through the production JWT role
 * mapping, so only {@code @PreAuthorize} decides the outcome.
 */
@WebMvcTest(CloudConnectionController.class)
@Import(SecurityConfiguration.class)
class CloudConnectionControllerServiceAccountTest {

    private static final String BASE = "/api/v1/admin/cloud-connections";
    private static final String CONNECTION_ID = "conn-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ICloudConnectionService cloudConnectionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private CloudConnectionDTO connection;

    @BeforeEach
    void setUp() {
        connection = CloudConnectionDTO.builder()
                .id(CONNECTION_ID)
                .name("Test GCP Connection")
                .providerType(CloudProviderType.GCP)
                .connectionParameters(Map.of("projectId", "test-project"))
                .enabled(true)
                .build();
        // Realm role names as dlap-realm.json defines them (ROLE_ prefix included).
        given(jwtDecoder.decode("sa-token")).willReturn(tokenWithRealmRoles("ROLE_SERVICE_ACCOUNT"));
        given(jwtDecoder.decode("sa-top-level-token")).willReturn(tokenWithTopLevelRoles("ROLE_SERVICE_ACCOUNT"));
        given(jwtDecoder.decode("admin-token")).willReturn(tokenWithRealmRoles("ROLE_ADMIN"));
        given(jwtDecoder.decode("engineer-token")).willReturn(tokenWithRealmRoles("ROLE_DATA_ENGINEER"));
    }

    @Test
    void serviceAccount_canListCloudConnections() throws Exception {
        given(cloudConnectionService.getAllCloudConnections()).willReturn(Collections.singletonList(connection));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer sa-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test GCP Connection"));
    }

    @Test
    void serviceAccount_canReadOneCloudConnection() throws Exception {
        given(cloudConnectionService.getCloudConnectionById(CONNECTION_ID)).willReturn(Optional.of(connection));

        mockMvc.perform(get(BASE + "/" + CONNECTION_ID).header("Authorization", "Bearer sa-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONNECTION_ID));
    }

    @Test
    void serviceAccount_withTopLevelRolesClaim_canListCloudConnections() throws Exception {
        given(cloudConnectionService.getAllCloudConnections()).willReturn(List.of(connection));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer sa-top-level-token"))
                .andExpect(status().isOk());
    }

    @Test
    void admin_canStillListAndReadCloudConnections() throws Exception {
        given(cloudConnectionService.getAllCloudConnections()).willReturn(List.of(connection));
        given(cloudConnectionService.getCloudConnectionById(CONNECTION_ID)).willReturn(Optional.of(connection));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer admin-token")).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + CONNECTION_ID).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void serviceAccount_cannotCreateCloudConnection() throws Exception {
        mockMvc.perform(post(BASE).header("Authorization", "Bearer sa-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(connection)))
                .andExpect(status().isForbidden());

        verify(cloudConnectionService, never()).createCloudConnection(any());
    }

    @Test
    void serviceAccount_cannotUpdateCloudConnection() throws Exception {
        mockMvc.perform(put(BASE + "/" + CONNECTION_ID).header("Authorization", "Bearer sa-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(connection)))
                .andExpect(status().isForbidden());

        verify(cloudConnectionService, never()).updateCloudConnection(anyString(), any());
    }

    @Test
    void serviceAccount_cannotDeleteCloudConnection() throws Exception {
        mockMvc.perform(delete(BASE + "/" + CONNECTION_ID).header("Authorization", "Bearer sa-token"))
                .andExpect(status().isForbidden());

        verify(cloudConnectionService, never()).deleteCloudConnection(anyString());
    }

    @Test
    void serviceAccount_cannotTestCloudConnection() throws Exception {
        mockMvc.perform(post(BASE + "/" + CONNECTION_ID + "/test").header("Authorization", "Bearer sa-token"))
                .andExpect(status().isForbidden());

        verify(cloudConnectionService, never()).testCloudConnection(anyString());
    }

    @Test
    void otherHumanRole_cannotReadCloudConnections() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", "Bearer engineer-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + CONNECTION_ID).header("Authorization", "Bearer engineer-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cloudConnectionService);
    }

    @Test
    void anonymousCaller_isUnauthorized() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());

        verifyNoInteractions(cloudConnectionService);
    }

    private static Jwt tokenWithRealmRoles(String... roles) {
        return baseToken().claim("realm_access", Map.of("roles", List.of(roles))).build();
    }

    private static Jwt tokenWithTopLevelRoles(String... roles) {
        return baseToken().claim("roles", List.of(roles)).build();
    }

    private static Jwt.Builder baseToken() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("service-account-dlap-backend")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
    }
}
