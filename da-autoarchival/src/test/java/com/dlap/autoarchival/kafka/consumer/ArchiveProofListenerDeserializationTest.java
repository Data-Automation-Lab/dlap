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

package com.dlap.autoarchival.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;

import com.dlap.autoarchival.config.KafkaReliabilityConfiguration;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Found by the live harness run: the proof listener received "UNSUPPORTED_PAYLOAD_TYPE" for every event on
 * {@code dlap.log.event.normalized}. This test reads a real event with the service's own consumer factory,
 * exactly as the container does, and hands the value to the listener's decode step.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ArchiveProofListenerDeserializationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(KafkaReliabilityConfiguration.class);

    @Test
    void aNormalizedEventReadByTheServicesConsumerFactoryDecodesBackToTheSameEvent() {
        DlapLogEventCodec codec = DlapLogEventCodec.defaults();
        DlapLogEvent event = DlapLogEvent.newBuilder().setEventId("ev-1").setOperation("LifecycleTransition")
                .setSourceProvider(SourceProvider.AWS).setResultStatus(ResultStatus.SUCCESS)
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri("s3://pilot/finance/q1.csv")
                        .putTechnicalMetadata("storageClass", "GLACIER_IR"))
                .build();
        byte[] wire = codec.encode(event).getBytes(StandardCharsets.UTF_8);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ConsumerFactory factory = context.getBean("policyActionConsumerFactory", ConsumerFactory.class);
            Object value = factory.getValueDeserializer().deserialize("dlap.log.event.normalized", new RecordHeaders(), wire);

            DlapLogEvent decoded = codec.decode(value);

            assertThat(decoded.getEventId()).as("value class " + value.getClass().getName()).isEqualTo("ev-1");
            assertThat(decoded.getTargetResource().getCanonicalUri()).isEqualTo("s3://pilot/finance/q1.csv");
            assertThat(decoded.getTargetResource().getTechnicalMetadataMap()).containsEntry("storageClass", "GLACIER_IR");
        });
    }
}
