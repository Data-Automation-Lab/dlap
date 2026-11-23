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

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;
import com.dlap.discovery.event.service.type.ResourceEvent;

/**
 * Manual acknowledgment and dead-letter recovery for the da-discovery consumers, built by the
 * shared {@link DlapKafkaFactoryBuilder} (KAFKA-01), so every {@code spring.kafka.*} setting,
 * security and SASL included, reaches them. Expose {@link #MANUAL_ACK_FACTORY} on every
 * {@code @KafkaListener} via {@code containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY}.
 *
 * <p>Both factories retry from 1 s, doubling, for up to 60 s, then publish to {@code <topic>.DLT}
 * with the partition left to the producer.
 */
@Configuration
public class KafkaReliabilityConfiguration {

    /** Bean name of the manual-acknowledgement factory whose values are JSON read as {@code Object}. */
    public static final String MANUAL_ACK_FACTORY = "discoveryManualAckKafkaListenerContainerFactory";

    /**
     * Dedicated factory for the resource-events topic, whose value is always a
     * {@link ResourceEvent}. The shared {@link #MANUAL_ACK_FACTORY} deserializes to
     * {@code Object} (LinkedHashMap) with type headers disabled, so a listener typed
     * on {@code ResourceEvent} cannot convert the map and every record dead-letters.
     * This factory fixes the value type to {@code ResourceEvent} so crawler events
     * actually reach their processors (CLAUDE.md section 11: no silent DLQ on the happy path).
     */
    public static final String RESOURCE_EVENT_FACTORY = "resourceEventManualAckKafkaListenerContainerFactory";

    private final DlapKafkaFactoryBuilder kafka;

    /**
     * Builds the clients from the bound properties.
     *
     * @param kafkaProperties the bound {@code spring.kafka.*} properties
     * @param beanFactory     the context, for the connection details and the factory customizers
     */
    public KafkaReliabilityConfiguration(KafkaProperties kafkaProperties, BeanFactory beanFactory) {
        this.kafka = DlapKafkaFactoryBuilder.of(kafkaProperties, beanFactory);
    }

    /**
     * JSON values read as {@code Object}: a {@code Map} for a JSON object, a {@code String} for a
     * JSON string; type headers are ignored.
     *
     * @return the consumer factory behind {@link #MANUAL_ACK_FACTORY}
     */
    @Bean
    public ConsumerFactory<String, Object> discoveryManualAckConsumerFactory() {
        return kafka.jsonConsumerFactory(Object.class);
    }

    /**
     * The manual-acknowledgement listener container factory for {@code Object} values.
     *
     * @param discoveryManualAckConsumerFactory the consumer factory
     * @param kafkaTemplate                     the producer for dead-letter records
     * @return the listener container factory
     */
    @Bean(name = MANUAL_ACK_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, Object> discoveryManualAckKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> discoveryManualAckConsumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        return kafka.manualAckListenerContainerFactory(discoveryManualAckConsumerFactory,
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(kafkaTemplate, DlapKafkaFactoryBuilder.exponentialBackOff()));
    }

    /**
     * Fixed target type: the resource-events topic only ever carries {@link ResourceEvent}. The
     * polymorphic {@code CloudResource} payload resolves through the closed type-name list on
     * {@link ResourceEvent#getPayload()} ({@code @type}, SEC-03), so no value type headers are needed
     * and a message cannot name a class.
     *
     * @return the consumer factory behind {@link #RESOURCE_EVENT_FACTORY}
     */
    @Bean
    public ConsumerFactory<String, ResourceEvent> resourceEventConsumerFactory() {
        return kafka.jsonConsumerFactory(ResourceEvent.class);
    }

    /**
     * The manual-acknowledgement listener container factory for {@link ResourceEvent} values.
     *
     * @param resourceEventConsumerFactory the consumer factory
     * @param kafkaTemplate                the producer for dead-letter records
     * @return the listener container factory
     */
    @Bean(name = RESOURCE_EVENT_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, ResourceEvent> resourceEventManualAckKafkaListenerContainerFactory(
            ConsumerFactory<String, ResourceEvent> resourceEventConsumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        return kafka.manualAckListenerContainerFactory(resourceEventConsumerFactory,
                DlapKafkaFactoryBuilder.deadLetterErrorHandler(kafkaTemplate, DlapKafkaFactoryBuilder.exponentialBackOff()));
    }
}
