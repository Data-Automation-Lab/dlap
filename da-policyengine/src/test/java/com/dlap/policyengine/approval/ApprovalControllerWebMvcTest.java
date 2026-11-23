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

package com.dlap.policyengine.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.policyengine.config.SecurityConfiguration;

/**
 * The approval API's authorization and wire shape: who may ask, read, decide and break glass
 * (docs/specs/access-model.md), the JWT {@code sub} as the decider, and refusals as status plus
 * reason.
 */
@WebMvcTest(controllers = ApprovalController.class)
@Import(SecurityConfiguration.class)
class ApprovalControllerWebMvcTest {

    private static final String BASE = "/api/v1/policyengine/approvals";
    private static final UUID ID = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000002");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApprovalService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor token(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub + "-name"))
                .authorities(java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    private static ApprovalService.Detail detail(ApprovalStatus status, int approvals) {
        ApprovalRequest r = new ApprovalRequest(ID, "fire:f-1", ApprovalRequest.ORIGIN_POLICY, ActionClass.HARD_DELETE,
                "delete", Map.of("softDeleteWindowDays", 0), ASSET, "gs://b", "GCP", "GCS_BUCKET", null, "p", "P", "f-1",
                "policy:x", ApprovalRequest.REQUESTER_POLICY, ApprovalRequest.POLICY_ENGINE,
                List.of("policy:x", "da-policyengine"), "stale", 2, approvals, status, T0, T0.plusSeconds(3600), null,
                null, ApprovalRequest.DISPATCH_NONE, null);
        List<ApprovalDecision> decisions = approvals == 0 ? List.of() : List.of(new ApprovalDecision(UUID.randomUUID(),
                ID, "sub-alice", "alice", List.of("ROLE_CDO"), DecisionKind.APPROVE, "looks right", T0));
        return new ApprovalService.Detail(r, decisions);
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/inbox")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + ID + "/decisions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"comment\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aDecisionCarriesTheTokenSubjectAndTheComment() throws Exception {
        when(service.decide(eq(ID), eq(DecisionKind.APPROVE), eq("looks right"), any()))
                .thenReturn(detail(ApprovalStatus.PENDING, 1));

        mvc.perform(post(BASE + "/" + ID + "/decisions").with(token("sub-alice", "ROLE_CDO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\",\"comment\":\"looks right\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.requiredApprovals").value(2))
                .andExpect(jsonPath("$.decisions[0].approverSub").value("sub-alice"))
                .andExpect(jsonPath("$.decisions[0].comment").value("looks right"));

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).decide(eq(ID), eq(DecisionKind.APPROVE), eq("looks right"), caller.capture());
        assertThat(caller.getValue().subject()).isEqualTo("sub-alice");
        assertThat(caller.getValue().username()).isEqualTo("sub-alice-name");
        assertThat(caller.getValue().authorities()).contains("ROLE_CDO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DATA_ENGINEER", "ROLE_DATA_SCIENTIST", "ROLE_AUDITOR", "ROLE_VIEWER",
            "ROLE_DATA_ANALYST", "ROLE_SERVICE_ACCOUNT"})
    void onlyExecutiveAndLeadershipMayDecide(String role) throws Exception {
        mvc.perform(post(BASE + "/" + ID + "/decisions").with(token("sub-x", role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).decide(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CDO", "ROLE_DIRECTOR", "ROLE_POLICY_MANAGER", "ROLE_SERVICE_ACCOUNT"})
    void onlyAnAdministratorMayBreakGlass(String role) throws Exception {
        mvc.perform(post(BASE + "/" + ID + "/break-glass").with(token("sub-x", role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\":\"ransomware spreading through this bucket\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).breakGlass(any(), any(), any());
    }

    @Test
    void anAdministratorBreaksGlassWithAJustification() throws Exception {
        when(service.breakGlass(eq(ID), eq("ransomware spreading through this bucket"), any()))
                .thenReturn(detail(ApprovalStatus.APPROVED_BREAK_GLASS, 0));

        mvc.perform(post(BASE + "/" + ID + "/break-glass").with(token("sub-carol", "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\":\"ransomware spreading through this bucket\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_BREAK_GLASS"))
                .andExpect(jsonPath("$.executable").value(true));
    }

    @Test
    void aRefusalAnswersItsStatusAndReason() throws Exception {
        when(service.decide(any(), any(), any(), any())).thenThrow(new ApprovalException(HttpStatus.FORBIDDEN,
                ApprovalException.SELF_APPROVAL, "the requester or submitter of a request cannot decide it"));

        mvc.perform(post(BASE + "/" + ID + "/decisions").with(token("sub-alice", "ROLE_CDO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"mine\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("self-approval"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value(BASE + "/" + ID + "/decisions"));
    }

    @Test
    void aMissingCommentOrAnUnknownDecisionIsABadRequest() throws Exception {
        mvc.perform(post(BASE + "/" + ID + "/decisions").with(token("sub-alice", "ROLE_CDO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/" + ID + "/decisions").with(token("sub-alice", "ROLE_CDO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"BREAK_GLASS\",\"comment\":\"sneaky\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("invalid"));
        verify(service, never()).decide(any(), any(), any(), any());
    }

    @Test
    void aServiceAccountAsksAndGets201ThenAnIdempotentRetryGets200() throws Exception {
        when(service.create(any(), any()))
                .thenReturn(new ApprovalService.Created(detail(ApprovalStatus.PENDING, 0), true))
                .thenReturn(new ApprovalService.Created(detail(ApprovalStatus.PENDING, 0), false));
        String body = "{\"actionClass\":\"HARD_DELETE\",\"actionType\":\"delete\",\"assetId\":\"" + ASSET
                + "\",\"reason\":\"window ended\",\"idempotencyKey\":\"k-1\",\"onBehalfOf\":\"sub-alice\"}";

        mvc.perform(post(BASE).with(token("sub-robot", "ROLE_SERVICE_ACCOUNT")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(ID.toString()));
        mvc.perform(post(BASE).with(token("sub-robot", "ROLE_SERVICE_ACCOUNT")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());

        ArgumentCaptor<ApprovalService.CreateCommand> cmd = ArgumentCaptor.forClass(ApprovalService.CreateCommand.class);
        verify(service, org.mockito.Mockito.times(2)).create(cmd.capture(), any());
        assertThat(cmd.getValue().onBehalfOf()).isEqualTo("sub-alice");
        assertThat(cmd.getValue().actionClass()).isEqualTo("HARD_DELETE");
    }

    @Test
    void readersOfTheReaderTierOtherThanAuditorCannotSeeApprovals() throws Exception {
        mvc.perform(get(BASE).with(token("sub-v", "ROLE_VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE).with(token("sub-v", "ROLE_DATA_ANALYST"))).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(token("sub-a", "ROLE_AUDITOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionClass\":\"ARCHIVE\",\"actionType\":\"archive\",\"assetId\":\"" + ASSET
                        + "\",\"reason\":\"cold\"}")).andExpect(status().isForbidden());
        verify(service, never()).create(any(), any());
    }

    @Test
    void anAuditorListsWithFiltersAndReadsOneRequest() throws Exception {
        when(service.list(eq(ApprovalStatus.PENDING), eq(ActionClass.HARD_DELETE), eq(ASSET), eq(0), eq(20)))
                .thenReturn(new ApprovalService.Page(List.of(detail(ApprovalStatus.PENDING, 0).request()), 0, 20, 41));
        when(service.get(ID)).thenReturn(detail(ApprovalStatus.PENDING, 1));

        mvc.perform(get(BASE).with(token("sub-aud", "ROLE_AUDITOR")).param("status", "pending")
                        .param("actionClass", "hard_delete").param("assetId", ASSET.toString()).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(41))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].actionClass").value("HARD_DELETE"))
                .andExpect(jsonPath("$.content[0].decisions").doesNotExist());
        mvc.perform(get(BASE + "/" + ID).with(token("sub-aud", "ROLE_AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions[0].decision").value("APPROVE"));
        mvc.perform(get(BASE).with(token("sub-aud", "ROLE_AUDITOR")).param("status", "LOST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theInboxIsForApprovers() throws Exception {
        when(service.inbox(any(), anyInt(), anyInt())).thenReturn(new ApprovalService.Page(List.of(), 0, 50, 0));

        mvc.perform(get(BASE + "/inbox").with(token("sub-bob", "ROLE_DIRECTOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BASE + "/inbox").with(token("sub-dave", "ROLE_DATA_ENGINEER")))
                .andExpect(status().isForbidden());
        verify(service).inbox(any(), eq(0), eq(50));
        verify(service, never()).list(isNull(), isNull(), isNull(), anyInt(), anyInt());
    }

    /* ----------------------------------------------------------------- withdraw (APPR-03) */

    private static final String WITHDRAW_BODY =
            "{\"reason\":\"Withdrawn: soft-delete record r-1 was restored\",\"onBehalfOf\":\"sub-alice\"}";

    private static ApprovalService.Detail withdrawn() {
        ApprovalRequest r = detail(ApprovalStatus.PENDING, 0).request();
        ApprovalRequest w = new ApprovalRequest(r.id(), r.requestKey(), r.origin(), r.actionClass(), r.actionType(),
                r.actionParameters(), r.assetId(), r.assetUri(), r.provider(), r.serviceType(), r.policyDbId(),
                r.policyId(), r.policyName(), r.fireId(), r.requestedBy(), r.requesterKind(), r.submittedBy(),
                r.excludedSubjects(), r.reason(), r.requiredApprovals(), 0, ApprovalStatus.WITHDRAWN, r.createdAt(),
                r.expiresAt(), T0.plusSeconds(60), null, ApprovalRequest.DISPATCH_NONE, null, "sub-robot",
                "sub-alice", "Withdrawn: soft-delete record r-1 was restored", T0.plusSeconds(60));
        return new ApprovalService.Detail(w, List.of());
    }

    @Test
    void aServiceAccountWithdrawsAndTheTokenSubjectIsTheWithdrawer() throws Exception {
        when(service.withdraw(eq(ID), eq("Withdrawn: soft-delete record r-1 was restored"), eq("sub-alice"), any()))
                .thenReturn(withdrawn());

        mvc.perform(post(BASE + "/" + ID + "/withdraw").with(token("sub-robot", "ROLE_SERVICE_ACCOUNT"))
                        .contentType(MediaType.APPLICATION_JSON).content(WITHDRAW_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.withdrawnBy").value("sub-robot"))
                .andExpect(jsonPath("$.onBehalfOf").value("sub-alice"))
                .andExpect(jsonPath("$.withdrawReason").value("Withdrawn: soft-delete record r-1 was restored"))
                .andExpect(jsonPath("$.withdrawnAt").exists())
                .andExpect(jsonPath("$.decisions").isEmpty());

        ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
        verify(service).withdraw(eq(ID), any(), any(), caller.capture());
        assertThat(caller.getValue().subject()).isEqualTo("sub-robot");
        assertThat(caller.getValue().serviceAccount()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_CDO", "ROLE_DIRECTOR", "ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER",
            "ROLE_DATA_SCIENTIST", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_DATA_ANALYST"})
    void aUserTokenEvenAdminGets403OnWithdraw(String role) throws Exception {
        mvc.perform(post(BASE + "/" + ID + "/withdraw").with(token("sub-x", role))
                        .contentType(MediaType.APPLICATION_JSON).content(WITHDRAW_BODY))
                .andExpect(status().isForbidden());
        verify(service, never()).withdraw(any(), any(), any(), any());
    }

    @Test
    void withdrawNeedsAToken() throws Exception {
        mvc.perform(post(BASE + "/" + ID + "/withdraw").contentType(MediaType.APPLICATION_JSON).content(WITHDRAW_BODY))
                .andExpect(status().isUnauthorized());
        verify(service, never()).withdraw(any(), any(), any(), any());
    }

    @Test
    void aWithdrawOfARequestThatIsNotPendingAnswers409WithTheCurrentStatus() throws Exception {
        when(service.withdraw(any(), any(), any(), any())).thenThrow(new ApprovalException(HttpStatus.CONFLICT,
                ApprovalException.NOT_PENDING, "the approval request is APPROVED", ApprovalStatus.APPROVED));

        mvc.perform(post(BASE + "/" + ID + "/withdraw").with(token("sub-robot", "ROLE_SERVICE_ACCOUNT"))
                        .contentType(MediaType.APPLICATION_JSON).content(WITHDRAW_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.httpStatus").value(409))
                .andExpect(jsonPath("$.reason").value("not-pending"))
                .andExpect(jsonPath("$.path").value(BASE + "/" + ID + "/withdraw"));
    }

    @Test
    void aWithdrawOfAMissingRequestAnswers404InTheServicesErrorShape() throws Exception {
        when(service.withdraw(any(), any(), any(), any())).thenThrow(new ApprovalException(HttpStatus.NOT_FOUND,
                ApprovalException.NOT_FOUND, "approval request not found"));

        mvc.perform(post(BASE + "/" + ID + "/withdraw").with(token("sub-robot", "ROLE_SERVICE_ACCOUNT"))
                        .contentType(MediaType.APPLICATION_JSON).content(WITHDRAW_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.reason").value("not-found"));
    }

    @Test
    void aShortReasonOrAMissingPersonIsABadRequestBeforeTheServiceIsCalled() throws Exception {
        for (String body : List.of("{\"reason\":\"too short\",\"onBehalfOf\":\"sub-alice\"}",
                "{\"onBehalfOf\":\"sub-alice\"}",
                "{\"reason\":\"Withdrawn: soft-delete record r-1 was restored\"}",
                "{\"reason\":\"Withdrawn: soft-delete record r-1 was restored\",\"onBehalfOf\":\"\"}")) {
            mvc.perform(post(BASE + "/" + ID + "/withdraw").with(token("sub-robot", "ROLE_SERVICE_ACCOUNT"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(service, never()).withdraw(any(), any(), any(), any());
    }

    @Test
    void theAuditLogBeingDownAnswers503WithItsReason() throws Exception {
        when(service.withdraw(any(), any(), any(), any())).thenThrow(new ApprovalException(
                HttpStatus.SERVICE_UNAVAILABLE, ApprovalException.AUDIT_UNAVAILABLE, "the audit log is down"));

        mvc.perform(post(BASE + "/" + ID + "/withdraw").with(token("sub-robot", "ROLE_SERVICE_ACCOUNT"))
                        .contentType(MediaType.APPLICATION_JSON).content(WITHDRAW_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("audit-unavailable"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void theListFilterAcceptsTheWithdrawnState() throws Exception {
        when(service.list(eq(ApprovalStatus.WITHDRAWN), isNull(), isNull(), eq(0), eq(50)))
                .thenReturn(new ApprovalService.Page(List.of(withdrawn().request()), 0, 50, 1));

        mvc.perform(get(BASE).with(token("sub-aud", "ROLE_AUDITOR")).param("status", "withdrawn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.content[0].executable").value(false));
    }
}
