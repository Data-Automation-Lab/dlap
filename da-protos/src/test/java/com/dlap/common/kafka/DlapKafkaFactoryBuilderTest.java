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

package com.dlap.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import com.dlap.common.kafka.DlapKafkaFactoryBuilder.DeadLetterPartitioning;

/**
 * KAFKA-01: what the shared builder puts into each factory. The properties are bound by Spring
 * Boot's own binder, exactly as {@code spring.kafka.*} is bound in a service.
 */
class DlapKafkaFactoryBuilderTest {

    private static KafkaProperties bind(Map<String, String> settings) {
        return bind(new MapConfigurationPropertySource(settings));
    }

    private static KafkaProperties bind(ConfigurationPropertySource source) {
        return new Binder(source).bindOrCreate("spring.kafka", KafkaProperties.class);
    }

    private static DlapKafkaFactoryBuilder builder(Map<String, String> settings) {
        return DlapKafkaFactoryBuilder.of(bind(settings), (KafkaConnectionDetails) null);
    }

    static Stream<Arguments> securitySettings() {
        return Stream.of(
                Arguments.of("spring.kafka.security.protocol", "SASL_SSL", "security.protocol"),
                Arguments.of("spring.kafka.properties.security.protocol", "SASL_PLAINTEXT", "security.protocol"),
                Arguments.of("spring.kafka.properties.sasl.mechanism", "SCRAM-SHA-512", "sasl.mechanism"),
                Arguments.of("spring.kafka.properties.sasl.jaas.config",
                        "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"u\" password=\"p\";",
                        "sasl.jaas.config"),
                Arguments.of("spring.kafka.ssl.trust-store-type", "PKCS12", "ssl.truststore.type"),
                Arguments.of("spring.kafka.client-id", "dlap-test", "client.id"));
    }

    @ParameterizedTest(name = "{0} reaches consumers, producers and admins")
    @MethodSource("securitySettings")
    void aSpringKafkaSettingReachesEveryClient(String property, String value, String clientKey) {
        DlapKafkaFactoryBuilder builder = builder(Map.of("spring.kafka.bootstrap-servers", "broker:9092", property, value));

        assertThat(builder.consumerConfig()).containsEntry(clientKey, value);
        assertThat(builder.producerConfig()).containsEntry(clientKey, value);
        assertThat(builder.adminConfig()).containsEntry(clientKey, value);
        assertThat(builder.stringConsumerFactory().getConfigurationProperties()).containsEntry(clientKey, value);
        assertThat(builder.jsonConsumerFactory(Object.class).getConfigurationProperties())
                .containsEntry(clientKey, value);
        assertThat(builder.producerFactory().getConfigurationProperties()).containsEntry(clientKey, value);
        assertThat(builder.kafkaAdmin().getConfigurationProperties()).containsEntry(clientKey, value);
    }

