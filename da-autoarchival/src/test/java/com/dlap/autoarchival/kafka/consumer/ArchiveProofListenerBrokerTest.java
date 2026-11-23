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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import com.dlap.autoarchival.config.KafkaReliabilityConfiguration;
import com.dlap.autoarchival.proof.ArchiveProofService;
import com.dlap.autoarchival.service.IArchivalTaskService;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The proof listener on a real broker with the service's own listener container factory. The live harness
 * run found that every normalized event reached the listener as a class the codec did not accept
 * ({@code UNSUPPORTED_PAYLOAD_TYPE}) and went to the dead-letter topic, so no provider event could ever prove
 * an archive. A mock of the payload cannot show that; a record through the real container can.
 */
class ArchiveProofListenerBrokerTest {

    private static final String TOPIC = "dlap.log.event.normalized";
    private static EmbeddedKafkaKraftBroker broker;

    @Configuration
    @EnableKafka
    static class ListenerConfig {
        static final ArchiveProofService PROOF = mock(ArchiveProofService.class);
        static final IArchivalTaskService TASKS = mock(IArchivalTaskService.class);

        @Bean
        ArchiveProofService proofService() {
            return PROOF;
        }

        @Bean
        ArchiveProofListener proofListener(ArchiveProofService proof) {
            return new ArchiveProofListener(proof);
        }

        @Bean
        IArchivalTaskService tasks() {
            return TASKS;
        }

        @Bean
        ApprovalDecidedListener decidedListener(IArchivalTaskService tasks) {
            return new ApprovalDecidedListener(tasks);
        }
    }

    @BeforeAll
    static void start() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC, "dlap.approvals.decided", "dlap.policies.actions");
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        broker.destroy();
    }

    private KafkaTemplate<String, String> producer() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    @Test
    void aNormalizedEventWrittenAsTheProducersWriteItReachesTheProofService() {
        DlapLogEventCodec codec = DlapLogEventCodec.defaults();
        DlapLogEvent event = DlapLogEvent.newBuilder().setEventId("ev-broker-1").setOperation("LifecycleTransition")
                .setSourceProvider(SourceProvider.AWS).setResultStatus(ResultStatus.SUCCESS)
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("s3://pilot/finance/q1.csv")
                        .putTechnicalMetadata("storageClass", "GLACIER_IR")).build();

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.consumer.auto-offset-reset=earliest",
                        "logging.level.org.apache.kafka=ERROR")
                .withUserConfiguration(KafkaReliabilityConfiguration.class, ListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    producer().send(TOPIC, "ev-broker-1", codec.encode(event));

                    ArgumentCaptor<DlapLogEvent> seen = ArgumentCaptor.forClass(DlapLogEvent.class);
                    await().atMost(Duration.ofSeconds(60)).untilAsserted(
                            () -> verify(ListenerConfig.PROOF, atLeastOnce()).onEvent(seen.capture()));
                    assertThat(seen.getAllValues()).anySatisfy(e -> {
                        assertThat(e.getEventId()).isEqualTo("ev-broker-1");
                        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://pilot/finance/q1.csv");
                        assertThat(e.getTargetResource().getTechnicalMetadataMap())
                                .containsEntry("storageClass", "GLACIER_IR");
                    });
                });
    }

    @Test
    void anApprovalDecisionWrittenAsThePolicyEngineWritesItReachesTheTaskService() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "spring.kafka.consumer.auto-offset-reset=earliest",
                        "logging.level.org.apache.kafka=ERROR")
                .withUserConfiguration(KafkaReliabilityConfiguration.class, ListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    producer().send("dlap.approvals.decided", "appr-broker-1",
                            "{\"schemaVersion\":\"v1\",\"eventType\":\"DECIDED\",\"approvalId\":\"appr-broker-1\","
                                    + "\"status\":\"APPROVED\",\"executable\":true,\"actionClass\":\"ARCHIVE\"}");

                    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> verify(ListenerConfig.TASKS, atLeastOnce())
                            .onApprovalDecided("appr-broker-1", true, "APPROVED"));
                });
    }
}
