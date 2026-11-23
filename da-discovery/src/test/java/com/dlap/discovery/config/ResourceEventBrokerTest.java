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
package com.dlap.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import com.dlap.discovery.common.config.KafkaConfiguration;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.event.service.type.ResourceEvent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * SEC-03 against a real (embedded, in-process) broker: a {@link ResourceEvent} produced by the
 * service's own template crosses Kafka and is read by the resource-events listener factory with its
 * concrete payload type, and a record that names a class instead of a type name is dead-lettered and
 * never delivered. The records come from and go to the service's real Kafka configuration.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ResourceEventBrokerTest {

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 3);
        broker.afterPropertiesSet();
    }

    /** A source topic with three partitions and its one-partition dead-letter topic; one pair per test. */
    private static String newTopic(String name) {
        broker.addTopics(new NewTopic(name, 3, (short) 1), new NewTopic(name + ".DLT", 1, (short) 1));
        return name;
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
                        "spring.kafka.consumer.group-id=sec03-" + System.nanoTime());
    }

    @Test
    void aResourceEventCrossesKafkaAndKeepsItsConcreteType() {
        BlockingQueue<ResourceEvent> delivered = new LinkedBlockingQueue<>();
        String source = newTopic("sec03.resource-events.ok");
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ConcurrentMessageListenerContainer<String, ResourceEvent> container = listen(context
                    .getBean(KafkaReliabilityConfiguration.RESOURCE_EVENT_FACTORY, ConcurrentKafkaListenerContainerFactory.class),
                    source, delivered);
            try {
                GcsResource bucket = new GcsResource(
                        new ResourceType("gcp_gcs_bucket", "Bucket", new CloudService("gcs", "GCS", CloudProvider.GCP)),
                        "demo-bucket", "demo-bucket");
                ResourceEvent sent = new ResourceEvent("GCP", "demo-project", ChangeType.CREATE, bucket);

                context.getBean("resourceEventKafkaTemplate", KafkaTemplate.class).send(source, "demo-bucket", sent)
                        .get(30, TimeUnit.SECONDS);

                ResourceEvent received = delivered.poll(60, TimeUnit.SECONDS);
                assertThat(received).as("the event the listener received").isNotNull();
                assertThat(received.getPayload()).isExactlyInstanceOf(GcsResource.class);
                assertThat(received.getPayload().getResourceId()).isEqualTo("demo-bucket");
                assertThat(received.getChangeType()).isEqualTo(ChangeType.CREATE);
            } finally {
                container.stop();
            }
        });
    }

    @Test
    void aRecordThatNamesAClassIsDeadLetteredAndNeverDelivered() throws Exception {
        BlockingQueue<ResourceEvent> delivered = new LinkedBlockingQueue<>();
        String source = newTopic("sec03.resource-events.hostile");
        String className = GcsResource.class.getName();
        String hostile = "{\"eventId\":\"hostile-1\",\"changeType\":\"CREATE\",\"payload\":{\"@type\":\"" + className
                + "\",\"resourceId\":\"x\"}}";
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ConcurrentMessageListenerContainer<String, ResourceEvent> container = listen(context
                    .getBean(KafkaReliabilityConfiguration.RESOURCE_EVENT_FACTORY, ConcurrentKafkaListenerContainerFactory.class),
                    source, delivered);
            try {
                // The payload names a real class, not a type name from the closed list. A header naming
                // another class changes nothing either.
                ProducerRecord<String, String> record = new ProducerRecord<>(source, "hostile-1", hostile);
                record.headers().add("__TypeId__", "java.lang.ProcessBuilder".getBytes(StandardCharsets.UTF_8));
                context.getBean("stringKafkaTemplate", KafkaTemplate.class).send(record).get(30, TimeUnit.SECONDS);

                ConsumerRecord<String, String> deadLetter = readOne(source + ".DLT", "hostile-1");
                assertThat(deadLetter.key()).isEqualTo("hostile-1");
                assertThat(delivered.poll(2, TimeUnit.SECONDS)).as("an event delivered to the listener").isNull();
            } finally {
                container.stop();
            }
        });
    }

    private static ConcurrentMessageListenerContainer<String, ResourceEvent> listen(
            ConcurrentKafkaListenerContainerFactory<String, ResourceEvent> factory, String source,
            BlockingQueue<ResourceEvent> delivered) {
        ConcurrentMessageListenerContainer<String, ResourceEvent> container = factory.createContainer(source);
        container.setupMessageListener((AcknowledgingMessageListener<String, ResourceEvent>) (record, ack) -> {
            delivered.add(record.value());
            ack.acknowledge();
        });
        container.start();
        return container;
    }

    private static ConsumerRecord<String, String> readOne(String topic, String key) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "group.id", "sec03-dlt-reader-" + System.nanoTime(),
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
