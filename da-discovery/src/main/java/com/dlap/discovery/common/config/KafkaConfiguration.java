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

package com.dlap.discovery.common.config;

import java.util.Map;
import java.util.concurrent.Executor;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.event.service.type.ResourceEvent;

/**
 * Kafka configuration for the discovery service: topics, producers, the default listener factory
 * and the async executor.
 *
 * <p>Every client is built by the shared {@link DlapKafkaFactoryBuilder} (KAFKA-01) from the
 * {@code spring.kafka.*} properties, so bootstrap servers, security and SASL settings reach all of
 * them. Each producer is idempotent with {@code acks=all}; the serializers are named here because
 * they fix the wire format (String keys, Jackson 2 JSON values, or Strings for
 * {@code stringKafkaTemplate}).
 */
@Configuration
@EnableKafka
@EnableAsync
@SuppressWarnings("removal")
public class KafkaConfiguration {

    /** Producer retries unless {@code spring.kafka.producer.retries} says otherwise. */
    private static final Map<String, Object> PRODUCER_DEFAULTS = Map.of(ProducerConfig.RETRIES_CONFIG, 3);

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Value("${kafka.topics.log-events:dlap.log.event.normalized}")
    private String logEventsTopic;

    @Value("${resource.kafka.topics.resource-discoveries:resource-discoveries}")
    private String resourceDiscoveriesTopic;

    @Value("${resource.kafka.topics.resource-updates:resource-updates}")
    private String resourceUpdatesTopic;

    @Value("${resource.kafka.topics.Crawler-registrations:Crawler-registrations}")
    private String CrawlerRegistrationsTopic;

    @Value("${resource.kafka.topics.resource-removals:resource-removals}")
    private String resourceRemovalsTopic;

    @Value("${kafka.admin.partitions:3}")
    private int defaultPartitions;

    @Value("${kafka.admin.replication-factor:1}")
    private int defaultReplicationFactor;

    private final DlapKafkaFactoryBuilder kafka;

    /**
     * Builds the clients from the bound properties.
     *
     * @param kafkaProperties the bound {@code spring.kafka.*} properties
     * @param beanFactory     the context, for the connection details and the factory customizers
     */
    public KafkaConfiguration(KafkaProperties kafkaProperties, BeanFactory beanFactory) {
        this.kafka = DlapKafkaFactoryBuilder.of(kafkaProperties, beanFactory);
    }

    /**
     * Kafka admin client for managing topics.
     *
     * @return the admin, with the {@code spring.kafka.admin.*} settings
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        return kafka.kafkaAdmin();
    }

    /**
     * Topic for publishing newly discovered resources.
     *
     * @return the topic
     */
    @Bean
    public NewTopic resourceDiscoveriesTopic() {
        return TopicBuilder.name(resourceDiscoveriesTopic).partitions(defaultPartitions).replicas(defaultReplicationFactor).build();
    }

    /**
     * Topic for resource updates (changes to existing resources).
     *
     * @return the topic
     */
    @Bean
    public NewTopic resourceUpdatesTopic() {
        return TopicBuilder.name(resourceUpdatesTopic).partitions(defaultPartitions).replicas(defaultReplicationFactor).build();
    }

    /**
     * Topic for Crawler registrations.
     *
     * @return the topic
     */
    @Bean
    public NewTopic CrawlerRegistrationsTopic() {
        return TopicBuilder.name(CrawlerRegistrationsTopic).partitions(1).replicas(defaultReplicationFactor).build();
    }

    /**
     * Topic for resource removals.
     *
     * @return the topic
     */
    @Bean
    public NewTopic resourceRemovalsTopic() {
        return TopicBuilder.name(resourceRemovalsTopic).partitions(defaultPartitions).replicas(defaultReplicationFactor).build();
    }

    /**
     * Consumer factory for consuming resource events: JSON values whose type comes from the type
     * header, through these mappings and trusted packages unless {@code spring.kafka.*} sets them.
     * The group is {@code spring.kafka.consumer.group-id}, or {@code resource-registry}.
     *
     * @return the consumer factory
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        return kafka.typeMappedJsonConsumerFactory(Map.of(
                ConsumerConfig.GROUP_ID_CONFIG, "resource-registry",
                // SEC-03: only the two event types the type mappings below name. The entities in
                // common.model are never a valid top-level value, so a header cannot select them.
                JsonDeserializer.TRUSTED_PACKAGES, "com.dlap.discovery.event.service.type",
                JsonDeserializer.TYPE_MAPPINGS,
                "ResourceEvent:com.dlap.discovery.event.service.type.ResourceEvent,"
                        + "LogEvent:com.dlap.discovery.event.service.type.LogEvent"));
    }

    /**
     * Kafka listener container factory for consuming messages with JSON values. A record that
     * fails is retried, then published to {@code <topic>.DLT}.
     *
     * @return the listener container factory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        return kafka.listenerContainerFactory(consumerFactory(),
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(kafkaTemplate(), DlapKafkaFactoryBuilder.exponentialBackOff()));
    }

    /**
     * Producer factory for generic JSON serialized objects.
     *
     * @return ProducerFactory for objects serialized as JSON
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        return kafka.producerFactory(PRODUCER_DEFAULTS, StringSerializer.class, JsonSerializer.class);
    }

    /**
     * KafkaTemplate for sending messages with string keys and JSON-serialized
     * values.
     *
     * @return Configured KafkaTemplate
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return kafka.kafkaTemplate(producerFactory());
    }

    /**
     * KafkaTemplate specifically for LogEvent messages.
     * This is used by log analyzers to publish log events.
     *
     * @return Configured KafkaTemplate for LogEvent messages
     */
    @Bean
    public KafkaTemplate<String, LogEvent> logEventKafkaTemplate() {
        ProducerFactory<String, LogEvent> factory =
                kafka.producerFactory(PRODUCER_DEFAULTS, StringSerializer.class, JsonSerializer.class);
        return kafka.kafkaTemplate(factory);
    }

    /**
     * KafkaTemplate for sending string messages.
     * Used by DiscoveryKafkaProducer for simple string messages.
     *
     * @return Configured KafkaTemplate for String messages
     */
    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate() {
        ProducerFactory<String, String> factory =
                kafka.producerFactory(PRODUCER_DEFAULTS, StringSerializer.class, StringSerializer.class);
        return kafka.kafkaTemplate(factory);
    }

    /**
     * KafkaTemplate for sending ResourceEvent messages.
     * Used by various resource crawlers to publish resource events.
     *
     * @return Configured KafkaTemplate for ResourceEvent messages
     */
    @Bean
    public KafkaTemplate<String, ResourceEvent> resourceEventKafkaTemplate() {
        ProducerFactory<String, ResourceEvent> factory =
                kafka.producerFactory(PRODUCER_DEFAULTS, StringSerializer.class, JsonSerializer.class);
        return kafka.kafkaTemplate(factory);
    }

    /**
     * Task executor for async operations.
     * Used by @Async methods for non-blocking operations.
     *
     * @return Configured thread pool task executor
     */
    @Bean(name = "discoveryAsyncExecutor")
    public Executor discoveryAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("discovery-async-");
        executor.initialize();
        return executor;
    }
}
