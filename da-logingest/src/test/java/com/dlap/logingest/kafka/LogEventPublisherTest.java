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

package com.dlap.logingest.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;

/**
 * What {@link LogEventPublisher} puts on {@code dlap.log.event.normalized}, and what it does when
 * the broker does not answer (SPINE-01): one encoding, one serialization, and a send that is
 * awaited.
 */
class LogEventPublisherTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final Duration SHORT = Duration.ofMillis(300);

    /** The flat map the legacy REST path builds, with its lower-case provider names left as the caller sent them. */
    private static Map<String, Object> legacyFlatEvent(String eventId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventTime", "2026-10-19T10:15:30Z");
        event.put("ingestTime", "2026-10-19T10:15:31Z");
        event.put("sourceProvider", "gcp");
        event.put("sourceService", "storage.googleapis.com");
        event.put("changeType", "access");
        event.put("operation", "storage.objects.get");
        event.put("resultStatus", "success");
        event.put("principal", Map.of("id", "alice@example.com", "type", "user", "displayName", "", "accountId", ""));
        event.put("targetResource", Map.of("kind", "gcs_bucket", "id", "gs://demo-bucket", "name", "",
                "project", "demo-project", "region", ""));
        event.put("requestMetadata", Map.of("callerIp", "", "userAgent", "", "requestId", "r-1", "traceId", ""));
        event.put("rawEvent", Map.of("insertId", "abc", "count", 12));
        event.put("schemaVersion", "v1");
        return event;
    }

    @Test
    void theValueOnTheWireIsTheJsonObjectNotAJsonStringHoldingIt() {
        StubKafka kafka = new StubKafka();

        kafka.publisher(SHORT).publish("gs://demo-bucket", legacyFlatEvent("e-1"));

        assertThat(kafka.received()).hasSize(1);
        StubKafka.Wire wire = kafka.received().get(0);
        assertThat(wire.topic()).isEqualTo(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED);
        assertThat(wire.key()).isEqualTo("gs://demo-bucket");
        assertThat((char) wire.value()[0]).as("first byte of the value").isEqualTo('{');
        // The strict reader refuses a string-wrapped payload, an unknown field and a non-canonical enum.
        DlapLogEvent event = CODEC.decodeStrict(wire.text());
        assertThat(event.getEventId()).isEqualTo("e-1");
        assertThat(event.getChangeType()).isEqualTo(ChangeType.ACCESS);
    }

    @Test
    void aLegacyEventWithLowerCaseNamesIsPrintedInCanonicalForm() {
        StubKafka kafka = new StubKafka();

        kafka.publisher(SHORT).publish("k", legacyFlatEvent("e-2"));

        String text = kafka.received().get(0).text();
        assertThat(text).contains("\"changeType\":\"ACCESS\"").contains("\"sourceProvider\":\"GCP\"")
                .contains("\"resultStatus\":\"SUCCESS\"").contains("\"principal\":{")
                .doesNotContain("\"changeType\":\"access\"");
    }

    @Test
    void theTextSentIsExactlyTheCodecsSingleEncodingOfTheEvent() {
        StubKafka kafka = new StubKafka();
        Map<String, Object> event = legacyFlatEvent("e-3");

        kafka.publisher(SHORT).publish("k", event);

        // Printed once: what the template got is the codec's output, byte for byte, with no second
        // serializer wrapping it.
        assertThat(kafka.received().get(0).text()).isEqualTo(CODEC.encode(CODEC.decode(event)));
    }

    @Test
    void aCanonicalMapRoundTripsToTheSameEvent() {
        StubKafka kafka = new StubKafka();
        DlapLogEvent original = CODEC.decode(legacyFlatEvent("e-4"));

        kafka.publisher(SHORT).publish("k", CODEC.toMap(original));

        assertThat(CODEC.decodeStrict(kafka.received().get(0).text())).isEqualTo(original);
    }

    @Test
    void oneCallIsOneSend() {
        StubKafka kafka = new StubKafka();
        LogEventPublisher publisher = kafka.publisher(SHORT);

        publisher.publish("k", legacyFlatEvent("e-5"));
        publisher.publish("k", legacyFlatEvent("e-6"));

        assertThat(kafka.received()).extracting(w -> CODEC.decode(w.text()).getEventId()).containsExactly("e-5", "e-6");
    }

    @Test
    void publishReturnsOnlyAfterTheBrokerAcknowledged() {
        // A broker that acknowledges after 250 ms: publish must not return before.
        KafkaTemplate<String, String> slow = new KafkaTemplate<>(new org.springframework.kafka.core.ProducerFactory<>() {
            @Override
            public org.apache.kafka.clients.producer.Producer<String, String> createProducer() {
                return slowProducer();
            }
        });
        LogEventPublisher publisher = new LogEventPublisher(slow, Duration.ofSeconds(5));

        long start = System.nanoTime();
        publisher.publish("k", legacyFlatEvent("e-7"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("publish waited for the acknowledgement").isGreaterThanOrEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private static org.apache.kafka.clients.producer.Producer<String, String> slowProducer() {
        return (org.apache.kafka.clients.producer.Producer<String, String>) java.lang.reflect.Proxy.newProxyInstance(
                LogEventPublisherTest.class.getClassLoader(),
                new Class<?>[] {org.apache.kafka.clients.producer.Producer.class}, (proxy, method, args) -> {
                    if ("send".equals(method.getName())) {
                        var future = new java.util.concurrent.CompletableFuture<
                                org.apache.kafka.clients.producer.RecordMetadata>();
                        var callback = (org.apache.kafka.clients.producer.Callback) args[1];
                        var meta = new org.apache.kafka.clients.producer.RecordMetadata(
                                new org.apache.kafka.common.TopicPartition("t", 0), 0L, 0, 0L, 0, 0);
                        Thread.ofVirtual().start(() -> {
                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            future.complete(meta);
                            callback.onCompletion(meta, null);
                        });
                        return future;
                    }
                    return method.getReturnType() == boolean.class ? (Object) false : null;
                });
    }

    @Test
    void aRefusedSendThrowsSoTheCallersTransactionRollsBack() {
        StubKafka kafka = new StubKafka().mode(StubKafka.Mode.FAIL);
        LogEventPublisher publisher = kafka.publisher(SHORT);

        assertThatThrownBy(() -> publisher.publish("k", legacyFlatEvent("e-8")))
                .isInstanceOf(LogEventPublishException.class)
                .hasMessageContaining("e-8")
                .hasMessageContaining(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED);
        assertThat(kafka.received()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = StubKafka.Mode.class, names = {"FAIL", "FAIL_LATER"})
    void aSendTheProducerListenerSawFailIsCountedOnceNotTwice(StubKafka.Mode mode) {
        // PublishFailureListener counts a send that failed through the producer callback. The
        // exception that reaches StoreDispositionAspect must then hold no Kafka error in its cause
        // chain, or the aspect counts the same failure again. KafkaTemplate reports the failure
        // either through the future (FAIL_LATER) or, when the future is done on return, as its own
        // "Send failed" exception (FAIL).
        StubKafka kafka = new StubKafka().mode(mode);
        AtomicInteger listened = new AtomicInteger();
        ProducerListener<String, String> listener = new ProducerListener<>() {
            @Override
            public void onError(org.apache.kafka.clients.producer.ProducerRecord<String, String> record,
                                org.apache.kafka.clients.producer.RecordMetadata metadata, Exception exception) {
                listened.incrementAndGet();
            }
        };
        kafka.template().setProducerListener(listener);

        Throwable thrown = org.assertj.core.api.Assertions
                .catchThrowable(() -> kafka.publisher(Duration.ofSeconds(2)).publish("k", legacyFlatEvent("e-9")));

        assertThat(thrown).isInstanceOf(LogEventPublishException.class);
        // With FAIL_LATER the producer callback can run on the sender thread just after the future
        // completes, so the listener's count may land a moment after publish() has thrown.
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (listened.get() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(listened).as("the listener counted the failed send").hasValue(1);
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            assertThat(c).as("cause chain of the thrown exception").isNotInstanceOf(KafkaException.class);
            assertThat(c).isNotInstanceOf(org.springframework.kafka.KafkaException.class);
        }
        assertThat(thrown.getSuppressed()).as("the broker error stays on the stack trace").hasSize(1);
    }

    @Test
    void aBrokerThatNeverAnswersTimesOutWithAKafkaCauseSoItIsCounted() {
        StubKafka kafka = new StubKafka().mode(StubKafka.Mode.HANG);
        LogEventPublisher publisher = kafka.publisher(Duration.ofMillis(150));

        Throwable thrown = org.assertj.core.api.Assertions
                .catchThrowable(() -> publisher.publish("k", legacyFlatEvent("e-10")));

        assertThat(thrown).isInstanceOf(LogEventPublishException.class).hasMessageContaining("e-10");
        assertThat(thrown.getCause()).as("nothing counted this send yet, so the aspect must")
                .isInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
    }

    @Test
    void anEventThatDoesNotFitTheContractIsRefusedBeforeAnySend() {
        StubKafka kafka = new StubKafka();
        Map<String, Object> event = legacyFlatEvent("e-11");
        event.put("eventTime", "not a time");

        assertThatThrownBy(() -> kafka.publisher(SHORT).publish("k", event))
                .isInstanceOf(LogEventPublishException.class)
                .hasMessageContaining("e-11")
                .hasMessageNotContaining("not a time");
        assertThat(kafka.received()).isEmpty();
    }

    @Test
    void theProductionPublisherSendsThroughStringSerializersWithAnIdempotentProducer() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("localhost:9"));
        properties.getProducer().setValueSerializer(org.springframework.kafka.support.serializer.JsonSerializer.class);
        properties.getProducer().setKeySerializer(StringSerializer.class);

        LogEventPublisher publisher = new LogEventPublisher(properties, new StaticListableBeanFactory(),
                new StaticListableBeanFactory().getBeanProvider(
                        org.springframework.core.ResolvableType.forClassWithGenerics(ProducerListener.class,
                                Object.class, Object.class)),
                Duration.ofSeconds(1));
        try {
            Map<String, Object> config = publisher.template().getProducerFactory().getConfigurationProperties();

            // The service-wide setting names a JSON value serializer; this producer fixes its own,
            // because the serializer decides the wire form.
            assertThat(config.get("value.serializer")).isEqualTo(StringSerializer.class);
            assertThat(config.get("key.serializer")).isEqualTo(StringSerializer.class);
            assertThat(config.get("enable.idempotence")).isEqualTo(true);
            assertThat(config.get("acks")).isEqualTo("all");
            assertThat(config.get("bootstrap.servers")).isEqualTo(List.of("localhost:9"));
        } finally {
            publisher.destroy();
        }
    }
}
