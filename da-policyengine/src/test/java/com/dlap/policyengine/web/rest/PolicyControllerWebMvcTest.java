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

package com.dlap.policyengine.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.dto.PolicySummaryDTO;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.service.IPolicyService;
import com.dlap.policyengine.service.PolicyDraftFromTextService;
import com.dlap.policyengine.service.PolicyEngineMetricsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * R-P0-6: minimum @WebMvcTest coverage for {@link PolicyController}.
 *
 * <p>Exercises three contract guarantees: (1) the controller wires
 * cleanly under MockMvc, (2) endpoints with {@code @PreAuthorize}
 * reject unauthenticated requests with HTTP 401, (3) the happy-path
 * GET delegates to {@link IPolicyService}.
 */
@WebMvcTest(controllers = PolicyController.class)
@Import(SecurityConfiguration.class)
class PolicyControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IPolicyService policyService;

    @MockitoBean
    private PolicyEngineMetricsService policyEngineMetricsService;

    @MockitoBean
    private PolicyDraftFromTextService policyDraftFromTextService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.dlap.policyengine.service.PolicyHistoryService policyHistory;

    @Test
    void getAllPolicies_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/policyengine/policies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_ANALYST")
    void getAllPolicies_withRoleDataAnalyst_returnsOk() throws Exception {
        Page<PolicySummaryDTO> empty = new PageImpl<>(List.of());
        when(policyService.getAllPolicies(any(Pageable.class), isNull(), isNull()))
                .thenReturn(empty);

        mockMvc.perform(get("/api/v1/policyengine/policies"))
                .andExpect(status().isOk());
    }

    @Test
    void setPolicyStatus_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/policyengine/policies/{id}/status", java.util.UUID.randomUUID())
                        .param("status", "ENABLED").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void setPolicyStatus_withPolicyManager_returnsOk() throws Exception {
        when(policyService.setPolicyStatus(any(), eq(PolicyStatus.ENABLED), any()))
                .thenReturn(new PolicyOutputDTO());

        mockMvc.perform(patch("/api/v1/policyengine/policies/{id}/status", java.util.UUID.randomUUID())
                        .param("status", "ENABLED").with(csrf()))
                .andExpect(status().isOk());
    }

    // ----- SEC-01: roles outside the realm set -------------------------------------------

    /**
     * ROLE_USER is not a realm role (it is not in DlapConstants.UserRole), so it must
     * not read policies. An earlier version of this suite expected 200 for it.
     */
    @Test
    @WithMockUser(authorities = "ROLE_USER")
    void getAllPolicies_withPhantomRoleUser_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/policyengine/policies"))
                .andExpect(status().isForbidden());
    }

    // ----- SEC-04: legacy rules[] carried MVEL and are retired: HTTP 400 for every role -----

    private static final String POLICIES = "/api/v1/policyengine/policies";
    private static final String BODY_DSL_ONLY =
            "{\"name\":\"p1\",\"policyDocument\":\"apiVersion: dlap/v1\"}";
    private static final String BODY_NULL_RULES =
            "{\"name\":\"p1\",\"policyDocument\":\"apiVersion: dlap/v1\",\"rules\":null}";
    private static final String BODY_EMPTY_RULES =
            "{\"name\":\"p1\",\"policyDocument\":\"apiVersion: dlap/v1\",\"rules\":[]}";
    private static final String BODY_LEGACY_RULES =
            "{\"name\":\"p1\",\"rules\":[{\"name\":\"r1\",\"condition\":\"java.lang.Runtime.getRuntime().exec('x')\"}]}";
    private static final String BODY_DSL_AND_LEGACY_RULES =
            "{\"name\":\"p1\",\"policyDocument\":\"apiVersion: dlap/v1\","
            + "\"rules\":[{\"name\":\"r1\",\"condition\":\"true\"}]}";

    private static PolicyOutputDTO savedPolicy() {
        PolicyOutputDTO out = new PolicyOutputDTO();
        out.setId(java.util.UUID.randomUUID());
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DATA_ANALYST"})
    void createPolicy_withLegacyRules_isBadRequestForEveryRole(String role) throws Exception {
        mockMvc.perform(post(POLICIES).with(csrf()).with(user("tester").authorities(new SimpleGrantedAuthority(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_LEGACY_RULES))
                .andExpect(status().isBadRequest());

        verify(policyService, never()).createPolicy(any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void createPolicy_withLegacyRulesAndADocument_isStillBadRequest() throws Exception {
        mockMvc.perform(post(POLICIES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_DSL_AND_LEGACY_RULES))
                .andExpect(status().isBadRequest());

        verify(policyService, never()).createPolicy(any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void createPolicy_dslOnly_byPolicyManager_isCreated() throws Exception {
        when(policyService.createPolicy(any(PolicyInputDTO.class), any())).thenReturn(savedPolicy());

        for (String body : new String[] {BODY_DSL_ONLY, BODY_NULL_RULES, BODY_EMPTY_RULES}) {
            mockMvc.perform(post(POLICIES).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_ANALYST")
    void createPolicy_dslOnly_byDataAnalyst_isForbidden() throws Exception {
        mockMvc.perform(post(POLICIES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_DSL_ONLY))
                .andExpect(status().isForbidden());
        verify(policyService, never()).createPolicy(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_POLICY_MANAGER"})
    void updatePolicy_withLegacyRules_isBadRequestForEveryRole(String role) throws Exception {
        mockMvc.perform(put(POLICIES + "/{id}", java.util.UUID.randomUUID()).with(csrf())
                        .with(user("tester").authorities(new SimpleGrantedAuthority(role)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_LEGACY_RULES))
                .andExpect(status().isBadRequest());

        verify(policyService, never()).updatePolicy(any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void updatePolicy_dslOnly_byPolicyManager_isOk() throws Exception {
        when(policyService.updatePolicy(any(), any(PolicyInputDTO.class), any())).thenReturn(savedPolicy());

        for (String body : new String[] {BODY_DSL_ONLY, BODY_NULL_RULES, BODY_EMPTY_RULES}) {
            mockMvc.perform(put(POLICIES + "/{id}", java.util.UUID.randomUUID()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
    }
}
