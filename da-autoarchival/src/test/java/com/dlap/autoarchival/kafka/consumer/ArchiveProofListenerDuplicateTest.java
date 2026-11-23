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

package com.dlap.autoarchival.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveTaskSync;
import com.dlap.autoarchival.executor.InMemoryRules;
import com.dlap.autoarchival.proof.ArchiveProofService;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;

/**
 * RTR-01 (Q-824): a duplicate of a provider log event has no second effect on
 * {@link ArchiveProofListener}'s state. Delivery on {@code dlap.log.event.normalized} is at least once
 * (log-event.md section 5.5). The listener and {@link ArchiveProofService} are the service's own over
 * the map-backed repository, which keeps the database's conditional update (the claim is the status
 * the proof loaded); the audit log and the task sync are counted. The clock moves between deliveries,
 * so a second application would show in the stored times. The conditional update itself is raced on
 * PostgreSQL in {@code ArchiveRulesPostgresTest}.
 */
class ArchiveProofListenerDuplicateTest {

    private static final Instant APPLIED = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant EVENT = Instant.parse("2026-11-02T00:05:00Z");
    private static final Instant FIRST_DELIVERY = Instant.parse("2026-11-02T00:06:00Z");

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(FIRST_DELIVERY);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final Map<String, ArchiveRuleEntity> store = new HashMap<>();
    private final ArchiveRuleRepository rules = InMemoryRules.over(store);
    private final ArchiveTaskSync sync = mock(ArchiveTaskSync.class);
    private final ArchiveAudit audit = mock(ArchiveAudit.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final MovableClock clock = new MovableClock();
    private ArchiveProofListener listener;

    @BeforeEach
    void setUp() {
        listener = new ArchiveProofListener(new ArchiveProofService(rules, sync, audit, clock, new ArchiveRuleProperties()));
    }

    private ArchiveRuleEntity applied(String ruleId, String taskId, Instant appliedAt) {
        ArchiveRuleEntity r = new ArchiveRuleEntity(ruleId, "appr-" + ruleId, "fire-" + ruleId, taskId, "asset-" + ruleId,
                "s3://pilot/finance/", "AWS", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "GLACIER_IR", 0,
                appliedAt);
        r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, appliedAt);
        store.put(ruleId, r);
        return r;
    }

    private static DlapLogEvent transition(String eventId, Instant at) {
        return DlapLogEvent.newBuilder().setEventId(eventId).setOperation("LifecycleTransition")
                .setEventTime(Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
                .setSourceProvider(SourceProvider.AWS).setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("s3://pilot/finance/q1.csv"))
                .build();
    }

    private void deliver(Object value) {
        listener.onLogEvent(new ConsumerRecord<>("dlap.log.event.normalized", 0, 0L, "k", value), ack);
    }

    @Test
    void aDuplicateOfTheProofEventChangesNothingAuditsNothingAndSyncsNothing() {
        ArchiveRuleEntity row = applied("dlap-archive-aaaaaaaaaaaa", "task-1", APPLIED);
        DlapLogEvent proof = transition("ct-77", EVENT);

        deliver(codec.toMap(proof));
        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
        assertThat(row.getEvidenceEventId()).isEqualTo("ct-77");
        Instant executedAt = row.getExecutedAt();
        assertThat(executedAt).as("business time is the provider's event time").isEqualTo(EVENT);

        // The copies arrive later, in both wire forms.
        clock.set(FIRST_DELIVERY.plus(Duration.ofHours(5)));
        deliver(codec.toMap(proof));
        deliver(codec.encode(proof));
        clock.set(FIRST_DELIVERY.plus(Duration.ofDays(2)));
        deliver(codec.toMap(proof));

        assertThat(row.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);
        assertThat(row.getEvidenceEventId()).isEqualTo("ct-77");
        assertThat(row.getEvidenceEventTime()).isEqualTo(EVENT);
        assertThat(row.getExecutedAt()).isEqualTo(executedAt);
        verify(audit, times(1)).executed(row, null);
        verify(sync, times(1)).sync("task-1");
        verify(ack, times(4)).acknowledge();
    }

    @Test
    void aDuplicateThatArrivesAfterTheRuleWasAppliedAgainDoesNotProveTheNewRule() {
        ArchiveRuleEntity first = applied("dlap-archive-aaaaaaaaaaaa", "task-1", APPLIED);
        DlapLogEvent proof = transition("ct-78", EVENT);
        deliver(codec.toMap(proof));
        assertThat(first.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_EXECUTED);

        // The operator applies a new rule for the same scope after the event happened; the late copy
        // of the old event is from before that rule began, so it is not evidence for it.
        ArchiveRuleEntity second = applied("dlap-archive-bbbbbbbbbbbb", "task-2", EVENT.plus(Duration.ofHours(1)));
        clock.set(FIRST_DELIVERY.plus(Duration.ofHours(2)));
        deliver(codec.toMap(proof));

        assertThat(second.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        verify(audit, times(1)).executed(first, null);
        verify(sync, times(1)).sync("task-1");
    }
}
