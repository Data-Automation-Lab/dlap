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

package com.dlap.autoarchival.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Q-607: the reconciliation reads, reports and counts; it writes nothing. */
class ArchiveRuleReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ArchiveReconcileProperties PROPS = new ArchiveReconcileProperties(true, Duration.ofDays(7),
            Duration.ofMinutes(30), 2);

    private final ArchiveRuleRepository rules = mock(ArchiveRuleRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static ArchiveRuleEntity applied(String id) {
        return applied(id, 0, 10);
    }

    /** A real rule with the given age that was applied {@code daysAgo} days before NOW. */
    private static ArchiveRuleEntity applied(String id, int afterDays, int daysAgo) {
        Instant at = NOW.minus(Duration.ofDays(daysAgo));
        ArchiveRuleEntity r = new ArchiveRuleEntity(id, "appr-" + id, null, "t", "a", "gs://b/p/", "GCP",
                ArchiveRuleEntity.MODE_NATIVE_RULE, "b", "p/", "COLDLINE", afterDays, at);
        r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, at);
        return r;
    }

    @Test
    void theCutoffsComeFromTheInjectedClockAndTheConfiguredAges() {
        new ArchiveRuleReconciler(rules, PROPS, CLOCK, registry).reconcile();

        verify(rules).findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(
                eq(ArchiveRuleEntity.STATUS_APPLIED), eq(NOW.minus(Duration.ofDays(7))), any());
        verify(rules).countByStatusAndUpdatedAtBefore(ArchiveRuleEntity.STATUS_EXECUTING,
                NOW.minus(Duration.ofMinutes(30)));
    }

    @Test
    void itCountsEveryRowButListsOnlyTheConfiguredNumberAndExposesTheCounts() {
        when(rules.countByStatusAndUpdatedAtBefore(any(), any())).thenReturn(1L);
        when(rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any()))
                .thenReturn(List.of(applied("dlap-archive-a00000000"), applied("dlap-archive-b00000000"),
                        applied("dlap-archive-d00000000"), applied("dlap-archive-e00000000"),
                        applied("dlap-archive-f00000000")));
        when(rules.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any(), any()))
                .thenReturn(List.of(applied("dlap-archive-c00000000")));

        ArchiveRuleReconciler.Result result = new ArchiveRuleReconciler(rules, PROPS, CLOCK, registry).reconcile();

        assertThat(result).isEqualTo(new ArchiveRuleReconciler.Result(5, 1));
        assertThat(registry.get(ArchiveRuleReconciler.UNPROVEN_METRIC).gauge().value()).isEqualTo(5.0);
        assertThat(registry.get(ArchiveRuleReconciler.STUCK_METRIC).gauge().value()).isEqualTo(1.0);
        // the stuck listing stays bounded by the configured log limit
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(rules).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(ArchiveRuleEntity.STATUS_EXECUTING), any(),
                page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void q672_aLongAgeRuleIsUnprovenOnlyAfterMaxAppliedPlusItsOwnAge() {
        // max-applied is P7D. All four were applied 40 days ago.
        when(rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any()))
                .thenReturn(List.of(
                        applied("dlap-archive-a00000000", 0, 40),   // 40 > 7 + 0   : unproven
                        applied("dlap-archive-b00000000", 30, 40),  // 40 > 7 + 30  : unproven
                        applied("dlap-archive-c00000000", 90, 40),  // 40 < 7 + 90  : cannot have fired yet
                        applied("dlap-archive-d00000000", 365, 40)  // 40 < 7 + 365 : cannot have fired yet
                ));

        ArchiveRuleReconciler.Result result = new ArchiveRuleReconciler(rules, PROPS, CLOCK, registry).reconcile();

        assertThat(result.unproven()).isEqualTo(2);
        assertThat(registry.get(ArchiveRuleReconciler.UNPROVEN_METRIC).gauge().value()).isEqualTo(2.0);
    }

    @Test
    void q672_theBoundaryIsMaxAppliedPlusAfterDaysExactly() {
        // applied 37 days ago with a 30 day rule: the cutoff is exactly 37 days, which is not "before" it
        when(rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any()))
                .thenReturn(List.of(applied("dlap-archive-a00000000", 30, 37), applied("dlap-archive-b00000000", 30, 38)));

        ArchiveRuleReconciler.Result result = new ArchiveRuleReconciler(rules, PROPS, CLOCK, registry).reconcile();

        assertThat(result.unproven()).isEqualTo(1);
    }

    @Test
    void itNeverWritesARowWhateverItFinds() {
        when(rules.countByStatusAndUpdatedAtBefore(any(), any())).thenReturn(1L);
        when(rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any()))
                .thenReturn(List.of(applied("dlap-archive-a00000000")));
        when(rules.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any(), any()))
                .thenReturn(List.of(applied("dlap-archive-c00000000")));

        new ArchiveRuleReconciler(rules, PROPS, CLOCK, registry).reconcile();

        // only reads: the bounded APPLIED scan, the stuck count and its bounded listing; no save, delete or update
        verify(rules).findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any());
        verify(rules).countByStatusAndUpdatedAtBefore(any(), any());
        verify(rules).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any(), any());
        verifyNoMoreInteractions(rules);
    }

    @Test
    void whenNothingIsOldTheGaugesReturnToZero() {
        ArchiveRuleReconciler job = new ArchiveRuleReconciler(rules, PROPS, CLOCK, registry);
        when(rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any()))
                .thenReturn(List.of(applied("dlap-archive-a00000000"), applied("dlap-archive-b00000000"),
                        applied("dlap-archive-c00000000")));
        job.reconcile();
        assertThat(registry.get(ArchiveRuleReconciler.UNPROVEN_METRIC).gauge().value()).isEqualTo(3.0);

        when(rules.findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        job.reconcile();

        assertThat(registry.get(ArchiveRuleReconciler.UNPROVEN_METRIC).gauge().value()).isZero();
    }

    @Test
    void theScheduledMethodHoldsAUniquelyNamedLock() throws Exception {
        Method m = ArchiveRuleReconciler.class.getMethod("scheduledReconcile");

        assertThat(AnnotatedElementUtils.hasAnnotation(m, Scheduled.class)).isTrue();
        SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(m, SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo(ArchiveRuleReconciler.LOCK_NAME).hasSizeLessThan(64);
        // two counts and two bounded listings take seconds; the lock is held at most ten minutes by default
        assertThat(lock.lockAtMostFor()).contains("PT10M");
    }

    @Test
    void settingsWithNonsenseFallBackToTheirDefaults() {
        ArchiveReconcileProperties p = new ArchiveReconcileProperties(true, Duration.ofDays(-1), Duration.ZERO, 0);

        assertThat(p.maxApplied()).isEqualTo(Duration.ofDays(7));
        assertThat(p.claimTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.logLimit()).isEqualTo(50);
        assertThat(new ArchiveReconcileProperties(true, null, null, 100000).logLimit()).isEqualTo(1000);
    }
}
