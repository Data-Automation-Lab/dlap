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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.support.CronExpression;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-01: schedule triggers fire. The sweeper pages the catalog activity report as of one
 * instant, evaluates the event-time fields, publishes only TRUE matches with the asset identity,
 * and records the slot only after the whole sweep.
 */
class ScheduledPolicySweeperTest {

    /** 02:00 daily; the clock stands at 03:00, so the 02:00 slot is due after a 01:00 cursor. */
    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant SLOT = Instant.parse("2026-10-20T02:00:00Z");
    private static final Instant CURSOR = Instant.parse("2026-10-20T01:00:00Z");

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
    private ScheduledPolicySweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = sweeper(properties(200, 100_000));
        when(gate.submitTracked(any())).thenReturn(tracked(ApprovalGate.Route.PUBLISHED));
    }

    private ScheduledPolicySweeper sweeper(PolicyScheduleProperties props) {
        return new ScheduledPolicySweeper(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock), store,
                catalog, gate, props, clock, registry);
    }

    static ApprovalGate.Submission tracked(ApprovalGate.Route route) {
        return new ApprovalGate.Submission(route, CompletableFuture.completedFuture(Boolean.TRUE));
    }

    private static PolicyScheduleProperties properties(int pageSize, int max) {
        return new PolicyScheduleProperties(true, "UTC", pageSize, max, List.of("ACTIVE", "ARCHIVED"), null);
    }

    private static Policy policy(String yaml) {
        Policy p = new Policy();
        p.setId(UUID.randomUUID());
        p.setName("p");
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument(yaml);
        return p;
    }

    private static CatalogActivityRowDTO row(String uri, String provider, Instant lastRead, String coverage) {
        return new CatalogActivityRowDTO(UUID.nameUUIDFromBytes(uri.getBytes()), uri, uri, provider, "GCS_BUCKET",
                "GCS_BUCKET", "us-central1", "ACTIVE", Instant.parse("2024-01-01T00:00:00Z"), 10L, lastRead,
                coverage, null, "UNKNOWN", null, false, List.of(), NOW);
    }

    private static CatalogPageDTO<CatalogActivityRowDTO> page(int number, int totalPages, CatalogActivityRowDTO... rows) {
        return new CatalogPageDTO<>(List.of(rows), number, totalPages, number + 1 >= totalPages, null);
    }

    private Policy givenDue(String yaml) {
        Policy p = policy(yaml);
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(p));
        when(store.find(p.getId())).thenReturn(Optional.of(new PolicyScheduleStore.State(p.getId(), CURSOR, CURSOR)));
        return p;
    }

    @Test
    void aPolicySeenForTheFirstTimeStartsItsCursorAndDoesNotFireRetroactively() {
        Policy p = policy(ARCHIVE_STALE);
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(p));
        when(store.find(p.getId())).thenReturn(Optional.empty());

        ScheduledPolicySweeper.SweepReport report = sweeper.runDue();

        assertThat(report.result()).isEqualTo(ScheduledPolicySweeper.SweepResult.IDLE);
        verify(store).insertBaseline(p.getId(), NOW, NOW);
        verify(catalog, never()).getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any());
    }

    @Test
    void aDueSlotPagesTheCatalogAsOfTheSweepInstantAndPublishesOnlyTrueMatches() {
        Policy p = givenDue(ARCHIVE_STALE);
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant recent = Instant.parse("2026-10-15T00:00:00Z");
        when(catalog.getAssetActivity(0, 200, ScheduledPolicySweeper.SORT, List.of("ACTIVE", "ARCHIVED"), NOW))
                .thenReturn(Optional.of(page(0, 2,
                        row("gs://stale-full", "GCP", old, "FULL"),
                        row("gs://recent", "GCP", recent, "FULL"))));
        when(catalog.getAssetActivity(1, 200, ScheduledPolicySweeper.SORT, List.of("ACTIVE", "ARCHIVED"), NOW))
                .thenReturn(Optional.of(page(1, 2,
                        row("gs://stale-partial", "GCP", old, "PARTIAL"),
                        row("gs://never-read", "GCP", null, null),
                        row("s3://other-cloud", "AWS", old, "FULL"))));

        ScheduledPolicySweeper.SweepReport report = sweeper.runDue();

        assertThat(report.result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(1)).submitTracked(sent.capture());
        PolicyActionEvent e = sent.getValue();
        assertThat(e.assetUri()).isEqualTo("gs://stale-full");
        assertThat(e.assetId()).isEqualTo(UUID.nameUUIDFromBytes("gs://stale-full".getBytes()).toString());
        assertThat(e.provider()).isEqualTo("GCP");
        assertThat(e.serviceType()).isEqualTo("GCS_BUCKET");
        assertThat(e.triggerType()).isEqualTo("schedule");
        assertThat(e.scheduledFor()).isEqualTo(SLOT.toString());
        assertThat(e.firedAt()).isEqualTo(NOW.toString());
        assertThat(e.actionType()).isEqualTo("archive");
        assertThat(e.policyDbId()).isEqualTo(p.getId().toString());
        assertThat(e.matchSnapshot()).containsEntry("lastAccessedAt", old.toString())
                .containsEntry("readCoverage", "FULL");

        PolicyScheduleStore.FireCounts counts = new PolicyScheduleStore.FireCounts(5, 1, 2, 1, 0);
        verify(store).recordFire(p.getId(), SLOT, NOW, NOW, counts);
        assertThat(report.fires()).extracting(ScheduledPolicySweeper.Fire::counts).containsExactly(counts);
    }

    @Test
    void aRerunOfTheSameSlotPublishesTheSameFireId() {
        givenDue(ARCHIVE_STALE);
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(page(0, 1, row("gs://stale-full", "GCP",
                        Instant.parse("2026-01-01T00:00:00Z"), "FULL"))));

        sweeper.runDue();
        sweeper.runDue();

        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(2)).submitTracked(sent.capture());
        assertThat(sent.getAllValues().get(0).fireId()).isEqualTo(sent.getAllValues().get(1).fireId());
    }

    @Test
    void aSlotThatHasNotComeIsNotDue() {
        Policy p = policy(ARCHIVE_STALE);
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(p));
        when(store.find(p.getId())).thenReturn(Optional.of(new PolicyScheduleStore.State(p.getId(), SLOT, SLOT)));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.IDLE);
        verify(catalog, never()).getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any());
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }

    @Test
    void anUnavailableCatalogAbortsTheSweepAndLeavesTheSlotDue() {
        givenDue(ARCHIVE_STALE);
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any())).thenReturn(Optional.empty());

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.ABORTED);
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
        assertThat(registry.counter("policyengine.schedule.sweeps", "result", "aborted").count()).isEqualTo(1.0);
    }

    @Test
    void aPageWithoutPagingDataAbortsTheSweep() {
        givenDue(ARCHIVE_STALE);
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(new CatalogPageDTO<>(List.of(row("gs://a", "GCP", null, null)),
                        null, null, null, null)));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.ABORTED);
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }

    @Test
    void theAssetBoundStopsTheSweepAndStillRecordsTheSlot() {
        sweeper = sweeper(properties(200, 2));
        Policy p = givenDue(ARCHIVE_STALE);
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(page(0, 3, row("gs://a", "GCP", old, "FULL"), row("gs://b", "GCP", old, "FULL"),
                        row("gs://c", "GCP", old, "FULL"))));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.TRUNCATED);
        verify(gate, times(2)).submitTracked(any());
        verify(store).recordFire(eq(p.getId()), eq(SLOT), any(), any(), any());
    }

    @Test
    void shadowedActionsAreCountedApart() {
        Policy p = givenDue(ARCHIVE_STALE);
        when(gate.submitTracked(any())).thenReturn(tracked(ApprovalGate.Route.SHADOWED));
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(page(0, 1, row("gs://a", "GCP", Instant.parse("2026-01-01T00:00:00Z"), "FULL"))));

        sweeper.runDue();

        verify(store).recordFire(p.getId(), SLOT, NOW, NOW, new PolicyScheduleStore.FireCounts(1, 1, 0, 0, 1));
    }

    @Test
    void onlyEnabledLifecyclePoliciesWithAScheduleTriggerAreSwept() {
        Policy eventOnly = policy(ARCHIVE_STALE.replace("[{type: schedule, cron: '0 0 2 * * ?'}]",
                "[{type: event, lifecycle: created}]"));
        Policy disabled = policy(ARCHIVE_STALE.replace("name: Archive stale}", "name: x, enabled: false}"));
        Policy access = policy("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: a}\nspec:\n"
                + "  subjects: {roles: [X]}\n  actions: [read]\n  effect: deny\n");
        Policy broken = policy("apiVersion: dlap/v0\n");
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(eventOnly, disabled, access, broken));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.IDLE);
        verify(store, never()).find(any());
        verify(store, never()).insertBaseline(any(), any(), any());
    }

    @Test
    void oneSweepServesEveryDuePolicy() {
        Policy a = policy(ARCHIVE_STALE);
        Policy b = policy(ARCHIVE_STALE.replace("id: archive-stale", "id: label-old")
                .replace("lastAccessedAt: {olderThan: P90D}", "createdAt: {olderThan: P365D}")
                .replace("{type: archive, parameters: {targetStorageClass: ARCHIVE}}",
                        "{type: addLabel, parameters: {labelName: old}}"));
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(a, b));
        when(store.find(any())).thenAnswer(inv -> Optional.of(
                new PolicyScheduleStore.State(inv.getArgument(0), CURSOR, CURSOR)));
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(page(0, 1, row("gs://a", "GCP", null, null))));

        sweeper.runDue();

        verify(catalog, times(1)).getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any());
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(1)).submitTracked(sent.capture());
        assertThat(sent.getValue().policyId()).isEqualTo("label-old");
        verify(store).recordFire(a.getId(), SLOT, NOW, NOW, new PolicyScheduleStore.FireCounts(1, 0, 1, 0, 0));
        verify(store).recordFire(b.getId(), SLOT, NOW, NOW, new PolicyScheduleStore.FireCounts(1, 1, 0, 1, 0));
    }

    @Test
    void missedSlotsCoalesceIntoTheLatestOne() {
        CronExpression hourly = CronExpression.parse("0 0 * * * ?");
        Instant latest = ScheduledPolicySweeper.latestSlot(List.of(hourly),
                Instant.parse("2026-10-18T00:00:00Z"), NOW, ZoneOffset.UTC);
        assertThat(latest).isEqualTo(Instant.parse("2026-10-20T03:00:00Z"));
        assertThat(ScheduledPolicySweeper.latestSlot(List.of(hourly), NOW, NOW, ZoneOffset.UTC)).isNull();
    }

    @Test
    void theLatestSlotOfSeveralCronsWins() {
        List<CronExpression> crons = new ArrayList<>(List.of(CronExpression.parse("0 0 2 * * ?"),
                CronExpression.parse("0 30 2 * * ?")));
        assertThat(ScheduledPolicySweeper.latestSlot(crons, CURSOR, NOW, ZoneOffset.UTC))
                .isEqualTo(Instant.parse("2026-10-20T02:30:00Z"));
    }

    @Test
    void anOverlongCatchUpJumpsTheCursorToNow() {
        CronExpression everySecond = CronExpression.parse("* * * * * ?");
        assertThat(ScheduledPolicySweeper.latestSlot(List.of(everySecond),
                Instant.parse("2026-10-19T00:00:00Z"), NOW, ZoneOffset.UTC)).isEqualTo(NOW);
    }

    @Test
    void heldFiresAreNeitherPublishedNorShadowed() {
        Policy p = givenDue(ARCHIVE_STALE);
        when(gate.submitTracked(any())).thenReturn(tracked(ApprovalGate.Route.HELD));
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(page(0, 1, row("gs://a", "GCP", Instant.parse("2026-01-01T00:00:00Z"), "FULL"))));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);

        verify(store).recordFire(p.getId(), SLOT, NOW, NOW, new PolicyScheduleStore.FireCounts(1, 1, 0, 0, 0));
    }

    @Test
    void anApprovalStoreOutageAbortsTheSweepAndLeavesTheSlotDue() {
        givenDue(ARCHIVE_STALE);
        when(gate.submitTracked(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(page(0, 1, row("gs://a", "GCP", Instant.parse("2026-01-01T00:00:00Z"), "FULL"))));

        assertThat(sweeper.runDue().result()).isEqualTo(ScheduledPolicySweeper.SweepResult.ABORTED);
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
    }
}
