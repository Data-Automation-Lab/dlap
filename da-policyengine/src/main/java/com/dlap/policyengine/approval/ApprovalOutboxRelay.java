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

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sends the approval outbox to Kafka (at least once). Messages go out oldest first; each send waits
 * for the broker's acknowledgment ({@code dlap.policyengine.approvals.send-timeout}) before the row
 * is marked sent, and the relay stops at the first failure so a request's messages never overtake
 * each other. A message sent twice carries the same {@code eventId} (approval events) or
 * {@code fireId} (released actions).
 *
 * <p>Run by {@link ApprovalMaintenance} under its cluster-wide lock.
 */
@Component
public class ApprovalOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ApprovalOutboxRelay.class);

    /** Counter of outbox sends, tagged {@code kind} and {@code result}. */
    static final String SENT_COUNTER = "policyengine.approvals.outbox";

    private final ApprovalStore store;
    private final KafkaTemplate<String, Object> kafka;
    private final ApprovalProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    /**
     * Creates the relay.
     *
     * @param store      the approval tables
     * @param kafka      the Kafka template (String values)
     * @param properties the settings
     * @param clock      the business clock
     * @param meters     metrics
     */
    public ApprovalOutboxRelay(ApprovalStore store, KafkaTemplate<String, Object> kafka, ApprovalProperties properties,
            Clock clock, MeterRegistry meters) {
        this.store = store;
        this.kafka = kafka;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Sends up to {@code relay-batch-size} waiting messages.
     *
     * @return how many were acknowledged
     */
    public int relay() {
        List<ApprovalStore.OutboxMessage> batch = store.unsent(properties.relayBatchSize());
        int sent = 0;
        for (ApprovalStore.OutboxMessage m : batch) {
            try {
                kafka.send(m.topic(), m.key(), m.payload())
                        .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(m, e);
                return sent;
            } catch (ExecutionException | TimeoutException | KafkaException e) {
                fail(m, e);
                return sent;
            }
            store.markSent(m.id(), clock.instant());
            if ("ACTION".equals(m.kind())) {
                store.markDispatched(m.requestId(), clock.instant());
                log.info("Approval request {} released its action on {}", m.requestId(), m.topic());
            }
            meters.counter(SENT_COUNTER, "kind", m.kind(), "result", "sent").increment();
            sent++;
        }
        return sent;
    }

    private void fail(ApprovalStore.OutboxMessage m, Exception e) {
        store.markFailed(m.id(), e.getClass().getSimpleName());
        meters.counter(SENT_COUNTER, "kind", m.kind(), "result", "failed").increment();
        log.warn("Approval outbox message {} ({} for request {}) not sent, retried on the next tick: {}", m.id(),
                m.kind(), m.requestId(), e.getClass().getSimpleName());
    }
}
