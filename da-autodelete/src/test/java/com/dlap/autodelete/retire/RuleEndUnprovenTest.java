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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * RET-08, decision D38.
 * <ul>
 *   <li>Q-695: the maximum age also covers an applied container rule that is never proven (the record
 *       AWAITING_PROOF, the disposition AWAITING_PROOF or PROOF_OVERDUE). The executor removes the rule and the
 *       record ends RULE_ENDED_UNPROVEN; the removal is audit-chained, logged as a warning and counted by its own
 *       metric.</li>
 *   <li>Q-699: RuleRemover.remove writes a pending apply SUCCEEDED record before it removes the rule.</li>
 * </ul>
 */
class RuleEndUnprovenTest {

    private static final Instant NOW = Instant.parse("2026-12-15T09:00:00Z");
    private static final Instant APPLIED = NOW.minus(Duration.ofDays(31));
    private static final Instant PROOF_DUE = APPLIED.plus(Duration.ofDays(3));

    private final SoftDeleteRecordRepository records = mock(SoftDeleteRecordRepository.class);
    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);
    private final DispositionPlanner planner = mock(DispositionPlanner.class);
    private final NativeRuleApplier applier = mock(NativeRuleApplier.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger expiryLog = (Logger) LoggerFactory.getLogger(RuleInForceExpiry.class);
    private RuleInForceExpiry expiry;

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(planner.applierFor("S3_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(records.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispositions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        expiry = new RuleInForceExpiry(records, new RuleRemover(dispositions, planner, audit, clock), audit,
                RetireProperties.defaults(), clock, tx, registry);
        logs.start();
        expiryLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        expiryLog.detachAppender(logs);
    }

    private SoftDeleteRecordEntity applied(String recordStatus, String dispositionStatus, boolean auditPending) {
        SoftDeleteRecordEntity rec = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("s3://bucket-b").cloudProvider("AWS").status(recordStatus).nextCheckAt(PROOF_DUE)
                .windowDays(30).build();
        RetireDispositionEntity d = RetireDispositionEntity.builder().id(UUID.randomUUID())
                .softDeleteRecordId(rec.getId()).targetUri("s3://bucket-b").mode("NATIVE_RULE")
                .platform("S3_LIFECYCLE").ruleId("dlap-retire-x").status(dispositionStatus)
                .ruleDocument("{\"bucket\":\"bucket-b\",\"rules\":[{\"ID\":\"dlap-retire-x\"}]}")
                .appliedAt(APPLIED).proofDueAt(PROOF_DUE).approvalId(UUID.randomUUID()).approvers("alice,bob")
                .successAuditPending(auditPending).dryRun(false).build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());
        return rec;
    }

    private RetireDispositionEntity disposition(SoftDeleteRecordEntity rec) {
        return dispositions.findBySoftDeleteRecordId(rec.getId()).orElseThrow();
    }

    /**
     * Q-695: an applied container rule still AWAITING_PROOF past its maximum age is removed without an approval,
     * audited retire.delete.rule_ended_unproven REQUESTED then SUCCEEDED, and the record ends RULE_ENDED_UNPROVEN.
     */
    @Test
    void anAppliedRuleNeverProvenPastItsMaximumAgeIsRemovedAndEndsRuleEndedUnproven() {
        SoftDeleteRecordEntity rec = applied(SoftDeleteRecordEntity.AWAITING_PROOF,
                RetireDispositionEntity.AWAITING_PROOF, false);
        when(applier.remove(any())).thenReturn(true);

        assertThat(expiry.endExpired()).isEqualTo(1);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN);
        assertThat(rec.getNextCheckAt()).as("the sweeper has nothing left to check").isNull();
        assertThat(rec.getRuleEndRetryAt()).isNull();
        assertThat(disposition(rec).getRuleRemovedAt()).isEqualTo(NOW);
        InOrder order = inOrder(audit, applier);
        order.verify(audit).record(eq(RetireAuditLog.RULE_ENDED_UNPROVEN), eq(NOW), eq(RetireAuditLog.SERVICE),
                eq("s3://bucket-b"), eq(AuditOutcome.REQUESTED), eq(rec.getId().toString()), any());
        order.verify(applier).remove(any());
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        order.verify(audit).record(eq(RetireAuditLog.RULE_ENDED_UNPROVEN), eq(NOW), eq(RetireAuditLog.SERVICE),
                eq("s3://bucket-b"), eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), details.capture());
        assertThat(details.getValue()).containsEntry("proven", false).containsEntry("ruleRemoved", true)
                .containsEntry("previousStatus", SoftDeleteRecordEntity.AWAITING_PROOF)
                .containsEntry("dispositionStatus", RetireDispositionEntity.AWAITING_PROOF)
                .containsEntry("endState", SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN)
                .containsEntry("ruleAppliedAt", APPLIED);
        verify(audit, never()).record(eq(RetireAuditLog.RULE_ENDED), any(), any(), any(), any(), any(), any());
    }

    /** Q-695: a disposition already marked PROOF_OVERDUE is covered the same way. */
    @Test
    void aProofOverdueRuleIsCoveredToo() {
        SoftDeleteRecordEntity rec = applied(SoftDeleteRecordEntity.AWAITING_PROOF,
                RetireDispositionEntity.PROOF_OVERDUE, false);
        when(applier.remove(any())).thenReturn(true);

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN);
        verify(audit).record(eq(RetireAuditLog.RULE_ENDED_UNPROVEN), any(), any(), any(), eq(AuditOutcome.SUCCEEDED),
                any(), any());
    }

    /** Q-695: the removal of an unproven rule is logged as a warning and counted by its own metric. */
    @Test
    void theUnprovenRemovalIsLoggedAsAWarningAndCountedByItsOwnMetric() {
        applied(SoftDeleteRecordEntity.AWAITING_PROOF, RetireDispositionEntity.AWAITING_PROOF, false);
        when(applier.remove(any())).thenReturn(true);
        assertThat(registry.get(RuleInForceExpiry.ENDED_UNPROVEN_METRIC).counter().count()).isZero();

        expiry.endExpired();

        assertThat(registry.get(RuleInForceExpiry.ENDED_UNPROVEN_METRIC).counter().count()).isEqualTo(1.0);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("RULE_ENDED_UNPROVEN").contains("no provider event ever proved");
        });
    }

    /** Q-695: a rule in force ending at its maximum age is not counted as unproven. */
    @Test
    void aProvenRuleInForceIsNotCountedAsUnproven() {
        applied(SoftDeleteRecordEntity.RULE_IN_FORCE, RetireDispositionEntity.EXECUTED, false);
        when(applier.remove(any())).thenReturn(true);

        expiry.endExpired();

        assertThat(registry.get(RuleInForceExpiry.ENDED_UNPROVEN_METRIC).counter().count()).isZero();
        verify(audit).record(eq(RetireAuditLog.RULE_ENDED), any(), any(), any(), eq(AuditOutcome.SUCCEEDED), any(),
                any());
    }

    /**
     * Q-695: a refused removal of an unproven rule leaves the record AWAITING_PROOF, retried after 6 hours on the
     * job's own retry time; the sweeper's proof-due check (next_check_at) is not moved.
     */
    @Test
    void aRefusedRemovalOfAnUnprovenRuleKeepsTheSweepersProofCheck() {
        SoftDeleteRecordEntity rec = applied(SoftDeleteRecordEntity.AWAITING_PROOF,
                RetireDispositionEntity.AWAITING_PROOF, false);
        doThrow(new NativeRuleException("S3 refused: AccessDenied (HTTP 403)", null)).when(applier).remove(any());

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThat(rec.getRuleEndRetryAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(rec.getNextCheckAt()).isEqualTo(PROOF_DUE);
        assertThat(registry.get(RuleInForceExpiry.ENDED_UNPROVEN_METRIC).counter().count()).isZero();
        verify(audit).record(eq(RetireAuditLog.RULE_ENDED_UNPROVEN), any(), any(), any(), eq(AuditOutcome.FAILED),
                any(), any());
    }

    /** Q-695 with Q-682: an unproven rule that is not found on the platform ends RESTORE_FAILED. */
    @Test
    void anUnprovenRuleThatIsNotFoundEndsRestoreFailed() {
        SoftDeleteRecordEntity rec = applied(SoftDeleteRecordEntity.AWAITING_PROOF,
                RetireDispositionEntity.PROOF_OVERDUE, false);
        when(applier.remove(any())).thenReturn(false);

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORE_FAILED);
        assertThat(rec.getLastError()).startsWith("rule-not-found");
        assertThat(registry.get(RuleInForceExpiry.ENDED_UNPROVEN_METRIC).counter().count()).isZero();
    }

    /** Q-695: an AWAITING_PROOF record without an applied container rule (an engine delete) is left alone. */
    @Test
    void anAwaitingProofRecordWithoutAContainerRuleIsLeftToTheSweeper() {
        SoftDeleteRecordEntity rec = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("s3://bucket-b/key.csv").status(SoftDeleteRecordEntity.AWAITING_PROOF).nextCheckAt(PROOF_DUE)
                .build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(RetireDispositionEntity
                .builder().id(UUID.randomUUID()).softDeleteRecordId(rec.getId()).mode("ENGINE").platform("ENGINE_AWS")
                .status(RetireDispositionEntity.AWAITING_PROOF).appliedAt(APPLIED).ruleDocument("{}").build()));
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.AWAITING_PROOF);
        assertThat(rec.getNextCheckAt()).isEqualTo(PROOF_DUE);
        verify(applier, never()).remove(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Q-699: a removal first writes the apply SUCCEEDED record that is still pending, then the intent, then
     * removes the rule; the pending flag is cleared, so the sweeper does not write it twice.
     */
    @Test
    void aRemovalWritesThePendingApplySucceededRecordBeforeItRemovesTheRule() {
        SoftDeleteRecordEntity rec = applied(SoftDeleteRecordEntity.AWAITING_PROOF,
                RetireDispositionEntity.AWAITING_PROOF, true);
        when(applier.remove(any())).thenReturn(true);

        expiry.endExpired();

        InOrder order = inOrder(audit, applier);
        ArgumentCaptor<Map<String, Object>> pending = ArgumentCaptor.forClass(Map.class);
        order.verify(audit).record(eq(RetireAuditLog.APPLY), eq(APPLIED), eq(RetireAuditLog.SERVICE),
                eq("s3://bucket-b"), eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), pending.capture());
        order.verify(audit).record(eq(RetireAuditLog.RULE_ENDED_UNPROVEN), any(), any(), any(),
                eq(AuditOutcome.REQUESTED), any(), any());
        order.verify(applier).remove(any());
        assertThat(pending.getValue()).containsEntry("retriedOutcome", true).containsEntry("dryRun", false)
                .containsEntry("proofDueAt", PROOF_DUE);
        assertThat(disposition(rec).isSuccessAuditPending()).isFalse();
        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_ENDED_UNPROVEN);
    }

    /** Q-699: when the pending apply record cannot be written, nothing is removed and the flag stays set. */
    @Test
    void aPendingApplyRecordThatCannotBeWrittenRemovesNothing() {
        SoftDeleteRecordEntity rec = applied(SoftDeleteRecordEntity.AWAITING_PROOF,
                RetireDispositionEntity.AWAITING_PROOF, true);
        doThrow(new AuditException("dlap_common is down", null)).when(audit).record(eq(RetireAuditLog.APPLY), any(),
                any(), any(), eq(AuditOutcome.SUCCEEDED), any(), any());

        assertThat(expiry.endExpired()).isZero();

        verify(applier, never()).remove(any());
        assertThat(disposition(rec).isSuccessAuditPending()).isTrue();
        verify(records).deferRuleEnd(rec.getId(), NOW.plus(Duration.ofHours(6)));
    }

    /** Q-699: nothing pending, nothing extra written: the removal starts with its intent. */
    @Test
    void aRemovalWithNothingPendingWritesNoApplyRecord() {
        applied(SoftDeleteRecordEntity.RULE_IN_FORCE, RetireDispositionEntity.EXECUTED, false);
        when(applier.remove(any())).thenReturn(true);

        expiry.endExpired();

        verify(audit, never()).record(eq(RetireAuditLog.APPLY), any(), any(), any(), any(), any(), any());
    }
}
