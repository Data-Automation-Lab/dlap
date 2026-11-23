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

package com.dlap.logingest.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.support.ProducerListener;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * KAFKA-01 acceptance for the producer {@link LogEventPublisher} builds for itself (SPINE-01): a
 * {@code spring.kafka.*} setting given on top of the service's own {@code application.properties}
 * reaches that producer as it reaches the factories of {@code KafkaReliabilityConfiguration}
 * ({@code KafkaFactoriesHonourSpringKafkaPropertiesTest} cannot see it, because it is not a bean),
 * it is idempotent with {@code acks=all}, it reports client metrics, and its serializers are the
 * String serializers whatever {@code spring.kafka.producer.value-serializer} says.
 *
 * <p>Scope: a slice context (Spring Boot's Kafka auto-configuration and metrics, the service's real
 * configuration files); no broker is needed, the test reads the configuration the factory hands to
 * the Kafka client.
 */
@SuppressWarnings({"removal", "rawtypes", "unchecked"})
class LogEventPublisherKafkaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    static Stream<Arguments> springKafkaSettings() {
        return Stream.of(
                Arguments.of("spring.kafka.security.protocol", "SASL_SSL", "security.protocol", "SASL_SSL"),
                Arguments.of("spring.kafka.properties.sasl.mechanism", "SCRAM-SHA-512", "sasl.mechanism",
                        "SCRAM-SHA-512"),
                Arguments.of("spring.kafka.ssl.trust-store-type", "PKCS12", "ssl.truststore.type", "PKCS12"),
                Arguments.of("spring.kafka.client-id", "logingest-probe", "client.id", "logingest-probe"),
                Arguments.of("spring.kafka.bootstrap-servers", "broker-a:9093,broker-b:9093", "bootstrap.servers",
                        List.of("broker-a:9093", "broker-b:9093")));
    }

    private static LogEventPublisher publisherOf(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        return new LogEventPublisher(context.getBean(KafkaProperties.class), context.getBeanFactory(),
                context.getBeanFactory().getBeanProvider(ResolvableType.forClassWithGenerics(ProducerListener.class,
                        Object.class, Object.class)), Duration.ofSeconds(1));
    }

    @ParameterizedTest(name = "{0} reaches the publisher's producer")
    @MethodSource("springKafkaSettings")
    void aSpringKafkaSettingReachesThePublishersProducer(String property, String value, String clientKey,
                                                          Object expected) {
        runner.withPropertyValues(property + "=" + value).run(context -> {
            LogEventPublisher publisher = publisherOf(context);
            try {
                assertThat(publisher.template().getProducerFactory().getConfigurationProperties())
                        .containsEntry(clientKey, expected);
            } finally {
                publisher.destroy();
            }
        });
    }

    @Test
    void theProducerDoesNotWaitToFillABatchUnlessTheServiceSaysSo() {
        runner.run(context -> {
            LogEventPublisher publisher = publisherOf(context);
            try {
                assertThat(publisher.template().getProducerFactory().getConfigurationProperties())
                        .as("each send is awaited, so no linger by default").containsEntry("linger.ms", 0);
            } finally {
                publisher.destroy();
            }
        });
        runner.withPropertyValues("spring.kafka.producer.properties.linger.ms=25").run(context -> {
            LogEventPublisher publisher = publisherOf(context);
            try {
                assertThat(publisher.template().getProducerFactory().getConfigurationProperties())
                        .as("a spring.kafka setting wins over the default").containsEntry("linger.ms", "25");
            } finally {
                publisher.destroy();
            }
        });
    }

    @Test
    void theProducerIsIdempotentReportsMetricsAndSerializesStrings() {
        runner.run(context -> {
            LogEventPublisher publisher = publisherOf(context);
            try {
                var factory = publisher.template().getProducerFactory();
                Map<String, Object> config = factory.getConfigurationProperties();
                assertThat(config).containsEntry("enable.idempotence", true).containsEntry("acks", "all")
                        .containsEntry("key.serializer", StringSerializer.class)
                        // application.properties names the JSON serializer for the shared template; the
                        // wire form of this producer is fixed in code.
                        .containsEntry("value.serializer", StringSerializer.class);
                assertThat(factory.getListeners()).as("client metrics")
                        .anyMatch(MicrometerProducerListener.class::isInstance);
            } finally {
                publisher.destroy();
            }
        });
    }
}
