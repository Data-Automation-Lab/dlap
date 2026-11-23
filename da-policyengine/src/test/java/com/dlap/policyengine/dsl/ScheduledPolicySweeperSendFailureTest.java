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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-02, decision D42 on Q-439: the sweeper does not record the slot when a send returns FAILED,
 * when the asynchronous send fails, or when the broker does not acknowledge it in time, so the next
 * sweep inside the slot window runs the slot again. FireCounts counts the failed sends.
 */
class ScheduledPolicySweeperSendFailureTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant SLOT = Instant.parse("2026-10-20T02:00:00Z");
    private static final Instant CURSOR = Instant.parse("2026-10-20T01:00:00Z");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    private static final String LABEL_STALE = ""
            + "apiVersion: dlap/v1\nkind: Policy\n"
            + "metadata: {id: label-stale, name: Label stale}\n"
            + "spec:\n"
            + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
            + "  match: {cloudProvider: GCP, lastAccessedAt: {olderThan: P90D}}\n"
            + "  action: {type: addLabel, parameters: {labelName: stale}}\n";

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final PolicyScheduleStore store = mock(PolicyScheduleStore.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final ApprovalGate gate = mock(ApprovalGate.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private Policy policy;

    @BeforeEach
    void setUp() {
        policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setName("p");
        policy.setStatus(PolicyStatus.ENABLED);
        policy.setPolicyDocument(LABEL_STALE);
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(policy));
        when(store.find(policy.getId()))
                .thenReturn(Optional.of(new PolicyScheduleStore.State(policy.getId(), CURSOR, CURSOR)));
        when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                .thenReturn(Optional.of(new CatalogPageDTO<>(List.of(row("gs://a"), row("gs://b")), 0, 1, true, null)));
    }

    private ScheduledPolicySweeper sweeper(Duration sendTimeout) {
        PolicyScheduleProperties props = new PolicyScheduleProperties(true, "UTC", 200, 100_000,
                List.of("ACTIVE", "ARCHIVED"), sendTimeout);
        return new ScheduledPolicySweeper(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock), store,
                catalog, gate, props, clock, registry);
    }

    private static CatalogActivityRowDTO row(String uri) {
        return new CatalogActivityRowDTO(UUID.nameUUIDFromBytes(uri.getBytes()), uri, uri, "GCP", "GCS_BUCKET",
                "GCS_BUCKET", "us-central1", "ACTIVE", null, 10L, OLD, "FULL", null, null, null, false, List.of(), NOW);
    }

    private static ApprovalGate.Submission published(CompletableFuture<Boolean> delivery) {
        return new ApprovalGate.Submission(ApprovalGate.Route.PUBLISHED, delivery);
    }

    private static ApprovalGate.Submission acknowledged() {
        return published(CompletableFuture.completedFuture(Boolean.TRUE));
    }

    private double failedSends() {
        return registry.find("policyengine.schedule.sends").tag("result", "failed").counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    @Test
    void aSendThatReturnsFailedKeepsTheSlotOpenAndIsCounted() {
        when(gate.submitTracked(any())).thenReturn(acknowledged(),
                new ApprovalGate.Submission(ApprovalGate.Route.FAILED, CompletableFuture.completedFuture(false)));

        ScheduledPolicySweeper.SweepReport report = sweeper(Duration.ofSeconds(5)).runDue();

        assertThat(report.result()).isEqualTo(ScheduledPolicySweeper.SweepResult.COMPLETED);
        verify(store, never()).recordFire(any(), any(), any(), any(), any());
        ScheduledPolicySweeper.Fire fire = report.fires().get(0);
        assertThat(fire.recorded()).isFalse();
        assertThat(fire.counts()).isEqualTo(new PolicyScheduleStore.FireCounts(2, 2, 0, 1, 0, 1));
        assertThat(failedSends()).isEqualTo(1.0);
        assertThat(registry.counter("policyengine.schedule.slots", "result", "send-failed").count()).isEqualTo(1.0);
    }

    @Test
    void anAsynchronousSendFailureKeepsTheSlotOpenAndIsCounted() {
        when(gate.submitTracked(any())).thenReturn(acknowledged(),
                published(CompletableFuture.completedFuture(Boolean.FALSE)));

        ScheduledPolicySweeper.SweepReport report = sweeper(Duration.ofSeconds(5)).runDue();

        verify(store, never()).recordFire(any(), any(), any(), any(), any());
        assertThat(report.fires().get(0).counts().failed()).isEqualTo(1);
        assertThat(report.fires().get(0).counts().published()).isEqualTo(2);
        assertThat(failedSends()).isEqualTo(1.0);
    }

    @Test
    void aSendTheBrokerDoesNotAcknowledgeInTimeKeepsTheSlotOpen() {
        when(gate.submitTracked(any())).thenReturn(acknowledged(), published(new CompletableFuture<>()));

        long started = System.nanoTime();
        ScheduledPolicySweeper.SweepReport report = sweeper(Duration.ofMillis(200)).runDue();
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        verify(store, never()).recordFire(any(), any(), any(), any(), any());
        assertThat(report.fires().get(0).counts().failed()).isEqualTo(1);
        assertThat(waitedMs).isGreaterThanOrEqualTo(150).isLessThan(10_000);
    }

    @Test
    void theNextSweepRetriesTheSameSlotAndRecordsItOnceEverySendIsAcknowledged() {
        when(gate.submitTracked(any())).thenReturn(acknowledged(),
                published(CompletableFuture.completedFuture(Boolean.FALSE)), acknowledged(), acknowledged());
        ScheduledPolicySweeper sweeper = sweeper(Duration.ofSeconds(5));

        ScheduledPolicySweeper.SweepReport first = sweeper.runDue();
        ScheduledPolicySweeper.SweepReport second = sweeper.runDue();

        assertThat(first.fires().get(0).slot()).isEqualTo(SLOT);
        assertThat(first.fires().get(0).recorded()).isFalse();
        assertThat(second.fires().get(0).slot()).isEqualTo(SLOT);
        assertThat(second.fires().get(0).recorded()).isTrue();
        verify(store).recordFire(policy.getId(), SLOT, NOW, NOW, new PolicyScheduleStore.FireCounts(2, 2, 0, 2, 0));
    }

    @Test
    void acknowledgedSendsRecordTheSlot() {
        when(gate.submitTracked(any())).thenReturn(acknowledged());

        ScheduledPolicySweeper.SweepReport report = sweeper(Duration.ofSeconds(5)).runDue();

        assertThat(report.fires().get(0).recorded()).isTrue();
        verify(store).recordFire(policy.getId(), SLOT, NOW, NOW, new PolicyScheduleStore.FireCounts(2, 2, 0, 2, 0));
        assertThat(failedSends()).isZero();
    }

    @Test
    void heldAndShadowedFiresNeedNoBrokerAcknowledgement() {
        when(gate.submitTracked(any())).thenReturn(
                new ApprovalGate.Submission(ApprovalGate.Route.HELD, CompletableFuture.completedFuture(true)),
                new ApprovalGate.Submission(ApprovalGate.Route.SHADOWED, new CompletableFuture<>()));

        ScheduledPolicySweeper.SweepReport report = sweeper(Duration.ofMillis(100)).runDue();

        assertThat(report.fires().get(0).recorded()).isTrue();
        assertThat(report.fires().get(0).counts().failed()).isZero();
    }
}
