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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.model.PolicyChangeType;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.model.PolicyVersionView;
import com.dlap.policyengine.service.IPolicyService;
import com.dlap.policyengine.service.PolicyDraftFromTextService;
import com.dlap.policyengine.service.PolicyEngineMetricsService;
import com.dlap.policyengine.service.PolicyHistoryService;

/**
 * AUD-02a, the HTTP side: the versions API is authorized for the policy readers and the auditor and
 * nobody else, a policy change the audit log could not record answers 503 {@code audit-unavailable}
 * (never 400 or 500), and a delete answers 204.
 */
@WebMvcTest(controllers = PolicyController.class)
@Import(SecurityConfiguration.class)
class PolicyVersionsWebMvcTest {

    private static final String POLICIES = "/api/v1/policyengine/policies";
    private static final UUID ID = UUID.fromString("7d0e6c55-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IPolicyService policyService;

    @MockitoBean
    private PolicyEngineMetricsService policyEngineMetricsService;

    @MockitoBean
    private PolicyDraftFromTextService policyDraftFromTextService;

    @MockitoBean
    private PolicyHistoryService policyHistory;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static PolicyVersionView view(int version, PolicyChangeType type, String document) {
        return new PolicyVersionView(ID, version, type, "retire-stale", PolicyStatus.DISABLED,
                type == PolicyChangeType.DELETED, "a".repeat(64), "sub-alice", "USER",
                Instant.parse("2026-11-01T12:00:00Z"), "govern.policy", 7, "b".repeat(64), null, null,
                document == null ? null : Map.of("addLabel", "Stale"), document);
    }

    @Test
    void theVersionsNeedAToken() throws Exception {
        mockMvc.perform(get(POLICIES + "/{id}/versions", ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(POLICIES + "/{id}/versions/{v}", ID, 1)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER", "ROLE_DATA_SCIENTIST",
            "ROLE_DATA_ANALYST", "ROLE_AUDITOR"})
    void policyReadersAndTheAuditorListTheVersions(String role) throws Exception {
        when(policyHistory.versions(ID)).thenReturn(List.of(view(1, PolicyChangeType.CREATED, null),
                view(2, PolicyChangeType.DELETED, null)));

        mockMvc.perform(get(POLICIES + "/{id}/versions", ID).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("u").authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].changeType").value("CREATED"))
                .andExpect(jsonPath("$[0].auditStream").value("govern.policy"))
                .andExpect(jsonPath("$[0].auditSeq").value(7))
                .andExpect(jsonPath("$[0].policyDocument").doesNotExist())
                .andExpect(jsonPath("$[1].deleted").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_SERVICE_ACCOUNT", "ROLE_DIRECTOR"})
    void otherRolesCannotReadTheHistory(String role) throws Exception {
        mockMvc.perform(get(POLICIES + "/{id}/versions", ID).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("u").authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(role))))
                .andExpect(status().isForbidden());
        verify(policyHistory, never()).versions(any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_AUDITOR")
    void oneVersionCarriesItsContent() throws Exception {
        when(policyHistory.version(ID, 2)).thenReturn(view(2, PolicyChangeType.UPDATED, "apiVersion: dlap/v1\n"));

        mockMvc.perform(get(POLICIES + "/{id}/versions/{v}", ID, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyDocument").value("apiVersion: dlap/v1\n"))
                .andExpect(jsonPath("$.actions.addLabel").value("Stale"))
                .andExpect(jsonPath("$.contentSha256").value("a".repeat(64)));
    }

    @Test
    @WithMockUser(authorities = "ROLE_AUDITOR")
    void anUnknownVersionIs404() throws Exception {
        when(policyHistory.version(ID, 9)).thenThrow(new ResourceNotFoundException("Policy version", "v", "9"));

        mockMvc.perform(get(POLICIES + "/{id}/versions/{v}", ID, 9)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void aPolicyChangeTheAuditLogCannotRecordIs503AuditUnavailable() throws Exception {
        when(policyHistory.now()).thenReturn(Instant.parse("2026-11-01T12:00:00Z"));
        doThrow(new AuditUnavailableException("append to audit stream govern.policy failed: refused", null))
                .when(policyService).deletePolicy(ID);
        when(policyService.setPolicyStatus(eq(ID), eq(PolicyStatus.ENABLED), any()))
                .thenThrow(new AuditUnavailableException("cannot read audit stream govern.policy", null));

        mockMvc.perform(delete(POLICIES + "/{id}", ID).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("audit-unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("the audit log could not record the policy change; nothing was changed"));
        mockMvc.perform(patch(POLICIES + "/{id}/status", ID).param("status", "ENABLED").with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("audit-unavailable"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void aDeleteIs204() throws Exception {
        mockMvc.perform(delete(POLICIES + "/{id}", ID).with(csrf())).andExpect(status().isNoContent());
        verify(policyService).deletePolicy(ID);
    }
}
