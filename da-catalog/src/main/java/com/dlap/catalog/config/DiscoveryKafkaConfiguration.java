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

package com.dlap.catalog.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.common.kafka.DlapKafkaFactoryBuilder.DeadLetterPartitioning;
import com.dlap.common.logevent.LogEventDecodeException;

/**
 * Kafka clients of da-catalog, all built by the shared {@link DlapKafkaFactoryBuilder} (KAFKA-01),
 * so every {@code spring.kafka.*} setting, security and SASL included, reaches them.
 *
 * <p>{@code kafkaProducerFactory} is the idempotent ({@code acks=all}) factory behind Spring
 * Boot's auto-configured {@code KafkaTemplate}, with the {@code spring.kafka.producer.*}
 * serializers.
 *
 * <p>{@link #MANUAL_ACK_FACTORY} is the manual-ack listener factory shared by every catalog
 * consumer (String payloads, read through {@code DlapLogEventCodec}). A listener acknowledges only
 * after its transaction commits. A failure is retried with exponential backoff (3 retries from
 * 1 s) and then published to {@code <topic>.DLT}. A payload that is not a log event
 * ({@link LogEventDecodeException}) cannot succeed on a retry, so it goes to the DLT at once. The
 * DLT record lets Kafka choose the partition, so a DLT with fewer partitions than its source still
 * accepts it (design risk B9.10).
 */
@Configuration
@EnableKafka
public class DiscoveryKafkaConfiguration {

    /** Bean name of the manual-acknowledgement listener container factory. */
    public static final String MANUAL_ACK_FACTORY = "discoveryManualAckKafkaListenerContainerFactory";

    private final DlapKafkaFactoryBuilder kafka;

    /**
     * Builds the clients from the bound properties.
     *
     * @param kafkaProperties the bound {@code spring.kafka.*} properties
     * @param beanFactory     the context, for the connection details and the factory customizers
     */
    public DiscoveryKafkaConfiguration(KafkaProperties kafkaProperties, BeanFactory beanFactory) {
        this.kafka = DlapKafkaFactoryBuilder.of(kafkaProperties, beanFactory);
    }

    /**
     * The producer factory Spring Boot's auto-configured {@code KafkaTemplate} uses.
     *
     * @return an idempotent producer factory
     */
    @Bean
    public DefaultKafkaProducerFactory<Object, Object> kafkaProducerFactory() {
        return kafka.producerFactory();
    }

    /**
     * The String consumer factory behind {@link #MANUAL_ACK_FACTORY}.
     *
     * @return the consumer factory
     */
    @Bean
    public ConsumerFactory<String, String> discoveryStringConsumerFactory() {
        return kafka.stringConsumerFactory();
    }

    /**
     * The manual-acknowledgement listener container factory with dead-letter recovery.
     *
     * @param discoveryStringConsumerFactory the consumer factory
     * @param kafkaTemplate                  the producer for dead-letter records
     * @return the listener container factory
     */
    @Bean(name = MANUAL_ACK_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> discoveryManualAckKafkaListenerContainerFactory(
            ConsumerFactory<String, String> discoveryStringConsumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        return kafka.manualAckListenerContainerFactory(discoveryStringConsumerFactory,
                errorHandler(deadLetterRecoverer(kafkaTemplate), defaultBackOff()));
    }

    /**
     * Publishes a failed record to {@code <topic>.DLT}, letting Kafka choose the partition.
     *
     * @param template the producer
     * @return the recoverer
     */
    static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaOperations<?, ?> template) {
        return DlapKafkaFactoryBuilder.deadLetterRecoverer(template, DeadLetterPartitioning.PRODUCER_CHOOSES);
    }

    /**
     * The dead-letter destination of a topic. A negative partition means "no partition set",
     * so the producer's partitioner picks one.
     *
     * @param topic the source topic
     * @return {@code <topic>.DLT}, partition -1
     */
    static TopicPartition dltPartition(String topic) {
        return DlapKafkaFactoryBuilder.deadLetterDestination(topic, 0, DeadLetterPartitioning.PRODUCER_CHOOSES);
    }

    /**
     * The error handler: retries with {@code backOff}, then the recoverer; decode failures skip
     * the retries.
     *
     * @param recoverer what happens after the last attempt
     * @param backOff   the retry schedule
     * @return the handler
     */
    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff) {
        return DlapKafkaFactoryBuilder.deadLetterErrorHandler(recoverer, backOff, LogEventDecodeException.class);
    }

    /**
     * Three retries, 1 s then doubling.
     *
     * @return the retry schedule
     */
    static BackOff defaultBackOff() {
        return DlapKafkaFactoryBuilder.exponentialBackOffWithMaxRetries(3);
    }
}
