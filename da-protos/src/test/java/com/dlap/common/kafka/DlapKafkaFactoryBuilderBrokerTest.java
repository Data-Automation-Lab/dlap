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

package com.dlap.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.util.backoff.FixedBackOff;

/**
 * KAFKA-01 against a real (embedded, in-process) broker: an idempotent producer from the builder
 * sends, and a record that fails in a manual-ack listener on partition 2 of a three-partition topic
 * reaches a one-partition dead-letter topic. With the old "same partition as the source" rule the
 * dead-letter publish fails, because partition 2 does not exist on the dead-letter topic (second
 * test).
 */
class DlapKafkaFactoryBuilderBrokerTest {

    private static final String SOURCE = "kafka01.source";

    private static final String DEAD_LETTER = SOURCE + DlapKafkaFactoryBuilder.DEAD_LETTER_SUFFIX;

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 3);
        broker.afterPropertiesSet();
        broker.addTopics(new NewTopic(SOURCE, 3, (short) 1), new NewTopic(DEAD_LETTER, 1, (short) 1));
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    private static DlapKafkaFactoryBuilder builder() {
        KafkaProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "spring.kafka.bootstrap-servers", broker.getBrokersAsString(),
                "spring.kafka.producer.properties.max.block.ms", "5000",
                "spring.kafka.consumer.group-id", "kafka01-test",
                "spring.kafka.producer.key-serializer", StringSerializer.class.getName(),
                "spring.kafka.producer.value-serializer", StringSerializer.class.getName())))
                .bindOrCreate("spring.kafka", KafkaProperties.class);
        return DlapKafkaFactoryBuilder.of(properties, (KafkaConnectionDetails) null);
    }

    @Test
    void aFailedRecordFromAHighPartitionReachesASinglePartitionDeadLetterTopic() throws Exception {
        DlapKafkaFactoryBuilder builder = builder();
        DefaultKafkaProducerFactory<String, String> producerFactory = builder.producerFactory();
        KafkaTemplate<String, String> template = builder.kafkaTemplate(producerFactory);

        ConcurrentKafkaListenerContainerFactory<String, String> factory = builder.manualAckListenerContainerFactory(
                builder.stringConsumerFactory(),
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(template, new FixedBackOff(0L, 0L)));
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(SOURCE);
        container.setupMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> {
            throw new IllegalStateException("listener always fails");
        });
        container.start();
        try {
            RecordMetadata sent = template.send(new ProducerRecord<>(SOURCE, 2, "asset-1", "payload"))
                    .get(30, TimeUnit.SECONDS).getRecordMetadata();
            assertThat(sent.partition()).isEqualTo(2);
            assertThat(producerFactory.getConfigurationProperties()).containsEntry("enable.idempotence", true);

            ConsumerRecord<String, String> deadLetter = readOne(DEAD_LETTER);
            assertThat(deadLetter.partition()).isZero();
            assertThat(deadLetter.key()).isEqualTo("asset-1");
            assertThat(deadLetter.value()).isEqualTo("payload");
            assertThat(new String(deadLetter.headers().lastHeader("kafka_dlt-original-partition").value()).length())
                    .isPositive();
        } finally {
            container.stop();
            producerFactory.destroy();
        }
    }

    @Test
    void theSamePartitionRuleCannotPublishToASmallerDeadLetterTopic() {
        DlapKafkaFactoryBuilder builder = builder();
        DefaultKafkaProducerFactory<String, String> producerFactory = builder.producerFactory();
        try {
            DeadLetterPublishingRecoverer samePartition = DlapKafkaFactoryBuilder.deadLetterRecoverer(
                    builder.kafkaTemplate(producerFactory), DlapKafkaFactoryBuilder.DeadLetterPartitioning.SAME_AS_SOURCE);
            ConsumerRecord<String, String> failed = new ConsumerRecord<>(SOURCE, 2, 0L, "asset-2", "payload");
            assertThatThrownBy(() -> samePartition.accept(failed, new IllegalStateException("listener failed")))
                    .as("partition 2 does not exist on the one-partition dead-letter topic")
                    .isInstanceOf(RuntimeException.class);
        } finally {
            producerFactory.destroy();
        }
    }

    private static ConsumerRecord<String, String> readOne(String topic) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "group.id", "kafka01-dlt-reader",
                "auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }
        throw new AssertionError("no record reached " + topic + " within 60 s");
    }
}
