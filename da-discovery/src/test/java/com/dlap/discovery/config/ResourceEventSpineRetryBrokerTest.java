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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.util.backoff.FixedBackOff;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.discovery.common.config.KafkaConfiguration;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.event.processor.CrawlerAssetSpineBridge;
import com.dlap.discovery.event.service.EventProcessorRegistry;
import com.dlap.discovery.event.service.ResourceEventListener;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Q-411 (FU-3C) against a real (embedded, in-process) broker: when the crawler-to-spine bridge cannot
 * put an event on the log spine, {@link ResourceEventListener} lets the {@code SpinePublishException}
 * reach the container's error handler, the record is retried, and after the last attempt it is
 * dead-lettered to {@code <topic>.DLT}. Before the fix the listener swallowed the exception and
 * acknowledged the record, so the crawler event was gone with the asset never announced.
 *
 * <p>The listener, the bridge, the container factory and the dead-letter handler are the service's
 * own; only the retry schedule is shortened (the production schedule runs for a minute) and the
 * spine send is made to fail.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ResourceEventSpineRetryBrokerTest {

    /** Retries after the first attempt. */
    private static final int RETRIES = 2;

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
                        "spring.kafka.consumer.group-id=fu3c-" + System.nanoTime());
    }

    private static GcsResource bucket() {
        GcsResource bucket = new GcsResource(
                new ResourceType("gcp_gcs_bucket", "Bucket", new CloudService("gcs", "GCS", CloudProvider.GCP)),
                "demo-bucket", "gs://demo-bucket");
        bucket.setProjectId("demo-project");
        return bucket;
    }

    @Test
    void aSpinePublishExceptionIsRetriedAndThenDeadLettered() throws Exception {
        String source = "fu3c.resource-events.spine-down";
        broker.addTopics(new NewTopic(source, 3, (short) 1), new NewTopic(source + ".DLT", 1, (short) 1));

        // The spine send never succeeds: the broker does not acknowledge it.
        AtomicInteger spineSends = new AtomicInteger();
        KafkaTemplate<String, String> spine = mock(KafkaTemplate.class);
        when(spine.send(any(String.class), any(String.class), any(String.class))).thenAnswer(call -> {
            spineSends.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        });
        CrawlerAssetSpineBridge bridge = new CrawlerAssetSpineBridge(spine, new ObjectMapper(), new SimpleMeterRegistry(),
                Duration.ofSeconds(5));
        EventProcessorRegistry registry = mock(EventProcessorRegistry.class);
        doReturn(List.of(bridge)).when(registry).getProcessorsForEvent(any(ResourceEvent.class));
        ResourceEventListener listener = new ResourceEventListener(registry);

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ConcurrentKafkaListenerContainerFactory<String, ResourceEvent> factory =
                    context.getBean(KafkaReliabilityConfiguration.RESOURCE_EVENT_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
            KafkaTemplate deadLetterTemplate = context.getBean("resourceEventKafkaTemplate", KafkaTemplate.class);
            ConcurrentMessageListenerContainer<String, ResourceEvent> container = factory.createContainer(source);
            // The factory's own error handler is the production one (retry, then <topic>.DLT); only the
            // schedule is shortened here so the test does not run for a minute.
            container.setCommonErrorHandler(DlapKafkaFactoryBuilder.deadLetterErrorHandler(deadLetterTemplate,
                    new FixedBackOff(100L, RETRIES)));
            container.setupMessageListener((AcknowledgingMessageListener<String, ResourceEvent>) (record, ack) -> listener.handleResourceEvent(record.value(), ack));
            container.start();
            try {
                ResourceEvent crawl = new ResourceEvent("GCP", "demo-project", ChangeType.CREATE, bucket());
                crawl.setEventId("fu3c-spine-down");
                deadLetterTemplate.send(source, "demo-bucket", crawl).get(30, TimeUnit.SECONDS);

                ConsumerRecord<String, String> deadLetter = readOne(source + ".DLT", "demo-bucket");

                assertThat(deadLetter.key()).isEqualTo("demo-bucket");
                assertThat(spineSends.get()).as("attempts to publish to the spine: first try plus retries")
                        .isEqualTo(1 + RETRIES);
            } finally {
                container.stop();
            }
        });
    }

    private static ConsumerRecord<String, String> readOne(String topic, String key) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "group.id", "fu3c-dlt-reader-" + System.nanoTime(),
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
