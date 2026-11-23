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

package com.dlap.autolabel.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * KAFKA-01 acceptance for da-autolabel: a {@code spring.kafka.*} setting, given on top of the
 * service's own {@code application.properties}, reaches every Kafka client the service builds,
 * every producer is idempotent with {@code acks=all}, and the serializers and deserializers (the
 * wire format) are the ones the service had before the shared builder.
 *
 * <p>Scope: a slice context (Spring Boot's Kafka auto-configuration and metrics plus the service's
 * Kafka configuration, with the service's real configuration files). No broker is needed: the
 * test reads the configuration the factories would hand to the Kafka clients.
 */
@SuppressWarnings({"removal", "rawtypes", "unchecked"})
class KafkaFactoriesHonourSpringKafkaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(KafkaReliabilityConfiguration.class);

    static Stream<Arguments> springKafkaSettings() {
        return Stream.of(
                Arguments.of("spring.kafka.security.protocol", "SASL_SSL", "security.protocol", "SASL_SSL"),
                Arguments.of("spring.kafka.properties.security.protocol", "SSL", "security.protocol", "SSL"),
                Arguments.of("spring.kafka.properties.sasl.mechanism", "SCRAM-SHA-512", "sasl.mechanism",
                        "SCRAM-SHA-512"),
                Arguments.of("spring.kafka.properties.sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=u password=p;",
                        "sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=u password=p;"),
                Arguments.of("spring.kafka.ssl.trust-store-type", "PKCS12", "ssl.truststore.type", "PKCS12"),
                Arguments.of("spring.kafka.bootstrap-servers", "broker-a:9093,broker-b:9093", "bootstrap.servers",
                        List.of("broker-a:9093", "broker-b:9093")));
    }

    @ParameterizedTest(name = "{0} reaches every client")
    @MethodSource("springKafkaSettings")
    void aSpringKafkaSettingReachesEveryClient(String property, String value, String clientKey, Object expected) {
        runner.withPropertyValues(property + "=" + value)
                .run(context -> assertEveryClientHas(context, clientKey, expected));
    }

    @Test
    void theEnvironmentVariableFormReachesEveryClient() {
        // Critique item 5: SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL never reached the hand-built factories.
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                "kafka01-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL", "SASL_SSL",
                                        "SPRING_KAFKA_PROPERTIES_SASL_MECHANISM", "PLAIN"))))
                .run(context -> {
                    assertEveryClientHas(context, "security.protocol", "SASL_SSL");
                    assertEveryClientHas(context, "sasl.mechanism", "PLAIN");
                });
    }

    @Test
    void everyProducerIsIdempotentAndKeepsItsSerializer() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("kafkaTemplate", JsonSerializer.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Map<String, KafkaTemplate> templates = context.getBeansOfType(KafkaTemplate.class);
            assertThat(templates).containsOnlyKeys(expected.keySet());
            templates.forEach((name, template) -> {
                ProducerFactory<?, ?> factory = template.getProducerFactory();
                assertThat(factory.getConfigurationProperties()).as(name)
                        .containsEntry("enable.idempotence", true)
                        .containsEntry("acks", "all")
                        .containsEntry("key.serializer", StringSerializer.class)
                        .containsEntry("value.serializer", expected.get(name));
                assertThat(factory.getListeners()).as(name + " client metrics")
                        .anyMatch(MicrometerProducerListener.class::isInstance);
            });
        });
    }

    @Test
    void everyConsumerKeepsItsDeserializerAndNeverAutoCommits() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : List.<String>of()) {
                ConsumerFactory<String, Object> factory = context.getBean(name, ConsumerFactory.class);
                assertThat(factory.getValueDeserializer()).as(name).isInstanceOf(ErrorHandlingDeserializer.class);
                assertThat(factory.getValueDeserializer().deserialize("t", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
                        .as(name + " reads a JSON object as a Map").isEqualTo(Map.of("a", 1));
            }
            for (String name : List.<String>of("autolabelManualAckConsumerFactory")) {
                ConsumerFactory<String, Object> factory = context.getBean(name, ConsumerFactory.class);
                assertThat(factory.getValueDeserializer().deserialize("t", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
                        .as(name + " delivers the raw String").isEqualTo("{\"a\":1}");
            }
            context.getBeansOfType(ConsumerFactory.class).forEach((name, factory) -> {
                assertThat(factory.getConfigurationProperties()).as(name).containsEntry("enable.auto.commit", false);
                assertThat(factory.getListeners()).as(name + " client metrics")
                        .anyMatch(MicrometerConsumerListener.class::isInstance);
            });
        });
    }

    @Test
    void theManualAckFactoriesAcknowledgeManuallyAndDeadLetter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : List.<String>of(KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)) {
                ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                        context.getBean(name, ConcurrentKafkaListenerContainerFactory.class);
                assertThat(factory.getContainerProperties().getAckMode()).as(name)
                        .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
                assertThat(factory.createContainer("kafka01.probe").getCommonErrorHandler()).as(name)
                        .isInstanceOf(DefaultErrorHandler.class);
            }
        });
    }

    private static void assertEveryClientHas(AssertableApplicationContext context, String key, Object expected) {
        assertThat(context).hasNotFailed();
        List<String> checked = new ArrayList<>();
        context.getBeansOfType(ConsumerFactory.class).forEach((name, factory) -> {
            assertThat(factory.getConfigurationProperties()).as("consumer factory " + name).containsEntry(key, expected);
            checked.add(name);
        });
        context.getBeansOfType(KafkaTemplate.class).forEach((name, template) -> {
            assertThat(template.getProducerFactory().getConfigurationProperties()).as("producer of " + name)
                    .containsEntry(key, expected);
            checked.add(name);
        });
        context.getBeansOfType(KafkaAdmin.class).forEach((name, admin) -> {
            assertThat(admin.getConfigurationProperties()).as("admin " + name).containsEntry(key, expected);
            checked.add(name);
        });
        assertThat(checked).as("clients checked").hasSizeGreaterThanOrEqualTo(3);
    }
}
