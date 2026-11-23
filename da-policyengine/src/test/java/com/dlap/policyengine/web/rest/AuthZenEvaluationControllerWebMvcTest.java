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

import com.dlap.policyengine.access.AccessDecision;
import com.dlap.policyengine.access.AccessDecisionEventProducer;
import com.dlap.policyengine.access.AccessDecisionService;
import com.dlap.policyengine.access.IAssetSnapshotResolver;
import com.dlap.policyengine.config.SecurityConfiguration;

/**
 * ADR-0040 @WebMvcTest for {@link AuthZenEvaluationController}: unauth → 401,
 * wrong role → 403, service-account happy path → 200 with the AuthZEN response
 * shape (decision boolean + context.id + reason_admin).
 */
@WebMvcTest(controllers = AuthZenEvaluationController.class)
@Import(SecurityConfiguration.class)
class AuthZenEvaluationControllerWebMvcTest {

    private static final String BODY = "{"
            + "\"subject\":{\"type\":\"agent\",\"id\":\"svc-1\","
            + "\"properties\":{\"roles\":[\"DATA_ANALYST\"]}},"
            + "\"action\":{\"name\":\"read\"},"
            + "\"resource\":{\"type\":\"asset\",\"properties\":{\"labels\":[\"EU_RESIDENT\"]}},"
            + "\"context\":{\"purpose\":\"campaign-analytics\",\"consent\":true}}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessDecisionService accessDecisionService;

    @MockitoBean
    private AccessDecisionEventProducer eventProducer;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private void stubAllow() {
        AccessDecision decision = new AccessDecision(
                "dec-test-1", true, "allowed by access-eu-marketing-aggregate",
                List.of("access-eu-marketing-aggregate"),
                List.of(new AccessDecision.PolicyDslObligation("mask", List.of("PII"))),
                false, 2);
        IAssetSnapshotResolver.ResolvedResource resolved =
                new IAssetSnapshotResolver.ResolvedResource(java.util.Map.of(), null, null);
        when(accessDecisionService.evaluate(any()))
                .thenReturn(new AccessDecisionService.EvaluationResult(decision, resolved));
    }

    @Test
    void evaluation_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ROLE_VIEWER")
    void evaluation_withWrongRole_isForbidden() throws Exception {
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void evaluation_withServiceAccount_returnsAuthZenDecision() throws Exception {
        stubAllow();
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value(true))
                .andExpect(jsonPath("$.context.id").value("dec-test-1"))
                .andExpect(jsonPath("$.context.reason_admin.en")
                        .value("allowed by access-eu-marketing-aggregate"))
                .andExpect(jsonPath("$.context.obligations[0].type").value("mask"))
                .andExpect(jsonPath("$.context.obligations[0].labels[0]").value("PII"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void evaluation_withAdmin_isAllowed() throws Exception {
        stubAllow();
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value(true));
    }

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void batchEvaluations_returnsResultsInOrder() throws Exception {
        stubAllow();
        String batch = "{\"evaluations\":[" + BODY + "," + BODY + "]}";
        mockMvc.perform(post("/access/v1/evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(batch).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].decision").value(true))
                .andExpect(jsonPath("$.evaluations[1].context.id").value("dec-test-1"));
    }
}
