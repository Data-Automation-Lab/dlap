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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-05 (decision D45, Q-445, sweeper side): the scheduled sweep pages the catalog activity endpoint by keyset
 * ({@code ?after=<assetUri>&size=N}, rows in {@code assetUri} order, {@code nextAfter} null on the last page)
 * when the catalog sends {@code nextAfter}, and by offset otherwise, so the package passes without CAT-04.
 */
class ScheduledPolicySweeperKeysetTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant SLOT = Instant.parse("2026-10-20T02:00:00Z");
    private static final Instant CURSOR = Instant.parse("2026-10-20T01:00:00Z");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> STATUSES = List.of("ACTIVE", "ARCHIVED");

    private static final String ARCHIVE_STALE = ""
            + "apiVersion: dlap/v1\nkind: Policy\n"
            + "metadata: {id: archive-stale, name: Archive stale}\n"
            + "spec:\n"
            + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
            + "  match: {cloudProvider: GCP, lastAccessedAt: {olderThan: P90D}}\n"
            + "  action: {type: archive, parameters: {targetStorageClass: ARCHIVE}}\n";

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final PolicyScheduleStore store = mock(PolicyScheduleStore.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final ApprovalGate gate = mock(ApprovalGate.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private ScheduledPolicySweeper sweeper;
    private Policy policy;

    @BeforeEach
    void setUp() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(ScheduledPolicySweeper.class)).addAppender(logs);
        sweeper = sweeper(100_000);
        when(gate.submitTracked(any())).thenReturn(
                new ApprovalGate.Submission(ApprovalGate.Route.PUBLISHED, CompletableFuture.completedFuture(Boolean.TRUE)));
        policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setName("p");
        policy.setStatus(PolicyStatus.ENABLED);
        policy.setPolicyDocument(ARCHIVE_STALE);
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(policy));
        when(store.find(policy.getId()))
                .thenReturn(Optional.of(new PolicyScheduleStore.State(policy.getId(), CURSOR, CURSOR)));
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(ScheduledPolicySweeper.class)).detachAppender(logs);
        logs.stop();
    }

    private ScheduledPolicySweeper sweeper(int maxAssets) {
        return new ScheduledPolicySweeper(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock), store,
                catalog, gate, new PolicyScheduleProperties(true, "UTC", 2, maxAssets, STATUSES, null), clock, registry);
    }

    private static CatalogActivityRowDTO row(String uri) {
        return new CatalogActivityRowDTO(UUID.nameUUIDFromBytes(uri.getBytes()), uri, uri, "GCP", "GCS_BUCKET",
                "GCS_BUCKET", "us-central1", "ACTIVE", Instant.parse("2024-01-01T00:00:00Z"), 10L, OLD,
                "FULL", null, "UNKNOWN", null, false, List.of(), NOW);
    }

    /** A keyset answer: {@code nextAfter} null means the last page (the field is sent, as null). */
    private static CatalogPageDTO<CatalogActivityRowDTO> keysetPage(String nextAfter, CatalogActivityRowDTO... rows) {
        return new CatalogPageDTO<>(List.of(rows), null, null, null, null, nextAfter == null ? "" : nextAfter);
    }

    private void givenFirstPage(CatalogPageDTO<CatalogActivityRowDTO> first) {
        when(catalog.getAssetActivity(0, 2, ScheduledPolicySweeper.SORT, STATUSES, NOW)).thenReturn(Optional.of(first));
    }

    private void givenPageAfter(String after, CatalogPageDTO<CatalogActivityRowDTO> page) {
        when(catalog.getAssetActivityAfter(after, 2, STATUSES, NOW)).thenReturn(Optional.of(page));
    }

    @Test
    void whenTheCatalogSendsNextAfterTheSweepFollowsTheCursorUntilItIsNull() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage("gs://d", row("gs://c"), row("gs://d")));
        givenPageAfter("gs://d", keysetPage(null, row("gs://e")));

        ScheduledPolicySweeper.SweepReport report = sweeper.runDue();

        assertThat(report.result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(5)).submitTracked(sent.capture());
        assertThat(sent.getAllValues()).extracting(PolicyActionEvent::assetUri)
                .containsExactly("gs://a", "gs://b", "gs://c", "gs://d", "gs://e");
        verify(catalog, times(1)).getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any());
        verify(catalog).getAssetActivityAfter("gs://b", 2, STATUSES, NOW);
        verify(catalog).getAssetActivityAfter("gs://d", 2, STATUSES, NOW);
        verify(catalog, times(2)).getAssetActivityAfter(anyString(), anyInt(), anyList(), any());
        verify(store).recordFire(eq(policy.getId()), eq(SLOT), eq(NOW), eq(NOW),
                eq(new PolicyScheduleStore.FireCounts(5, 5, 0, 5, 0)));
        assertThat(registry.counter("policyengine.schedule.paging", "mode", "keyset").count()).isEqualTo(1.0);
    }

    @Test
    void theCursorNotThePageNumberDecidesWhatIsReadSoAnInsertDuringTheSweepSkipsNothing() {
        // Page 0 ended at gs://b. A row gs://a2 is inserted before gs://b while the sweep runs: by offset the next
        // page would repeat gs://b; by keyset it starts after gs://b whatever was inserted before it.
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage(null, row("gs://c")));

        sweeper.runDue();

        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(3)).submitTracked(sent.capture());
        assertThat(sent.getAllValues()).extracting(PolicyActionEvent::assetUri)
                .containsExactly("gs://a", "gs://b", "gs://c");
        verify(catalog, never()).getAssetActivity(eq(1), anyInt(), anyString(), anyList(), any());
    }

    @Test
    void aSinglePageKeysetAnswerWithNullNextAfterNeedsNoPagingData() {
        givenFirstPage(keysetPage(null, row("gs://a")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);

        verify(catalog, never()).getAssetActivityAfter(any(), anyInt(), anyList(), any());
        verify(store).recordFire(eq(policy.getId()), eq(SLOT), any(), any(), any());
    }

    @Test
    void aCatalogWithoutNextAfterIsReadByOffsetAndTheKeysetMethodIsNeverCalled() {
        when(catalog.getAssetActivity(0, 2, ScheduledPolicySweeper.SORT, STATUSES, NOW)).thenReturn(Optional.of(
                new CatalogPageDTO<>(List.of(row("gs://a"), row("gs://b")), 0, 2, false, null)));
        when(catalog.getAssetActivity(1, 2, ScheduledPolicySweeper.SORT, STATUSES, NOW)).thenReturn(Optional.of(
                new CatalogPageDTO<>(List.of(row("gs://c")), 1, 2, true, null)));

        ScheduledPolicySweeper.SweepReport report = sweeper.runDue();

        assertThat(report.result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);
        verify(gate, times(3)).submitTracked(any());
        verify(catalog, never()).getAssetActivityAfter(any(), anyInt(), anyList(), any());
        assertThat(registry.find("policyengine.schedule.paging").counter()).isNull();
    }

    @Test
    void anUnavailableCatalogInTheMiddleOfAKeysetSweepAbortsAndLeavesTheSlotDue() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        when(catalog.getAssetActivityAfter("gs://b", 2, STATUSES, NOW)).thenReturn(Optional.empty());

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.ABORTED);

        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }

    @Test
    void aCursorThatRepeatsItselfAbortsTheSweepInsteadOfLooping() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage("gs://b", row("gs://c")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.ABORTED);

        verify(catalog, times(1)).getAssetActivityAfter(anyString(), anyInt(), anyList(), any());
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }

    @Test
    void aCursorAfterAnEmptyPageAbortsTheSweep() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage("gs://c"));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.ABORTED);

        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }

    @Test
    void theCursorIsNotCheckedForOrderBecauseTheCatalogOrdersByItsOwnCollation() {
        // Java would order "gs://B" before "gs://a"; a database collation may not. Only a repeat is refused.
        givenFirstPage(keysetPage("gs://a", row("gs://x"), row("gs://a")));
        givenPageAfter("gs://a", keysetPage(null, row("gs://B")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);
    }

    @Test
    void theAssetBoundStillStopsAKeysetSweepAndRecordsTheSlot() {
        sweeper = sweeper(3);
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage("gs://d", row("gs://c"), row("gs://d")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.TRUNCATED);

        verify(gate, times(3)).submitTracked(any());
        verify(store).recordFire(eq(policy.getId()), eq(SLOT), any(), any(), any());
    }

    /** The answer of an older catalog replica to an {@code after} request: page 0 by offset, no nextAfter field. */
    private static CatalogPageDTO<CatalogActivityRowDTO> offsetAnswer(boolean last, CatalogActivityRowDTO... rows) {
        return new CatalogPageDTO<>(List.of(rows), 0, last ? 1 : 2, last, null);
    }

    @Test
    void mixedVersionAnswerWithoutNextAfterAfterKeysetEndsIncompleteAndTheSlotIsNotRecorded() {
        // Page 0 came from a new replica (keyset). The request after gs://b reached an old replica, which ignored
        // "after" and answered page 0 by offset with last=false and no nextAfter field. Q-917 recorded the slot here.
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", offsetAnswer(false, row("gs://a"), row("gs://b")));

        ScheduledPolicySweeper.SweepReport report = sweeper.runDue();

        assertThat(report.result()).isEqualTo(ScheduledPolicySweeper.SweepResult.INCOMPLETE);
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
        assertThat(report.fires()).hasSize(1);
        assertThat(report.fires().get(0).recorded()).isFalse();
        assertThat(report.fires().get(0).slot()).isEqualTo(SLOT);
        // Only the rows of the genuine first page were evaluated; the repeated rows of the old answer were not.
        verify(gate, times(2)).submitTracked(any());
        verify(catalog, times(1)).getAssetActivityAfter(anyString(), anyInt(), anyList(), any());
        verify(catalog, never()).getAssetActivity(eq(1), anyInt(), anyString(), anyList(), any());
        assertThat(registry.counter("policyengine.schedule.slots", "result", "keyset-incomplete").count()).isEqualTo(1.0);
        assertThat(registry.counter("policyengine.schedule.sweeps", "result", "incomplete").count()).isEqualTo(1.0);
        assertThat(registry.find("policyengine.schedule.slots").tag("result", "recorded").counter()).isNull();
    }

    @Test
    void anOffsetAnswerMarkedLastInTheMiddleOfAKeysetWalkIsStillIncomplete() {
        // The missing field, not the last flag, decides: an old replica that says last=true is no completion either.
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", offsetAnswer(true, row("gs://a")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.INCOMPLETE);

        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }

    @Test
    void anIncompleteWalkWarnsWithTheSlotAndThePageReached() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage("gs://d", row("gs://c"), row("gs://d")));
        givenPageAfter("gs://d", offsetAnswer(false, row("gs://a")));

        sweeper.runDue();

        List<String> warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).anySatisfy(m -> assertThat(m)
                .contains("archive-stale").contains(SLOT.toString()).contains("page 2").contains("after gs://d")
                .contains("without nextAfter"));
    }

    @Test
    void theNextSweepRetriesTheSlotAfterAnIncompleteWalkAndRecordsItOnceTheCatalogAnswersWholly() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", offsetAnswer(false, row("gs://a"), row("gs://b")));
        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.INCOMPLETE);
        verify(store, never()).recordFire(any(), any(), any(), any(), any());

        // The rollout finishes: every replica now answers by keyset.
        givenPageAfter("gs://b", keysetPage(null, row("gs://c")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);
        verify(store, times(1)).recordFire(eq(policy.getId()), eq(SLOT), eq(NOW), eq(NOW),
                eq(new PolicyScheduleStore.FireCounts(3, 3, 0, 3, 0)));
    }

    @Test
    void anExplicitNullNextAfterStillCompletesTheWalkAfterKeysetPages() {
        givenFirstPage(keysetPage("gs://b", row("gs://a"), row("gs://b")));
        givenPageAfter("gs://b", keysetPage(null, row("gs://c")));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);

        verify(store).recordFire(eq(policy.getId()), eq(SLOT), any(), any(), any());
        assertThat(registry.find("policyengine.schedule.slots").tag("result", "keyset-incomplete").counter()).isNull();
    }
}
