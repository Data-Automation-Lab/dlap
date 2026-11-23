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

package com.dlap.reporting.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.reporting.drift.DecisionRecord;
import com.dlap.reporting.drift.DriftDetectionOrchestrator;
import com.dlap.reporting.drift.DriftEventProjector;
import com.dlap.reporting.drift.DriftEventSkips;
import com.dlap.reporting.drift.ReadRecord;
import com.dlap.reporting.identity.PrincipalIdentityResolver;
import com.dlap.reporting.identity.ResolvedPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The two drift listeners: every skip is acknowledged and counted, an accepted event reaches the
 * orchestrator before the acknowledgment, and a persistence failure is rethrown unacknowledged so
 * the container retries and then dead-letters.
 */
class DriftConsumersTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant T = Instant.parse("2026-10-19T08:30:00Z");

    private final DriftDetectionOrchestrator orchestrator = mock(DriftDetectionOrchestrator.class);
    private final PrincipalIdentityResolver identities = mock(PrincipalIdentityResolver.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private AssetAccessedConsumer reads;
    private AccessDecisionConsumer decisions;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        DriftEventProjector projector = new DriftEventProjector(identities, meters,
                beans.getBeanProvider(Clock.class), 300L);
        DriftEventSkips skips = new DriftEventSkips(meters);
        reads = new AssetAccessedConsumer(orchestrator, projector, skips);
        decisions = new AccessDecisionConsumer(orchestrator, projector, skips);
        when(identities.resolve(any(), anyString()))
                .thenAnswer(inv -> Optional.of(new ResolvedPrincipal("svc-a", inv.getArgument(1), true)));
    }

    private static Map<String, Object> read(String eventTime) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", "evt-1");
        if (eventTime != null) {
            e.put("eventTime", eventTime);
        }
        e.put("changeType", "ACCESS");
        e.put("targetResource", Map.of("id", "asset-1"));
        e.put("principal", Map.of("id", "cloud-principal"));
        return e;
    }

    private static Map<String, Object> decision(String timestamp) {
        Map<String, Object> e = new HashMap<>();
        e.put("decisionId", "dec-1");
        e.put("subjectId", "svc-a");
        e.put("assetId", "asset-1");
        e.put("decision", "ALLOW");
        if (timestamp != null) {
            e.put("timestamp", timestamp);
        }
        return e;
    }

    private static ConsumerRecord<String, Object> record(String topic, Object value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    private double skipped(String stream, String reason) {
        return meters.counter(DriftEventSkips.SKIPPED_COUNTER, "stream", stream, "reason", reason).count();
    }

    @Test
    void read_isRecordedWithItsEventTime_thenAcknowledged() {
        reads.onAssetAccessed(record("dlap.discovery.asset.accessed", read(T.toString())), ack);

        verify(orchestrator).recordReadAndDetect(new ReadRecord("evt-1", "svc-a", "asset-1", T));
        verify(ack).acknowledge();
    }

    @Test
    void readWithoutEventTime_isAcknowledgedAndCounted_notStampedWithTheClock() {
        reads.onAssetAccessed(record("dlap.discovery.asset.accessed", read(null)), ack);

        verify(orchestrator, never()).recordReadAndDetect(any());
        verify(ack).acknowledge();
        assertThat(skipped("read", "missing_event_time")).isEqualTo(1.0);
    }

    @Test
    void nullRead_isAcknowledgedAndCounted() {
        reads.onAssetAccessed(record("dlap.discovery.asset.accessed", null), ack);

        verify(ack).acknowledge();
        assertThat(skipped("read", "null_payload")).isEqualTo(1.0);
    }

    @Test
    void readPersistenceFailure_isRethrownWithoutAcknowledging() {
        when(orchestrator.recordReadAndDetect(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> reads.onAssetAccessed(record("dlap.discovery.asset.accessed", read(T.toString())), ack))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    void decision_isRecordedWithItsTimestamp_thenAcknowledged() {
        decisions.onDecision(record("dlap.access.decision", decision(T.toString())), ack);

        verify(orchestrator).recordDecision(new DecisionRecord("dec-1", "svc-a", "asset-1", true, T));
        verify(ack).acknowledge();
    }

    @Test
    void decisionWithoutTimestamp_isAcknowledgedAndCounted_notStampedWithTheClock() {
        decisions.onDecision(record("dlap.access.decision", decision(null)), ack);

        verify(orchestrator, never()).recordDecision(any());
        verify(ack).acknowledge();
        assertThat(skipped("decision", "missing_event_time")).isEqualTo(1.0);
    }

    @Test
    void decisionWithUnparsableTimestamp_isAcknowledgedAndCounted() {
        decisions.onDecision(record("dlap.access.decision", decision("not-a-time")), ack);

        verify(ack).acknowledge();
        assertThat(skipped("decision", "unparsable_event_time")).isEqualTo(1.0);
    }

    @Test
    void decisionPersistenceFailure_isRethrownWithoutAcknowledging() {
        when(orchestrator.recordDecision(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> decisions.onDecision(record("dlap.access.decision", decision(T.toString())), ack))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(ack, never()).acknowledge();
    }
}
