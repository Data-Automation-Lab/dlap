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

package com.dlap.autodelete.service.impl;

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
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.ApprovalWithdrawer;
import com.dlap.autodelete.retire.DispositionPlanner;
import com.dlap.autodelete.retire.RefusalReason;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireSweeper;
import com.dlap.autodelete.retire.RuleRemover;
import com.dlap.autodelete.retire.SoftDeleteAdmission;
import com.dlap.autodelete.service.RestoreOutcome;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RET-02 and RET-04: the deletion service delegates policy deletes to the admission and hard deletes to the
 * sweeper, and restores what was not touched in the cloud as the caller's JWT subject; a rule in force is
 * restored by removing the rule, a pending hard-delete approval is withdrawn, and a refused restore is audited.
 */
class DeletionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final SoftDeleteRecordRepository repo = mock(SoftDeleteRecordRepository.class);
    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);
    private final SoftDeleteAdmission admission = mock(SoftDeleteAdmission.class);
    private final RetireSweeper sweeper = mock(RetireSweeper.class);
    private final DispositionPlanner planner = mock(DispositionPlanner.class);
    private final ApprovalWithdrawer withdrawer = mock(ApprovalWithdrawer.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private final NativeRuleApplier applier = mock(NativeRuleApplier.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RuleRemover remover = new RuleRemover(dispositions, planner, audit, clock);
    private final DeletionServiceImpl service = new DeletionServiceImpl(repo, admission, sweeper, remover, withdrawer,
            audit, clock);

    private static SoftDeleteRecordEntity row(String status) {
        return SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("gs://bucket").cloudProvider("GCP").status(status).build();
    }

    private static RetireDispositionEntity disposition(SoftDeleteRecordEntity rec) {
        return RetireDispositionEntity.builder().id(UUID.randomUUID()).softDeleteRecordId(rec.getId())
                .assetId(rec.getAssetId()).targetUri("s3://bucket").cloudProvider("AWS").mode("NATIVE_RULE")
                .platform("S3_LIFECYCLE").ruleId("dlap-retire-abc").ruleDocument("{\"bucket\":\"bucket\"}")
                .status(RetireDispositionEntity.EXECUTED).effectiveAt(NOW.minusSeconds(3600))
                .appliedAt(NOW.minus(java.time.Duration.ofDays(2))).dryRun(false).build();
    }

    @Test
    void aPolicyDeleteGoesThroughTheAdmission() {
        UUID id = UUID.randomUUID();
        Map<String, Object> event = Map.of("actionType", "delete");
        when(admission.admitPolicyAction(event)).thenReturn(new SoftDeleteAdmission.Outcome(id, null, true));

        assertThat(service.queueSoftDelete(event)).isEqualTo(id);
        assertThatThrownBy(() -> service.queueSoftDelete(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aManualRunIsOneSweep() {
        when(sweeper.sweep()).thenReturn(4);
        assertThat(service.processDueHardDeletes()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {SoftDeleteRecordEntity.SOFT_DELETED, SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL,
            SoftDeleteRecordEntity.HARD_DELETE_REFUSED, SoftDeleteRecordEntity.DRY_RUN_COMPLETE})
    void anUntouchedDeleteIsRestoredAsTheCallerAndAudited(String status) {
        SoftDeleteRecordEntity rec = row(status);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", "wrong asset");

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.ruleRemoved()).isFalse();
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORED);
        assertThat(rec.getRestoredBy()).isEqualTo("sub-restorer");
        assertThat(rec.getRestoredAt()).isEqualTo(NOW);
        assertThat(rec.getNextCheckAt()).isNull();
        verify(audit).record(eq(RetireAuditLog.RESTORED), eq(NOW), eq(AuditActor.user("sub-restorer")),
                eq("gs://bucket"), eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), any());
        verify(applier, never()).remove(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {SoftDeleteRecordEntity.AWAITING_PROOF, SoftDeleteRecordEntity.HARD_DELETED,
            SoftDeleteRecordEntity.HARD_DELETE_FAILED, SoftDeleteRecordEntity.RESTORED,
            SoftDeleteRecordEntity.RULE_ENDED, SoftDeleteRecordEntity.RESTORE_FAILED,
            SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN})
    void aDeleteAppliedInTheCloudIsNotRestorableAndTheRefusalIsAudited(String status) {
        SoftDeleteRecordEntity rec = row(status);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", null);

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).isEqualTo("record-is-" + status);
        assertThat(rec.getStatus()).isEqualTo(status);
        verify(repo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
        // Q-632: every refusal is a record.
        verify(audit).refused(eq(NOW), eq(AuditActor.user("sub-restorer")), eq("gs://bucket"), eq(rec.getId().toString()),
                eq("restore"), eq(RefusalReason.NOT_RESTORABLE), anyString(), any());
    }

    @Test
    void aRestoreNeedsASubjectAndAKnownRecord() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.empty());

        RestoreOutcome outcome = service.restore(id, "sub", null);

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).isEqualTo("record-not-found");
        verify(audit).refused(eq(NOW), eq(AuditActor.user("sub")), eq(id.toString()), eq(id.toString()), eq("restore"),
                eq(RefusalReason.NOT_RESTORABLE), anyString(), any());
        assertThatThrownBy(() -> service.restore(id, " ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Q-631: a rule in force is restored by removing the rule, and the answer says that objects the rule
     * already expired are gone. The removal is audited intent first, and the disposition keeps when it happened.
     */
    @Test
    void aRuleInForceIsRestoredByRemovingTheRuleAndTheAnswerSaysExpiredObjectsAreGone() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.RULE_IN_FORCE);
        RetireDispositionEntity d = disposition(rec);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(applier.remove(any())).thenReturn(true);

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", "bucket was kept by mistake");

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.ruleRemoved()).isTrue();
        assertThat(outcome.message()).contains("rule was removed").contains("already expired are gone");
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORED);
        ArgumentCaptor<NativeRule> removed = ArgumentCaptor.forClass(NativeRule.class);
        InOrder order = inOrder(audit, applier);
        order.verify(audit).record(eq(RetireAuditLog.RESTORED), eq(NOW), any(), eq("gs://bucket"),
                eq(AuditOutcome.REQUESTED), eq(rec.getId().toString()), any());
        order.verify(applier).remove(removed.capture());
        order.verify(audit).record(eq(RetireAuditLog.RESTORED), eq(NOW), any(), eq("gs://bucket"),
                eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), any());
        assertThat(removed.getValue().platform()).isEqualTo(NativeRulePlatform.S3_LIFECYCLE);
        assertThat(removed.getValue().ruleId()).isEqualTo("dlap-retire-abc");
        assertThat(removed.getValue().document()).isEqualTo(d.getRuleDocument());
        assertThat(d.getRuleRemovedAt()).isEqualTo(NOW);
        assertThat(d.getStatus()).as("the rule did fire: the disposition stays EXECUTED")
                .isEqualTo(RetireDispositionEntity.EXECUTED);
        verify(dispositions).saveAndFlush(d);
    }

    /** Q-631: when the platform refuses the removal, the record stays a rule in force and the failure is audited. */
    @Test
    void aRuleThatCannotBeRemovedLeavesTheRecordAsItWasAndAuditsTheFailure() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.RULE_IN_FORCE);
        RetireDispositionEntity d = disposition(rec);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        doThrow(new NativeRuleException("S3 refused removing the lifecycle rule: AccessDenied (HTTP 403)", null))
                .when(applier).remove(any());

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", "x");

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).isEqualTo("rule-removal-failed");
        assertThat(outcome.message()).contains("AccessDenied");
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_IN_FORCE);
        assertThat(d.getRuleRemovedAt()).isNull();
        verify(repo, never()).saveAndFlush(any());
        verify(audit).record(eq(RetireAuditLog.RESTORED), any(), any(), any(), eq(AuditOutcome.FAILED),
                eq(rec.getId().toString()), any());
    }

    /** The intent of a removal that cannot be audited is never followed by the removal. */
    @Test
    void aRemovalWhoseIntentCannotBeAuditedRemovesNothing() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.RULE_IN_FORCE);
        RetireDispositionEntity d = disposition(rec);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        doThrow(new AuditException("dlap_common is down", null)).when(audit).record(eq(RetireAuditLog.RESTORED), any(),
                any(), any(), eq(AuditOutcome.REQUESTED), any(), any());

        assertThatThrownBy(() -> service.restore(rec.getId(), "sub", null)).isInstanceOf(AuditException.class);

        verify(applier, never()).remove(any());
    }

    @Test
    void aRuleInForceWithoutAStoredRuleIsNotRestoredAndIsAudited() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.RULE_IN_FORCE);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.empty());

        RestoreOutcome outcome = service.restore(rec.getId(), "sub", null);

        assertThat(outcome.restored()).isFalse();
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_IN_FORCE);
        verify(audit).refused(any(), any(), any(), any(), eq("restore"), eq(RefusalReason.RULE_REMOVAL_FAILED),
                anyString(), any());
    }

    /**
     * Q-621, Q-676: restoring a record that waits for a hard-delete approval withdraws that approval through the
     * service account, on behalf of the restorer's subject.
     */
    @Test
    void restoringARecordThatWaitsForAHardDeleteApprovalWithdrawsTheApproval() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        UUID approval = UUID.randomUUID();
        rec.setHardDeleteApprovalId(approval);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(withdrawer.withdraw(approval, "sub-restorer", rec.getId(), "kept")).thenReturn(
                new ApprovalWithdrawer.Outcome(ApprovalWithdrawer.Result.WITHDRAWN, null));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", "kept");

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.approvalWithdrawal()).isEqualTo("WITHDRAWN");
        verify(withdrawer).withdraw(approval, "sub-restorer", rec.getId(), "kept");
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.RESTORED), any(), any(), any(), eq(AuditOutcome.SUCCEEDED), any(),
                details.capture());
        assertThat(details.getValue()).containsEntry("approvalWithdrawal", "WITHDRAWN")
                .containsEntry("hardDeleteApprovalId", approval);
    }

    /** Q-621: the withdrawal is best effort; a refusal by the policy engine does not block the restore. */
    @Test
    void aRefusedWithdrawalStillRestoresAndSaysSo() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        UUID approval = UUID.randomUUID();
        rec.setHardDeleteApprovalId(approval);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(withdrawer.withdraw(any(), any(), any(), any())).thenReturn(
                new ApprovalWithdrawer.Outcome(ApprovalWithdrawer.Result.REFUSED, "self-approval"));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", null);

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.approvalWithdrawal()).isEqualTo("REFUSED:self-approval");
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORED);
    }

    @ParameterizedTest
    @ValueSource(strings = {SoftDeleteRecordEntity.SOFT_DELETED, SoftDeleteRecordEntity.HARD_DELETE_REFUSED,
            SoftDeleteRecordEntity.DRY_RUN_COMPLETE})
    void otherStatesHaveNoApprovalToWithdraw(String status) {
        SoftDeleteRecordEntity rec = row(status);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));

        assertThat(service.restore(rec.getId(), "sub", null).approvalWithdrawal()).isEqualTo("NOT_ATTEMPTED");

        verify(withdrawer, never()).withdraw(any(), any(), any(), any());
    }

    /**
     * Q-677 (RET-06): a container rule that was applied and still waits for its proof is restorable, and the
     * restore removes the rule.
     */
    @Test
    void anAppliedContainerRuleStillAwaitingProofIsRestorableAndTheRestoreRemovesTheRule() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.AWAITING_PROOF);
        RetireDispositionEntity d = disposition(rec);
        d.setStatus(RetireDispositionEntity.AWAITING_PROOF);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(applier.remove(any())).thenReturn(true);

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", "stop it");

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.ruleRemoved()).isTrue();
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORED);
        assertThat(rec.getNextCheckAt()).as("the sweeper no longer waits for its proof").isNull();
        assertThat(d.getRuleRemovedAt()).isEqualTo(NOW);
        verify(applier).remove(any());
    }

    /** RET-06: an engine delete or a table expiration waiting for its proof is still not restorable. */
    @Test
    void anAwaitingProofEngineDeleteOrTableExpirationIsStillNotRestorable() {
        for (String platform : new String[] {"ENGINE_GCP", "BIGQUERY_TABLE_EXPIRATION"}) {
            SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.AWAITING_PROOF);
            RetireDispositionEntity d = disposition(rec);
            d.setPlatform(platform);
            d.setMode(platform.startsWith("ENGINE_") ? "DIRECT" : "NATIVE_RULE");
            d.setStatus(RetireDispositionEntity.AWAITING_PROOF);
            when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
            when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));

            RestoreOutcome outcome = service.restore(rec.getId(), "sub", null);

            assertThat(outcome.restored()).as(platform).isFalse();
            assertThat(outcome.reason()).isEqualTo("record-is-AWAITING_PROOF");
            assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        }
        verify(applier, never()).remove(any());
    }

    /**
     * Q-682 (RET-06): when no rule on the platform matches DLAP's, the restore reports ruleRemoved=false, the
     * record ends RESTORE_FAILED with reason rule-not-found, and the outcome is audit-chained.
     */
    @Test
    void aRestoreThatFindsNoMatchingRuleEndsRestoreFailedWithRuleNotFound() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.RULE_IN_FORCE);
        RetireDispositionEntity d = disposition(rec);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(applier.remove(any())).thenReturn(false);

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", null);

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.ruleRemoved()).isFalse();
        assertThat(outcome.reason()).isEqualTo("rule-not-found");
        assertThat(outcome.recordStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORE_FAILED);
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORE_FAILED);
        assertThat(rec.getLastError()).startsWith("rule-not-found");
        assertThat(d.getRuleRemovedAt()).isNull();
        verify(repo).saveAndFlush(rec);
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.RESTORED), eq(NOW), eq(AuditActor.user("sub-restorer")),
                eq("gs://bucket"), eq(AuditOutcome.FAILED), eq(rec.getId().toString()), details.capture());
        assertThat(details.getValue()).containsEntry("reason", "rule-not-found").containsEntry("ruleRemoved", false);
        verify(audit, never()).record(eq(RetireAuditLog.RESTORED), any(), any(), any(), eq(AuditOutcome.SUCCEEDED),
                any(), any());
    }

    /**
     * Q-676 (RET-06): a policy engine without the withdraw operation (404 or 405) is logged and recorded in the
     * restore details; the restore stands.
     */
    @Test
    void aWithdrawEndpointThatIsNotAvailableKeepsTheRestoreAndIsRecordedInTheDetails() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        UUID approval = UUID.randomUUID();
        rec.setHardDeleteApprovalId(approval);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(withdrawer.withdraw(any(), any(), any(), any())).thenReturn(
                new ApprovalWithdrawer.Outcome(ApprovalWithdrawer.Result.ENDPOINT_NOT_AVAILABLE, "http-404"));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", null);

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.approvalWithdrawal()).isEqualTo("ENDPOINT_NOT_AVAILABLE:http-404");
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORED);
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.RESTORED), any(), any(), any(), eq(AuditOutcome.SUCCEEDED), any(),
                details.capture());
        assertThat(details.getValue()).containsEntry("approvalWithdrawal", "ENDPOINT_NOT_AVAILABLE:http-404")
                .containsKey("approvalWithdrawalNote");
    }

    /**
     * Q-699 (RET-08): a restore of an applied rule whose apply SUCCEEDED record is still pending writes that record
     * first, then the restore's intent, then removes the rule; the record is RESTORED and nothing is left pending.
     */
    @Test
    void aRestoreWritesThePendingApplySucceededRecordBeforeItRemovesTheRuleAndRestores() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.AWAITING_PROOF);
        RetireDispositionEntity d = disposition(rec);
        d.setStatus(RetireDispositionEntity.AWAITING_PROOF);
        d.setSuccessAuditPending(true);
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(applier.remove(any())).thenReturn(true);

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", "stop it");

        assertThat(outcome.restored()).isTrue();
        assertThat(d.isSuccessAuditPending()).isFalse();
        InOrder order = inOrder(audit, applier, repo);
        order.verify(audit).record(eq(RetireAuditLog.APPLY), eq(d.getAppliedAt()), eq(RetireAuditLog.SERVICE),
                eq("gs://bucket"), eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), any());
        order.verify(audit).record(eq(RetireAuditLog.RESTORED), eq(NOW), eq(AuditActor.user("sub-restorer")),
                eq("gs://bucket"), eq(AuditOutcome.REQUESTED), eq(rec.getId().toString()), any());
        order.verify(applier).remove(any());
        order.verify(repo).saveAndFlush(rec);
        order.verify(audit).record(eq(RetireAuditLog.RESTORED), eq(NOW), eq(AuditActor.user("sub-restorer")),
                eq("gs://bucket"), eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), any());
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORED);
    }

    /**
     * Q-700 (RET-08): a withdraw 404 whose body reason is not-found is REQUEST_NOT_FOUND; the restore stands and
     * says so in its details.
     */
    @Test
    void aWithdrawOfAnUnknownRequestIsRequestNotFoundAndTheRestoreStands() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        rec.setHardDeleteApprovalId(UUID.randomUUID());
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(withdrawer.withdraw(any(), any(), any(), any())).thenReturn(
                new ApprovalWithdrawer.Outcome(ApprovalWithdrawer.Result.REQUEST_NOT_FOUND, null));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", null);

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.approvalWithdrawal()).isEqualTo("REQUEST_NOT_FOUND");
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.RESTORED), any(), any(), any(), eq(AuditOutcome.SUCCEEDED), any(),
                details.capture());
        assertThat(details.getValue()).containsEntry("approvalWithdrawal", "REQUEST_NOT_FOUND");
        assertThat((String) details.getValue().get("approvalWithdrawalNote")).contains("does not know");
    }

    /** Q-676: a request someone decided first is labelled APPROVAL_ALREADY_DECIDED, and the restore stands. */
    @Test
    void aDecidedApprovalIsLabelledApprovalAlreadyDecided() {
        SoftDeleteRecordEntity rec = row(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        rec.setHardDeleteApprovalId(UUID.randomUUID());
        when(repo.findByIdForUpdate(rec.getId())).thenReturn(Optional.of(rec));
        when(withdrawer.withdraw(any(), any(), any(), any())).thenReturn(
                new ApprovalWithdrawer.Outcome(ApprovalWithdrawer.Result.APPROVAL_ALREADY_DECIDED, "APPROVED"));

        RestoreOutcome outcome = service.restore(rec.getId(), "sub-restorer", null);

        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.approvalWithdrawal()).isEqualTo("APPROVAL_ALREADY_DECIDED:APPROVED");
    }

    /** Q-632: a policy dry run executes nothing and still writes a retire.delete.dry_run record. */
    @Test
    void aPolicyDryRunWritesAnAuditRecord() {
        UUID asset = UUID.randomUUID();
        UUID fire = UUID.randomUUID();

        service.recordPolicyDryRun(Map.of("assetId", asset.toString(), "assetUri", "gs://bucket", "policyId", "p-1",
                "fireId", fire.toString(), "dryRun", true));

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.DRY_RUN), eq(NOW), eq(RetireAuditLog.SERVICE), eq("gs://bucket"),
                eq(AuditOutcome.SUCCEEDED), eq(fire.toString()), details.capture());
        assertThat(details.getValue()).containsEntry("stage", "policy_dry_run").containsEntry("executed", false)
                .containsEntry("policyId", "p-1");
        verify(admission, never()).admitPolicyAction(any());
    }

    @Test
    void aPolicyDryRunWithoutAUriOrAFireStillWritesARecord() {
        UUID asset = UUID.randomUUID();

        service.recordPolicyDryRun(Map.of("assetId", asset.toString(), "dryRun", true));

        verify(audit).record(eq(RetireAuditLog.DRY_RUN), eq(NOW), eq(RetireAuditLog.SERVICE), eq(asset.toString()),
                eq(AuditOutcome.SUCCEEDED), eq(null), any());
    }
}
