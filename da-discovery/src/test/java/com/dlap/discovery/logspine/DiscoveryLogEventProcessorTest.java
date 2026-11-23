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

package com.dlap.discovery.logspine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.common.errors.NetworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaNull;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The router reads both wire forms of {@code dlap.log.event.normalized} through
 * {@link DlapLogEventCodec} (ADR-0045 dual read), forwards the event exactly as received, keys the
 * typed topics by the canonical URI, and counts every record it acknowledges without routing.
 */
class DiscoveryLogEventProcessorTest {

    private static final String INBOUND = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private KafkaTemplate<String, Object> kafka;
    private Acknowledgment ack;
    private DiscoveryLogEventProcessor router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = RouterKafkaMocks.acceptingTemplate();
        ack = mock(Acknowledgment.class);
        router = new DiscoveryLogEventProcessor(kafka, objectMapper, meters);
    }

    private static final String LEGACY_BRIDGE_EVENT = """
            {"eventId":"e-1","changeType":"CREATE","source":"crawler","operation":"DISCOVERY",
             "sourceProvider":"GCP","eventTime":"2026-10-19T09:00:00Z",
             "targetResource":{"id":"gs://demo-bucket","kind":"GCS_BUCKET","canonicalUri":"gs://demo-bucket",
                               "sizeBytes":"0","createdTime":"2026-01-15T12:00:00Z"}}
            """;

    private DlapLogEvent contractEvent(ChangeType type, String canonical, String id) {
        return DlapLogEvent.newBuilder()
                .setEventId("a".repeat(64))
                .setChangeType(type)
                .setSourceProvider(SourceProvider.AWS)
                .setTargetResource(TargetResource.newBuilder().setId(id).setCanonicalUri(canonical))
                .build();
    }

    private Map<String, Object> asMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() { });
    }

    private double routed(String topic, boolean legacy) {
        var counter = meters.find(DiscoveryLogEventProcessor.METER_ROUTED)
                .tag("topic", topic).tag("legacy", Boolean.toString(legacy)).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double dropped(String reason) {
        var counter = meters.find(DiscoveryLogEventProcessor.METER_DROPPED).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ---- duplicates (Q-783) --------------------------------------------------------------------

    @Test
    void aDuplicateOfAnEventIsForwardedExactlyLikeTheFirstCopy() throws Exception {
        String json = codec.encode(contractEvent(ChangeType.ACCESS, "s3://dlap-demo/a.csv", "a.csv"));

        // The same event twice, once per wire form, as a producer killed mid-batch leaves them.
        router.route(asMap(json), INBOUND, 100L, ack);
        router.route(asMap(json), INBOUND, 101L, ack);
        router.route(objectMapper.writeValueAsString(json), INBOUND, 102L, ack);

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafka, org.mockito.Mockito.times(3)).send(topic.capture(), key.capture(), value.capture());
        assertThat(topic.getAllValues()).containsOnly(DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED);
        assertThat(key.getAllValues()).as("one partition key, so both copies keep their order")
                .containsOnly("s3://dlap-demo/a.csv");
        assertThat(value.getAllValues()).allSatisfy(v -> assertThat((JsonNode) v).isEqualTo(objectMapper.readTree(json)));
        verify(ack, org.mockito.Mockito.times(3)).acknowledge();
    }

    @Test
    void aPassThroughAndTheReplayedFullEventOfOneIdAreRoutedOnTheirOwnMerits() throws Exception {
        // Q-363: a pass-through (no change type) and the full event share an event_id. The first is
        // dropped and counted, the second is routed, in either order.
        DlapLogEvent full = contractEvent(ChangeType.ACCESS, "s3://dlap-demo/b.csv", "b.csv");
        DlapLogEvent partial = full.toBuilder().setChangeType(ChangeType.CHANGE_TYPE_UNSPECIFIED)
                .setParseStatus(com.dlap.proto.log.v1.ParseStatus.PARSE_STATUS_PARTIAL).build();

        router.route(asMap(codec.encode(partial)), INBOUND, 200L, ack);
        router.route(asMap(codec.encode(full)), INBOUND, 201L, ack);
        router.route(asMap(codec.encode(partial)), INBOUND, 202L, ack);

        verify(kafka, org.mockito.Mockito.times(1)).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED),
                eq("s3://dlap-demo/b.csv"), any());
        assertThat(dropped(DiscoveryLogEventProcessor.REASON_MISSING_CHANGE_TYPE)
                + dropped(DiscoveryLogEventProcessor.REASON_UNKNOWN_CHANGE_TYPE)).isEqualTo(2.0);
    }

    // ---- dual read ---------------------------------------------------------------------------

    @Test
    void routesTheLegacyDoubleEncodedEventAsAString() throws Exception {
        // The container's JSON deserializer removed the outer string; the event text arrives as a String.
        router.route(LEGACY_BRIDGE_EVENT, INBOUND, 7L, ack);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), eq("gs://demo-bucket"), value.capture());
        verify(ack).acknowledge();
        assertThat((JsonNode) value.getValue()).isEqualTo(objectMapper.readTree(LEGACY_BRIDGE_EVENT));
        assertThat(routed(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED, true)).isEqualTo(1.0);
    }

    @Test
    void routesTheSingleEncodedEventAsAMap() throws Exception {
        String json = codec.encode(contractEvent(ChangeType.UPDATE, "s3://dlap-demo", "dlap-demo"));

        router.route(asMap(json), INBOUND, 8L, ack);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED), eq("s3://dlap-demo"), value.capture());
        verify(ack).acknowledge();
        assertThat((JsonNode) value.getValue()).isEqualTo(objectMapper.readTree(json));
        assertThat(routed(DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED, false)).isEqualTo(1.0);
    }

    @Test
    void aStringThatIsItselfJsonWrappedIsUnwrappedBeforeForwarding() throws Exception {
        String wrapped = objectMapper.writeValueAsString(LEGACY_BRIDGE_EVENT);

        router.route(wrapped, INBOUND, 9L, ack);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), any(), value.capture());
        assertThat(((JsonNode) value.getValue()).path("eventId").asText()).isEqualTo("e-1");
    }

    @ParameterizedTest
    @CsvSource({
            "CREATE,dlap.discovery.asset.discovered",
            "UPDATE,dlap.discovery.asset.modified",
            "DELETE,dlap.discovery.asset.deleted",
            "ACCESS,dlap.discovery.asset.accessed",
            "PERMISSION,dlap.discovery.asset.permission-changed",
            "ARCHIVE,dlap.discovery.asset.archived"})
    void everyChangeTypeGoesToItsTypedTopic(String changeType, String expectedTopic) throws Exception {
        String json = codec.encode(contractEvent(ChangeType.valueOf(changeType), "s3://b", "b"));

        router.route(asMap(json), INBOUND, 1L, ack);

        verify(kafka).send(eq(expectedTopic), any(), any());
    }

    // ---- keys and forwarding -----------------------------------------------------------------

    @Test
    void theTypedTopicIsKeyedByTheCanonicalUriThenTheTargetIdThenTheEventId() throws Exception {
        router.route(asMap(codec.encode(contractEvent(ChangeType.ACCESS, "s3://canon", "native-id"))), INBOUND, 1L, ack);
        router.route(asMap(codec.encode(contractEvent(ChangeType.ACCESS, "", "native-id"))), INBOUND, 2L, ack);
        router.route(asMap(codec.encode(contractEvent(ChangeType.ACCESS, "", ""))), INBOUND, 3L, ack);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(kafka, org.mockito.Mockito.times(3)).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED),
                keys.capture(), any());
        assertThat(keys.getAllValues()).containsExactly("s3://canon", "native-id", "a".repeat(64));
    }

    @Test
    void anEventWithoutAnyKeyIsStillRoutedWithoutAKey() throws Exception {
        router.route("{\"changeType\":\"CREATE\"}", INBOUND, 1L, ack);

        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED), eq(null), any());
        verify(ack).acknowledge();
    }

    @Test
    void theEventIsForwardedExactlyAsReceivedIncludingKeysThisBuildDoesNotKnow() throws Exception {
        String withFutureField = "{\"changeType\":\"DELETE\",\"eventId\":\"e-2\",\"fieldFromTomorrow\":{\"x\":1},"
                + "\"targetResourceId\":\"legacy-flat-id\"}";

        router.route(withFutureField, INBOUND, 1L, ack);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        // The flat legacy key still keys the topic (the codec maps it to the target id).
        verify(kafka).send(eq(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED), eq("legacy-flat-id"), value.capture());
        assertThat((JsonNode) value.getValue()).isEqualTo(objectMapper.readTree(withFutureField));
    }

    // ---- no lost events: acknowledge only after the send succeeded (Q-823) ------------------------

    private static final String CREATE_EVENT = "{\"eventId\":\"e-9\",\"changeType\":\"CREATE\","
            + "\"targetResource\":{\"id\":\"s3://b\",\"canonicalUri\":\"s3://b\"}}";

    private double sendFailed(String reason) {
        var counter = meters.find(DiscoveryLogEventProcessor.METER_SEND_FAILED).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void aSendTheBrokerRefusedIsNotAcknowledgedSoTheErrorHandlerRetriesIt() {
        doReturn(CompletableFuture.failedFuture(new NetworkException("broker is down"))).when(kafka)
                .send(any(String.class), any(), any());

        assertThatThrownBy(() -> router.route(CREATE_EVENT, INBOUND, 7L, ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("route failed")
                .hasRootCauseInstanceOf(NetworkException.class);

        verify(ack, never()).acknowledge();
        assertThat(sendFailed(DiscoveryLogEventProcessor.SEND_ERROR)).isEqualTo(1.0);
        assertThat(routed(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED, true))
                .as("a failed send is not counted as routed").isZero();
    }

    @Test
    void aSendThatThrowsInsteadOfReturningAFailedFutureIsNotAcknowledged() {
        doThrow(new IllegalStateException("producer is closed")).when(kafka)
                .send(any(String.class), any(), any());

        assertThatThrownBy(() -> router.route(CREATE_EVENT, INBOUND, 8L, ack)).isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
        assertThat(sendFailed(DiscoveryLogEventProcessor.SEND_ERROR)).isEqualTo(1.0);
    }

    @Test
    void aSendWithNoAcknowledgementWithinTheTimeoutIsNotAcknowledged() {
        // A future nobody completes: the broker never answers.
        doReturn(new CompletableFuture<>()).when(kafka).send(any(String.class), any(), any());
        DiscoveryLogEventProcessor impatient =
                new DiscoveryLogEventProcessor(kafka, objectMapper, meters, Duration.ofMillis(50));

        assertThatThrownBy(() -> impatient.route(CREATE_EVENT, INBOUND, 9L, ack))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);

        verify(ack, never()).acknowledge();
        assertThat(sendFailed(DiscoveryLogEventProcessor.SEND_TIMEOUT)).isEqualTo(1.0);
    }

    @Test
    void aProducerThatReturnsNoFutureIsNotAcknowledged() {
        doReturn(null).when(kafka).send(any(String.class), any(), any());

        assertThatThrownBy(() -> router.route(CREATE_EVENT, INBOUND, 10L, ack)).isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    void theRecordIsAcknowledgedOnlyAfterTheBrokerAcknowledgedTheSend() {
        CompletableFuture<Object> brokerAnswer = new CompletableFuture<>();
        doReturn(brokerAnswer).when(kafka).send(any(String.class), any(), any());
        AtomicBoolean sentWhenAcknowledged = new AtomicBoolean();
        doAnswer(call -> {
            sentWhenAcknowledged.set(brokerAnswer.isDone());
            return null;
        }).when(ack).acknowledge();
        // The broker answers 100 ms after the send was issued.
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            brokerAnswer.complete(null);
        }).start();

        router.route(CREATE_EVENT, INBOUND, 11L, ack);

        verify(ack).acknowledge();
        assertThat(sentWhenAcknowledged).as("the future was already done when the ack happened").isTrue();
        assertThat(routed(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED, true)).isEqualTo(1.0);
        assertThat(meters.find(DiscoveryLogEventProcessor.METER_SEND_FAILED).counters()).isEmpty();
    }

    @Test
    void anInterruptedWaitIsNotAcknowledgedAndKeepsTheInterruptFlag() {
        doReturn(new CompletableFuture<>()).when(kafka).send(any(String.class), any(), any());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> router.route(CREATE_EVENT, INBOUND, 12L, ack)).isInstanceOf(RuntimeException.class);

            assertThat(Thread.currentThread().isInterrupted()).as("the flag is restored for the container").isTrue();
            verify(ack, never()).acknowledge();
        } finally {
            Thread.interrupted();
        }
    }

    // ---- drops are counted -------------------------------------------------------------------

    @Test
    void emptyPayloadsAreAcknowledgedAndCounted() {
        router.route(null, INBOUND, 1L, ack);
        router.route("   ", INBOUND, 2L, ack);
        router.route(Map.of(), INBOUND, 3L, ack);
        router.route(KafkaNull.INSTANCE, INBOUND, 4L, ack);
        router.route(new byte[0], INBOUND, 5L, ack);

        verify(kafka, never()).send(any(String.class), any(), any());
        verify(ack, org.mockito.Mockito.times(5)).acknowledge();
        assertThat(dropped(DiscoveryLogEventProcessor.REASON_EMPTY_PAYLOAD)).isEqualTo(5.0);
    }

    @Test
    void anEventWithoutAChangeTypeIsAcknowledgedAndCountedNotDeadLettered() {
        // A PARSE_STATUS_PARTIAL pass-through event legitimately has none (log-event.md section 4.5).
        router.route("{\"eventId\":\"e-3\",\"targetResource\":{\"id\":\"b\"}}", INBOUND, 1L, ack);

        verify(kafka, never()).send(any(String.class), any(), any());
        verify(ack).acknowledge();
        assertThat(dropped(DiscoveryLogEventProcessor.REASON_MISSING_CHANGE_TYPE)).isEqualTo(1.0);
    }

    @Test
    void anUnrecognisedChangeTypeIsAcknowledgedAndCountedSeparately() {
        router.route("{\"eventId\":\"e-4\",\"changeType\":\"SHRED\"}", INBOUND, 1L, ack);

        verify(kafka, never()).send(any(String.class), any(), any());
        verify(ack).acknowledge();
        assertThat(dropped(DiscoveryLogEventProcessor.REASON_UNKNOWN_CHANGE_TYPE)).isEqualTo(1.0);
        assertThat(dropped(DiscoveryLogEventProcessor.REASON_MISSING_CHANGE_TYPE)).isZero();
    }

    @Test
    void aRoutedEventIsNotCountedAsDropped() {
        router.route(LEGACY_BRIDGE_EVENT, INBOUND, 1L, ack);

        assertThat(meters.find(DiscoveryLogEventProcessor.METER_DROPPED).counters()).isEmpty();
    }

    // ---- undecodable records go to the error handler -----------------------------------------

    @Test
    void aPayloadThatIsNotJsonIsNotAcknowledgedSoTheErrorHandlerDeadLettersIt() {
        assertThatThrownBy(() -> router.route("this is not json", INBOUND, 1L, ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("route failed");

        verify(ack, never()).acknowledge();
        verify(kafka, never()).send(any(String.class), any(), any());
        assertThat(meters.find(DiscoveryLogEventProcessor.METER_DROPPED).counters()).isEmpty();
    }

    @Test
    void aPayloadThatIsNotAnObjectIsNotAcknowledged() {
        assertThatThrownBy(() -> router.route("[1,2,3]", INBOUND, 1L, ack))
                .isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    void aPayloadTypeNobodyProducesIsNotAcknowledged() {
        assertThatThrownBy(() -> router.route(42, INBOUND, 1L, ack)).isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    void aFieldThatCannotHoldItsValueIsNotAcknowledged() {
        assertThatThrownBy(() -> router.route(
                "{\"changeType\":\"CREATE\",\"eventTime\":\"not a timestamp\"}", INBOUND, 1L, ack))
                .isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }
}
