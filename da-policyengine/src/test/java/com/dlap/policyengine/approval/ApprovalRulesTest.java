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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * APPR-01 rules that need no database: the action classes, the configuration floors, the gate in
 * front of the producer, the caller identity and the approval block on a released action.
 */
class ApprovalRulesTest {

    private static PolicyActionEvent action(String type, Map<String, Object> params, boolean requireApproval,
            boolean dryRun) {
        return new PolicyActionEvent("v1", "p", "6c1e0d2a-0000-4000-8000-000000000001", "P", "f-1",
                "2026-10-20T02:00:00Z", "event", "accessed", null, "e-1", null,
                "1f2d3a4b-0000-4000-8000-000000000001", "gs://b", "GCP", "GCS_BUCKET", type, params, requireApproval,
                dryRun, Map.of());
    }

    /* ----------------------------------------------------------------- classes */

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "archive,,ARCHIVE",
            "ARCHIVE,,ARCHIVE",
            "auto_archive,,ARCHIVE",
            "delete,,SOFT_DELETE",
            "delete,30,SOFT_DELETE",
            "AUTO_DELETE,7,SOFT_DELETE",
            "delete,0,HARD_DELETE",
            "delete,-1,HARD_DELETE",
            "delete,soon,HARD_DELETE",
            "delete,NaN,HARD_DELETE",
            "addLabel,,OTHER",
            "alert.email,,OTHER",
            "setRetention,0,OTHER"})
    void actionClassesFollowTheConsumerRules(String type, String window, ActionClass expected) {
        Map<String, Object> params = new HashMap<>();
        if (window != null) {
            params.put("softDeleteWindowDays", window);
        }
        assertThat(ActionClass.of(type, params)).isEqualTo(expected);
    }

    @Test
    void anExplicitHardDeleteFlagWins() {
        assertThat(ActionClass.of("delete", Map.of("softDeleteWindowDays", 30, "hardDelete", true)))
                .isEqualTo(ActionClass.HARD_DELETE);
        assertThat(ActionClass.of("delete", Map.of("hardDelete", "false"))).isEqualTo(ActionClass.SOFT_DELETE);
        assertThat(ActionClass.of(null, null)).isEqualTo(ActionClass.OTHER);
        assertThat(ActionClass.of("delete", null)).isEqualTo(ActionClass.SOFT_DELETE);
    }

    @Test
    void onlyArchiveAndDeletesAreDestructive() {
        assertThat(ActionClass.ARCHIVE.destructive()).isTrue();
        assertThat(ActionClass.SOFT_DELETE.destructive()).isTrue();
        assertThat(ActionClass.HARD_DELETE.destructive()).isTrue();
        assertThat(ActionClass.OTHER.destructive()).isFalse();
        assertThat(ActionClass.parse(" hard_delete ")).isEqualTo(ActionClass.HARD_DELETE);
        assertThatThrownBy(() -> ActionClass.parse("PURGE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionClass.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    /* ----------------------------------------------------------------- configuration */

    @Test
    void theDefaultsAreTwoForHardDeleteAndOneElsewhere() {
        ApprovalProperties p = ApprovalProperties.defaults();
        assertThat(p.requiredFor(ActionClass.HARD_DELETE)).isEqualTo(2);
        assertThat(p.requiredFor(ActionClass.SOFT_DELETE)).isEqualTo(1);
        assertThat(p.requiredFor(ActionClass.ARCHIVE)).isEqualTo(1);
        assertThat(p.requiredFor(ActionClass.OTHER)).isEqualTo(1);
        assertThat(p.ttl()).isEqualTo(Duration.ofDays(7));
        assertThat(p.approverRoles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_CDO", "ROLE_DIRECTOR",
                "ROLE_POLICY_MANAGER");
        assertThat(p.mfaMarkerAuthority()).isEqualTo("ROLE_dlap-mfa-required");
        assertThat(p.breakGlass().minJustificationLength()).isEqualTo(20);
    }

    @Test
    void settingsThatWeakenDualControlStopTheStartUp() {
        assertThatThrownBy(() -> new ApprovalProperties.Required(0, 1, 2, 1)).hasMessageContaining("required.archive");
        assertThatThrownBy(() -> new ApprovalProperties.Required(1, 1, 11, 1)).hasMessageContaining("required.hard");
        assertThatThrownBy(() -> new ApprovalProperties.BreakGlass(true, 5)).hasMessageContaining("at least 20");
        assertThatThrownBy(() -> props(Duration.ofMinutes(5), List.of("ADMIN"))).hasMessageContaining("ttl");
        assertThatThrownBy(() -> props(Duration.ofDays(91), List.of("ADMIN"))).hasMessageContaining("ttl");
        assertThatThrownBy(() -> props(Duration.ofDays(7), List.of("DATA_ENGINEER")))
                .hasMessageContaining("approver-roles");
        assertThatThrownBy(() -> props(Duration.ofDays(7), List.of("SERVICE_ACCOUNT")))
                .hasMessageContaining("approver-roles");
        assertThatThrownBy(() -> props(Duration.ofDays(7), List.of())).hasMessageContaining("at least one role");
        assertThat(props(Duration.ofDays(7), List.of("role_cdo")).approverRoles()).containsExactly("ROLE_CDO");
    }

    private static ApprovalProperties props(Duration ttl, List<String> roles) {
        return new ApprovalProperties(ttl, null, roles, false, "dlap-mfa-required", null, 2000, 50,
                Duration.ofSeconds(5));
    }

    /* ----------------------------------------------------------------- gate */

    @Test
    void destructiveFiresAreHeldWhateverThePolicySays() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(producer.actionsEnabled()).thenReturn(true);
        when(approvals.hold(any(), any(), any())).thenReturn(true, false);
        ApprovalGate gate = new ApprovalGate(producer, approvals);

        assertThat(gate.submit(action("delete", Map.of("softDeleteWindowDays", 0), false, false)))
                .isEqualTo(ApprovalGate.Route.HELD);
        assertThat(gate.submit(action("archive", Map.of(), false, false))).isEqualTo(ApprovalGate.Route.ALREADY_HELD);
        verify(approvals).hold(any(), org.mockito.ArgumentMatchers.eq(ActionClass.HARD_DELETE), any());
        verify(producer, never()).send(any());
    }

    @Test
    void theTriggeringSubjectIsPassedToTheServiceAndEventFiresPassNone() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(producer.actionsEnabled()).thenReturn(true);
        when(approvals.hold(any(), any(), any())).thenReturn(true);
        ApprovalGate gate = new ApprovalGate(producer, approvals);

        gate.submit(action("archive", Map.of(), false, false), "sub-admin-alice");
        gate.submit(action("archive", Map.of(), false, false));

        verify(approvals).hold(any(), org.mockito.ArgumentMatchers.eq(ActionClass.ARCHIVE),
                org.mockito.ArgumentMatchers.eq("sub-admin-alice"));
        verify(approvals).hold(any(), org.mockito.ArgumentMatchers.eq(ActionClass.ARCHIVE),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void nonDestructiveFiresAreHeldOnlyWhenThePolicyAsks() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(producer.actionsEnabled()).thenReturn(true);
        when(producer.send(any())).thenReturn(PolicyActionProducer.Outcome.PUBLISHED);
        when(approvals.hold(any(), any(), any())).thenReturn(true);
        ApprovalGate gate = new ApprovalGate(producer, approvals);

        assertThat(gate.submit(action("addLabel", Map.of("label", "PII"), false, false)))
                .isEqualTo(ApprovalGate.Route.PUBLISHED);
        assertThat(gate.submit(action("addLabel", Map.of("label", "PII"), true, false)))
                .isEqualTo(ApprovalGate.Route.HELD);
        verify(approvals).hold(any(), org.mockito.ArgumentMatchers.eq(ActionClass.OTHER), any());
    }

    @Test
    void aDestructiveDryRunPassesBecauseItExecutesNothing() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(producer.actionsEnabled()).thenReturn(true);
        when(producer.send(any())).thenReturn(PolicyActionProducer.Outcome.PUBLISHED);
        ApprovalGate gate = new ApprovalGate(producer, approvals);

        assertThat(gate.submit(action("delete", Map.of(), false, true))).isEqualTo(ApprovalGate.Route.PUBLISHED);
        assertThat(ApprovalGate.gated(action("delete", Map.of(), true, true))).isTrue();
        verify(approvals, never()).hold(any(), any(), any());
    }

    @Test
    void inShadowModeNothingIsHeldAndTheProducerShadowsTheFire() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(producer.actionsEnabled()).thenReturn(false);
        when(producer.send(any())).thenReturn(PolicyActionProducer.Outcome.SHADOWED);
        ApprovalGate gate = new ApprovalGate(producer, approvals);

        assertThat(gate.submit(action("delete", Map.of(), false, false))).isEqualTo(ApprovalGate.Route.SHADOWED);
        verify(approvals, never()).hold(any(), any(), any());
        when(producer.send(any())).thenReturn(PolicyActionProducer.Outcome.FAILED);
        assertThat(gate.submit(action("addLabel", Map.of(), false, false))).isEqualTo(ApprovalGate.Route.FAILED);
    }

    /* ----------------------------------------------------------------- caller */

    @Test
    void theCallerIsTheJwtSubject() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(" 7d0e-sub ")
                .claim("preferred_username", "alice").issuedAt(Instant.parse("2026-10-20T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-20T01:00:00Z")).build();
        Caller c = Caller.of(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_CDO", "ROLE_dlap-mfa-required")));

        assertThat(c.subject()).isEqualTo("7d0e-sub");
        assertThat(c.username()).isEqualTo("alice");
        assertThat(c.has("ROLE_CDO")).isTrue();
        assertThat(c.serviceAccount()).isFalse();
    }

    @Test
    void anythingButABearerTokenWithASubjectIsRefused() {
        assertThatThrownBy(() -> Caller.of(new UsernamePasswordAuthenticationToken("alice", "x",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"))))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NO_SUBJECT));
        Jwt noSub = Jwt.withTokenValue("t").header("alg", "none").claim("scope", "x")
                .issuedAt(Instant.parse("2026-10-20T00:00:00Z")).expiresAt(Instant.parse("2026-10-20T01:00:00Z"))
                .build();
        assertThatThrownBy(() -> Caller.of(new JwtAuthenticationToken(noSub, List.of())))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NO_SUBJECT));
        assertThat(new Caller("s", null, Set.of("ROLE_SERVICE_ACCOUNT")).serviceAccount()).isTrue();
    }

    /* ----------------------------------------------------------------- released action */

    @Test
    void aReleasedActionClearsRequireApprovalAndCarriesItsApproval() throws Exception {
        PolicyActionEvent fire = action("delete", Map.of("softDeleteWindowDays", 0), true, false);
        PolicyActionEvent released = fire.approved(new PolicyActionEvent.Approval("a-1", "APPROVED", "HARD_DELETE", 2,
                List.of("sub-a", "sub-b"), false, "2026-10-20T03:00:00Z"));
        ObjectMapper json = new ObjectMapper();

        assertThat(released.requireApproval()).isFalse();
        assertThat(released.fireId()).isEqualTo(fire.fireId());
        String text = json.writeValueAsString(released);
        assertThat(text).contains("\"approval\":{\"approvalId\":\"a-1\",\"status\":\"APPROVED\"");
        assertThat(json.readValue(text, PolicyActionEvent.class)).isEqualTo(released);
        assertThat(json.writeValueAsString(fire)).as("a fire without approval has no approval key")
                .doesNotContain("\"approval\"");
        assertThatThrownBy(() -> fire.approved(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
