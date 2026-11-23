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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.common.ConflictException;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.dto.PolicyDraftActionDTO;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.service.IEventSubscriptionService;
import com.dlap.policyengine.service.IPolicyEvaluationService;
import com.dlap.policyengine.service.IPolicyService;
import com.dlap.policyengine.service.PolicyDraftFromTextService;
import com.dlap.policyengine.service.PolicyEngineMetricsService;
import com.dlap.policyengine.service.PolicyHistoryService;

/**
 * PE-01 (Q-715, D39): one central error mapping for every controller of the policy engine. An exception
 * class that declares {@code @ResponseStatus} (ResourceNotFoundException 404, ConflictException 409)
 * answers that status through {@code GlobalExceptionHandler} for the draft, policy, evaluation and event
 * controllers alike, in the service's error shape; the audit 503 still holds; and an unexpected failure
 * answers 500 with a fixed message and no internal detail.
 */
@WebMvcTest(controllers = {PolicyDraftController.class, PolicyController.class,
        PolicyEvaluationController.class, EventCenterController.class})
@Import(SecurityConfiguration.class)
class PolicyEngineErrorMappingWebMvcTest {

    private static final String DRAFTS = "/api/v1/policyengine/drafts";
    private static final String POLICIES = "/api/v1/policyengine/policies";
    private static final String EVALUATIONS = "/api/v1/policyengine/evaluations";
    private static final String SUBSCRIPTIONS = "/api/v1/policyengine/events/subscriptions";
    private static final UUID ID = UUID.fromString("7d0e6c55-0000-4000-8000-0000000000d1");
    private static final UUID USER = UUID.fromString("7d0e6c55-0000-4000-8000-0000000000a1");

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
    private IPolicyEvaluationService evaluations;

    @MockitoBean
    private IEventSubscriptionService subscriptions;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(USER.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static void assertShape(ResultActions result, int status, String error, String path) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void aMissingDraftIs404FromPolicyDraftController() throws Exception {
        doThrow(new ResourceNotFoundException("Policy draft not found with ID: " + ID))
                .when(policyService).deleteDraft(ID, USER);

        ResultActions result = mockMvc.perform(delete(DRAFTS + "/{id}", ID).with(admin()).with(csrf()));

        assertShape(result, 404, "Not Found", DRAFTS + "/" + ID);
        result.andExpect(jsonPath("$.message").value("Policy draft not found with ID: " + ID));
    }

    @Test
    void aDraftInTheWrongStateIs409FromPolicyDraftController() throws Exception {
        when(policyService.approveDraft(eq(ID), any(PolicyDraftActionDTO.class), eq(USER)))
                .thenThrow(new ConflictException("Draft cannot be approved in current status: REJECTED"));

        ResultActions result = mockMvc.perform(post(DRAFTS + "/{id}/approve", ID).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\":\"APPROVE\"}"));

        assertShape(result, 409, "Conflict", DRAFTS + "/" + ID + "/approve");
        result.andExpect(jsonPath("$.message").value("Draft cannot be approved in current status: REJECTED"));
    }

    @Test
    void anUnknownPolicyIs404AndAConflictingStatusChangeIs409FromPolicyController() throws Exception {
        when(policyService.getPolicyById(ID))
                .thenThrow(new ResourceNotFoundException("Policy", "id", ID.toString()));
        when(policyService.setPolicyStatus(eq(ID), eq(PolicyStatus.ENABLED), any()))
                .thenThrow(new ConflictException("Policy " + ID + " changed concurrently; reload it and retry."));

        ResultActions missing = mockMvc.perform(get(POLICIES + "/{id}", ID).with(admin()));
        ResultActions conflict = mockMvc.perform(patch(POLICIES + "/{id}/status", ID).param("status", "ENABLED")
                .with(admin()).with(csrf()));

        assertShape(missing, 404, "Not Found", POLICIES + "/" + ID);
        missing.andExpect(jsonPath("$.message").value("Policy not found with id : '" + ID + "'"));
        assertShape(conflict, 409, "Conflict", POLICIES + "/" + ID + "/status");
        conflict.andExpect(jsonPath("$.message").value("Policy " + ID + " changed concurrently; reload it and retry."));
    }

    @Test
    void anUnknownEvaluationIs404FromPolicyEvaluationController() throws Exception {
        when(evaluations.getPolicyEvaluationById(ID))
                .thenThrow(new ResourceNotFoundException("PolicyEvaluation", "id", ID.toString()));

        ResultActions result = mockMvc.perform(get(EVALUATIONS + "/{id}", ID).with(admin()));

        assertShape(result, 404, "Not Found", EVALUATIONS + "/" + ID);
        result.andExpect(jsonPath("$.message").value("PolicyEvaluation not found with id : '" + ID + "'"));
    }

    @Test
    void anUnknownSubscriptionIs404FromEventCenterController() throws Exception {
        when(subscriptions.getSubscriptionById(ID))
                .thenThrow(new ResourceNotFoundException("EventSubscription", "id", ID.toString()));

        ResultActions result = mockMvc.perform(get(SUBSCRIPTIONS + "/{id}", ID).with(admin()));

        assertShape(result, 404, "Not Found", SUBSCRIPTIONS + "/" + ID);
    }

    @Test
    void theAuditUnavailableAnswerStaysA503ForADraftPublish() throws Exception {
        when(policyService.publishDraft(eq(ID), any(PolicyDraftActionDTO.class), eq(USER)))
                .thenThrow(new AuditUnavailableException("append to audit stream govern.policy failed: refused", null));

        ResultActions result = mockMvc.perform(post(DRAFTS + "/{id}/publish", ID).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\":\"PUBLISH\"}"));

        assertShape(result, 503, "Service Unavailable", DRAFTS + "/" + ID + "/publish");
        result.andExpect(jsonPath("$.reason").value("audit-unavailable"));
    }

    @Test
    void anUnexpectedFailureIs500WithNoStackTraceAndNoInternalDetailInTheBody() throws Exception {
        String leak = "jdbc:postgresql://db.internal.example:5432/dlap password=s3cr3t-value";
        when(policyService.getPolicyById(ID)).thenThrow(new NullPointerException(leak));
        when(evaluations.getPolicyEvaluationById(ID)).thenThrow(new UnsupportedOperationException(leak));

        for (String path : new String[] {POLICIES + "/" + ID, EVALUATIONS + "/" + ID}) {
            ResultActions result = mockMvc.perform(get(path).with(admin()));

            assertShape(result, 500, "Internal Server Error", path);
            result.andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist())
                    .andExpect(content().string(not(containsString("s3cr3t"))))
                    .andExpect(content().string(not(containsString("NullPointerException"))))
                    .andExpect(content().string(not(containsString("UnsupportedOperationException"))))
                    .andExpect(content().string(not(containsString("com.dlap"))))
                    .andExpect(content().string(not(containsString("\tat "))));
        }
    }
}
