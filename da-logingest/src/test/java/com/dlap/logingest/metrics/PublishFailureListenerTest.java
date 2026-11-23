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

package com.dlap.logingest.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

/**
 * A send that fails asynchronously is counted by topic. That Spring Boot wires this listener into the
 * service's {@code KafkaTemplate} is asserted in {@code QuarantinePipelineIntegrationTest}.
 */
class PublishFailureListenerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();
    private final LogIngestMetrics metrics = new LogIngestMetrics(meters, new ParseTelemetry(clock, props), clock,
            props, 2);

    @Test
    void aFailedSendIsCountedUnderItsTopic() {
        PublishFailureListener listener = new PublishFailureListener(metrics);

        listener.onError(new ProducerRecord<>("dlap.logingest.binding.status", "b1", "payload"), null,
                new org.apache.kafka.common.errors.TimeoutException("broker unreachable"));

        assertThat(meters.find("dlap.logingest.publish.failures").tag("topic", "dlap.logingest.binding.status")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aTopicThatIsNotADlapTopicAndAMissingRecordAreCountedAsOther() {
        PublishFailureListener listener = new PublishFailureListener(metrics);

        listener.onError(new ProducerRecord<>("customer.topic", "k", "v"), null, new RuntimeException("x"));
        listener.onError(null, null, new RuntimeException("y"));

        assertThat(meters.find("dlap.logingest.publish.failures").tag("topic", "other").counter().count())
                .isEqualTo(2.0);
    }
}
