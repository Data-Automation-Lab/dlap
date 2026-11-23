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

package com.dlap.policyengine.web.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.dto.PolicyDraftActionDTO;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.service.IEventSubscriptionService;
import com.dlap.policyengine.service.IPolicyEvaluationService;
import com.dlap.policyengine.service.IPolicyService;
import com.dlap.policyengine.service.PolicyDraftFromTextService;
import com.dlap.policyengine.service.PolicyEngineMetricsService;
import com.dlap.policyengine.service.PolicyHistoryService;
import com.dlap.policyengine.web.rest.PolicyController;
import com.dlap.policyengine.web.rest.PolicyDraftController;

/**
 * PE-02 (Q-757, Q-758, D41) through the real MVC stack and the real security filter chain: Spring MVC's own
 * client errors keep their status in the service's error shape (a malformed body 400, a path variable of the
 * wrong type 400, an unsupported method 405 with its Allow header, a ResponseStatusException its own
 * status), the same-name create race on {@code uq_policies_live_name} answers 409 {@code name-conflict},
 * and every other data integrity error stays 500. No body names a class, a stack frame or the parser's text.
 */
@WebMvcTest(controllers = {PolicyDraftController.class, PolicyController.class})
@Import(SecurityConfiguration.class)
class ClientErrorMappingWebMvcTest {

    private static final String DRAFTS = "/api/v1/policyengine/drafts";
    private static final String POLICIES = "/api/v1/policyengine/policies";
    private static final UUID ID = UUID.fromString("7d0e6c55-0000-4000-8000-0000000000d2");
    private static final UUID USER = UUID.fromString("7d0e6c55-0000-4000-8000-0000000000a2");
    private static final String CREATE_BODY = "{\"name\":\"p1\",\"policyDocument\":\"apiVersion: dlap/v1\"}";

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
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("com.dlap"))))
                .andExpect(content().string(not(containsString("com.fasterxml"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("\tat "))));
    }

    private static DataIntegrityViolationException integrity(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException(
                        "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"\n"
                                + "  Detail: Key (name)=(secret-policy-name) already exists.", "23505"),
                        "insert into policies", constraint));
    }

    // ----- Q-758: Spring MVC client errors keep their status ----------------------------------

    @Test
    void aMalformedJsonBodyIs400WithAFixedMessage() throws Exception {
        ResultActions result = mockMvc.perform(post(DRAFTS + "/{id}/approve", ID).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\": not-json"));

        assertShape(result, 400, "Bad Request", DRAFTS + "/" + ID + "/approve");
        result.andExpect(jsonPath("$.message").value("Malformed request body"));
        verify(policyService, never()).approveDraft(any(), any(PolicyDraftActionDTO.class), any());
    }

    @Test
    void anEmptyBodyOnAPostThatNeedsOneIs400() throws Exception {
        ResultActions result = mockMvc.perform(post(POLICIES).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON));

        assertShape(result, 400, "Bad Request", POLICIES);
        result.andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void aPathVariableThatIsNotAUuidIs400NamingTheParameterNotTheValue() throws Exception {
        ResultActions result = mockMvc.perform(get(POLICIES + "/{id}", "not-a-uuid").with(admin()));

        assertShape(result, 400, "Bad Request", POLICIES + "/not-a-uuid");
        result.andExpect(jsonPath("$.message").value("Invalid value for parameter 'policyId'"))
                .andExpect(jsonPath("$.message").value(not(containsString("not-a-uuid"))));
    }

    @Test
    void aPathVariableOfTheWrongNumericTypeIs400() throws Exception {
        ResultActions result = mockMvc.perform(get(POLICIES + "/{id}/versions/{version}", ID, "x").with(admin()));

        assertShape(result, 400, "Bad Request", POLICIES + "/" + ID + "/versions/x");
        result.andExpect(jsonPath("$.message").value("Invalid value for parameter 'version'"));
    }

    @Test
    void anUnsupportedMethodIs405WithTheAllowHeader() throws Exception {
        ResultActions result = mockMvc.perform(patch(DRAFTS + "/{id}/approve", ID).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertShape(result, 405, "Method Not Allowed", DRAFTS + "/" + ID + "/approve");
        result.andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.message").value(containsString("PATCH")));
    }

    @Test
    void aResponseStatusExceptionKeepsItsStatus() throws Exception {
        when(policyService.getPolicyById(ID))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down"));

        ResultActions result = mockMvc.perform(get(POLICIES + "/{id}", ID).with(admin()));

        assertShape(result, 429, "Too Many Requests", POLICIES + "/" + ID);
        result.andExpect(jsonPath("$.message").value("slow down"));
    }

    // ----- Q-757: the same-name create race --------------------------------------------------

    @Test
    void theCreateRaceOnTheLiveNameIndexIs409NameConflict() throws Exception {
        when(policyService.createPolicy(any(PolicyInputDTO.class), any()))
                .thenThrow(integrity("uq_policies_live_name"));

        ResultActions result = mockMvc.perform(post(POLICIES).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));

        assertShape(result, 409, "Conflict", POLICIES);
        result.andExpect(jsonPath("$.reason").value("name-conflict"))
                .andExpect(jsonPath("$.message").value("A policy with this name already exists"))
                .andExpect(content().string(not(containsString("secret-policy-name"))))
                .andExpect(content().string(not(containsString("uq_policies_live_name"))));
    }

    @Test
    void theDriverTextAloneIdentifiesTheLiveNameIndex() throws Exception {
        when(policyService.createPolicy(any(PolicyInputDTO.class), any())).thenThrow(
                new DataIntegrityViolationException("could not execute statement", new SQLException(
                        "ERROR: duplicate key value violates unique constraint \"uq_policies_live_name\"")));

        ResultActions result = mockMvc.perform(post(POLICIES).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));

        assertShape(result, 409, "Conflict", POLICIES);
        result.andExpect(jsonPath("$.reason").value("name-conflict"));
    }

    @Test
    void anotherUniqueConstraintStaysA500WithAFixedMessage() throws Exception {
        when(policyService.createPolicy(any(PolicyInputDTO.class), any()))
                .thenThrow(integrity("uq_policy_versions_policy_version"));

        ResultActions result = mockMvc.perform(post(POLICIES).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));

        assertShape(result, 500, "Internal Server Error", POLICIES);
        result.andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(content().string(not(containsString("secret-policy-name"))));
    }

    @Test
    void aDataIntegrityErrorWithNoConstraintNameStaysA500() throws Exception {
        when(policyService.createPolicy(any(PolicyInputDTO.class), any())).thenThrow(
                new DataIntegrityViolationException("null value in column \"name\" violates not-null constraint"));

        ResultActions result = mockMvc.perform(post(POLICIES).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));

        assertShape(result, 500, "Internal Server Error", POLICIES);
        result.andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
