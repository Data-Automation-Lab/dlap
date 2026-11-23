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

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DecodedLogEvent;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.discovery.config.KafkaReliabilityConfiguration;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.stereotype.Component;

/**
 * Central router for the log-event spine (ADR-0021).
 *
 * <p>Subscribes to {@link DlapConstants.KafkaTopic#LOG_EVENT_NORMALIZED}
 * (produced by per-cloud {@code *LogSource} adapters in
 * {@code da-logingest}, plus crawlers that emit synthetic
 * {@code DlapLogEvent}s with {@code source=crawler}), reads the
 * {@code changeType} field, and republishes the same payload onto
 * exactly one of the six typed lifecycle topics:
 *
 * <ul>
 *   <li>{@code CREATE}     → {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_DISCOVERED}</li>
 *   <li>{@code UPDATE}     → {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_MODIFIED}</li>
 *   <li>{@code DELETE}     → {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_DELETED}</li>
 *   <li>{@code ACCESS}     → {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_ACCESSED}</li>
 *   <li>{@code PERMISSION} → {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_PERMISSION_CHANGED}</li>
 *   <li>(cloud-side archive observation) → {@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_ARCHIVED}</li>
 * </ul>
 *
 * <p>This is the only place in the platform that knows the mapping
 * from {@code DlapLogEvent.changeType} to lifecycle topic. Downstream
 * services subscribe to the typed topics; they do not see the raw
 * normalized stream.
 *
 * <p><b>Dual read (ADR-0045).</b> The listener reads every record through
 * {@link DlapLogEventCodec}, so it accepts both wire forms. The container factory
 * deserializes the value as JSON: a producer that sends the event as a pre-serialized
 * String through Spring's JSON serializer (the legacy double encoding: the crawler
 * bridge, the log-analyzer publisher, da-logingest) arrives here as a {@code String},
 * and a producer that sends the JSON object (the single encoding SPINE-01 switches to)
 * arrives as a {@code Map}. The codec also reads the legacy producer shapes, so the
 * router keeps working while producers switch one by one. The payload is forwarded to
 * the typed topic exactly as it was received (as the JSON object it holds), so every
 * downstream consumer still sees the producer's own keys.
 *
 * <p><b>Drops are counted.</b> A record that is acknowledged without being routed (an
 * empty payload, no change type, a change type no typed topic carries) increments
 * {@value #METER_DROPPED} with the reason; routed records increment {@value #METER_ROUTED}
 * with the typed topic and whether the producer used a legacy form, which is how the
 * double-encoding retirement is tracked. A record that cannot be decoded is not
 * acknowledged: the error handler retries it and then dead-letters it.
 *
 * <p>The processor is intentionally stateless and Kafka-only - no DB
 * writes, no Feign calls. State updates happen in the services that
 * subscribe to the typed topics ({@code da-catalog}'s per-topic
 * listeners are the state of record).
 *
 * <p>Idempotency: routing is keyed by the asset ({@link LogEventSemantics#partitionKey}),
 * and the event carries {@code DlapLogEvent.event_id}, which is stable across
 * re-normalization per {@code docs/specs/log-event.md}. Consumers receive duplicate
 * event-ids on the typed topics and must de-dupe accordingly.
 *
 * <p>Duplicates are expected, not exceptional (Q-783): da-logingest publishes inside its database
 * transaction, so a kill can leave two messages for one event_id, and this listener is at-least-once
 * like every other. The router is a pure function of the record, so a duplicate is forwarded exactly
 * as the first copy was: same typed topic, same partition key (so both copies keep their place in
 * the asset's order) and same payload. It keeps no state of its own and so has nothing to
 * de-duplicate; every state-changing consumer downstream claims the event_id before it applies the
 * event (docs/specs/log-event.md section 5.4).
 *
 * <p><b>No lost events (Q-823).</b> The record is acknowledged only after the broker acknowledged the
 * forward send. {@link #route} waits for the send future for at most the configured timeout
 * ({@value SpineEvents#PUBLISH_TIMEOUT_PROPERTY}, default 30 s). A send that fails or times out
 * counts {@value #METER_SEND_FAILED}, leaves the record unacknowledged and throws, so the container's
 * error handler retries it and then dead-letters it to {@code <topic>.DLT}. A send that times out may
 * still reach the broker later, which publishes the event twice; that is the duplicate the consumers
 * already handle. The cost is one broker round trip per record on the listener thread (the producer is flushed
 * before the wait, so the record does not sit out {@code linger.ms}) instead of the producer's
 * batching; the figures are in {@code RouterSendThroughputBrokerTest}.
 */
@Component
@Slf4j
public class DiscoveryLogEventProcessor {

    /**
     * Records routed onto a typed topic. Tags: {@code topic} (the typed topic) and {@code legacy}
     * ({@code true} when the producer used the double encoding or a pre-ADR-0045 shape).
     * Prometheus name: {@code dlap_discovery_router_routed_total}.
     */
    public static final String METER_ROUTED = "dlap.discovery.router.routed";

