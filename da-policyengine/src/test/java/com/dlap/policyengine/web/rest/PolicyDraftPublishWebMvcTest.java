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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.dto.PolicyDraftActionDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.service.IPolicyService;

/**
 * AUD-02a review fix, the draft publish: publishing an approved draft creates a policy, which is a
 * policy change chained in {@code govern.policy}. When the audit log cannot record it the change is
 * rolled back and the call answers 503 {@code audit-unavailable} through GlobalExceptionHandler,
 * never the catch-all 500 (D38, Q-692).
 */
@WebMvcTest(controllers = PolicyDraftController.class)
@Import(SecurityConfiguration.class)
class PolicyDraftPublishWebMvcTest {

    private static final String DRAFTS = "/api/v1/policyengine/drafts";
    private static final UUID DRAFT = UUID.fromString("7d0e6c55-0000-4000-8000-0000000000d1");
    private static final UUID PUBLISHER = UUID.fromString("7d0e6c55-0000-4000-8000-0000000000a1");
    private static final String BODY = "{\"actionType\":\"PUBLISH\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IPolicyService policyService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void publishingADraftTheAuditLogCannotRecordIs503AuditUnavailable() throws Exception {
        when(policyService.publishDraft(eq(DRAFT), any(PolicyDraftActionDTO.class), eq(PUBLISHER)))
                .thenThrow(new AuditUnavailableException("append to audit stream govern.policy failed: refused",
                        new IllegalArgumentException("idempotency key holds another event")));

        mockMvc.perform(post(DRAFTS + "/{id}/publish", DRAFT)
                        .with(jwt().jwt(j -> j.subject(PUBLISHER.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_POLICY_MANAGER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.reason").value("audit-unavailable"))
                .andExpect(jsonPath("$.message").value("the audit log could not record the change; nothing was changed"))
                .andExpect(jsonPath("$.path").value(DRAFTS + "/" + DRAFT + "/publish"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void aDraftTheAuditLogRecordsIsPublished() throws Exception {
        PolicyOutputDTO published = new PolicyOutputDTO();
        published.setId(UUID.fromString("7d0e6c55-0000-4000-8000-0000000000e1"));
        published.setName("retire-stale");
        when(policyService.publishDraft(eq(DRAFT), any(PolicyDraftActionDTO.class), eq(PUBLISHER)))
                .thenReturn(published);

        mockMvc.perform(post(DRAFTS + "/{id}/publish", DRAFT)
                        .with(jwt().jwt(j -> j.subject(PUBLISHER.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("retire-stale"));
    }
}
