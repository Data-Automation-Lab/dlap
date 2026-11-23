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

package com.dlap.autoarchival.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.autoarchival.approval.ApprovalRefusedException.Reason;

/** Approvals.md section 9, rule 1, for the archive class. */
class ApprovalVerifierTest {

    private static final String ID = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private final PolicyEngineApprovalClient client = mock(PolicyEngineApprovalClient.class);
    private final ApprovalVerifier verifier = new ApprovalVerifier(client);

    private static Map<String, Object> event(Map<String, Object> approval) {
        Map<String, Object> e = new HashMap<>();
        e.put("actionType", "archive");
        e.put("requireApproval", false);
        if (approval != null) {
            e.put("approval", approval);
        }
        return e;
    }

    private static Map<String, Object> block() {
        return new HashMap<>(Map.of("approvalId", ID, "status", "APPROVED", "actionClass", "ARCHIVE",
                "requiredApprovals", 1, "approvers", List.of("alice-sub"), "breakGlass", false,
                "decidedAt", "2026-10-21T09:14:03.120004Z"));
    }

    private static Reason reason(Runnable r) {
        try {
            r.run();
        } catch (ApprovalRefusedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void anApprovedArchiveCarriesItsApprovalThrough() {
        ArchiveApproval a = verifier.fromEvent(event(block()));

        assertThat(a.approvalId()).isEqualTo(ID);
        assertThat(a.status()).isEqualTo("APPROVED");
        assertThat(a.actionClass()).isEqualTo("ARCHIVE");
        assertThat(a.approvers()).containsExactly("alice-sub");
        assertThat(a.breakGlass()).isFalse();
    }

    @Test
    void aBreakGlassReleaseIsAcceptedAndFlagged() {
        Map<String, Object> b = block();
        b.put("status", "APPROVED_BREAK_GLASS");
        b.put("breakGlass", true);

        assertThat(verifier.fromEvent(event(b)).breakGlass()).isTrue();
    }

    @Test
    void anArchiveWithoutAnApprovalObjectIsRefusedWhateverRequireApprovalSays() {
        assertThat(reason(() -> verifier.fromEvent(event(null)))).isEqualTo(Reason.NO_APPROVAL);
        Map<String, Object> flagged = event(null);
        flagged.put("requireApproval", true);
        assertThat(reason(() -> verifier.fromEvent(flagged))).isEqualTo(Reason.NO_APPROVAL);
        assertThat(reason(() -> verifier.fromEvent(null))).isEqualTo(Reason.NO_APPROVAL);
    }

    @Test
    void anApprovalThatIsNotAnObjectOrHasNoIdIsRefused() {
        Map<String, Object> notAnObject = event(null);
        notAnObject.put("approval", "APPROVED");
        assertThat(reason(() -> verifier.fromEvent(notAnObject))).isEqualTo(Reason.NO_APPROVAL);
        Map<String, Object> noId = block();
        noId.remove("approvalId");
        assertThat(reason(() -> verifier.fromEvent(event(noId)))).isEqualTo(Reason.NO_APPROVAL);
        Map<String, Object> blankId = block();
        blankId.put("approvalId", " ");
        assertThat(reason(() -> verifier.fromEvent(event(blankId)))).isEqualTo(Reason.NO_APPROVAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "EXPIRED", "approved", "", "SHADOWED"})
    void onlyApprovedAndBreakGlassStatusesAreExecutable(String status) {
        Map<String, Object> b = block();
        b.put("status", status);
        assertThat(reason(() -> verifier.fromEvent(event(b)))).isEqualTo(Reason.NOT_APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HARD_DELETE", "SOFT_DELETE", "OTHER", "archive", ""})
    void anApprovalGivenForAnotherActionClassDoesNotAuthorizeAnArchive(String cls) {
        Map<String, Object> b = block();
        b.put("actionClass", cls);
        assertThat(reason(() -> verifier.fromEvent(event(b)))).isEqualTo(Reason.WRONG_CLASS);
    }

    @Test
    void verifyAsksThePolicyEngineEveryTimeNeverTrustingTheEvent() {
        ArchiveApproval claimed = verifier.fromEvent(event(block()));
        ArchiveApproval server = new ArchiveApproval(ID, "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false);
        when(client.fetchExecutable(ID, "asset-1")).thenReturn(server);

        assertThat(verifier.verify(claimed, "asset-1")).isSameAs(server);
        assertThat(verifier.verifyById(ID, "asset-1")).isSameAs(server);
        verify(client, org.mockito.Mockito.times(2)).fetchExecutable(ID, "asset-1");
    }

    @Test
    void aForgedEventTheServerDoesNotConfirmDoesNotRun() {
        ArchiveApproval forged = verifier.fromEvent(event(block()));
        when(client.fetchExecutable(ID, "asset-1")).thenThrow(
                new ApprovalRefusedException(Reason.NOT_EXECUTABLE, "approval is PENDING and not executable"));

        assertThatThrownBy(() -> verifier.verify(forged, "asset-1")).isInstanceOf(ApprovalRefusedException.class);
    }

    @Test
    void anEventWithoutAnApprovalNeverReachesThePolicyEngine() {
        reason(() -> verifier.fromEvent(event(null)));
        verifyNoInteractions(client);
    }
}