    /**
     * Records acknowledged without being routed. Tag: {@code reason}, one of
     * {@value #REASON_EMPTY_PAYLOAD}, {@value #REASON_MISSING_CHANGE_TYPE} and
     * {@value #REASON_UNKNOWN_CHANGE_TYPE}. Prometheus name: {@code dlap_discovery_router_dropped_total}.
     */
    public static final String METER_DROPPED = "dlap.discovery.router.dropped";

    /**
     * Forward sends the broker did not acknowledge, so the record was left unacknowledged for the
     * error handler. Tag: {@code reason}, {@value #SEND_TIMEOUT} or {@value #SEND_ERROR}.
     * Prometheus name: {@code dlap_discovery_router_send_failed_total}.
     */
    public static final String METER_SEND_FAILED = "dlap.discovery.router.send.failed";

    /** Send-failure reason: no acknowledgement within the timeout. */
    public static final String SEND_TIMEOUT = "timeout";

    /** Send-failure reason: the broker or the producer refused the record. */
    public static final String SEND_ERROR = "error";

    /** Drop reason: no value, or a blank or empty one. */
    public static final String REASON_EMPTY_PAYLOAD = "empty_payload";

    /** Drop reason: the event names no change type (a pass-through event, or a producer that omitted it). */
    public static final String REASON_MISSING_CHANGE_TYPE = "missing_change_type";

    /** Drop reason: the event names a change type that no typed topic carries. */
    public static final String REASON_UNKNOWN_CHANGE_TYPE = "unknown_change_type";

    /** Special {@code changeType} value reserved for cloud-side archive observation events. */
    private static final String CHANGE_TYPE_ARCHIVE = "ARCHIVE";

