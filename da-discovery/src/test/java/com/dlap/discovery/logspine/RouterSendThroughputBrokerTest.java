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
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.common.config.KafkaConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Measures what Q-823 costs. The router used to hand the record to the producer and acknowledge at
 * once, so the producer batched sends in the background. It now waits for each broker acknowledgement
 * before it acknowledges, which makes one listener thread pay one broker round trip per record.
 *
 * <p>Both loops run against the same real (embedded, in-process, one-broker) cluster and the
 * service's own producer settings: {@code fireAndForget} is the old behaviour, {@code awaited} is the
 * router. The figures go to the test output as {@code RTR-01 throughput}. The only assertion is that
 * every record arrived, because timing is machine-dependent; the figures are reported in the ADR.
 * The router flushes the producer before it waits, so a record does not sit out {@code linger.ms}
 * (5 ms by default in Kafka 4); a first measurement without the flush gave 63 records/s, 15.8 ms per
 * record, for the same loop.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RouterSendThroughputBrokerTest {

    private static final int RECORDS = 2000;

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 3);
        broker.afterPropertiesSet();
        broker.addTopics(new NewTopic(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED, 3, (short) 1));
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    private static String event(int i) {
        return "{\"eventId\":\"rtr01-t-" + i + "\",\"changeType\":\"CREATE\",\"targetResource\":"
                + "{\"id\":\"s3://b/o" + i + "\",\"canonicalUri\":\"s3://b/o" + i + "\"}}";
    }

    @Test
    void measuresTheRouterWithAndWithoutAwaitingTheSend() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(KafkaConfiguration.class)
                .withPropertyValues("spring.kafka.bootstrap-servers=" + broker.getBrokersAsString())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KafkaTemplate<String, Object> real = context.getBean("kafkaTemplate", KafkaTemplate.class);
                    ObjectMapper mapper = new ObjectMapper();
                    Acknowledgment ack = mock(Acknowledgment.class);
                    // Warm up the producer, metadata and class loading before either timed loop.
                    DiscoveryLogEventProcessor warm = new DiscoveryLogEventProcessor(real, mapper, new SimpleMeterRegistry());
                    for (int i = 0; i < 200; i++) {
                        warm.route(event(-i - 1), DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, i, ack);
                    }

                    // Old behaviour: send without waiting, flush once at the end.
                    AtomicInteger unawaited = new AtomicInteger();
                    long start = System.nanoTime();
                    for (int i = 0; i < RECORDS; i++) {
                        JsonNode tree = mapper.readTree(event(i));
                        CompletableFuture<?> f = real.send(DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED,
                                "s3://b/o" + i, tree);
                        f.whenComplete((r, e) -> unawaited.incrementAndGet());
                    }
                    real.flush();
                    long fireAndForgetNanos = System.nanoTime() - start;

                    // The router: every record waits for its acknowledgement.
                    DiscoveryLogEventProcessor router = new DiscoveryLogEventProcessor(real, mapper,
                            new SimpleMeterRegistry(), Duration.ofSeconds(30));
                    start = System.nanoTime();
                    for (int i = 0; i < RECORDS; i++) {
                        router.route(event(RECORDS + i), DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, i, ack);
                    }
                    long awaitedNanos = System.nanoTime() - start;

                    double fireRate = RECORDS / (fireAndForgetNanos / 1e9);
                    double awaitedRate = RECORDS / (awaitedNanos / 1e9);
                    System.out.printf("RTR-01 throughput: %d records, fireAndForget %.0f records/s (%.0f ms), "
                                    + "awaited %.0f records/s (%.0f ms, %.2f ms per record)%n",
                            RECORDS, fireRate, fireAndForgetNanos / 1e6, awaitedRate, awaitedNanos / 1e6,
                            awaitedNanos / 1e6 / RECORDS);

                    assertThat(unawaited.get()).as("every fire-and-forget send completed").isEqualTo(RECORDS);
                    org.mockito.Mockito.verify(ack, org.mockito.Mockito.times(200 + RECORDS)).acknowledge();
                });
    }
}
