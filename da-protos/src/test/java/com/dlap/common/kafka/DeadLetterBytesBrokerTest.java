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
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;
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
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Q-404 acceptance, against an embedded KRaft broker: a record that fails deserialization is
 * dead-lettered with its original bytes. The service template of the test is the one the services
 * build, a String key and a JSON value serializer; through it alone the dead-letter value used to be
 * a JSON-quoted base64 string (the control test shows that). Through
 * {@link DlapKafkaFactoryBuilder#deadLetterRecoverer} the dead-letter value equals the source bytes,
 * a record that failed in the listener keeps going out through the service template, and a template
 * that cannot be copied keeps the old behaviour.
 */
class DeadLetterBytesBrokerTest {

    /** Bytes that are not valid UTF-8 and not JSON, so any text round trip changes them. */
    private static final byte[] POISON = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x7B, (byte) 0x80, 'x', (byte) 0xC3};

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.afterPropertiesSet();
        for (String topic : List.of("q404.poison", "q404.listener", "q404.tombstone", "q404.control")) {
            broker.addTopics(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".DLT", 1, (short) 1));
        }
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    private static DlapKafkaFactoryBuilder builder() {
        KafkaProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "spring.kafka.bootstrap-servers", broker.getBrokersAsString(),
                "spring.kafka.producer.properties.max.block.ms", "5000",
                "spring.kafka.consumer.group-id", "q404-test")))
                .bindOrCreate("spring.kafka", KafkaProperties.class);
        return DlapKafkaFactoryBuilder.of(properties, (KafkaConnectionDetails) null);
    }

    /** A value deserializer that rejects the poison bytes the way a JSON deserializer rejects bad JSON. */
    private static final class Strict implements Deserializer<String> {
        @Override
        public String deserialize(String topic, byte[] data) {
            if (data != null && data.length > 0 && data[0] == (byte) 0xFF) {
                throw new SerializationException("not a record of this topic");
            }
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        }
    }

    private static DefaultKafkaProducerFactory<String, Object> serviceProducer(DlapKafkaFactoryBuilder b) {
        return b.producerFactory(Map.of(), StringSerializer.class, JsonSerializer.class);
    }

    private ConcurrentMessageListenerContainer<String, String> container(DlapKafkaFactoryBuilder b, String source,
            KafkaOperations<?, ?> template, boolean listenerFails) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = b.manualAckListenerContainerFactory(
                b.consumerFactory(new StringDeserializer(), new Strict(), Map.of()),
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(template, new FixedBackOff(0L, 0L)));
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer(source);
        container.setupMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> {
            if (listenerFails) {
                throw new IllegalStateException("listener always fails");
            }
            ack.acknowledge();
        });
        container.start();
        return container;
    }

    @Test
    void anUndeserializableRecordIsDeadLetteredWithItsOriginalBytes() throws Exception {
        DlapKafkaFactoryBuilder b = builder();
        DefaultKafkaProducerFactory<String, Object> serviceFactory = serviceProducer(b);
        KafkaTemplate<String, Object> service = b.kafkaTemplate(serviceFactory);
        ConcurrentMessageListenerContainer<String, String> container = container(b, "q404.poison", service, false);
        try {
            sendRaw("q404.poison", "asset-poison", POISON);

            ConsumerRecord<String, byte[]> deadLetter = readOne("q404.poison.DLT", "asset-poison");

            assertThat(deadLetter.value()).as("the dead-letter value is the source value, byte for byte")
                    .isEqualTo(POISON);
            assertThat(deadLetter.key()).isEqualTo("asset-poison");
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        } finally {
            container.stop();
            serviceFactory.destroy();
        }
    }

    @Test
    void theServiceTemplateAloneQuotesTheBytesAsJsonBase64() throws Exception {
        // The control: the recoverer as the builder made it before Q-404 (one template, the service one).
        DlapKafkaFactoryBuilder b = builder();
        DefaultKafkaProducerFactory<String, Object> serviceFactory = serviceProducer(b);
        KafkaTemplate<String, Object> service = b.kafkaTemplate(serviceFactory);
        DeadLetterPublishingRecoverer old = new DeadLetterPublishingRecoverer(service,
                (record, ex) -> DlapKafkaFactoryBuilder.deadLetterDestination(record,
                        DlapKafkaFactoryBuilder.DeadLetterPartitioning.PRODUCER_CHOOSES));
        ConcurrentKafkaListenerContainerFactory<String, String> factory = b.manualAckListenerContainerFactory(
                b.consumerFactory(new StringDeserializer(), new Strict(), Map.of()),
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(old, new FixedBackOff(0L, 0L)));
        ConcurrentMessageListenerContainer<String, String> container = factory.createContainer("q404.control");
        container.setupMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> ack.acknowledge());
        container.start();
        try {
            sendRaw("q404.control", "asset-control", POISON);

            ConsumerRecord<String, byte[]> deadLetter = readOne("q404.control.DLT", "asset-control");

            assertThat(deadLetter.value()).as("the bug: not the original bytes").isNotEqualTo(POISON);
            assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).startsWith("\"").endsWith("\"");
        } finally {
            container.stop();
            serviceFactory.destroy();
        }
    }

    @Test
    void aRecordThatFailedInTheListenerStillGoesThroughTheServiceTemplate() throws Exception {
        DlapKafkaFactoryBuilder b = builder();
        DefaultKafkaProducerFactory<String, Object> serviceFactory = serviceProducer(b);
        KafkaTemplate<String, Object> service = b.kafkaTemplate(serviceFactory);
        ConcurrentMessageListenerContainer<String, String> container = container(b, "q404.listener", service, true);
        try {
            sendRaw("q404.listener", "asset-listener", "{\"id\":7}".getBytes(StandardCharsets.UTF_8));

            ConsumerRecord<String, byte[]> deadLetter = readOne("q404.listener.DLT", "asset-listener");

            // The listener saw the String {"id":7}; the JSON template writes that String as a JSON string.
            assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).isEqualTo("\"{\\\"id\\\":7}\"");
        } finally {
            container.stop();
            serviceFactory.destroy();
        }
    }

    @Test
    void aTombstoneThatFailsInTheListenerIsDeadLettered() throws Exception {
        DlapKafkaFactoryBuilder b = builder();
        DefaultKafkaProducerFactory<String, Object> serviceFactory = serviceProducer(b);
        KafkaTemplate<String, Object> service = b.kafkaTemplate(serviceFactory);
        ConcurrentMessageListenerContainer<String, String> container = container(b, "q404.tombstone", service, true);
        try {
            sendRaw("q404.tombstone", "asset-tombstone", null);

            ConsumerRecord<String, byte[]> deadLetter = readOne("q404.tombstone.DLT", "asset-tombstone");

            assertThat(deadLetter.value()).isNull();
        } finally {
            container.stop();
            serviceFactory.destroy();
        }
    }

    @Test
    void aTemplateThatCannotBeCopiedKeepsTheSingleTemplateBehaviour() {
        // A mock has no producer factory: the recoverer must still be built and use the one template.
        DeadLetterPublishingRecoverer recoverer = DlapKafkaFactoryBuilder.deadLetterRecoverer(
                mock(KafkaOperations.class), DlapKafkaFactoryBuilder.DeadLetterPartitioning.PRODUCER_CHOOSES);

        assertThat(recoverer).isNotNull();
    }

    @Test
    void theDeadLetterKeySerializerWritesStringsAndBytesAsTheyAre() {
        DlapKafkaFactoryBuilder.DeadLetterKeySerializer keys = new DlapKafkaFactoryBuilder.DeadLetterKeySerializer();

        assertThat(keys.serialize("t", "asset-1")).isEqualTo("asset-1".getBytes(StandardCharsets.UTF_8));
        assertThat(keys.serialize("t", POISON)).isSameAs(POISON);
        assertThat(keys.serialize("t", null)).isNull();
        assertThat(keys.serialize("t", 42L)).isEqualTo("42".getBytes(StandardCharsets.UTF_8));
    }

    private static void sendRaw(String topic, String key, byte[] value) throws Exception {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", org.apache.kafka.common.serialization.ByteArraySerializer.class.getName());
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(config)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);
        }
    }

    private static ConsumerRecord<String, byte[]> readOne(String topic, String key) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "group.id", "q404-dlt-reader-" + System.nanoTime(),
                "auto.offset.reset", "earliest");
        try (KafkaConsumer<String, byte[]> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record with key " + key + " reached " + topic + " within 60 s");
    }
}