    @Test
    void theEnvironmentVariableFormOfASecuritySettingReachesTheClients() {
        // Critique item 5: SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL set through the environment never
        // reached the hand-built factories.
        StandardEnvironment environment = new StandardEnvironment();
        // Only the environment: an embedded broker in another test class sets JVM system properties.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // Same source name as the real process environment, so Spring Boot maps the names the same way.
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                "SPRING_KAFKA_BOOTSTRAP_SERVERS", "broker-1:9093,broker-2:9093",
                "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL", "SASL_SSL",
                "SPRING_KAFKA_PROPERTIES_SASL_MECHANISM", "PLAIN")));
        KafkaProperties properties = Binder.get(environment).bindOrCreate("spring.kafka", KafkaProperties.class);
        DlapKafkaFactoryBuilder builder = DlapKafkaFactoryBuilder.of(properties, (KafkaConnectionDetails) null);

        for (Map<String, Object> config : List.of(builder.consumerConfig(), builder.producerConfig(),
                builder.adminConfig())) {
            assertThat(config)
                    .containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "PLAIN")
                    .containsEntry("bootstrap.servers", List.of("broker-1:9093", "broker-2:9093"));
        }
    }

    @Test
    void consumerPrecedenceIsBuilderThenServiceThenProperties() {
        DlapKafkaFactoryBuilder unset = builder(Map.of());
        Map<String, Object> defaults = unset.consumerConfig(Map.of("group.id", "service-default"));
        assertThat(defaults)
                .containsEntry("auto.offset.reset", "earliest")
                .containsEntry("group.id", "service-default")
                .containsEntry("enable.auto.commit", false);

        DlapKafkaFactoryBuilder set = builder(Map.of(
                "spring.kafka.consumer.group-id", "from-properties",
                "spring.kafka.consumer.auto-offset-reset", "latest",
                "spring.kafka.consumer.properties.max.poll.records", "17"));
        assertThat(set.consumerConfig(Map.of("group.id", "service-default")))
                .containsEntry("group.id", "from-properties")
                .containsEntry("auto.offset.reset", "latest")
                .containsEntry("max.poll.records", "17");
    }

    @Test
    void autoCommitIsRefused() {
        assertThat(builder(Map.of("spring.kafka.consumer.enable-auto-commit", "false")).consumerConfig())
                .containsEntry("enable.auto.commit", false);
        assertThatThrownBy(() -> builder(Map.of("spring.kafka.consumer.enable-auto-commit", "true")).consumerConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enable.auto.commit");
    }

    @Test
    void everyProducerIsIdempotentWithAcksAll() {
        Map<String, Object> config = builder(Map.of()).producerConfig();
        assertThat(config).containsEntry("enable.idempotence", true).containsEntry("acks", "all");

        assertThat(builder(Map.of("spring.kafka.producer.acks", "-1", "spring.kafka.producer.retries", "5"))
                .producerConfig())
                .containsEntry("acks", "all")
                .containsEntry("retries", 5)
                .containsEntry("enable.idempotence", true);
        assertThat(builder(Map.of("spring.kafka.producer.properties.enable.idempotence", "true")).producerConfig())
                .containsEntry("enable.idempotence", true);
    }

    static Stream<Arguments> settingsThatBreakIdempotence() {
        return Stream.of(
                Arguments.of("spring.kafka.producer.acks", "1"),
                Arguments.of("spring.kafka.producer.retries", "0"),
                Arguments.of("spring.kafka.producer.properties.enable.idempotence", "false"),
                Arguments.of("spring.kafka.producer.properties.max.in.flight.requests.per.connection", "6"));
    }

    @ParameterizedTest(name = "{0}={1} is refused")
    @MethodSource("settingsThatBreakIdempotence")
    void aSettingThatBreaksIdempotenceIsRefused(String property, String value) {
        DlapKafkaFactoryBuilder builder = builder(Map.of(property, value));
        assertThatThrownBy(builder::producerConfig).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(builder::producerFactory).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void producerSerializersComeFromPropertiesUnlessTheServiceNamesThem() {
        DlapKafkaFactoryBuilder builder = builder(Map.of(
                "spring.kafka.producer.key-serializer", StringSerializer.class.getName(),
                "spring.kafka.producer.value-serializer", JsonSerializer.class.getName()));
        DefaultKafkaProducerFactory<Object, Object> configured = builder.producerFactory();
        assertThat(configured.getConfigurationProperties())
                .containsEntry("value.serializer", JsonSerializer.class);

        DefaultKafkaProducerFactory<String, String> named = builder.producerFactory(Map.of("retries", 3),
                StringSerializer.class, StringSerializer.class);
        assertThat(named.getConfigurationProperties())
                .containsEntry("value.serializer", StringSerializer.class)
                .containsEntry("retries", 3);
    }

    @Test
    void aTransactionIdPrefixIsCarriedOver() {
        DefaultKafkaProducerFactory<Object, Object> factory =
                builder(Map.of("spring.kafka.producer.transaction-id-prefix", "tx-")).producerFactory();
        assertThat(factory.getTransactionIdPrefix()).isEqualTo("tx-");
    }

    @Test
    void connectionDetailsApplyTheWaySpringBootAppliesThem() {
        SslBundle bundle = mock(SslBundle.class);
        KafkaConnectionDetails details = new KafkaConnectionDetails() {
            @Override
            public List<String> getBootstrapServers() {
                return List.of("container:19092");
            }

            @Override
            public String getSecurityProtocol() {
                return "SSL";
            }

            @Override
            public SslBundle getSslBundle() {
                return bundle;
            }
        };
        DlapKafkaFactoryBuilder builder = DlapKafkaFactoryBuilder.of(
                bind(Map.of("spring.kafka.bootstrap-servers", "ignored:9092")), details);

        for (Map<String, Object> config : List.of(builder.consumerConfig(), builder.producerConfig(),
                builder.adminConfig())) {
            assertThat(config)
                    .containsEntry("bootstrap.servers", List.of("container:19092"))
                    .containsEntry("security.protocol", "SSL")
                    .containsEntry("ssl.engine.factory.class", SslBundleSslEngineFactory.class)
                    .containsEntry(SslBundle.class.getName(), bundle);
        }
    }

    @Test
    void theJsonConsumerReadsObjectsAndStringsWithoutTypeHeaders() {
        // spring.json.trusted.packages in the configuration used to clash with a setter-configured
        // JsonDeserializer ("not both"); the builder configures it from the map only.
        DefaultKafkaConsumerFactory<String, Object> factory = builder(Map.of(
                "spring.kafka.consumer.properties.spring.json.trusted.packages", "*")).jsonConsumerFactory(Object.class);
        Deserializer<Object> value = factory.getValueDeserializer();
        assertThat(value).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThatCode(() -> value.configure(factory.getConfigurationProperties(), false)).doesNotThrowAnyException();

        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "com.example.Unknown".getBytes(StandardCharsets.UTF_8));
        assertThat(value.deserialize("t", headers, "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Map.of("a", 1));
        assertThat(value.deserialize("t", "\"text\"".getBytes(StandardCharsets.UTF_8))).isEqualTo("text");
    }

    /** A fixed-type value for the type-mapping test. */
    public record Sample(String name) {
    }

    @Test
    void theTypeMappedConsumerHonoursServiceMappingsAndLetsPropertiesWin() {
        Map<String, String> mapping = Map.of(
                "spring.json.trusted.packages", "com.dlap.common.kafka",
                "spring.json.type.mapping", "Sample:" + Sample.class.getName());
        Deserializer<Object> value = builder(Map.of()).typeMappedJsonConsumerFactory(mapping).getValueDeserializer();
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "Sample".getBytes(StandardCharsets.UTF_8));
        assertThat(value.deserialize("t", headers, "{\"name\":\"n\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new Sample("n"));

        DefaultKafkaConsumerFactory<String, Object> overridden = builder(Map.of(
                "spring.kafka.consumer.properties.spring.json.type.mapping", "Other:java.util.LinkedHashMap"))
                .typeMappedJsonConsumerFactory(mapping);
        assertThat(overridden.getConfigurationProperties())
                .containsEntry("spring.json.type.mapping", "Other:java.util.LinkedHashMap");
    }

    @Test
    void theStringConsumerWrapsBothDeserializers() {
        DefaultKafkaConsumerFactory<String, String> factory = builder(Map.of()).stringConsumerFactory();
        assertThat(factory.getKeyDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThat(factory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThat(factory.getValueDeserializer().deserialize("t", "v".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v");
        assertThat(builder(Map.of()).consumerFactory(new StringDeserializer(),
                new ErrorHandlingDeserializer<>(new StringDeserializer()), Map.of()).getValueDeserializer())
                .as("an already wrapped deserializer is not wrapped twice")
                .isInstanceOf(ErrorHandlingDeserializer.class);
    }

    @Test
    void theManualAckFactoryAcknowledgesManuallyAndAppliesListenerSettings() {
        DlapKafkaFactoryBuilder builder = builder(Map.of(
                "spring.kafka.listener.concurrency", "3",
                "spring.kafka.listener.missing-topics-fatal", "true",
                "spring.kafka.listener.poll-timeout", "7s",
                "spring.kafka.listener.ack-mode", "BATCH"));
        DefaultErrorHandler handler = DlapKafkaFactoryBuilder.deadLetterErrorHandler(
                mock(ConsumerRecordRecoverer.class), new FixedBackOff(0L, 0L));
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                builder.manualAckListenerContainerFactory(builder.stringConsumerFactory(), handler);

        ContainerProperties container = factory.getContainerProperties();
        assertThat(container.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(container.isMissingTopicsFatal()).isTrue();
        assertThat(container.getPollTimeout()).isEqualTo(7000L);
        ConcurrentMessageListenerContainer<String, String> created = factory.createContainer("t");
        assertThat(created.getConcurrency()).isEqualTo(3);
        assertThat(created.getCommonErrorHandler()).isSameAs(handler);

        ConcurrentKafkaListenerContainerFactory<String, String> plain =
                builder.listenerContainerFactory(builder.stringConsumerFactory(), handler);
        assertThat(plain.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
    }

    @Test
    void theAdminTakesTheAdminSettings() {
        KafkaAdmin admin = builder(Map.of("spring.kafka.admin.properties.request.timeout.ms", "1234",
                "spring.kafka.admin.client-id", "dlap-admin")).kafkaAdmin();
        assertThat(admin.getConfigurationProperties())
                .containsEntry("request.timeout.ms", "1234")
                .containsEntry("client.id", "dlap-admin");
    }

    @Test
    void theDeadLetterPartitionIsLeftToTheProducerByDefault() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("dlap.policies.actions", 2, 9L, "k", "v");
        TopicPartition chosen = DlapKafkaFactoryBuilder.deadLetterDestination(record,
                DeadLetterPartitioning.PRODUCER_CHOOSES);
        assertThat(chosen.topic()).isEqualTo("dlap.policies.actions.DLT");
        assertThat(chosen.partition()).isNegative();
        assertThat(DlapKafkaFactoryBuilder.deadLetterDestination(record, DeadLetterPartitioning.SAME_AS_SOURCE)
                .partition()).isEqualTo(2);
    }

    @Test
    void notRetryableFailuresSkipTheRetries() {
        List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
        DefaultErrorHandler handler = DlapKafkaFactoryBuilder.deadLetterErrorHandler(
                (ConsumerRecordRecoverer) (record, ex) -> recovered.add(record), new FixedBackOff(0L, 5L),
                UnsupportedOperationException.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 1L, "k", "v");

        handler.handleRemaining(new ListenerExecutionFailedException("failed", new UnsupportedOperationException("no")),
                List.of(record), mock(Consumer.class), mock(MessageListenerContainer.class));
        assertThat(recovered).containsExactly(record);
    }

    @Test
    void theTemplateRecovererPublishesToTheDeadLetterTopic() {
        assertThat(DlapKafkaFactoryBuilder.deadLetterErrorHandler(mock(KafkaOperations.class),
                DlapKafkaFactoryBuilder.exponentialBackOff())).isNotNull();
        assertThat(DlapKafkaFactoryBuilder.exponentialBackOffWithMaxRetries(3).start().nextBackOff()).isEqualTo(1000L);
        assertThat(DlapKafkaFactoryBuilder.exponentialBackOff().start().nextBackOff()).isEqualTo(1000L);
    }

    @Test
    void withNoConnectionDetailsBeanThePropertiesAloneApply() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        DlapKafkaFactoryBuilder none = DlapKafkaFactoryBuilder.of(bind(Map.of("spring.kafka.bootstrap-servers", "a:1")),
                beans);
        assertThat(none.consumerConfig()).containsEntry("bootstrap.servers", List.of("a:1"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theContextsConnectionDetailsAndFactoryCustomizersApply() {
        // Spring Boot's Micrometer client metrics arrive as factory customizers; the builder applies them
        // as Spring Boot's own auto-configured factories do.
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("kafkaConnectionDetails", (KafkaConnectionDetails) () -> List.of("context:9092"));
        ProducerFactory.Listener<Object, Object> producerListener = new ProducerFactory.Listener<>() { };
        ConsumerFactory.Listener<Object, Object> consumerListener = new ConsumerFactory.Listener<>() { };
        beans.addBean("producerMetrics", (DefaultKafkaProducerFactoryCustomizer) factory -> ((DefaultKafkaProducerFactory) factory)
                .addListener(producerListener));
        beans.addBean("consumerMetrics", (DefaultKafkaConsumerFactoryCustomizer) factory -> ((DefaultKafkaConsumerFactory) factory)
                .addListener(consumerListener));
        DlapKafkaFactoryBuilder builder = DlapKafkaFactoryBuilder.of(bind(Map.of()), beans);

        assertThat(builder.producerConfig()).containsEntry("bootstrap.servers", List.of("context:9092"));
        assertThat(builder.producerFactory().getListeners()).singleElement().isSameAs(producerListener);
        assertThat(builder.stringConsumerFactory().getListeners()).singleElement().isSameAs(consumerListener);
        assertThat(builder.jsonConsumerFactory(Object.class).getListeners()).singleElement().isSameAs(consumerListener);
        assertThat(builder.typeMappedJsonConsumerFactory(Map.of()).getListeners()).singleElement().isSameAs(consumerListener);
    }
}
