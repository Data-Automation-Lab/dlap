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

package com.dlap.reporting.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.dlap.reporting.model.AccessDecisionRecordEntity;
import com.dlap.reporting.model.AssetReadRecordEntity;
import com.dlap.reporting.model.DriftFindingEntity;
import com.dlap.reporting.repository.AccessDecisionRecordRepository;
import com.dlap.reporting.repository.AssetReadRecordRepository;
import com.dlap.reporting.repository.DriftFindingRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The stateful drift join with its stores mocked: the per-key lock comes first, received and
 * detected times come from the clock, and findings are reconciled whichever side arrives last.
 */
class DriftDetectionOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-10-19T08:30:00Z");
    private static final String ASSET = "gs://sales-exports";
    private static final String SUBJECT = "svc-marketing-agent";

    private final AccessDecisionRecordRepository decisions = mock(AccessDecisionRecordRepository.class);
    private final AssetReadRecordRepository reads = mock(AssetReadRecordRepository.class);
    private final DriftFindingRepository findings = mock(DriftFindingRepository.class);
    private final DriftJoinRepository join = mock(DriftJoinRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DriftDetectionOrchestrator orchestrator;

    /** Decisions the mocked buffer returns; the orchestrator's own saves are appended. */
    private final List<AccessDecisionRecordEntity> buffered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        orchestrator = new DriftDetectionOrchestrator(new DriftDetectionService(300L), decisions, reads, findings,
                join, meters, beans.getBeanProvider(Clock.class));
        when(decisions.findByAssetIdAndSubjectIdAndDecidedAtGreaterThanEqual(anyString(), anyString(), any()))
                .thenAnswer(inv -> List.copyOf(buffered));
        when(decisions.save(any())).thenAnswer(inv -> {
            buffered.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(join.findFindingsForRead(anyString())).thenReturn(List.of());
    }

    private static AccessDecisionRecordEntity decisionRow(String id, boolean allow, Instant at) {
        AccessDecisionRecordEntity d = new AccessDecisionRecordEntity();
        d.setDecisionId(id);
        d.setAssetId(ASSET);
        d.setSubjectId(SUBJECT);
        d.setAllowed(allow);
        d.setDecidedAt(at);
        return d;
    }

    private static AssetReadRecordEntity readRow(String eventId, Instant at) {
        AssetReadRecordEntity r = new AssetReadRecordEntity();
        r.setEventId(eventId);
        r.setAssetId(ASSET);
        r.setSubjectId(SUBJECT);
        r.setReadAt(at);
        return r;
    }

    private static DriftFindingEntity findingRow(DriftType type, String readEventId, String decisionId) {
        DriftFindingEntity f = new DriftFindingEntity();
        f.setDriftType(type);
        f.setAssetId(ASSET);
        f.setSubjectId(SUBJECT);
        f.setReadEventId(readEventId);
        f.setDecisionId(decisionId);
        f.setReadAt(READ_AT);
        f.setDetail("stored detail");
        return f;
    }

    private double counter(String name, DriftType type) {
        return meters.counter(name, "type", type.name()).count();
    }

    @Test
    void readWithNoDecision_isBufferedWithTheClockAsReceivedTime_andProducesOneFinding() {
        List<DriftFinding> out = orchestrator.recordReadAndDetect(new ReadRecord("evt-1", SUBJECT, ASSET, READ_AT));

        assertThat(out).extracting(DriftFinding::type).containsExactly(DriftType.UNENFORCED_READ);
        ArgumentCaptor<AssetReadRecordEntity> read = ArgumentCaptor.forClass(AssetReadRecordEntity.class);
        verify(reads).save(read.capture());
        assertThat(read.getValue().getReadAt()).isEqualTo(READ_AT);
        assertThat(read.getValue().getReceivedAt()).isEqualTo(NOW);
        ArgumentCaptor<DriftFindingEntity> finding = ArgumentCaptor.forClass(DriftFindingEntity.class);
        verify(findings).save(finding.capture());
        assertThat(finding.getValue().getDetectedAt()).isEqualTo(NOW);
        assertThat(finding.getValue().getReadAt()).isEqualTo(READ_AT);
        assertThat(finding.getValue().getReadEventId()).isEqualTo("evt-1");
        assertThat(counter(DriftDetectionOrchestrator.DRIFT_FINDINGS_COUNTER, DriftType.UNENFORCED_READ)).isEqualTo(1.0);
    }

    @Test
    void read_takesTheJoinKeyLockBeforeAnythingElse() {
        orchestrator.recordReadAndDetect(new ReadRecord("evt-1", SUBJECT, ASSET, READ_AT));

        InOrder order = inOrder(join, reads);
        order.verify(join).lockJoinKey(DriftDetectionOrchestrator.joinLockKey(ASSET, SUBJECT));
        order.verify(reads).existsByEventId("evt-1");
        order.verify(reads).save(any());
    }

    @Test
    void redeliveredRead_changesNothing() {
        when(reads.existsByEventId("evt-1")).thenReturn(true);

        assertThat(orchestrator.recordReadAndDetect(new ReadRecord("evt-1", SUBJECT, ASSET, READ_AT))).isEmpty();

        verify(reads, never()).save(any());
        verifyNoInteractions(findings);
    }

    @Test
    void readWithAnAllowInTheWindow_producesNoFinding() {
        buffered.add(decisionRow("dec-allow", true, READ_AT.minusSeconds(30)));

        assertThat(orchestrator.recordReadAndDetect(new ReadRecord("evt-1", SUBJECT, ASSET, READ_AT))).isEmpty();
        verify(findings, never()).save(any());
    }

    @Test
    void lateAllow_retractsTheUnenforcedReadOfEveryReadItCovers() {
        DriftFindingEntity stale = findingRow(DriftType.UNENFORCED_READ, "evt-1", null);
        when(join.findReadsInWindow(ASSET, SUBJECT, READ_AT.minusSeconds(30), READ_AT.minusSeconds(30).plusSeconds(300)))
                .thenReturn(List.of(readRow("evt-1", READ_AT)));
        when(join.findFindingsForRead("evt-1")).thenReturn(List.of(stale));

        List<DriftFinding> out = orchestrator.recordDecision(
                new DecisionRecord("dec-allow", SUBJECT, ASSET, true, READ_AT.minusSeconds(30)));

        assertThat(out).isEmpty();
        verify(findings).delete(stale);
        assertThat(counter(DriftDetectionOrchestrator.DRIFT_FINDINGS_RETRACTED_COUNTER, DriftType.UNENFORCED_READ))
                .isEqualTo(1.0);
        ArgumentCaptor<AccessDecisionRecordEntity> saved = ArgumentCaptor.forClass(AccessDecisionRecordEntity.class);
        verify(decisions).save(saved.capture());
        assertThat(saved.getValue().getReceivedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getDecidedAt()).isEqualTo(READ_AT.minusSeconds(30));
    }

    @Test
    void lateDeny_turnsTheUnenforcedReadIntoABypass() {
        DriftFindingEntity stale = findingRow(DriftType.UNENFORCED_READ, "evt-1", null);
        when(join.findReadsInWindow(eq(ASSET), eq(SUBJECT), any(), any())).thenReturn(List.of(readRow("evt-1", READ_AT)));
        when(join.findFindingsForRead("evt-1")).thenReturn(List.of(stale));

        List<DriftFinding> out = orchestrator.recordDecision(
                new DecisionRecord("dec-deny", SUBJECT, ASSET, false, READ_AT.minusSeconds(10)));

        assertThat(out).extracting(DriftFinding::type, DriftFinding::decisionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DriftType.ENFORCEMENT_BYPASS, "dec-deny"));
        verify(findings).delete(stale);
        assertThat(counter(DriftDetectionOrchestrator.DRIFT_FINDINGS_COUNTER, DriftType.ENFORCEMENT_BYPASS))
                .isEqualTo(1.0);
    }

    @Test
    void newerDeny_updatesTheBypassToTheLatestDecision_withoutANewFinding() {
        buffered.add(decisionRow("dec-deny-old", false, READ_AT.minusSeconds(60)));
        DriftFindingEntity bypass = findingRow(DriftType.ENFORCEMENT_BYPASS, "evt-1", "dec-deny-old");
        when(join.findReadsInWindow(eq(ASSET), eq(SUBJECT), any(), any())).thenReturn(List.of(readRow("evt-1", READ_AT)));
        when(join.findFindingsForRead("evt-1")).thenReturn(List.of(bypass));

        List<DriftFinding> out = orchestrator.recordDecision(
                new DecisionRecord("dec-deny-new", SUBJECT, ASSET, false, READ_AT.minusSeconds(5)));

        assertThat(out).isEmpty();
        verify(findings, never()).delete(any());
        verify(findings).save(bypass);
        assertThat(bypass.getDecisionId()).isEqualTo("dec-deny-new");
        assertThat(bypass.getDetail()).contains("dec-deny-new");
    }

    @Test
    void unchangedFinding_isNotRewritten() {
        buffered.add(decisionRow("dec-deny", false, READ_AT.minusSeconds(60)));
        DriftFinding expected = new DriftDetectionService(300L).detect(
                List.of(new DecisionRecord("dec-deny", SUBJECT, ASSET, false, READ_AT.minusSeconds(60))),
                List.of(new ReadRecord("evt-1", SUBJECT, ASSET, READ_AT))).get(0);
        DriftFindingEntity bypass = findingRow(DriftType.ENFORCEMENT_BYPASS, "evt-1", "dec-deny");
        bypass.setDetail(expected.detail());
        when(join.findReadsInWindow(eq(ASSET), eq(SUBJECT), any(), any())).thenReturn(List.of(readRow("evt-1", READ_AT)));
        when(join.findFindingsForRead("evt-1")).thenReturn(List.of(bypass));

        orchestrator.recordDecision(new DecisionRecord("dec-allow", SUBJECT, ASSET, true, READ_AT.minusSeconds(20)));

        verify(findings, never()).save(any());
        verify(findings, never()).delete(any());
    }

    @Test
    void decision_takesTheLockFirst_andARedeliveryChangesNothing() {
        when(decisions.existsByDecisionId("dec-1")).thenReturn(true);

        assertThat(orchestrator.recordDecision(new DecisionRecord("dec-1", SUBJECT, ASSET, true, READ_AT))).isEmpty();

        InOrder order = inOrder(join, decisions);
        order.verify(join).lockJoinKey(DriftDetectionOrchestrator.joinLockKey(ASSET, SUBJECT));
        order.verify(decisions).existsByDecisionId("dec-1");
        verify(decisions, never()).save(any());
        verify(join, never()).findReadsInWindow(anyString(), anyString(), any(), any());
    }

    @Test
    void incompleteRecords_areIgnoredWithoutTouchingTheStores() {
        assertThat(orchestrator.recordDecision(null)).isEmpty();
        assertThat(orchestrator.recordDecision(new DecisionRecord("d", SUBJECT, ASSET, true, null))).isEmpty();
        assertThat(orchestrator.recordDecision(new DecisionRecord(null, SUBJECT, ASSET, true, READ_AT))).isEmpty();
        assertThat(orchestrator.recordDecision(new DecisionRecord("d", null, ASSET, true, READ_AT))).isEmpty();
        assertThat(orchestrator.recordDecision(new DecisionRecord("d", SUBJECT, null, true, READ_AT))).isEmpty();
        assertThat(orchestrator.recordReadAndDetect(null)).isEmpty();
        assertThat(orchestrator.recordReadAndDetect(new ReadRecord("e", SUBJECT, ASSET, null))).isEmpty();
        assertThat(orchestrator.recordReadAndDetect(new ReadRecord(null, SUBJECT, ASSET, READ_AT))).isEmpty();
        assertThat(orchestrator.recordReadAndDetect(new ReadRecord("e", null, ASSET, READ_AT))).isEmpty();
        assertThat(orchestrator.recordReadAndDetect(new ReadRecord("e", SUBJECT, null, READ_AT))).isEmpty();

        verify(join, never()).lockJoinKey(anyLong());
        verifyNoInteractions(decisions, reads, findings);
    }

    @Test
    void joinLockKey_isStable_andSeparatesAssetFromSubject() {
        assertThat(DriftDetectionOrchestrator.joinLockKey("a", "b"))
                .isEqualTo(DriftDetectionOrchestrator.joinLockKey("a", "b"))
                .isNotEqualTo(DriftDetectionOrchestrator.joinLockKey("b", "a"))
                .isNotEqualTo(DriftDetectionOrchestrator.joinLockKey("ab", ""));
    }
}
