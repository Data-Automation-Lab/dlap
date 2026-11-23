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
package com.dlap.logingest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder;

/**
 * SEC-03: a record that fails in da-logingest is dead-lettered to {@code <topic>.DLT} without the
 * partition of the source record, so a dead-letter topic with fewer partitions than its source (for
 * example one the broker auto-created with the default count) still accepts it. The old code asked
 * for the same partition number and lost the record when that partition did not exist.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DeadLetterPartitionTest {

    @Test
    void aFailedRecordGoesToTheDeadLetterTopicWithoutTheSourcePartition() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaReliabilityConfiguration config = new KafkaReliabilityConfiguration(new KafkaProperties(),
                new DefaultListableBeanFactory());
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = config
                .logingestManualAckKafkaListenerContainerFactory(config.logingestManualAckConsumerFactory(), template);
        DefaultErrorHandler handler = (DefaultErrorHandler) factory.createContainer("sec03.probe").getCommonErrorHandler();
        ConsumerRecord<String, Object> failed = new ConsumerRecord<>("dlap.log.event.normalized", 7, 42L, "k", "v");

        // A ClassCastException is not retryable, so the record is recovered at once.
        boolean recovered = handler.handleOne(new ClassCastException("probe"), failed, mock(Consumer.class),
                mock(MessageListenerContainer.class));

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(recovered).isTrue();
        assertThat(sent.getValue().topic()).isEqualTo("dlap.log.event.normalized.DLT");
        assertThat(sent.getValue().partition()).as("the producer picks the partition").isNull();
    }

    @Test
    void controlTheSourcePartitionPolicyWouldHaveAskedForPartitionSeven() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        DefaultErrorHandler old = DlapKafkaFactoryBuilder.deadLetterErrorHandler(
                DlapKafkaFactoryBuilder.deadLetterRecoverer(template,
                        DlapKafkaFactoryBuilder.DeadLetterPartitioning.SAME_AS_SOURCE),
                DlapKafkaFactoryBuilder.exponentialBackOff());

        // The dead-letter topic has the partition the source record came from, so Spring Kafka keeps it.
        Consumer consumer = mock(Consumer.class);
        when(consumer.partitionsFor(eq("dlap.log.event.normalized.DLT"), any(Duration.class)))
                .thenReturn(IntStream.range(0, 8)
                        .mapToObj(i -> new PartitionInfo("dlap.log.event.normalized.DLT", i, null, null, null))
                        .toList());

        old.handleOne(new ClassCastException("probe"),
                new ConsumerRecord<String, Object>("dlap.log.event.normalized", 7, 42L, "k", "v"),
                consumer, mock(MessageListenerContainer.class));

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().partition()).as("control: the policy the old code used").isEqualTo(7);
    }
}
