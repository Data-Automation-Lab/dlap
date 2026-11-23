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

package com.dlap.policyengine.approval;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The approval outbox is sent oldest first, marked only after the broker acknowledged, and the
 * relay stops at the first failure; {@link ApprovalMaintenance} never throws.
 */
class ApprovalOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final ApprovalStore store = mock(ApprovalStore.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ApprovalOutboxRelay relay = new ApprovalOutboxRelay(store, kafka, ApprovalProperties.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC), meters);

    private static ApprovalStore.OutboxMessage message(String kind, UUID request) {
        return new ApprovalStore.OutboxMessage(UUID.randomUUID(), request, kind, "dlap.approvals.decided",
                request.toString(), "{\"k\":\"" + kind + "\"}", NOW);
    }

    private static CompletableFuture<SendResult<String, Object>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    @Test
    void everyMessageIsSentInOrderAndMarkedAfterTheAck() {
        UUID request = UUID.randomUUID();
        ApprovalStore.OutboxMessage decided = message("DECIDED", request);
        ApprovalStore.OutboxMessage action = message("ACTION", request);
        when(store.unsent(50)).thenReturn(List.of(decided, action));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(acked());

        assertThat(relay.relay()).isEqualTo(2);

        InOrder order = inOrder(kafka, store);
        order.verify(kafka).send("dlap.approvals.decided", request.toString(), decided.payload());
        order.verify(store).markSent(decided.id(), NOW);
        order.verify(kafka).send("dlap.approvals.decided", request.toString(), action.payload());
        order.verify(store).markSent(action.id(), NOW);
        order.verify(store).markDispatched(request, NOW);
        assertThat(meters.counter(ApprovalOutboxRelay.SENT_COUNTER, "kind", "ACTION", "result", "sent").count())
                .isEqualTo(1.0);
    }

    @Test
    void aFailedSendStaysInTheOutboxAndStopsTheBatch() {
        UUID request = UUID.randomUUID();
        ApprovalStore.OutboxMessage first = message("DECIDED", request);
        ApprovalStore.OutboxMessage second = message("ACTION", request);
        when(store.unsent(50)).thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        assertThat(relay.relay()).isZero();

        verify(store).markFailed(first.id(), "ExecutionException");
        verify(store, never()).markSent(any(), any());
        verify(kafka, never()).send(anyString(), anyString(), eq(second.payload()));
        verify(store, never()).markDispatched(any(), any());
    }

    @Test
    void theMaintenanceTickNeverThrows() {
        ApprovalService service = mock(ApprovalService.class);
        ApprovalOutboxRelay failingRelay = mock(ApprovalOutboxRelay.class);
        when(service.expireOverdue()).thenThrow(new IllegalStateException("db down"));
        doThrow(new IllegalStateException("kafka down")).when(failingRelay).relay();
        ApprovalMaintenance job = new ApprovalMaintenance(service, failingRelay, meters);

        job.tick();

        verify(failingRelay).relay();
        assertThat(meters.counter("policyengine.approvals.maintenance", "result", "expiry-error").count()).isEqualTo(1.0);
        assertThat(meters.counter("policyengine.approvals.maintenance", "result", "relay-error").count()).isEqualTo(1.0);
    }
}
