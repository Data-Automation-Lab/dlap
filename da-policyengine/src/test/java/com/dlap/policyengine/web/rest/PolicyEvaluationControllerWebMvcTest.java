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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.dlap.policyengine.config.SecurityConfiguration;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.service.IPolicyEvaluationService;

/**
 * Q-569 (FU-4B): {@code POST /{policyId}/evaluations} hands the caller's JWT {@code sub} to the
 * evaluation, which makes the caller an excluded approver of what the evaluation holds. The call
 * needs ROLE_ADMIN and a bearer token with a subject; anything else is a 403 before any evaluation
 * or fire.
 */
@WebMvcTest(controllers = PolicyEvaluationController.class)
@Import(SecurityConfiguration.class)
class PolicyEvaluationControllerWebMvcTest {

    private static final UUID POLICY = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000009");
    private static final String SUB = "7c1f6a52-3b0e-4f8a-9d2c-5a1e0b9c4d33";
    private static final String URL = "/api/v1/policyengine/" + POLICY + "/evaluations";
    private static final String BODY = "{\"targetAssetId\":\"1f2d3a4b-0000-4000-8000-000000000001\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IPolicyEvaluationService evaluations;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor token(String... roles) {
        return jwt().jwt(j -> j.subject(SUB).claim("preferred_username", "alice"))
                .authorities(java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    private org.springframework.test.web.servlet.ResultActions trigger(
            org.springframework.test.web.servlet.request.RequestPostProcessor auth) throws Exception {
        return mvc.perform(post(URL).with(auth).contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    @Test
    void theAdminsJwtSubIsPassedToTheEvaluationAsTheTriggeringSubject() throws Exception {
        PolicyEvaluationOutputDTO out = new PolicyEvaluationOutputDTO();
        out.setId(UUID.randomUUID());
        when(evaluations.triggerPolicyEvaluation(eq(POLICY), any(), any(), anyString())).thenReturn(out);

        trigger(token("ROLE_ADMIN")).andExpect(status().isCreated());

        verify(evaluations).triggerPolicyEvaluation(eq(POLICY), any(), eq(UUID.fromString(SUB)), eq(SUB));
    }

    @Test
    void aSubThatIsNotAUuidStillReachesTheGateAsTheTriggeringSubject() throws Exception {
        PolicyEvaluationOutputDTO out = new PolicyEvaluationOutputDTO();
        out.setId(UUID.randomUUID());
        when(evaluations.triggerPolicyEvaluation(eq(POLICY), any(), any(), anyString())).thenReturn(out);
        JwtRequestPostProcessor opaque = jwt().jwt(j -> j.subject("opaque-subject-17"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        trigger(opaque).andExpect(status().isCreated());

        verify(evaluations).triggerPolicyEvaluation(eq(POLICY), any(), any(), eq("opaque-subject-17"));
    }

    @Test
    void aTokenWithoutASubjectIsRefusedBeforeAnyEvaluation() throws Exception {
        JwtRequestPostProcessor noSub = jwt().jwt(j -> j.claims(c -> c.remove("sub")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        trigger(noSub).andExpect(status().isForbidden());

        verify(evaluations, never()).triggerPolicyEvaluation(any(), any(), any(), any());
    }

    @Test
    void anAdminWithoutABearerTokenIsRefusedBeforeAnyEvaluation() throws Exception {
        trigger(user("alice").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).andExpect(status().isForbidden());

        verify(evaluations, never()).triggerPolicyEvaluation(any(), any(), any(), any());
    }

    @Test
    void aCallerWhoIsNotAnAdminIsRefused() throws Exception {
        trigger(token("ROLE_POLICY_MANAGER")).andExpect(status().isForbidden());
        trigger(token("ROLE_CDO")).andExpect(status().isForbidden());

        verify(evaluations, never()).triggerPolicyEvaluation(any(), any(), any(), any());
    }
}
