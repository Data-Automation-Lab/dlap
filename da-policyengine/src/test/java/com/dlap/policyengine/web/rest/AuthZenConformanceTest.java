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
import java.util.Map;

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
import com.dlap.policyengine.access.AuthZenEvaluationRequest;
import com.dlap.policyengine.access.IAssetSnapshotResolver;
import com.dlap.policyengine.config.SecurityConfiguration;

/**
 * OpenID AuthZEN Authorization API 1.0 wire-contract conformance suite for
 * DLAP's Consumed-stage PDP (ADR-0040, enforcement-contract.md sections 1-3;
 * interop profile in authzen-interop.md). This is a deterministic
 * {@code @WebMvcTest} slice: the {@link AccessDecisionService} is mocked so the
 * assertions target the AuthZEN request/response contract as DLAP profiles it,
 * not the policy-evaluation logic (covered by the service's own unit tests).
 *
 * <p>Coverage (each maps to a spec section / AuthZEN 1.0 requirement):
 * <ul>
 *   <li>Single evaluation ({@code POST /access/v1/evaluation}) returns a JSON
 *       object whose {@code decision} member is a JSON boolean (AuthZEN 5.2).</li>
 *   <li>The DLAP {@code context} extension carries {@code id},
 *       {@code reason_admin.en}, and the {@code obligations} array (section 3).</li>
 *   <li>A default-deny verdict serialises {@code decision:false} with the
 *       default reason and omits null obligations (section 3, section 5).</li>
 *   <li>Batch ({@code POST /access/v1/evaluations}) returns an {@code evaluations}
 *       array in request order (AuthZEN 6 boxcarring).</li>
 *   <li>Malformed request (missing required {@code subject}/{@code action}) is 400.</li>
 *   <li>Missing auth is 401; insufficient role is 403; ROLE_SERVICE_ACCOUNT and
 *       ROLE_ADMIN are 200 (ADR-0034 M2M handshake).</li>
 * </ul>
 */
@WebMvcTest(controllers = AuthZenEvaluationController.class)
@Import(SecurityConfiguration.class)
class AuthZenConformanceTest {

    /** Canonical well-formed AuthZEN 1.0 request (enforcement-contract.md section 2). */
    private static final String REQUEST = "{"
            + "\"subject\":{\"type\":\"agent\",\"id\":\"svc-marketing-agent\","
            + "\"properties\":{\"roles\":[\"DATA_ANALYST\"],\"tier\":\"PRACTITIONER\"}},"
            + "\"action\":{\"name\":\"read\"},"
            + "\"resource\":{\"type\":\"asset\",\"id\":\"9f6d-asset-uuid\","
            + "\"properties\":{\"assetUri\":\"bq://project/dataset/table\","
            + "\"labels\":[\"PII\",\"EU_RESIDENT\"]}},"
            + "\"context\":{\"purpose\":\"campaign-analytics\",\"consent\":true,"
            + "\"region\":\"eu-west1\"}}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessDecisionService accessDecisionService;

    @MockitoBean
    private AccessDecisionEventProducer eventProducer;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /* ------------------------------------------------------------------ */
    /* Test fixtures: deterministic stubbed verdicts (no DB, no Kafka).   */
    /* ------------------------------------------------------------------ */

    private static IAssetSnapshotResolver.ResolvedResource resolved() {
        return new IAssetSnapshotResolver.ResolvedResource(Map.of(), "9f6d-asset-uuid",
                "bq://project/dataset/table");
    }

    private void stub(AccessDecision decision) {
        when(accessDecisionService.evaluate(any(AuthZenEvaluationRequest.class)))
                .thenReturn(new AccessDecisionService.EvaluationResult(decision, resolved()));
    }

    private void stubAllowWithObligation() {
        stub(new AccessDecision(
                "dec-0191c2", true, "allowed by access-eu-marketing-aggregate",
                List.of("access-eu-marketing-aggregate"),
                List.of(new AccessDecision.PolicyDslObligation("mask", List.of("PII"))),
                false, 3));
    }

    private void stubDefaultDeny() {
        stub(new AccessDecision(
                "dec-deny-1", false, "no matching policy; default-decision deny",
                List.of(), List.of(), true, 1));
    }

