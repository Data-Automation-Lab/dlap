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
import static com.dlap.autodelete.retire.Approvals.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RET-02: what lets a policy delete into its soft-delete window, and what refuses it.
 */
class SoftDeleteAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final UUID ASSET = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID APPROVAL = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000002");
    private static final String FIRE = "9a9a9a9a-0000-4000-8000-000000000f1e";
    private static final String POLICY_DB = "5e5e5e5e-0000-4000-8000-000000000042";

    private final SoftDeleteRecordRepository records = mock(SoftDeleteRecordRepository.class);
    private final ApprovalClient approvals = mock(ApprovalClient.class);
    private final CatalogFactsClient catalog = mock(CatalogFactsClient.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private SoftDeleteAdmission admission;

    @BeforeEach
    void setUp() {
        admission = new SoftDeleteAdmission(records, approvals, catalog, audit, RetireProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(records.findById(any())).thenReturn(Optional.empty());
        when(records.findFirstByAssetIdAndStatusInOrderByQueuedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(records.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Map<String, Object> event(Map<String, Object> params, Map<String, Object> approval) {
        Map<String, Object> e = new HashMap<>();
        e.put("actionType", "delete");
        e.put("assetId", ASSET.toString());
        e.put("assetUri", "GS://Dlap-GCP-E2E-Raw");
        e.put("provider", "GCP");
        e.put("serviceType", "GCS_BUCKET");
        e.put("fireId", FIRE);
        e.put("policyId", "e2e-gcp-delete");
        e.put("policyDbId", POLICY_DB);
        e.put("requireApproval", false);
        e.put("actionParameters", params);
        if (approval != null) {
            e.put("approval", approval);
        }
        return e;
    }

    private static Map<String, Object> approvalBlock(String actionClass, List<String> approvers) {
        return Map.of("approvalId", APPROVAL.toString(), "status", "APPROVED", "actionClass", actionClass,
                "requiredApprovals", approvers.size(), "approvers", approvers, "breakGlass", false);
    }

    private void approved(String actionClass, String... approvers) {
        when(approvals.read(APPROVAL)).thenReturn(Optional.of(view(APPROVAL, ASSET, "APPROVED", actionClass,
                approvers.length, "policy:" + POLICY_DB, FIRE, approvers)));
    }

    private void catalogSays(Boolean hold, String coverage) {
        when(catalog.read(ASSET)).thenReturn(Optional.of(new AssetFacts(ASSET, "gs://dlap-gcp-e2e-raw", "GCP",
                "GCS_BUCKET", hold, coverage, List.of(), 2048L)));
    }

    @Test
    void anApprovedDeleteEntersItsWindowWithTheCanonicalUriAndIsAudited() {
        approved("SOFT_DELETE", ALICE, BOB);
        catalogSays(false, "PARTIAL");

        SoftDeleteAdmission.Outcome out = admission.admitPolicyAction(event(Map.of("auditNote", "e2e"),
                approvalBlock("SOFT_DELETE", List.of(ALICE, BOB))));

        assertThat(out.admitted()).isTrue();
        assertThat(out.recordId()).isEqualTo(SoftDeleteAdmission.nameUuid("fire:" + FIRE));
        ArgumentCaptor<SoftDeleteRecordEntity> saved = ArgumentCaptor.forClass(SoftDeleteRecordEntity.class);
        verify(records).saveAndFlush(saved.capture());
        SoftDeleteRecordEntity row = saved.getValue();
        assertThat(row.getAssetUri()).as("canonical URI on the soft-delete row").isEqualTo("gs://dlap-gcp-e2e-raw");
        assertThat(row.getStatus()).isEqualTo(SoftDeleteRecordEntity.SOFT_DELETED);
        assertThat(row.getWindowDays()).isEqualTo(30);
        assertThat(row.getScheduledHardDeleteAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(row.getApprovalId()).isEqualTo(APPROVAL);
        assertThat(row.getApprovers()).isEqualTo(ALICE + "," + BOB);
        assertThat(row.getRequestedBy()).isEqualTo("policy:" + POLICY_DB);
        assertThat(row.getReadCoverage()).isEqualTo("PARTIAL");
        assertThat(row.getServiceType()).isEqualTo("GCS_BUCKET");
        assertThat(row.getSizeBytes()).as("Q-619: the catalog size is kept for the storage-reclaimed KPI")
                .isEqualTo(2048L);
        verify(audit).record(eq(RetireAuditLog.SOFT_DELETED), eq(NOW), eq(RetireAuditLog.SERVICE),
                eq("gs://dlap-gcp-e2e-raw"), eq(AuditOutcome.SUCCEEDED), eq(row.getId().toString()), any());
    }

    @Test
    void aDeleteWithoutApprovalIsRefusedAndAuditedEvenWhenItSaysNoApprovalIsNeeded() {
        SoftDeleteAdmission.Outcome out = admission.admitPolicyAction(event(Map.of(), null));

        assertThat(out.admitted()).isFalse();
        assertThat(out.reason()).isEqualTo(RefusalReason.APPROVAL_MISSING);
        verify(audit).refused(eq(NOW), eq(RetireAuditLog.SERVICE), anyString(), eq(FIRE), eq("soft_delete"),
                eq(RefusalReason.APPROVAL_MISSING), anyString(), any());
        verify(records, never()).saveAndFlush(any());
        verify(approvals, never()).read(any());
    }

    @Test
    void theWindowIsRaisedToTheFloor() {
        approved("SOFT_DELETE", ALICE);
        catalogSays(false, "FULL");

        admission.admitPolicyAction(event(Map.of("softDeleteWindowDays", 1), approvalBlock("SOFT_DELETE", List.of(ALICE))));

        ArgumentCaptor<SoftDeleteRecordEntity> saved = ArgumentCaptor.forClass(SoftDeleteRecordEntity.class);
        verify(records).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRequestedWindowDays()).isEqualTo(1);
        assertThat(saved.getValue().getWindowDays()).isEqualTo(7);
        assertThat(saved.getValue().getScheduledHardDeleteAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.SOFT_DELETED), any(), any(), any(), any(), any(), details.capture());
        assertThat(details.getValue().get("windowClamped")).isEqualTo(true);
    }

    @Test
    void anImmediateHardDeleteStillGetsTheFloorAndNeedsAHardDeleteApproval() {
        approved("SOFT_DELETE", ALICE);
        catalogSays(false, "FULL");

        SoftDeleteAdmission.Outcome refused = admission.admitPolicyAction(event(Map.of("softDeleteWindowDays", 0),
                approvalBlock("SOFT_DELETE", List.of(ALICE))));
        assertThat(refused.reason()).isEqualTo(RefusalReason.APPROVAL_CLASS);

        approved("HARD_DELETE", ALICE, BOB);
        SoftDeleteAdmission.Outcome admitted = admission.admitPolicyAction(event(Map.of("hardDelete", true),
                approvalBlock("HARD_DELETE", List.of(ALICE, BOB))));
        assertThat(admitted.admitted()).isTrue();
        ArgumentCaptor<SoftDeleteRecordEntity> saved = ArgumentCaptor.forClass(SoftDeleteRecordEntity.class);
        verify(records).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getWindowDays()).as("never deleted before the floor").isEqualTo(7);
    }

    @Test
    void anAssetOnLegalHoldOrWithAnUnknownHoldIsRefused() {
        approved("SOFT_DELETE", ALICE);
        catalogSays(true, "FULL");
        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).reason())
                .isEqualTo(RefusalReason.LEGAL_HOLD);
        catalogSays(null, "FULL");
        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).reason())
                .isEqualTo(RefusalReason.LEGAL_HOLD);
        verify(records, never()).saveAndFlush(any());
    }

    @Test
    void anAssetTheCatalogDoesNotKnowOrWhoseUriDisagreesIsRefused() {
        approved("SOFT_DELETE", ALICE);
        when(catalog.read(ASSET)).thenReturn(Optional.empty());
        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).reason())
                .isEqualTo(RefusalReason.ASSET_UNKNOWN);

        when(catalog.read(ASSET)).thenReturn(Optional.of(new AssetFacts(ASSET, "gs://another-bucket", "GCP",
                "GCS_BUCKET", false, "FULL", List.of(), null)));
        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).reason())
                .isEqualTo(RefusalReason.URI_MISMATCH);
    }

    @Test
    void anApprovalGivenByTheRequesterIsRefused() {
        when(approvals.read(APPROVAL)).thenReturn(Optional.of(view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 1,
                ALICE, FIRE, ALICE)));
        catalogSays(false, "FULL");

        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).reason())
                .isEqualTo(RefusalReason.REQUESTER_IS_APPROVER);
    }

    @Test
    void aRedeliveredFireJoinsItsRowWithoutASecondRecord() {
        SoftDeleteRecordEntity existing = SoftDeleteRecordEntity.builder()
                .id(SoftDeleteAdmission.nameUuid("fire:" + FIRE)).build();
        when(records.findById(existing.getId())).thenReturn(Optional.of(existing));

        SoftDeleteAdmission.Outcome out = admission.admitPolicyAction(event(Map.of(),
                approvalBlock("SOFT_DELETE", List.of(ALICE))));

        assertThat(out.recordId()).isEqualTo(existing.getId());
        assertThat(out.created()).isFalse();
        verify(records, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnreachablePolicyEngineOrCatalogIsRetriedNotRefused() {
        when(approvals.read(APPROVAL)).thenThrow(new RetireRemoteException("down", true, null));
        assertThatThrownBy(() -> admission.admitPolicyAction(event(Map.of(),
                approvalBlock("SOFT_DELETE", List.of(ALICE))))).isInstanceOf(RetireRemoteException.class);

        org.mockito.Mockito.reset(approvals);
        approved("SOFT_DELETE", ALICE);
        when(catalog.read(ASSET)).thenThrow(new RetireRemoteException("down", true, null));
        assertThatThrownBy(() -> admission.admitPolicyAction(event(Map.of(),
                approvalBlock("SOFT_DELETE", List.of(ALICE))))).isInstanceOf(RetireRemoteException.class);
        verify(audit, never()).refused(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPolicyEngineThatRefusesTheServiceIsARefusal() {
        when(approvals.read(APPROVAL)).thenThrow(new RetireRemoteException("HTTP 403", false, null));
        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).reason())
                .isEqualTo(RefusalReason.REMOTE_REFUSED);
    }

    @Test
    void aSecondDeleteOfAnAssetJoinsTheActiveOne() {
        approved("SOFT_DELETE", ALICE);
        catalogSays(false, "FULL");
        SoftDeleteRecordEntity active = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).build();
        when(records.findFirstByAssetIdAndStatusInOrderByQueuedAtDesc(eq(ASSET), any())).thenReturn(Optional.of(active));

        assertThat(admission.admitPolicyAction(event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)))).recordId())
                .isEqualTo(active.getId());
        verify(records, never()).saveAndFlush(any());
    }

    /** Q-631: a bucket whose rule is in force is still being deleted, so a second request joins that row. */
    @Test
    void aBucketWhoseRuleIsInForceCountsAsAnActiveDelete() {
        assertThat(SoftDeleteRecordEntity.ACTIVE).contains(SoftDeleteRecordEntity.RULE_IN_FORCE);
    }

    @Test
    void aTaskAssetExcludesItsSubmitterAndUsesItsOwnRecordId() {
        when(approvals.read(APPROVAL)).thenReturn(Optional.of(view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 1,
                "someone", null, Approvals.CAROL)));
        catalogSays(false, "FULL");

        assertThat(admission.admitTaskAsset("t1", ASSET, APPROVAL, Approvals.CAROL, null, false, "why").reason())
                .isEqualTo(RefusalReason.REQUESTER_IS_APPROVER);

        when(approvals.read(APPROVAL)).thenReturn(Optional.of(view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 1,
                "someone", null, ALICE)));
        SoftDeleteAdmission.Outcome out = admission.admitTaskAsset("t1", ASSET, APPROVAL, Approvals.CAROL, 10, false, "why");
        assertThat(out.recordId()).isEqualTo(SoftDeleteAdmission.nameUuid("task:t1:" + ASSET));
        ArgumentCaptor<SoftDeleteRecordEntity> saved = ArgumentCaptor.forClass(SoftDeleteRecordEntity.class);
        verify(records).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOrigin()).isEqualTo(SoftDeleteAdmission.ORIGIN_TASK);
        assertThat(saved.getValue().getRequestedBy()).isEqualTo(Approvals.CAROL);
        assertThat(saved.getValue().getWindowDays()).isEqualTo(10);
    }

    @Test
    void theWindowParametersFollowTheApprovalClasses() {
        assertThat(SoftDeleteAdmission.windowAsk(Map.of())).isEqualTo(new SoftDeleteAdmission.WindowAsk(null, false));
        assertThat(SoftDeleteAdmission.windowAsk(Map.of("softDeleteWindowDays", "14")))
                .isEqualTo(new SoftDeleteAdmission.WindowAsk(14, false));
        assertThat(SoftDeleteAdmission.windowAsk(Map.of("softDeleteWindowDays", -1)).hard()).isTrue();
        assertThat(SoftDeleteAdmission.windowAsk(Map.of("softDeleteWindowDays", "soon")).hard()).isTrue();
        assertThat(SoftDeleteAdmission.windowAsk(Map.of("hardDelete", "true")).hard()).isTrue();
    }

    @Test
    void anActionWithoutAssetIdIsInvalid() {
        Map<String, Object> e = event(Map.of(), approvalBlock("SOFT_DELETE", List.of(ALICE)));
        e.remove("assetId");
        assertThat(admission.admitPolicyAction(e).reason()).isEqualTo(RefusalReason.INVALID);
    }
}
