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

package com.dlap.policyengine.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.model.EventSubscription;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.StreamEventLedger;
import com.dlap.policyengine.model.SubscriptionEventMatch;
import com.dlap.policyengine.repository.EventRuleRepository;
import com.dlap.policyengine.repository.EventSubscriptionRepository;
import com.dlap.policyengine.repository.StreamEventLedgerRepository;
import com.dlap.policyengine.repository.SubscriptionEventMatchRepository;
import com.dlap.policyengine.service.EventSubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RTR-01 (Q-824): the Event Center consumer is idempotent. Delivery on the lifecycle topics is at
 * least once (log-event.md section 5.5), and the consumer used to draw a random id for every
 * delivery, so a second copy of one message added a second feed row, a second subscription match and
 * a second notification. The consumer here runs against the real {@link EventSubscriptionService};
 * only the repositories are in-memory fakes that behave like the ledger table (a primary key on the id).
 */
class LifecycleEventStreamConsumerTest {

    private static final String DISCOVERED = DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED;
    private static final String ACTIONS = DlapConstants.KafkaTopic.POLICY_ACTION_EVENT;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, StreamEventLedger> ledger = new HashMap<>();
    private final AtomicInteger matches = new AtomicInteger();
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private StreamEventLedgerRepository ledgerRepository;
    private LifecycleEventStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        ledgerRepository = mock(StreamEventLedgerRepository.class);
        when(ledgerRepository.existsById(any())).thenAnswer(call -> ledger.containsKey(call.<UUID>getArgument(0)));
        when(ledgerRepository.save(any(StreamEventLedger.class))).thenAnswer(call -> {
            StreamEventLedger row = call.getArgument(0);
            ledger.put(row.getId(), row);
            return row;
        });
        EventSubscriptionRepository subscriptions = mock(EventSubscriptionRepository.class);
        EventSubscription everything = new EventSubscription("everything", UUID.randomUUID());
        everything.setId(UUID.randomUUID());
        everything.setStatus(EventSubscriptionStatus.ACTIVE);
        when(subscriptions.findByStatus(EventSubscriptionStatus.ACTIVE)).thenReturn(List.of(everything));
        SubscriptionEventMatchRepository matchRepository = mock(SubscriptionEventMatchRepository.class);
        when(matchRepository.save(any(SubscriptionEventMatch.class))).thenAnswer(call -> {
            matches.incrementAndGet();
            return call.getArgument(0);
        });
        EventSubscriptionService service = new EventSubscriptionService(subscriptions,
                mock(EventRuleRepository.class), ledgerRepository, matchRepository);
        consumer = new LifecycleEventStreamConsumer(service, mapper);
    }

    private static String logEvent(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"changeType\":\"CREATE\",\"targetResource\":"
                + "{\"id\":\"s3://b\",\"canonicalUri\":\"s3://b\"}}";
    }

    private static String policyAction(String fireId, String policyId) {
        return "{\"fireId\":\"" + fireId + "\",\"policyId\":\"" + policyId + "\","
                + "\"actionType\":\"alert.email\",\"assetId\":\"" + UUID.randomUUID() + "\"}";
    }

    @Test
    void aDuplicateOfADiscoveredEventAddsNoFeedRowNoMatchAndNoNotification() throws Exception {
        String payload = logEvent("a".repeat(64));

        consumer.onLifecycleEvent(payload, DISCOVERED, ack);
        consumer.onLifecycleEvent(payload, DISCOVERED, ack);
        // The same event in the legacy double encoding (a JSON string that holds the event).
        consumer.onLifecycleEvent(mapper.writeValueAsString(payload), DISCOVERED, ack);
        consumer.onLifecycleEvent(payload, DISCOVERED, ack);

        assertThat(ledger).as("one feed row for four copies").hasSize(1);
        assertThat(matches.get()).as("one subscription match, so one notification").isEqualTo(1);
        verify(ack, times(4)).acknowledge();
    }

    @Test
    void aDuplicateOfAPolicyActionIsRecognisedByItsFireId() {
        consumer.onLifecycleEvent(policyAction("fire-1", "p1"), ACTIONS, ack);
        // A redelivered fire carries the same fireId; a field that differs between copies does not matter.
        consumer.onLifecycleEvent(policyAction("fire-1", "p1"), ACTIONS, ack);

        assertThat(ledger).hasSize(1);
        assertThat(matches.get()).isEqualTo(1);
    }

    @Test
    void aMessageWithNoIdOfItsOwnIsRecognisedByItsText() {
        String payload = "{\"assetId\":\"" + UUID.randomUUID() + "\",\"actionType\":\"label\"}";

        consumer.onLifecycleEvent(payload, DlapConstants.KafkaTopic.LABEL_ASSIGNMENT_CHANGED, ack);
        consumer.onLifecycleEvent(payload, DlapConstants.KafkaTopic.LABEL_ASSIGNMENT_CHANGED, ack);

        assertThat(ledger).hasSize(1);
        assertThat(matches.get()).isEqualTo(1);
    }

    @Test
    void messagesThatDifferAreNotCollapsed() {
        consumer.onLifecycleEvent(logEvent("1".repeat(64)), DISCOVERED, ack);
        consumer.onLifecycleEvent(logEvent("2".repeat(64)), DISCOVERED, ack);
        // Two policies fired by one source event are two actions with two fire ids.
        consumer.onLifecycleEvent(policyAction("fire-a", "p1"), ACTIONS, ack);
        consumer.onLifecycleEvent(policyAction("fire-b", "p2"), ACTIONS, ack);
        // One id on two topics is two events.
        consumer.onLifecycleEvent(logEvent("1".repeat(64)), DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED, ack);

        assertThat(ledger).hasSize(5);
        assertThat(matches.get()).isEqualTo(5);
    }

    @Test
    void theFeedIdOfAMessageIsTheNameBasedIdOfItsTopicAndIdentity() {
        Map<String, Object> event = Map.of("eventId", "e-1");

        UUID id = LifecycleEventStreamConsumer.eventIdFor(DISCOVERED, event, "{\"eventId\":\"e-1\"}");

        assertThat(LifecycleEventStreamConsumer.eventIdFor(DISCOVERED, event, "{ \"eventId\": \"e-1\" }"))
                .as("the identity is the event id, not the spelling").isEqualTo(id);
        assertThat(id).as("pinned: ids already stored must keep matching")
                .isEqualTo(UUID.nameUUIDFromBytes(
                        "dlap-event-center|dlap.discovery.asset.discovered|eventId=e-1"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aRecordThatCannotBeRecordedIsNotAcknowledgedAndItsRetryRecordsItOnce() {
        String payload = logEvent("f".repeat(64));
        when(ledgerRepository.save(any(StreamEventLedger.class)))
                .thenThrow(new DataAccessResourceFailureException("database is down"))
                .thenAnswer(call -> {
                    StreamEventLedger row = call.getArgument(0);
                    ledger.put(row.getId(), row);
                    return row;
                });

        assertThatThrownBy(() -> consumer.onLifecycleEvent(payload, DISCOVERED, ack))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(ack, never()).acknowledge();
        assertThat(ledger).isEmpty();

        // The container redelivers the record; the second attempt records it, a third copy is a duplicate.
        consumer.onLifecycleEvent(payload, DISCOVERED, ack);
        consumer.onLifecycleEvent(payload, DISCOVERED, ack);

        verify(ack, times(2)).acknowledge();
        assertThat(ledger).hasSize(1);
        assertThat(matches.get()).isEqualTo(1);
    }

    @Test
    void aMessageThatIsNotJsonIsStillRecordedOnceAndAnEmptyOneIsDrained() {
        consumer.onLifecycleEvent("not json at all", DISCOVERED, ack);
        consumer.onLifecycleEvent("not json at all", DISCOVERED, ack);
        consumer.onLifecycleEvent(null, DISCOVERED, ack);
        consumer.onLifecycleEvent("  ", DISCOVERED, ack);

        assertThat(ledger).hasSize(1);
        verify(ack, times(4)).acknowledge();
    }
}
