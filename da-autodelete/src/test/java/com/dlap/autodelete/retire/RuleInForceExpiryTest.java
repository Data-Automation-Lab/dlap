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
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Q-677 (RET-06): a rule in force ends. Past its maximum age (default 30 days, on the injected clock) the executor
 * removes its own rule without an approval, audit-chained, and the record ends RULE_ENDED; a rule it cannot find
 * ends RESTORE_FAILED; a platform that refuses leaves the record in force and is tried again later.
 */
class RuleInForceExpiryTest {

    private static final Instant NOW = Instant.parse("2026-12-15T09:00:00Z");

    private final SoftDeleteRecordRepository records = mock(SoftDeleteRecordRepository.class);
    private final RetireDispositionRepository dispositions = mock(RetireDispositionRepository.class);
    private final DispositionPlanner planner = mock(DispositionPlanner.class);
    private final NativeRuleApplier applier = mock(NativeRuleApplier.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RuleInForceExpiry expiry;

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(planner.applierFor("GCS_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(records.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispositions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        expiry = new RuleInForceExpiry(records, new RuleRemover(dispositions, planner, audit, clock), audit,
                RetireProperties.defaults(), clock, tx, registry);
    }

    private SoftDeleteRecordEntity inForce(Instant appliedAt) {
        SoftDeleteRecordEntity rec = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("gs://bucket-a").cloudProvider("GCP").status(SoftDeleteRecordEntity.RULE_IN_FORCE).build();
        RetireDispositionEntity d = RetireDispositionEntity.builder().id(UUID.randomUUID())
                .softDeleteRecordId(rec.getId()).targetUri("gs://bucket-a").mode("NATIVE_RULE")
                .platform("GCS_LIFECYCLE").ruleId("dlap-retire-x").status(RetireDispositionEntity.EXECUTED)
                .ruleDocument("{\"bucket\":\"bucket-a\",\"lifecycle\":{\"rule\":[]}}").appliedAt(appliedAt)
                .dryRun(false).build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.of(d));
        return rec;
    }

    /** Q-677: the claim asks for rules applied at or before now minus the maximum age, on the injected clock. */
    @Test
    void theClaimIsClockDrivenWithTheMaximumAgeOf30Days() {
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.empty());

        assertThat(expiry.endExpired()).isZero();

