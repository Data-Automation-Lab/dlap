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

package com.dlap.policyengine.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.approval.ApprovalService;
import com.dlap.policyengine.dsl.PolicyActionRequests;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-02, Q-439: the producer and the approval gate hand the scheduled sweep the delivery of each
 * fire, so a failed asynchronous send is visible to the caller, not only to a metric.
 */
class PolicyActionDeliveryTrackingTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private PolicyActionProducer producer(boolean enabled) {
        PolicyActionProducer p = new PolicyActionProducer(kafka, registry, new ObjectMapper(), enabled);
        ReflectionTestUtils.setField(p, "policyActionEventTopic", "dlap.policies.actions");
        return p;
    }

    private static PolicyActionEvent fire(String actionType) {
        PolicyDsl dsl = new PolicyDslParser().parse("apiVersion: dlap/v1\nkind: Policy\n"
                + "metadata: {id: p, name: P}\nspec:\n  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
                + "  match: {cloudProvider: GCP}\n  action: {type: " + actionType + "}\n");
        return PolicyActionRequests.forSchedule(dsl, UUID.randomUUID(),
                Map.of("assetId", "1f2d3a4b-0000-4000-8000-000000000001"), Instant.parse("2026-10-20T02:00:00Z"),
                Instant.parse("2026-10-20T03:00:00Z"));
    }

    @Test
    void anAcknowledgedSendIsDelivered() {
        CompletableFuture<SendResult<String, Object>> broker = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(broker);

        PolicyActionProducer.Dispatch d = producer(true).dispatch(fire("addLabel"));

        assertThat(d.outcome()).isEqualTo(PolicyActionProducer.Outcome.PUBLISHED);
        assertThat(d.delivered()).isNotDone();
        broker.complete(null);
        assertThat(d.delivered().join()).isTrue();
    }

    @Test
    void aSendTheBrokerRefusesLaterIsNotDeliveredAndIsCounted() {
        CompletableFuture<SendResult<String, Object>> broker = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(broker);

        PolicyActionProducer.Dispatch d = producer(true).dispatch(fire("addLabel"));
        broker.completeExceptionally(new IllegalStateException("broker down"));

        assertThat(d.outcome()).isEqualTo(PolicyActionProducer.Outcome.PUBLISHED);
        assertThat(d.delivered().join()).isFalse();
        assertThat(registry.find("policyengine.send.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aSendThatFailsBeforeTheHandOffIsNotDelivered() {
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("no metadata"));

        PolicyActionProducer.Dispatch d = producer(true).dispatch(fire("addLabel"));

        assertThat(d.outcome()).isEqualTo(PolicyActionProducer.Outcome.FAILED);
        assertThat(d.delivered().join()).isFalse();
    }

    @Test
    void shadowModeHasNothingToDeliver() {
        PolicyActionProducer.Dispatch d = producer(false).dispatch(fire("addLabel"));

        assertThat(d.outcome()).isEqualTo(PolicyActionProducer.Outcome.SHADOWED);
        assertThat(d.delivered().join()).isTrue();
    }

    @Test
    void theGatePassesTheDeliveryOfAnUngatedFireThrough() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        CompletableFuture<Boolean> delivery = new CompletableFuture<>();
        when(producer.actionsEnabled()).thenReturn(true);
        when(producer.dispatch(any())).thenReturn(new PolicyActionProducer.Dispatch(
                PolicyActionProducer.Outcome.PUBLISHED, delivery));
        ApprovalGate gate = new ApprovalGate(producer, mock(ApprovalService.class));

        ApprovalGate.Submission s = gate.submitTracked(fire("addLabel"));

        assertThat(s.route()).isEqualTo(ApprovalGate.Route.PUBLISHED);
        assertThat(s.delivered()).isSameAs(delivery);
    }

    @Test
    void aHeldFireIsDurableOnceRecorded() {
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(producer.actionsEnabled()).thenReturn(true);
        when(approvals.hold(any(), any(), any())).thenReturn(true);
        ApprovalGate gate = new ApprovalGate(producer, approvals);

        ApprovalGate.Submission s = gate.submitTracked(fire("archive"));

        assertThat(s.route()).isEqualTo(ApprovalGate.Route.HELD);
        assertThat(s.delivered().join()).isTrue();
        verify(producer, never()).dispatch(any());
    }
}
