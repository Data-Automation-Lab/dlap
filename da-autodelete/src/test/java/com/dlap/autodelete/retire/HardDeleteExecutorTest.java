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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.ApprovalCreateRequest;
import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRuleGenerator;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.providers.aws.S3LifecycleRuleGenerator;
import com.dlap.autodelete.providers.gcp.GcsLifecycleRuleGenerator;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.service.storage.CloudDeletionServiceFactory;
import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;
import com.dlap.autodelete.service.storage.ICloudDeletionService;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * RET-02: the hard delete at the end of the window, from the approval request to the applied rule.
 */
class HardDeleteExecutorTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final UUID ASSET = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID HARD_APPROVAL = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-0000000000aa");

    private final SoftDeleteRecordRepository records = mock(SoftDeleteRecordRepository.class);
    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);
    private final ApprovalClient approvals = mock(ApprovalClient.class);
    private final CatalogFactsClient catalog = mock(CatalogFactsClient.class);
    private final CloudDeletionServiceFactory engines = mock(CloudDeletionServiceFactory.class);
    private final ICloudDeletionService gcpEngine = mock(ICloudDeletionService.class);
    private final NativeRuleApplier gcsApplier = mock(NativeRuleApplier.class);
    private final NativeRuleApplier s3Applier = mock(NativeRuleApplier.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);

    @BeforeEach
    void setUp() {
        when(gcsApplier.platform()).thenReturn(NativeRulePlatform.GCS_LIFECYCLE);
        when(s3Applier.platform()).thenReturn(NativeRulePlatform.S3_LIFECYCLE);
        when(engines.forProvider("GCP")).thenReturn(gcpEngine);
        when(records.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispositions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispositions.findBySoftDeleteRecordId(any())).thenReturn(Optional.empty());
        when(gcsApplier.versioning(any())).thenReturn(BucketVersioning.UNVERSIONED);
        when(s3Applier.versioning(any())).thenReturn(BucketVersioning.UNVERSIONED);
    }

    /** A generator that still writes the pre-RET-06 date-based S3 rule. */
    private static final class DatedS3Generator implements NativeRuleGenerator {
        @Override
        public NativeRulePlatform platform() {
            return NativeRulePlatform.S3_LIFECYCLE;
        }

        @Override
        public boolean supports(com.dlap.common.assetid.CanonicalAssetId asset) {
            return asset != null && asset.kind() == com.dlap.common.assetid.AssetKind.S3_BUCKET;
        }

        @Override
        public NativeRule generate(com.dlap.common.assetid.CanonicalAssetId asset, UUID ruleKey, Instant now) {
            return new NativeRule(platform(), asset.uri(), "dlap-retire-" + ruleKey, now,
                    "{\"api\":\"s3:PutBucketLifecycleConfiguration\",\"bucket\":\"" + asset.parts().get(0)
                            + "\",\"rules\":[{\"ID\":\"dlap-retire-" + ruleKey + "\",\"Filter\":{\"Prefix\":\"\"},"
                            + "\"Status\":\"Enabled\",\"Expiration\":{\"Date\":\"2026-11-02T00:00:00Z\"}}]}");
        }
    }

    private HardDeleteExecutor executorWith(boolean dryRun, NativeRuleGenerator generator) {
        DispositionPlanner planner = new DispositionPlanner(List.of(generator), List.of(gcsApplier, s3Applier), engines);
        return new HardDeleteExecutor(records, dispositions, approvals, catalog, planner, engines, audit,
                RetireProperties.defaults().withDryRun(dryRun), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SoftDeleteRecordEntity s3Row(String status) {
        SoftDeleteRecordEntity rec = row(status, "s3://bucket-b", "S3_BUCKET");
        rec.setCloudProvider("AWS");
        return rec;
    }

    private void s3CatalogSays() {
        when(catalog.read(ASSET)).thenReturn(Optional.of(new AssetFacts(ASSET, "s3://bucket-b", "AWS", null, false,
                "FULL", List.of("data events on"), 4096L)));
    }

    private HardDeleteExecutor executor(boolean dryRun) {
        DispositionPlanner planner = new DispositionPlanner(
                List.of(new S3LifecycleRuleGenerator(), new GcsLifecycleRuleGenerator()),
                List.of(gcsApplier, s3Applier), engines);
        return new HardDeleteExecutor(records, dispositions, approvals, catalog, planner, engines, audit,
                RetireProperties.defaults().withDryRun(dryRun), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SoftDeleteRecordEntity row(String status, String uri, String serviceType) {
        return SoftDeleteRecordEntity.builder()
                .id(UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1"))
                .assetId(ASSET)
                .assetUri(uri)
                .cloudProvider("GCP")
                .serviceType(serviceType)
                .status(status)
                .origin(SoftDeleteAdmission.ORIGIN_POLICY)
                .requestedBy("policy:42")
                .windowDays(30)
                .queuedAt(NOW.minus(Duration.ofDays(30)))
                .scheduledHardDeleteAt(NOW.minusSeconds(60))
                .hardDeleteApprovalId(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL.equals(status)
                        ? HARD_APPROVAL : null)
                .build();
    }

    private void hardApproved(String... approvers) {
        when(approvals.read(HARD_APPROVAL)).thenReturn(Optional.of(view(HARD_APPROVAL, ASSET, "APPROVED",
                "HARD_DELETE", 2, Approvals.SERVICE, null, approvers)));
    }

    private void catalogSays(Boolean hold, String coverage, String uri) {
        when(catalog.read(ASSET)).thenReturn(Optional.of(new AssetFacts(ASSET, uri, "GCP", null, hold, coverage,
                List.of("dataAccess logs on"), 4096L)));
    }

    @Test
    void aWindowThatEndedAsksForAHardDeleteApprovalWithTwoApprovers() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.SOFT_DELETED, "gs://bucket-a", "GCS_BUCKET");
        when(approvals.create(any())).thenReturn(view(HARD_APPROVAL, ASSET, "PENDING", "HARD_DELETE", 2,
                Approvals.SERVICE, null));

        executor(true).handle(rec);

        ArgumentCaptor<ApprovalCreateRequest> asked = ArgumentCaptor.forClass(ApprovalCreateRequest.class);
        verify(approvals).create(asked.capture());
        assertThat(asked.getValue().actionClass()).isEqualTo("HARD_DELETE");
        assertThat(asked.getValue().requiredApprovals()).isEqualTo(2);
        assertThat(asked.getValue().assetUri()).isEqualTo("gs://bucket-a");
        assertThat(asked.getValue().idempotencyKey()).isEqualTo("retire.delete:hard:" + rec.getId());
        assertThat(asked.getValue().onBehalfOf()).as("a policy delete has no person to act for").isNull();
        assertThat(asked.getValue().actionParameters()).containsEntry("mode", "NATIVE_RULE")
                .containsEntry("platform", "GCS_LIFECYCLE");
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        assertThat(rec.getHardDeleteApprovalId()).isEqualTo(HARD_APPROVAL);
        assertThat(rec.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(audit).record(eq(RetireAuditLog.HARD_DELETE_REQUESTED), eq(NOW), any(), eq("gs://bucket-a"),
                eq(AuditOutcome.REQUESTED), eq(rec.getId().toString()), any());
    }

    @Test
    void aTaskDeleteAsksOnBehalfOfItsSubmitter() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.SOFT_DELETED, "gs://bucket-a", "GCS_BUCKET");
        rec.setOrigin(SoftDeleteAdmission.ORIGIN_TASK);
        rec.setRequestedBy(Approvals.CAROL);
        when(approvals.create(any())).thenReturn(view(HARD_APPROVAL, ASSET, "PENDING", "HARD_DELETE", 2,
                Approvals.CAROL, null));

        executor(true).handle(rec);

        ArgumentCaptor<ApprovalCreateRequest> asked = ArgumentCaptor.forClass(ApprovalCreateRequest.class);
        verify(approvals).create(asked.capture());
        assertThat(asked.getValue().onBehalfOf()).isEqualTo(Approvals.CAROL);
    }

    @Test
    void aKindNothingCanDeleteIsRefusedBeforeAnyoneIsAskedToApprove() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.SOFT_DELETED, "bq://dlap-gcp-e2e/sales", "BIGQUERY_DATASET");

        executor(true).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        assertThat(rec.getLastError()).startsWith("no-executor");
        verify(approvals, never()).create(any());
        verify(audit).refused(eq(NOW), any(), eq("bq://dlap-gcp-e2e/sales"), eq(rec.getId().toString()), eq("hard_delete"),
                eq(RefusalReason.NO_EXECUTOR), anyString(), any());
    }

    @Test
    void anUnreachablePolicyEngineLeavesTheRowForALaterSweep() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.SOFT_DELETED, "gs://bucket-a", "GCS_BUCKET");
        when(approvals.create(any())).thenThrow(new RetireRemoteException("down", true, null));

        executor(true).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.SOFT_DELETED);
        assertThat(rec.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(audit, never()).refused(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPendingApprovalIsReadAgainLater() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        when(approvals.read(HARD_APPROVAL)).thenReturn(Optional.of(view(HARD_APPROVAL, ASSET, "PENDING", "HARD_DELETE",
                2, Approvals.SERVICE, null, ALICE)));

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        assertThat(rec.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(gcsApplier, never()).apply(any());
    }

    @Test
    void aHardDeleteApprovedByOnePersonIsRefusedAndNothingIsApplied() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE);
        catalogSays(false, "FULL", "gs://bucket-a");

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        assertThat(rec.getLastError()).startsWith("too-few-approvers");
        verify(gcsApplier, never()).apply(any());
    }

    @Test
    void aRejectedHardDeleteIsRefused() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        when(approvals.read(HARD_APPROVAL)).thenReturn(Optional.of(view(HARD_APPROVAL, ASSET, "REJECTED", "HARD_DELETE",
                2, Approvals.SERVICE, null)));

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        verify(audit).refused(any(), any(), any(), any(), eq("hard_delete"), eq(RefusalReason.APPROVAL_REJECTED), any(),
                any());
    }

    @Test
    void aLegalHoldPlacedDuringTheWindowStopsTheHardDelete() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(true, "FULL", "gs://bucket-a");

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        assertThat(rec.getLastError()).startsWith("legal-hold");
        verify(gcsApplier, never()).apply(any());
    }

    @Test
    void readCoverageThatIsNotFullStopsTheHardDelete() {
        for (String coverage : new String[] {"PARTIAL", "NONE", null}) {
            SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a",
                    "GCS_BUCKET");
            hardApproved(ALICE, BOB);
            catalogSays(false, coverage, "gs://bucket-a");

            executor(false).handle(rec);

            assertThat(rec.getStatus()).as("coverage %s", coverage).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
            assertThat(rec.getLastError()).startsWith("coverage-not-full");
        }
        verify(gcsApplier, never()).apply(any());
    }

    @Test
    void underDryRunTheRuleIsPlannedAndRecordedButNothingIsApplied() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");

        executor(true).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.DRY_RUN_COMPLETE);
        assertThat(rec.getLastError()).isEqualTo(HardDeleteExecutor.DRY_RUN_MARKER);
        ArgumentCaptor<RetireDispositionEntity> d = ArgumentCaptor.forClass(RetireDispositionEntity.class);
        verify(dispositions).saveAndFlush(d.capture());
        assertThat(d.getValue().getStatus()).isEqualTo(RetireDispositionEntity.DRY_RUN);
        assertThat(d.getValue().isDryRun()).isTrue();
        assertThat(d.getValue().getAppliedAt()).isNull();
        assertThat(d.getValue().getRuleDocument()).contains("\"api\":\"storage.buckets.patch\"");
        assertThat(d.getValue().getApprovers()).isEqualTo(ALICE + "," + BOB);
        verify(gcsApplier, never()).apply(any());
        verify(audit).record(eq(RetireAuditLog.DRY_RUN), eq(NOW), any(), eq("gs://bucket-a"), eq(AuditOutcome.SUCCEEDED),
                eq(rec.getId().toString()), any());
    }

    @Test
    void withDryRunOffTheRuleIsAppliedAfterTheIntentAndTheRowWaitsForProof() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");

        executor(false).handle(rec);

        InOrder order = inOrder(audit, gcsApplier);
        order.verify(audit).record(eq(RetireAuditLog.APPLY), any(), any(), any(), eq(AuditOutcome.REQUESTED), any(), any());
        order.verify(gcsApplier).apply(any());
        order.verify(audit).record(eq(RetireAuditLog.APPLY), any(), any(), any(), eq(AuditOutcome.SUCCEEDED), any(), any());
        assertThat(rec.getStatus()).as("applied is not executed").isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThat(rec.getHardDeletedAt()).isNull();
        ArgumentCaptor<RetireDispositionEntity> d = ArgumentCaptor.forClass(RetireDispositionEntity.class);
        verify(dispositions).saveAndFlush(d.capture());
        assertThat(d.getValue().getStatus()).isEqualTo(RetireDispositionEntity.AWAITING_PROOF);
        assertThat(d.getValue().getAppliedAt()).isEqualTo(NOW);
        Instant nextMidnight = Instant.parse("2026-11-02T00:00:00Z");
        assertThat(d.getValue().getEffectiveAt()).isEqualTo(nextMidnight);
        assertThat(d.getValue().getProofDueAt()).isEqualTo(nextMidnight.plus(Duration.ofDays(3)));
        assertThat(rec.getNextCheckAt()).isEqualTo(d.getValue().getProofDueAt());
    }

    @Test
    void anIntentThatCannotBeAuditedAppliesNothing() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");
        doThrow(new AuditException("dlap_common is down", null)).when(audit).record(eq(RetireAuditLog.APPLY), any(),
                any(), any(), eq(AuditOutcome.REQUESTED), any(), any());

        assertThatThrownBy(() -> executor(false).handle(rec)).isInstanceOf(AuditException.class);

        verify(gcsApplier, never()).apply(any());
    }

    /**
     * Q-633: the provider call succeeded and the outcome record could not be written. The disposition is saved
     * as applied and flagged, the sweep does not fail, and the next sweep neither applies nor re-audits the intent.
     */
    @Test
    void aFailedOutcomeAppendAfterTheApplyDoesNotApplyAgainOnTheNextSweep() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");
        doThrow(new AuditException("dlap_common is down", null)).when(audit).record(eq(RetireAuditLog.APPLY), any(),
                any(), any(), eq(AuditOutcome.SUCCEEDED), any(), any());
        HardDeleteExecutor executor = executor(false);

        executor.handle(rec);

        verify(gcsApplier, org.mockito.Mockito.times(1)).apply(any());
        ArgumentCaptor<RetireDispositionEntity> saved = ArgumentCaptor.forClass(RetireDispositionEntity.class);
        verify(dispositions).saveAndFlush(saved.capture());
        RetireDispositionEntity d = saved.getValue();
        assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.AWAITING_PROOF);
        assertThat(d.getAppliedAt()).isEqualTo(NOW);
        assertThat(d.isSuccessAuditPending()).isTrue();
        assertThat(rec.getStatus()).as("the apply is not rolled back").isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThat(rec.getNextCheckAt()).as("looks again soon, not at the proof deadline")
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));

        // The next sweep: the audit store is back. It writes the missing record and calls the provider no more.
        org.mockito.Mockito.reset(audit);
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        executor.handle(rec);

        verify(gcsApplier, org.mockito.Mockito.times(1)).apply(any());
        ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq(RetireAuditLog.APPLY), eq(NOW), any(), eq("gs://bucket-a"), eq(AuditOutcome.SUCCEEDED),
                eq(rec.getId().toString()), details.capture());
        verify(audit, never()).record(eq(RetireAuditLog.APPLY), any(), any(), any(), eq(AuditOutcome.REQUESTED), any(),
                any());
        assertThat(details.getValue()).containsEntry("retriedOutcome", true);
        assertThat(d.isSuccessAuditPending()).isFalse();
        assertThat(rec.getNextCheckAt()).isEqualTo(d.getProofDueAt());
    }

    @Test
    void anOutcomeAppendThatFailsAgainIsRetriedLaterAndStillNeverAppliesAgain() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.AWAITING_PROOF, "gs://bucket-a", "GCS_BUCKET");
        RetireDispositionEntity d = RetireDispositionEntity.builder().id(UUID.randomUUID())
                .softDeleteRecordId(rec.getId()).status(RetireDispositionEntity.AWAITING_PROOF).mode("NATIVE_RULE")
                .platform("GCS_LIFECYCLE").appliedAt(NOW.minusSeconds(60)).proofDueAt(NOW.plus(Duration.ofDays(3)))
                .successAuditPending(true).build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        doThrow(new AuditException("still down", null)).when(audit).record(eq(RetireAuditLog.APPLY), any(), any(), any(),
                eq(AuditOutcome.SUCCEEDED), any(), any());

        executor(false).handle(rec);

        assertThat(d.isSuccessAuditPending()).isTrue();
        assertThat(rec.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(gcsApplier, never()).apply(any());
    }

    /**
     * Q-633, idempotency by disposition id: a disposition that was applied is never applied again, even when the
     * row is handled as a pending approval once more (its state was lost).
     */
    @Test
    void aDispositionThatWasAlreadyAppliedIsNotAppliedAgain() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");
        Instant due = NOW.plus(Duration.ofDays(2));
        RetireDispositionEntity applied = RetireDispositionEntity.builder()
                .id(SoftDeleteAdmission.nameUuid("disposition:" + rec.getId())).softDeleteRecordId(rec.getId())
                .status(RetireDispositionEntity.AWAITING_PROOF).mode("NATIVE_RULE").platform("GCS_LIFECYCLE")
                .appliedAt(NOW.minusSeconds(600)).proofDueAt(due).build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(applied));

        executor(false).handle(rec);

        verify(gcsApplier, never()).apply(any());
        verify(audit, never()).record(eq(RetireAuditLog.APPLY), any(), any(), any(), eq(AuditOutcome.REQUESTED), any(),
                any());
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThat(rec.getNextCheckAt()).isEqualTo(due);
        assertThat(applied.getAppliedAt()).isEqualTo(NOW.minusSeconds(600));
    }

    /** Q-619: the size the catalog reports at the hard-delete step is kept on the row. */
    @Test
    void theCatalogSizeIsRefreshedAtTheHardDeleteStep() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        rec.setSizeBytes(10L);
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");

        executor(true).handle(rec);

        assertThat(rec.getSizeBytes()).isEqualTo(4096L);
    }

    @Test
    void aRuleThePlatformRefusesIsRecordedAsFailed() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a", "GCS_BUCKET");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a");
        doThrow(new NativeRuleException("GCS refused: HTTP 403", null)).when(gcsApplier).apply(any());

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_FAILED);
        assertThat(rec.getLastError()).contains("HTTP 403");
        verify(audit).record(eq(RetireAuditLog.APPLY), any(), any(), any(), eq(AuditOutcome.FAILED), any(), any());
    }

    @Test
    void aSingleObjectIsDeletedByTheEngineAndStillWaitsForProof() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a/dir/file.csv",
                "GCS_OBJECT");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a/dir/file.csv");
        when(gcpEngine.deleteData(eq(ASSET), eq("gs://bucket-a/dir/file.csv"), eq(DeletionType.HARD), any()))
                .thenReturn(DeletionResult.builder().status(DeletionStatus.COMPLETED).build());

        executor(false).handle(rec);

        verify(gcpEngine).deleteData(eq(ASSET), eq("gs://bucket-a/dir/file.csv"), eq(DeletionType.HARD), any());
        verify(gcsApplier, never()).apply(any());
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        ArgumentCaptor<RetireDispositionEntity> d = ArgumentCaptor.forClass(RetireDispositionEntity.class);
        verify(dispositions).saveAndFlush(d.capture());
        assertThat(d.getValue().getMode()).isEqualTo(RetireDispositionEntity.MODE_DIRECT);
        assertThat(d.getValue().getPlatform()).isEqualTo("ENGINE_GCP");
    }

    @Test
    void anEngineDeleteThatDoesNotCompleteFailsClosed() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a/f", "GCS_OBJECT");
        hardApproved(ALICE, BOB);
        catalogSays(false, "FULL", "gs://bucket-a/f");
        when(gcpEngine.deleteData(any(), any(), any(), any()))
                .thenReturn(DeletionResult.builder().status(DeletionStatus.FAILED).errorMessage("not found").build());

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_FAILED);
        assertThat(rec.getLastError()).isEqualTo("not found");
    }

    @Test
    void anOverdueProofIsMarkedAndAuditedAndTheRowKeepsListening() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.AWAITING_PROOF, "gs://bucket-a", "GCS_BUCKET");
        RetireDispositionEntity d = RetireDispositionEntity.builder().id(UUID.randomUUID())
                .softDeleteRecordId(rec.getId()).status(RetireDispositionEntity.AWAITING_PROOF).mode("NATIVE_RULE")
                .platform("GCS_LIFECYCLE").proofDueAt(NOW.minusSeconds(1)).build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));

        executor(false).handle(rec);

        assertThat(d.getStatus()).isEqualTo(RetireDispositionEntity.PROOF_OVERDUE);
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThat(rec.getNextCheckAt()).isNull();
        verify(audit).record(eq(RetireAuditLog.PROOF_OVERDUE), eq(NOW), any(), any(), eq(AuditOutcome.FAILED), any(),
                any());
    }

    /**
     * Q-677 (RET-06): a date-based or zero-day container rule is refused at planning, DATE_RULE_UNSUPPORTED,
     * before anyone is asked to approve.
     */
    @Test
    void aDateBasedContainerRuleIsRefusedAtPlanningBeforeAnyoneIsAskedToApprove() {
        SoftDeleteRecordEntity rec = s3Row(SoftDeleteRecordEntity.SOFT_DELETED);

        executorWith(false, new DatedS3Generator()).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        assertThat(rec.getLastError()).startsWith("date-rule-unsupported");
        verify(approvals, never()).create(any());
        verify(audit).refused(eq(NOW), any(), eq("s3://bucket-b"), eq(rec.getId().toString()), eq("hard_delete"),
                eq(RefusalReason.DATE_RULE_UNSUPPORTED), anyString(), any());
    }

    /** Q-677: the check runs again at execution, so a date-based rule is never applied even after approval. */
    @Test
    void aDateBasedContainerRuleIsNeverAppliedEvenWhenApproved() {
        SoftDeleteRecordEntity rec = s3Row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        hardApproved(ALICE, BOB);
        s3CatalogSays();

        executorWith(false, new DatedS3Generator()).handle(rec);

        assertThat(rec.getLastError()).startsWith("date-rule-unsupported");
        verify(s3Applier, never()).apply(any());
    }

    /** Q-656 (RET-06): a bucket with versioning Enabled or Suspended is refused at planning. */
    @Test
    void aVersionedBucketIsRefusedAtPlanningWhetherEnabledOrSuspended() {
        for (BucketVersioning state : new BucketVersioning[] {BucketVersioning.ENABLED, BucketVersioning.SUSPENDED}) {
            org.mockito.Mockito.reset(audit, approvals);
            when(s3Applier.versioning(any())).thenReturn(state);
            SoftDeleteRecordEntity rec = s3Row(SoftDeleteRecordEntity.SOFT_DELETED);

            executor(false).handle(rec);

            assertThat(rec.getStatus()).as(state.name()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
            assertThat(rec.getLastError()).startsWith("versioned-bucket-unsupported");
            verify(approvals, never()).create(any());
            verify(audit).refused(eq(NOW), any(), eq("s3://bucket-b"), any(), eq("hard_delete"),
                    eq(RefusalReason.VERSIONED_BUCKET_UNSUPPORTED), anyString(), any());
        }
    }

    /**
     * Q-694 (RET-08, decision D38): an Azure container rule is planned with the real generator and applier, and the
     * applier reads the account's blob versioning; an account with versioning enabled is refused
     * VERSIONED_BUCKET_UNSUPPORTED at planning, before anyone is asked to approve. Mocked Azure clients only.
     */
    @Test
    void anAzureAccountWithBlobVersioningIsRefusedAtPlanning() {
        String accountId = "/subscriptions/00000000-0000-4000-8000-000000000001/resourceGroups/rg-data"
                + "/providers/Microsoft.Storage/storageAccounts/dlapazuree2e";
        RetireProperties properties = new RetireProperties(false, 30, 7, 2, 100, null, null,
                new RetireProperties.NativeRule(Duration.ofHours(1), List.of(accountId), 1), null);
        com.azure.resourcemanager.storage.fluent.BlobServicesClient blobs =
                mock(com.azure.resourcemanager.storage.fluent.BlobServicesClient.class);
        com.azure.resourcemanager.storage.fluent.ManagementPoliciesClient policies =
                mock(com.azure.resourcemanager.storage.fluent.ManagementPoliciesClient.class);
        when(blobs.getServiceProperties("rg-data", "dlapazuree2e")).thenReturn(
                new com.azure.resourcemanager.storage.fluent.models.BlobServicePropertiesInner()
                        .withIsVersioningEnabled(true));
        DispositionPlanner planner = new DispositionPlanner(
                List.of(new com.dlap.autodelete.providers.azure.AzureBlobLifecycleRuleGenerator(properties)),
                List.of(new com.dlap.autodelete.providers.azure.AzureBlobLifecycleRuleApplier(sub -> policies,
                        sub -> blobs)), engines);
        HardDeleteExecutor executor = new HardDeleteExecutor(records, dispositions, approvals, catalog, planner,
                engines, audit, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.SOFT_DELETED, "az://dlapazuree2e/raw",
                "AZURE_CONTAINER");
        rec.setCloudProvider("AZURE");

        executor.handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        assertThat(rec.getLastError()).startsWith("versioned-bucket-unsupported").contains("ENABLED");
        verify(approvals, never()).create(any());
        verify(policies, never()).createOrUpdate(any(), any(), any(), any());
        verify(audit).refused(eq(NOW), any(), eq("az://dlapazuree2e/raw"), any(), eq("hard_delete"),
                eq(RefusalReason.VERSIONED_BUCKET_UNSUPPORTED), anyString(), any());
    }

    /**
     * Q-656: versioning is read again just before the apply. A bucket that turned versioning on after the
     * approval was asked for is refused, and nothing is applied.
     */
    @Test
    void aBucketVersionedAfterPlanningIsRefusedJustBeforeTheApply() {
        SoftDeleteRecordEntity rec = s3Row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        hardApproved(ALICE, BOB);
        s3CatalogSays();
        when(s3Applier.versioning(any())).thenReturn(BucketVersioning.ENABLED);

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        assertThat(rec.getLastError()).startsWith("versioned-bucket-unsupported");
        verify(s3Applier, never()).apply(any());
        verify(audit, never()).record(eq(RetireAuditLog.APPLY), any(), any(), any(), any(), any(), any());
    }

    /**
     * Q-656: a versioning answer the platform did not give is never read as unversioned. With dry run off the row
     * waits; under dry run (nothing is applied) the answer is recorded as UNKNOWN.
     */
    @Test
    void anUnknownVersioningWaitsInsteadOfApplyingAndIsOnlyRecordedUnderDryRun() {
        when(s3Applier.versioning(any())).thenReturn(BucketVersioning.UNKNOWN);
        SoftDeleteRecordEntity rec = s3Row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        hardApproved(ALICE, BOB);
        s3CatalogSays();

        executor(false).handle(rec);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        assertThat(rec.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(s3Applier, never()).apply(any());

        SoftDeleteRecordEntity dry = s3Row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        executor(true).handle(dry);

        assertThat(dry.getStatus()).isEqualTo(SoftDeleteRecordEntity.DRY_RUN_COMPLETE);
        ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq(RetireAuditLog.DRY_RUN), any(), any(), any(), any(), any(), details.capture());
        assertThat(details.getValue()).containsEntry("bucketVersioning", "UNKNOWN");
    }

    /** Q-656: an unversioned bucket's rule is applied, and the audit record names the versioning read. */
    @Test
    void anUnversionedBucketsRuleIsAppliedAndTheVersioningIsAudited() {
        SoftDeleteRecordEntity rec = s3Row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        hardApproved(ALICE, BOB);
        s3CatalogSays();

        executor(false).handle(rec);

        verify(s3Applier).apply(any());
        verify(s3Applier).versioning(any());
        ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq(RetireAuditLog.APPLY), any(), any(), any(), eq(AuditOutcome.REQUESTED), any(),
                details.capture());
        assertThat(details.getValue()).containsEntry("bucketVersioning", "UNVERSIONED");
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
    }

    /**
     * Q-683 (RET-06): a disposition with applied_at is never applied again, whatever its status. Found EXECUTED or
     * FAILED under a row that is pending again, it is refused ALREADY_APPLIED, audited, and nothing is called.
     */
    @Test
    void aDispositionWithAppliedAtIsNeverAppliedAgainWhateverItsStatus() {
        for (String status : new String[] {RetireDispositionEntity.EXECUTED, RetireDispositionEntity.FAILED,
                RetireDispositionEntity.DRY_RUN}) {
            org.mockito.Mockito.reset(audit);
            SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL, "gs://bucket-a",
                    "GCS_BUCKET");
            hardApproved(ALICE, BOB);
            catalogSays(false, "FULL", "gs://bucket-a");
            RetireDispositionEntity applied = RetireDispositionEntity.builder()
                    .id(SoftDeleteAdmission.nameUuid("disposition:" + rec.getId())).softDeleteRecordId(rec.getId())
                    .status(status).mode("NATIVE_RULE").platform("GCS_LIFECYCLE")
                    .appliedAt(NOW.minus(Duration.ofDays(1))).build();
            when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(applied));

            executor(false).handle(rec);

            assertThat(rec.getStatus()).as(status).isEqualTo(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
            assertThat(rec.getLastError()).startsWith("already-applied");
            assertThat(applied.getStatus()).as("the disposition is left as it was").isEqualTo(status);
            verify(audit).refused(eq(NOW), any(), eq("gs://bucket-a"), any(), eq("hard_delete"),
                    eq(RefusalReason.ALREADY_APPLIED), anyString(), any());
            verify(audit, never()).record(eq(RetireAuditLog.APPLY), any(), any(), any(), any(), any(), any());
        }
        verify(gcsApplier, never()).apply(any());
    }
}
