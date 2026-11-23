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

package com.dlap.autodelete.retire;

import static com.dlap.autodelete.retire.Approvals.ALICE;
import static com.dlap.autodelete.retire.Approvals.BOB;
import static com.dlap.autodelete.retire.Approvals.CAROL;
import static com.dlap.autodelete.retire.Approvals.DECIDED;
import static com.dlap.autodelete.retire.Approvals.view;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autodelete.dto.ApprovalViewDTO;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The executor rules of docs/specs/approvals.md section 9, one test per rule.
 */
class ApprovalVerifierTest {

    private static final UUID APPROVAL = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000002");
    private static final UUID ASSET = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String FIRE = "9a9a9a9a-0000-4000-8000-000000000f1e";
    private static final String REQUESTER = "policy:42";

    private static ApprovalVerifier.Expectation hard() {
        return new ApprovalVerifier.Expectation(APPROVAL, ASSET, Set.of(ApprovalVerifier.HARD_DELETE), null, Set.of());
    }

    private static ApprovalVerifier.Expectation soft() {
        return new ApprovalVerifier.Expectation(APPROVAL, ASSET,
                Set.of(ApprovalVerifier.SOFT_DELETE, ApprovalVerifier.HARD_DELETE), FIRE, Set.of());
    }

    @Test
    void anApprovedSoftDeleteWithOneApproverIsReleased() {
        ApprovalVerifier.Verdict v = ApprovalVerifier.verify(soft(), null,
                view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 1, REQUESTER, FIRE, ALICE));
        assertThat(v.allowed()).isTrue();
        assertThat(v.approvers()).containsExactly(ALICE);
    }

    @Test
    void aHardDeleteNeedsTwoDistinctApproversEvenWhenTheRequestSaysOne() {
        ApprovalVerifier.Verdict one = ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "APPROVED", "HARD_DELETE", 1, REQUESTER, null, ALICE));
        assertThat(one.allowed()).isFalse();
        assertThat(one.reason()).isEqualTo(RefusalReason.TOO_FEW_APPROVERS);

        ApprovalVerifier.Verdict two = ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "APPROVED", "HARD_DELETE", 2, REQUESTER, null, ALICE, BOB));
        assertThat(two.allowed()).isTrue();
        assertThat(two.approvers()).containsExactly(ALICE, BOB);
    }

    @Test
    void onePersonCountedTwiceIsNotTwoApprovers() {
        ApprovalVerifier.Verdict v = ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "APPROVED", "HARD_DELETE", 2, REQUESTER, null, ALICE, ALICE));
        assertThat(v.allowed()).isFalse();
        assertThat(v.reason()).isEqualTo(RefusalReason.TOO_FEW_APPROVERS);
    }

    @Test
    void theRequesterOrTheSubmitterCannotBeAnApprover() {
        ApprovalVerifier.Verdict requester = ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "APPROVED", "HARD_DELETE", 2, ALICE, null, ALICE, BOB));
        assertThat(requester.reason()).isEqualTo(RefusalReason.REQUESTER_IS_APPROVER);

        ApprovalVerifier.Verdict submitter = ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "APPROVED", "HARD_DELETE", 2, REQUESTER, null, Approvals.SERVICE, BOB));
        assertThat(submitter.reason()).isEqualTo(RefusalReason.REQUESTER_IS_APPROVER);
    }

    @Test
    void theExecutorExcludesTheTaskSubmitterItself() {
        ApprovalVerifier.Expectation expect = new ApprovalVerifier.Expectation(APPROVAL, ASSET,
                Set.of(ApprovalVerifier.HARD_DELETE), null, Set.of(CAROL));
        ApprovalVerifier.Verdict v = ApprovalVerifier.verify(expect, null,
                view(APPROVAL, ASSET, "APPROVED", "HARD_DELETE", 2, REQUESTER, null, CAROL, BOB));
        assertThat(v.reason()).isEqualTo(RefusalReason.REQUESTER_IS_APPROVER);
    }

    @Test
    void rejectedExpiredPendingAndUnknownApprovalsAreRefused() {
        assertThat(ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "REJECTED", "HARD_DELETE", 2, REQUESTER, null)).reason())
                .isEqualTo(RefusalReason.APPROVAL_REJECTED);
        assertThat(ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "EXPIRED", "HARD_DELETE", 2, REQUESTER, null)).reason())
                .isEqualTo(RefusalReason.APPROVAL_EXPIRED);
        assertThat(ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "PENDING", "HARD_DELETE", 2, REQUESTER, null, ALICE)).reason())
                .isEqualTo(RefusalReason.APPROVAL_NOT_EXECUTABLE);
        assertThat(ApprovalVerifier.verify(hard(), null, null).reason()).isEqualTo(RefusalReason.APPROVAL_UNKNOWN);
    }

    @Test
    void anApprovalForAnotherAssetOrFireIsRefused() {
        assertThat(ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, UUID.randomUUID(), "APPROVED", "HARD_DELETE", 2, REQUESTER, null, ALICE, BOB)).reason())
                .isEqualTo(RefusalReason.APPROVAL_MISMATCH);
        assertThat(ApprovalVerifier.verify(soft(), null,
                view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 1, REQUESTER, "other-fire", ALICE)).reason())
                .isEqualTo(RefusalReason.APPROVAL_MISMATCH);
    }

    @Test
    void anArchiveOrSoftDeleteApprovalDoesNotCoverAHardDelete() {
        assertThat(ApprovalVerifier.verify(soft(), null,
                view(APPROVAL, ASSET, "APPROVED", "ARCHIVE", 1, REQUESTER, FIRE, ALICE)).reason())
                .isEqualTo(RefusalReason.APPROVAL_CLASS);
        assertThat(ApprovalVerifier.verify(hard(), null,
                view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 2, REQUESTER, null, ALICE, BOB)).reason())
                .isEqualTo(RefusalReason.APPROVAL_CLASS);
    }

    @Test
    void aKafkaBlockThatDisagreesWithThePolicyEngineIsRefused() {
        ApprovalViewDTO record = view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 1, REQUESTER, FIRE, ALICE);
        ApprovalEvidence forged = new ApprovalEvidence(APPROVAL, "APPROVED", "SOFT_DELETE", 1, List.of(CAROL), false);
        assertThat(ApprovalVerifier.verify(soft(), forged, record).reason()).isEqualTo(RefusalReason.APPROVAL_MISMATCH);

        ApprovalEvidence otherClass = new ApprovalEvidence(APPROVAL, "APPROVED", "HARD_DELETE", 2, List.of(ALICE), false);
        assertThat(ApprovalVerifier.verify(soft(), otherClass, record).reason()).isEqualTo(RefusalReason.APPROVAL_MISMATCH);

        ApprovalEvidence honest = new ApprovalEvidence(APPROVAL, "APPROVED", "SOFT_DELETE", 1, List.of(ALICE), false);
        assertThat(ApprovalVerifier.verify(soft(), honest, record).allowed()).isTrue();
    }

    @Test
    void aBreakGlassReleaseNeedsTheAdministratorsDecision() {
        ApprovalViewDTO base = view(APPROVAL, ASSET, "APPROVED_BREAK_GLASS", "HARD_DELETE", 2, REQUESTER, null);
        ApprovalVerifier.Verdict without = ApprovalVerifier.verify(hard(), null, base);
        assertThat(without.reason()).isEqualTo(RefusalReason.TOO_FEW_APPROVERS);

        ApprovalViewDTO glass = Approvals.withDecisions(base, "APPROVED_BREAK_GLASS",
                List.of(new ApprovalViewDTO.Decision(CAROL, "BREAK_GLASS", DECIDED)));
        ApprovalVerifier.Verdict with = ApprovalVerifier.verify(hard(), null, glass);
        assertThat(with.allowed()).isTrue();
        assertThat(with.breakGlass()).isTrue();
        assertThat(with.approvers()).containsExactly(CAROL);
    }

    @Test
    void anEvidenceBlockIsReadFromTheReleasedAction() {
        ApprovalEvidence e = ApprovalEvidence.from(java.util.Map.of("approvalId", APPROVAL.toString(), "status",
                "APPROVED", "actionClass", "HARD_DELETE", "requiredApprovals", 2, "approvers", List.of(ALICE, BOB),
                "breakGlass", false));
        assertThat(e.approvalId()).isEqualTo(APPROVAL);
        assertThat(e.approvers()).containsExactly(ALICE, BOB);
        assertThat(ApprovalEvidence.from(null)).isNull();
        assertThat(ApprovalEvidence.from(java.util.Map.of("approvalId", "not-a-uuid"))).isNull();
    }
}