        verify(records).claimNextRuleToEnd(NOW.minus(Duration.ofDays(30)), NOW);
    }

    /**
     * Q-677: past its maximum age the rule is removed (no approval asked), the removal is audited intent first and
     * outcome after, and the record ends RULE_ENDED.
     */
    @Test
    void aRuleInForcePastItsMaximumAgeIsRemovedAuditedAndEndsRuleEnded() {
        SoftDeleteRecordEntity rec = inForce(NOW.minus(Duration.ofDays(31)));
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());
        when(applier.remove(any())).thenReturn(true);

        assertThat(expiry.endExpired()).isEqualTo(1);

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_ENDED);
        assertThat(rec.getNextCheckAt()).isNull();
        InOrder order = inOrder(audit, applier);
        order.verify(audit).record(eq(RetireAuditLog.RULE_ENDED), eq(NOW), eq(RetireAuditLog.SERVICE),
                eq("gs://bucket-a"), eq(AuditOutcome.REQUESTED), eq(rec.getId().toString()), any());
        order.verify(applier).remove(any());
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        order.verify(audit).record(eq(RetireAuditLog.RULE_ENDED), eq(NOW), eq(RetireAuditLog.SERVICE),
                eq("gs://bucket-a"), eq(AuditOutcome.SUCCEEDED), eq(rec.getId().toString()), details.capture());
        assertThat(details.getValue()).containsEntry("ruleRemoved", true).containsEntry("maxAge", "PT720H")
                .containsEntry("previousStatus", SoftDeleteRecordEntity.RULE_IN_FORCE);
        assertThat(dispositions.findBySoftDeleteRecordId(rec.getId()).orElseThrow().getRuleRemovedAt()).isEqualTo(NOW);
    }

    /** Q-682 applied to the end of a rule: a rule that is not found ends RESTORE_FAILED, audited rule-not-found. */
    @Test
    void aRuleThatIsNotFoundEndsRestoreFailed() {
        SoftDeleteRecordEntity rec = inForce(NOW.minus(Duration.ofDays(40)));
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());
        when(applier.remove(any())).thenReturn(false);

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORE_FAILED);
        assertThat(rec.getLastError()).startsWith("rule-not-found");
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(RetireAuditLog.RULE_ENDED), any(), any(), any(), eq(AuditOutcome.FAILED), any(),
                details.capture());
        assertThat(details.getValue()).containsEntry("reason", "rule-not-found");
        verify(audit, never()).record(eq(RetireAuditLog.RULE_ENDED), any(), any(), any(), eq(AuditOutcome.SUCCEEDED),
                any(), any());
    }

    /** A platform that refuses the removal leaves the record in force, audited FAILED, tried again after 6 hours. */
    @Test
    void aRefusedRemovalLeavesTheRuleInForceAndIsTriedAgainLater() {
        SoftDeleteRecordEntity rec = inForce(NOW.minus(Duration.ofDays(31)));
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());
        doThrow(new NativeRuleException("GCS refused: HTTP 403", null)).when(applier).remove(any());

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RULE_IN_FORCE);
        // RET-08: the retry time is the job's own (rule_end_retry_at); the sweeper's next_check_at is left alone.
        assertThat(rec.getRuleEndRetryAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(rec.getNextCheckAt()).isNull();
        assertThat(rec.getLastError()).startsWith("rule-removal-failed").contains("HTTP 403");
        verify(audit).record(eq(RetireAuditLog.RULE_ENDED), any(), any(), any(), eq(AuditOutcome.FAILED), any(), any());
    }

    /** An intent that cannot be audited removes nothing; the row is deferred and the run goes on. */
    @Test
    void anIntentThatCannotBeAuditedRemovesNothingAndTheRowIsDeferred() {
        SoftDeleteRecordEntity rec = inForce(NOW.minus(Duration.ofDays(31)));
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());
        doThrow(new AuditException("dlap_common is down", null)).when(audit).record(eq(RetireAuditLog.RULE_ENDED),
                any(), any(), any(), eq(AuditOutcome.REQUESTED), any(), any());

        assertThat(expiry.endExpired()).isZero();

        verify(applier, never()).remove(any());
        verify(records).deferRuleEnd(rec.getId(), NOW.plus(Duration.ofHours(6)));
        verify(records, never()).deferNextCheck(any(), any());
    }

    /** A record without a stored rule cannot be ended by DLAP; it ends RESTORE_FAILED and the refusal is audited. */
    @Test
    void aRecordWithoutAStoredRuleEndsRestoreFailedAndIsAudited() {
        SoftDeleteRecordEntity rec = SoftDeleteRecordEntity.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .assetUri("gs://bucket-a").status(SoftDeleteRecordEntity.RULE_IN_FORCE).build();
        when(dispositions.findBySoftDeleteRecordId(rec.getId())).thenReturn(Optional.empty());
        when(records.claimNextRuleToEnd(any(), any())).thenReturn(Optional.of(rec), Optional.empty());

        expiry.endExpired();

        assertThat(rec.getStatus()).isEqualTo(SoftDeleteRecordEntity.RESTORE_FAILED);
        verify(audit).refused(eq(NOW), eq(RetireAuditLog.SERVICE), eq("gs://bucket-a"), eq(rec.getId().toString()),
                eq("rule_end"), eq(RefusalReason.RULE_NOT_FOUND), anyString(), any());
    }

    /** Q-677: a metric counts the records in force (and the records whose rule could not be found). */
    @Test
    void aMetricCountsTheRecordsInForce() {
        when(records.countByStatus(SoftDeleteRecordEntity.RULE_IN_FORCE)).thenReturn(7L);
        when(records.countByStatus(SoftDeleteRecordEntity.RESTORE_FAILED)).thenReturn(2L);

        assertThat(registry.get(RuleInForceExpiry.IN_FORCE_METRIC).gauge().value()).isEqualTo(7.0);
        assertThat(registry.get(RuleInForceExpiry.RESTORE_FAILED_METRIC).gauge().value()).isEqualTo(2.0);
    }

    /** Q-677: the job is scheduled and runs under its own ShedLock (rule R7). */
    @Test
    void theScheduledEndCarriesItsShedLock() throws NoSuchMethodException {
        Method m = RuleInForceExpiry.class.getMethod("scheduledEnd");
        assertThat(m.getAnnotation(Scheduled.class)).isNotNull();
        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo(RuleInForceExpiry.LOCK_NAME).hasSizeLessThanOrEqualTo(64);
        assertThat(lock.lockAtMostFor()).isNotBlank();
    }
}
