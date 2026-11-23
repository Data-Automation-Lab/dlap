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

package com.dlap.reporting.config;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;

/**
 * Kafka clients of da-reporting, all built by the shared {@link DlapKafkaFactoryBuilder}
 * (KAFKA-01), so every {@code spring.kafka.*} setting, security and SASL included, reaches them.
 *
 * <ul>
 *   <li>{@code kafkaProducerFactory}: the factory behind Spring Boot's auto-configured
 *       {@code KafkaTemplate}; serializers from {@code spring.kafka.producer.*}, idempotent,
 *       {@code acks=all}.</li>
 *   <li>{@link #MANUAL_ACK_FACTORY}: manual acknowledgement, JSON values read as {@code Object}
 *       (a {@code Map} for a JSON object, a {@code String} for a JSON string) without type
 *       headers; retries from 1 s, doubling, for up to 60 s, then {@code <topic>.DLT}. Use it on
 *       every {@code @KafkaListener} via {@code containerFactory =
 *       KafkaConsumerConfig.MANUAL_ACK_FACTORY}.</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

    /** Bean name of the manual-acknowledgement listener container factory. */
    public static final String MANUAL_ACK_FACTORY = "reportingManualAckKafkaListenerContainerFactory";

    private final DlapKafkaFactoryBuilder kafka;

    /**
     * Builds the clients from the bound properties.
     *
     * @param kafkaProperties the bound {@code spring.kafka.*} properties
     * @param beanFactory     the context, for the connection details and the factory customizers
     */
    public KafkaConsumerConfig(KafkaProperties kafkaProperties, BeanFactory beanFactory) {
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
     * The consumer factory behind {@link #MANUAL_ACK_FACTORY}.
     *
     * @return the consumer factory
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        return kafka.jsonConsumerFactory(Object.class);
    }

    /**
     * The manual-acknowledgement listener container factory with dead-letter recovery.
     *
     * @param consumerFactory the consumer factory
     * @param kafkaTemplate the producer for dead-letter records
     * @return the listener container factory
     */
    @Bean(name = MANUAL_ACK_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        return kafka.manualAckListenerContainerFactory(consumerFactory,
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(kafkaTemplate,
                        DlapKafkaFactoryBuilder.exponentialBackOff()));
    }
}
