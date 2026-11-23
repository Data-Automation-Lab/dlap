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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import com.dlap.discovery.event.service.type.ResourceEvent;

/**
 * SEC-03: a record that fails in da-discovery, on either manual-acknowledgement factory, is
 * dead-lettered to {@code <topic>.DLT} without the partition of the source record, so a dead-letter
 * topic with fewer partitions than its source still accepts it.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DeadLetterPartitionTest {

    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

    private final KafkaReliabilityConfiguration config = new KafkaReliabilityConfiguration(new KafkaProperties(),
            new DefaultListableBeanFactory());

    @Test
    void theObjectFactoryDeadLettersWithoutTheSourcePartition() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = config
                .discoveryManualAckKafkaListenerContainerFactory(config.discoveryManualAckConsumerFactory(), template);

        assertDeadLetteredWithoutPartition(factory.createContainer("sec03.probe").getCommonErrorHandler());
    }

    @Test
    void theResourceEventFactoryDeadLettersWithoutTheSourcePartition() {
        ConcurrentKafkaListenerContainerFactory<String, ResourceEvent> factory = config
                .resourceEventManualAckKafkaListenerContainerFactory(config.resourceEventConsumerFactory(), template);

        assertDeadLetteredWithoutPartition(factory.createContainer("sec03.probe").getCommonErrorHandler());
    }

    private void assertDeadLetteredWithoutPartition(Object errorHandler) {
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        DefaultErrorHandler handler = (DefaultErrorHandler) errorHandler;
        ConsumerRecord<String, Object> failed = new ConsumerRecord<>("dlap.discovery.asset.discovered", 7, 42L, "k", "v");

        // A ClassCastException is not retryable, so the record is recovered at once.
        boolean recovered = handler.handleOne(new ClassCastException("probe"), failed, mock(Consumer.class),
                mock(MessageListenerContainer.class));

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(recovered).isTrue();
        assertThat(sent.getValue().topic()).isEqualTo("dlap.discovery.asset.discovered.DLT");
        assertThat(sent.getValue().partition()).as("the producer picks the partition").isNull();
    }
}
