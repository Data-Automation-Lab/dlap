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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.service.IPolicyService;
import com.dlap.policyengine.service.PolicyDraftFromTextService;
import com.dlap.policyengine.service.PolicyDraftFromTextService.Draft;
import com.dlap.policyengine.service.PolicyDraftFromTextService.Outcome;
import com.dlap.policyengine.service.PolicyEngineMetricsService;

/**
 * Web-layer contract for {@code POST /api/v1/policyengine/policies/draft-from-text}
 * (ADR-0044): who may call it, and how each drafting outcome maps to a status code.
 */
@WebMvcTest(controllers = PolicyController.class)
@Import(SecurityConfiguration.class)
class PolicyDraftFromTextWebMvcTest {

    private static final String URL = "/api/v1/policyengine/policies/draft-from-text";
    private static final String BODY = "{\"text\":\"Archive stale PII buckets after 90 days\"}";

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
    void draftFromText_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verify(policyDraftFromTextService, never()).draftFromText(any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_ANALYST")
    void draftFromText_withoutRequiredRole_isForbidden() throws Exception {
        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(policyDraftFromTextService, never()).draftFromText(any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void draftFromText_withPolicyManager_returnsDraft() throws Exception {
        when(policyDraftFromTextService.draftFromText(any(), any()))
                .thenReturn(new Draft(Outcome.DRAFTED, "apiVersion: dlap/v1\n", null,
                        0.9, List.of("assumed P90D"), List.of("Draft only - nothing was saved.")));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dslYaml").value("apiVersion: dlap/v1\n"))
                .andExpect(jsonPath("$.confidence").value(0.9))
                .andExpect(jsonPath("$.assumptions[0]").value("assumed P90D"));

        verify(policyDraftFromTextService).draftFromText(
                eq("Archive stale PII buckets after 90 days"), eq(null));
        // Draft only: the endpoint must never reach the policy store.
        verify(policyService, never()).createPolicy(any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void draftFromText_rejectedDraft_isUnprocessableEntityWithWarningsAndNoDsl() throws Exception {
        when(policyDraftFromTextService.draftFromText(any(), any()))
                .thenReturn(new Draft(Outcome.REJECTED, null, null, null,
                        List.of(), List.of("Unsupported action type 'encryptAsset'.")));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.dslYaml").doesNotExist())
                .andExpect(jsonPath("$.warnings[0]").value("Unsupported action type 'encryptAsset'."));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void draftFromText_noProviderConfigured_isServiceUnavailable() throws Exception {
        when(policyDraftFromTextService.draftFromText(any(), any()))
                .thenReturn(new Draft(Outcome.PROVIDER_UNAVAILABLE, null, null, null,
                        List.of(), List.of("No LLM provider is configured. Set llm.provider.")));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.dslYaml").doesNotExist())
                .andExpect(jsonPath("$.warnings[0]").value("No LLM provider is configured. Set llm.provider."));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void draftFromText_blankText_isBadRequest() throws Exception {
        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(policyDraftFromTextService, never()).draftFromText(any(), any());
    }
}