    /* ------------------------------------------------------------------ */
    /* AuthZEN 5.2: single evaluation returns a boolean decision.         */
    /* ------------------------------------------------------------------ */

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void singleEvaluation_returnsJsonObjectWithBooleanDecision() throws Exception {
        stubAllowWithObligation();
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").isBoolean())
                .andExpect(jsonPath("$.decision").value(true));
    }

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void singleEvaluation_carriesDlapContextExtensionShapes() throws Exception {
        stubAllowWithObligation();
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST).with(csrf()))
                .andExpect(status().isOk())
                // context.id: the audit-join decision id (section 3, section 6).
                .andExpect(jsonPath("$.context.id").value("dec-0191c2"))
                // reason_admin is a language-tagged object per AuthZEN reason semantics.
                .andExpect(jsonPath("$.context.reason_admin.en")
                        .value("allowed by access-eu-marketing-aggregate"))
                // obligations extension: {type, labels}.
                .andExpect(jsonPath("$.context.obligations[0].type").value("mask"))
                .andExpect(jsonPath("$.context.obligations[0].labels[0]").value("PII"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void singleEvaluation_defaultDeny_serialisesFalseAndOmitsNullObligations() throws Exception {
        stubDefaultDeny();
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value(false))
                .andExpect(jsonPath("$.context.reason_admin.en")
                        .value("no matching policy; default-decision deny"))
                // No contributing allow, obligations null, omitted (JsonInclude.NON_NULL).
                .andExpect(jsonPath("$.context.obligations").doesNotExist());
    }

    /* ------------------------------------------------------------------ */
    /* AuthZEN 6: batch evaluations returns results in request order.     */
    /* ------------------------------------------------------------------ */

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void batchEvaluations_returnsResultsInRequestOrder() throws Exception {
        // Two distinct verdicts returned in order so ordering is observable.
        when(accessDecisionService.evaluate(any(AuthZenEvaluationRequest.class)))
                .thenReturn(new AccessDecisionService.EvaluationResult(
                        new AccessDecision("dec-first", true, "allowed by p-a",
                                List.of("p-a"), List.of(), false, 1),
                        resolved()))
                .thenReturn(new AccessDecisionService.EvaluationResult(
                        new AccessDecision("dec-second", false, "denied by p-b",
                                List.of("p-b"), List.of(), false, 1),
                        resolved()));
        String batch = "{\"evaluations\":[" + REQUEST + "," + REQUEST + "]}";
        mockMvc.perform(post("/access/v1/evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(batch).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluations[0].decision").value(true))
                .andExpect(jsonPath("$.evaluations[0].context.id").value("dec-first"))
                .andExpect(jsonPath("$.evaluations[1].decision").value(false))
                .andExpect(jsonPath("$.evaluations[1].context.id").value("dec-second"));
    }

    /* ------------------------------------------------------------------ */
    /* Malformed request is 400 (bean validation on @NotNull members).    */
    /* ------------------------------------------------------------------ */

    @Test
    @WithMockUser(authorities = "ROLE_SERVICE_ACCOUNT")
    void malformedRequest_missingSubjectAndAction_isBadRequest() throws Exception {
        // Bean validation on the @NotNull subject/action members rejects a
        // request that omits them (AuthZEN 5.1 required members).
        String malformed = "{\"resource\":{\"type\":\"asset\"}}";
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(malformed).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------------ */
    /* Auth handshake (ADR-0034): 401 unauth, 403 wrong role, 200 M2M.    */
    /* ------------------------------------------------------------------ */

    @Test
    void evaluation_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ROLE_VIEWER")
    void evaluation_withInsufficientRole_isForbidden() throws Exception {
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void evaluation_withAdmin_isAllowed() throws Exception {
        stubAllowWithObligation();
        mockMvc.perform(post("/access/v1/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value(true));
    }

    @Test
    void batchEvaluations_withoutAuth_isUnauthorized() throws Exception {
        String batch = "{\"evaluations\":[" + REQUEST + "]}";
        mockMvc.perform(post("/access/v1/evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(batch).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
