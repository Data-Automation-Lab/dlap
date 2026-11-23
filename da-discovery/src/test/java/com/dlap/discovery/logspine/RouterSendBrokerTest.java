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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.util.backoff.FixedBackOff;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.discovery.common.config.KafkaConfiguration;
import com.dlap.discovery.config.KafkaReliabilityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Q-823 against a real (embedded, in-process) broker and the service's own listener container: when
 * the router's forward send fails, the record is not acknowledged, the error handler redelivers it,
 * the send is tried again, and a record that never goes through is dead-lettered to {@code <topic>.DLT}.
 * Before the fix the router acknowledged right after {@code send}, so a failed send lost the event.
 *
 * <p>The router, the manual-acknowledgement container factory and the dead-letter handler are the
 * service's own; only the retry schedule is shortened and the forward send is made to fail.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RouterSendBrokerTest {

    /** Retries after the first attempt. */
    private static final int RETRIES = 3;

    private static final String EVENT = "{\"eventId\":\"rtr01-e1\",\"changeType\":\"CREATE\","
            + "\"targetResource\":{\"id\":\"s3://b\",\"canonicalUri\":\"s3://b\"}}";

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 3);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(KafkaReliabilityConfiguration.class, KafkaConfiguration.class)
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.producer.properties.max.block.ms=5000",
                        "spring.kafka.consumer.group-id=rtr01-" + System.nanoTime());
    }

    @Test
    void aFailedForwardSendIsRedeliveredAndAcknowledgedOnlyOnceItSucceeds() throws Exception {
        String source = "rtr01.router.transient";
        broker.addTopics(new NewTopic(source, 3, (short) 1), new NewTopic(source + ".DLT", 1, (short) 1));

        // The first two forward sends fail, the third goes through.
        AtomicInteger sends = new AtomicInteger();
        KafkaTemplate<String, Object> forward = mock(KafkaTemplate.class);
        when(forward.send(any(String.class), any(), any())).thenAnswer(call ->
                sends.incrementAndGet() <= 2
                        ? CompletableFuture.failedFuture(new NetworkException("broker is down"))
                        : CompletableFuture.completedFuture(null));
        AtomicInteger acks = new AtomicInteger();
        AtomicInteger sendsAtAck = new AtomicInteger(-1);

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(
                    forward, new ObjectMapper(), new SimpleMeterRegistry(), Duration.ofSeconds(5));
            ConcurrentMessageListenerContainer<String, Object> container =
                    container(context, source, router, acks, sendsAtAck, sends);
            container.start();
            try {
                KafkaTemplate<String, Object> producer = context.getBean("kafkaTemplate", KafkaTemplate.class);
                producer.send(source, "s3://b", new ObjectMapper().readValue(EVENT, Map.class)).get(30, TimeUnit.SECONDS);

                await(() -> acks.get() == 1, "the record was acknowledged");

                assertThat(sends.get()).as("two refused sends and the one that went through").isEqualTo(3);
                assertThat(sendsAtAck.get()).as("the ack came after the third send, not after the first").isEqualTo(3);
                assertThat(acks.get()).as("acknowledged once").isEqualTo(1);
            } finally {
                container.stop();
            }
        });
    }

    @Test
    void aForwardSendThatNeverSucceedsIsDeadLetteredAndNeverAcknowledged() throws Exception {
        String source = "rtr01.router.permanent";
        broker.addTopics(new NewTopic(source, 3, (short) 1), new NewTopic(source + ".DLT", 1, (short) 1));

        AtomicInteger sends = new AtomicInteger();
        KafkaTemplate<String, Object> forward = mock(KafkaTemplate.class);
        when(forward.send(any(String.class), any(), any())).thenAnswer(call -> {
            sends.incrementAndGet();
            return CompletableFuture.failedFuture(new NetworkException("broker is down"));
        });
        AtomicInteger acks = new AtomicInteger();

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(
                    forward, new ObjectMapper(), new SimpleMeterRegistry(), Duration.ofSeconds(5));
            ConcurrentMessageListenerContainer<String, Object> container =
                    container(context, source, router, acks, new AtomicInteger(), sends);
            container.start();
            try {
                KafkaTemplate<String, Object> producer = context.getBean("kafkaTemplate", KafkaTemplate.class);
                producer.send(source, "dead-key", new ObjectMapper().readValue(EVENT, Map.class)).get(30, TimeUnit.SECONDS);

                ConsumerRecord<String, String> deadLetter = readOne(source + ".DLT", "dead-key");

                assertThat(deadLetter.key()).isEqualTo("dead-key");
                assertThat(deadLetter.value()).contains("rtr01-e1");
                assertThat(sends.get()).as("first try plus retries").isEqualTo(1 + RETRIES);
                assertThat(acks.get()).as("never acknowledged").isZero();
            } finally {
                container.stop();
            }
        });
    }

    /** The production factory and listener method, with only the back-off shortened. */
    private static ConcurrentMessageListenerContainer<String, Object> container(
            ApplicationContext context, String topic, DiscoveryLogEventProcessor router,
            AtomicInteger acks, AtomicInteger sendsAtAck, AtomicInteger sends) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = context.getBean(
                KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
        KafkaTemplate deadLetterTemplate = context.getBean("kafkaTemplate", KafkaTemplate.class);
        ConcurrentMessageListenerContainer<String, Object> container = factory.createContainer(topic);
        container.setCommonErrorHandler(DlapKafkaFactoryBuilder.deadLetterErrorHandler(deadLetterTemplate,
                new FixedBackOff(100L, RETRIES)));
        container.setupMessageListener((AcknowledgingMessageListener<String, Object>) (record, ack) -> {
            Acknowledgment counting = () -> {
                sendsAtAck.set(sends.get());
                acks.incrementAndGet();
                ack.acknowledge();
            };
            router.onLogEvent(record, counting);
        });
        return container;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(50);
        }
    }

    private static ConsumerRecord<String, String> readOne(String topic, String key) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "group.id", "rtr01-dlt-reader-" + System.nanoTime(),
                "auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record with key " + key + " reached " + topic + " within 90 s");
    }
}
