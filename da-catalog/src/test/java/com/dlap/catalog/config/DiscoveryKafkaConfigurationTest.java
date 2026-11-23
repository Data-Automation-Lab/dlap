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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedRuntimeException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

import com.dlap.common.logevent.LogEventDecodeException;

/**
 * The catalog consumers' error handling: a payload that is not a log event goes to the DLT on
 * the first failure, any other failure is retried first, and the DLT partition is left to
 * Kafka.
 */
class DiscoveryKafkaConfigurationTest {

    private final List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
    private final ConsumerRecordRecoverer recoverer = (record, ex) -> recovered.add(record);
    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("dlap.discovery.asset.accessed", 3, 42L, "k", "v");

    @Test
    void decodeFailuresAreDeadLetteredWithoutRetries() {
        DefaultErrorHandler handler = DiscoveryKafkaConfiguration.errorHandler(recoverer, new FixedBackOff(0L, 3L));
        Exception thrown = new ListenerExecutionFailedException("listener failed",
                new LogEventDecodeException(LogEventDecodeException.Reason.INVALID_JSON, "payload is not valid JSON",
                        null));

        handler.handleRemaining(thrown, List.of(record), mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).containsExactly(record);
    }

    @Test
    void otherFailuresAreRetriedBeforeTheDeadLetterTopic() {
        DefaultErrorHandler handler = DiscoveryKafkaConfiguration.errorHandler(recoverer, new FixedBackOff(0L, 2L));
        Exception thrown = new ListenerExecutionFailedException("listener failed",
                new IllegalStateException("lock timeout"));
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        // Not recovered yet: the handler seeks back and rethrows so the container redelivers. Spring Kafka 4
        // throws its package-private RecordInRetryException here (Spring Kafka 3 threw a KafkaException);
        // both are NestedRuntimeExceptions (BOOT-01).
        assertThatThrownBy(() -> handler.handleRemaining(thrown, List.of(record), consumer, container))
                .isInstanceOf(NestedRuntimeException.class);
        assertThatThrownBy(() -> handler.handleRemaining(thrown, List.of(record), consumer, container))
                .isInstanceOf(NestedRuntimeException.class);
        assertThat(recovered).as("two retries are still due").isEmpty();

        handler.handleRemaining(thrown, List.of(record), consumer, container);
        assertThat(recovered).containsExactly(record);
    }

    @Test
    void theDeadLetterPartitionIsLeftToKafka() {
        TopicPartition dlt = DiscoveryKafkaConfiguration.dltPartition("dlap.log.event.normalized");

        assertThat(dlt.topic()).isEqualTo("dlap.log.event.normalized.DLT");
        assertThat(dlt.partition()).as("negative means the producer picks the partition").isNegative();
    }
}