    /** String wraps unwrapped before giving up; the same limit the codec applies. */
    private static final int MAX_STRING_WRAPS = 2;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Duration sendTimeout;

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the router.
     *
     * @param kafkaTemplate the template for the typed topics (JSON serializer)
     * @param objectMapper  parses the payload into the tree that is forwarded
     * @param meterRegistry counts routed, dropped and failed records
     * @param sendTimeout   how long {@link #route} waits for the broker's acknowledgement of the
     *                      forward send before it fails the record
     */
    @Autowired
    public DiscoveryLogEventProcessor(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper,
                                      MeterRegistry meterRegistry,
                                      @Value("${" + SpineEvents.PUBLISH_TIMEOUT_PROPERTY + ":30s}")
                                      Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Creates the router with the default acknowledgement timeout.
     *
     * @param kafkaTemplate the template for the typed topics (JSON serializer)
     * @param objectMapper  parses the payload into the tree that is forwarded
     * @param meterRegistry counts routed, dropped and failed records
     */
    public DiscoveryLogEventProcessor(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper,
                                      MeterRegistry meterRegistry) {
        this(kafkaTemplate, objectMapper, meterRegistry, SpineEvents.DEFAULT_TIMEOUT);
    }

    /**
     * Listener entry point. It takes the whole record rather than a payload parameter: Spring Kafka
     * binds a parameter whose type is assignable from the consumer record (such as {@code Object})
     * to the record itself, before looking at {@code @Payload}, so an {@code Object} payload
     * parameter would never see the value.
     *
     * @param record the record; its value is a {@code String} holding the event (the legacy double
     *               encoding, after the container's JSON deserializer removed the outer string), a
     *               {@code Map} (the event as a JSON object), or {@code null}
     * @param ack    the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED,
            groupId = "da-discovery-log-event-router",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onLogEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        route(record.value(), record.topic(), record.offset(), ack);
    }

    /**
     * Routes one normalized event onto the typed lifecycle topic for its change type.
     *
     * @param payload      the record value: a {@code String} holding the event (double encoding) or a
     *                     {@code Map} (the event as a JSON object)
     * @param inboundTopic the topic the record came from
     * @param offset       the record offset, for log messages
     * @param ack          the manual acknowledgment
     */
    void route(Object payload, String inboundTopic, long offset, Acknowledgment ack) {

        if (isEmpty(payload)) {
            log.warn("Empty payload at offset {}; acknowledging to drain", offset);
            countDropped(REASON_EMPTY_PAYLOAD);
            ack.acknowledge();
            return;
        }

        try {
            DecodedLogEvent decoded = codec.decodeDetailed(payload);
            DlapLogEvent event = decoded.event();
            // A String means the producer double-encoded the event: the container's JSON
            // deserializer removed the outer string and left the event text.
            boolean legacy = payload instanceof CharSequence || decoded.isLegacy();

            String typedTopic = topicForChangeType(event.getChangeType().name());
            if (typedTopic == null) {
                JsonNode tree = toTree(payload);
                String reason = declaresChangeType(tree) ? REASON_UNKNOWN_CHANGE_TYPE : REASON_MISSING_CHANGE_TYPE;
                log.warn("Log event eventId={} has {} changeType; acknowledging without routing",
                        event.getEventId(), REASON_UNKNOWN_CHANGE_TYPE.equals(reason) ? "an unrecognised" : "no");
                countDropped(reason);
                ack.acknowledge();
                return;
            }

            // Republish the normalized event onto the typed topic. Send the parsed
            // JsonNode (not a String): the producer's JsonSerializer would wrap a String
            // in quotes (double-encoding), which downstream consumers like da-catalog
            // cannot deserialize back into an object.
            // Partition key: the canonical URI, else the producer's target id, else the event id
            // (events for the same asset land on the same partition so downstream consumers
            // see them in causal order).
            String partitionKey = LogEventSemantics.partitionKey(event);
            sendAndAwait(typedTopic, partitionKey.isBlank() ? null : partitionKey, toTree(payload));
            meterRegistry.counter(METER_ROUTED, "topic", typedTopic, "legacy", Boolean.toString(legacy)).increment();
            log.debug("Routed eventId={} changeType={} → topic={}", event.getEventId(), event.getChangeType(), typedTopic);
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to route log event at offset {} on topic {}: {}",
                    offset, inboundTopic, e.getMessage());
            // Don't ack - DefaultErrorHandler (configured in KafkaReliabilityConfiguration)
            // runs the backoff and routes to .DLT on exhaustion.
            throw new RuntimeException("DiscoveryLogEventProcessor route failed", e);
        }
    }

    /**
     * Sends to the typed topic and waits for the broker's acknowledgement, so the caller
     * acknowledges the inbound record only after the event is safe on the typed topic (Q-823).
     *
     * @throws IllegalStateException when the send fails, times out, returns no future or the wait is
     *                               interrupted
     */
    private void sendAndAwait(String typedTopic, String key, JsonNode value) {
        try {
            CompletableFuture<?> future = kafkaTemplate.send(typedTopic, key, value);
            if (future == null) {
                throw new IllegalStateException("the producer returned no result for " + typedTopic);
            }
            // The record would otherwise sit in the producer's batch until linger.ms (5 ms by default
            // since Kafka 4, 20 ms in the prod profile); this thread is about to wait for it.
            kafkaTemplate.flush();
            future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            countSendFailed(SEND_ERROR);
            throw new IllegalStateException("interrupted while sending to " + typedTopic, e);
        } catch (TimeoutException e) {
            countSendFailed(SEND_TIMEOUT);
            throw new IllegalStateException(
                    "no acknowledgement from " + typedTopic + " within " + sendTimeout, e);
        } catch (ExecutionException e) {
            countSendFailed(SEND_ERROR);
            throw new IllegalStateException("broker did not accept the event on " + typedTopic + ": " + e.getCause(),
                    e.getCause() == null ? e : e.getCause());
        } catch (RuntimeException e) {
            // A send that throws instead of returning a failed future (serialization, metadata block).
            countSendFailed(SEND_ERROR);
            throw e;
        }
    }

    private void countSendFailed(String reason) {
        meterRegistry.counter(METER_SEND_FAILED, "reason", reason).increment();
    }

    /**
     * Returns the typed lifecycle topic for a given {@code DlapLogEvent.changeType}.
     * Returns {@code null} for unrecognised values; the caller acknowledges and
     * skips routing rather than fail the consumer.
     */
    static String topicForChangeType(String changeType) {
        if (changeType == null) {
            return null;
        }
        return switch (changeType.toUpperCase()) {
            case "CREATE" -> DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED;
            case "UPDATE" -> DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED;
            case "DELETE" -> DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED;
            case "ACCESS" -> DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED;
            case "PERMISSION" -> DlapConstants.KafkaTopic.DISCOVERY_ASSET_PERMISSION_CHANGED;
            case CHANGE_TYPE_ARCHIVE -> DlapConstants.KafkaTopic.DISCOVERY_ASSET_ARCHIVED;
            default -> null;
        };
    }

    private void countDropped(String reason) {
        meterRegistry.counter(METER_DROPPED, "reason", reason).increment();
    }

    private static boolean isEmpty(Object payload) {
        if (payload == null || payload instanceof KafkaNull) {
            return true;
        }
        if (payload instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (payload instanceof byte[] bytes) {
            return bytes.length == 0;
        }
        return payload instanceof Map<?, ?> map && map.isEmpty();
    }

    /** True when the event text carries a non-blank {@code changeType} (so the value is the unknown part). */
    private static boolean declaresChangeType(JsonNode tree) {
        JsonNode field = tree.get("changeType");
        return field != null && !field.isNull() && !field.asText().isBlank();
    }

    /**
     * The event as the JSON object it holds, for forwarding: the String payload parsed (and
     * unwrapped when it is itself a JSON string), or the Map converted.
     */
    private JsonNode toTree(Object payload) throws JsonProcessingException {
        if (payload instanceof JsonNode node) {
            return node;
        }
        if (payload instanceof Map<?, ?> map) {
            return objectMapper.valueToTree(map);
        }
        String text = payload instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : payload.toString();
        JsonNode node = objectMapper.readTree(text);
        for (int wraps = 0; node != null && node.isTextual() && wraps < MAX_STRING_WRAPS; wraps++) {
            node = objectMapper.readTree(node.asText());
        }
        return node;
    }
}
