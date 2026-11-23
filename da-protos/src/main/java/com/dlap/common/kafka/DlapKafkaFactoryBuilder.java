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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.ProducerPostProcessor;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.StringUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The one place a DLAP service builds its Kafka clients (KAFKA-01).
 *
 * <p>Every producer factory, consumer factory, listener container factory, template and admin a
 * service declares comes from here, so the {@code spring.kafka.*} settings (bootstrap servers,
 * {@code security.protocol}, SASL and SSL, client ids, and anything under
 * {@code spring.kafka.properties.*}, {@code spring.kafka.consumer.properties.*} or
 * {@code spring.kafka.producer.properties.*}, including their environment-variable forms such as
 * {@code SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL}) reach all of them, exactly as they reach the
 * clients Spring Boot auto-configures. A {@link KafkaConnectionDetails} bean (Spring Boot's own,
 * or a Testcontainers service connection) is applied the way Spring Boot applies it, and so are the
 * {@link DefaultKafkaProducerFactoryCustomizer} and {@link DefaultKafkaConsumerFactoryCustomizer}
 * beans (Spring Boot's Micrometer client metrics among them).
 *
 * <p>Precedence, lowest first:
 * <ol>
 *   <li>the builder's defaults ({@code auto.offset.reset=earliest} for consumers);</li>
 *   <li>the defaults a service passes in code (a consumer group, trusted packages, type mappings,
 *       producer retries);</li>
 *   <li>the {@code spring.kafka.*} properties;</li>
 *   <li>the connection details (bootstrap servers, security protocol, SSL bundle);</li>
 *   <li>the serializers and deserializers a service names in code, because they fix the wire
 *       format;</li>
 *   <li>the invariants of CLAUDE.md section 11: producers are idempotent with {@code acks=all},
 *       consumers never auto-commit. A setting that contradicts an invariant fails the build of
 *       the factory with an {@link IllegalStateException} instead of being silently ignored.</li>
 * </ol>
 *
 * <p>Failed records go to {@code <topic>.DLT}. By default the dead-letter record carries no
 * partition, so the producer's partitioner places it (by key) and a dead-letter topic with fewer
 * partitions than its source still accepts it. A record that failed deserialization keeps its
 * original bytes: the recoverer sends a {@code byte[]} value through a second template of the
 * same connection settings whose value serializer is {@link ByteArraySerializer}, and every other
 * value through the service's own template (Q-404).
 *
 * <p>This class holds no state beyond the two inputs and is safe to share.
 */
public final class DlapKafkaFactoryBuilder {

    /** Suffix of every dead-letter topic: {@code <source topic>.DLT}. */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** The JSON deserializer setting that turns type headers on or off. */
    static final String USE_TYPE_HEADERS = "spring.json.use.type.headers";

    private static final Logger log = LoggerFactory.getLogger(DlapKafkaFactoryBuilder.class);

    private static final Set<String> ACKS_ALL = Set.of("all", "-1");

    private static final int MAX_IDEMPOTENT_IN_FLIGHT = 5;

    /** How a dead-letter record picks its partition. */
    public enum DeadLetterPartitioning {
        /** No partition on the record; the producer's partitioner chooses (the default). */
        PRODUCER_CHOOSES,
        /** The source record's partition; the dead-letter topic must have at least as many. */
        SAME_AS_SOURCE
    }

    private final KafkaProperties properties;

    private final Supplier<KafkaConnectionDetails> connectionDetails;

    private final Supplier<Stream<DefaultKafkaProducerFactoryCustomizer>> producerCustomizers;

    private final Supplier<Stream<DefaultKafkaConsumerFactoryCustomizer>> consumerCustomizers;

    private DlapKafkaFactoryBuilder(KafkaProperties properties, Supplier<KafkaConnectionDetails> connectionDetails,
            Supplier<Stream<DefaultKafkaProducerFactoryCustomizer>> producerCustomizers,
            Supplier<Stream<DefaultKafkaConsumerFactoryCustomizer>> consumerCustomizers) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.connectionDetails = connectionDetails;
        this.producerCustomizers = producerCustomizers;
        this.consumerCustomizers = consumerCustomizers;
    }

    /**
     * A builder over the bound {@code spring.kafka.*} properties and, from the application
     * context, the Kafka connection details and the producer and consumer factory customizers.
     * The beans are looked up when a factory is built, not now.
     *
     * @param properties  Spring Boot's bound {@code spring.kafka.*} properties
     * @param beanFactory the application context
     * @return the builder
     */
    public static DlapKafkaFactoryBuilder of(KafkaProperties properties, BeanFactory beanFactory) {
        ObjectProvider<KafkaConnectionDetails> details = beanFactory.getBeanProvider(KafkaConnectionDetails.class);
        ObjectProvider<DefaultKafkaProducerFactoryCustomizer> producers =
                beanFactory.getBeanProvider(DefaultKafkaProducerFactoryCustomizer.class);
        ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> consumers =
                beanFactory.getBeanProvider(DefaultKafkaConsumerFactoryCustomizer.class);
        return new DlapKafkaFactoryBuilder(properties, details::getIfAvailable, producers::orderedStream,
                consumers::orderedStream);
    }

    /**
     * A builder over the given properties and connection details, with no customizers.
     *
     * @param properties        the {@code spring.kafka.*} properties
     * @param connectionDetails the connection details, or {@code null} for the properties alone
     * @return the builder
     */
    public static DlapKafkaFactoryBuilder of(KafkaProperties properties, KafkaConnectionDetails connectionDetails) {
        return new DlapKafkaFactoryBuilder(properties, () -> connectionDetails, Stream::empty, Stream::empty);
    }

    // ------------------------------------------------------------------ consumers

    /**
     * The consumer configuration with no service defaults.
     *
     * @return a new mutable map
     */
    public Map<String, Object> consumerConfig() {
        return consumerConfig(Map.of());
    }

    /**
     * The consumer configuration: builder defaults, then {@code serviceDefaults}, then the
     * {@code spring.kafka.*} properties, then the connection details, then
     * {@code enable.auto.commit=false}.
     *
     * @param serviceDefaults settings the service wants unless a property says otherwise
     * @return a new mutable map
     * @throws IllegalStateException if {@code enable.auto.commit} is configured {@code true}
     */
    public Map<String, Object> consumerConfig(Map<String, ?> serviceDefaults) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.putAll(serviceDefaults);
        config.putAll(properties.buildConsumerProperties());
        KafkaConnectionDetails details = connectionDetails.get();
        if (details != null) {
            applyConnection(config, details.getConsumer());
        }
        Object autoCommit = config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        if (autoCommit != null && Boolean.parseBoolean(autoCommit.toString())) {
            throw new IllegalStateException("enable.auto.commit=true is not allowed: DLAP listeners acknowledge "
                    + "manually (CLAUDE.md section 11); remove spring.kafka.consumer.enable-auto-commit=true");
        }
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return config;
    }

    /**
     * A consumer factory with the given deserializers, each wrapped in an
     * {@link ErrorHandlingDeserializer} so a record that cannot be read reaches the error handler
     * (and the dead-letter topic) instead of failing every poll.
     *
     * @param <K>             key type
     * @param <V>             value type
     * @param keyDeserializer   the key deserializer
     * @param valueDeserializer the value deserializer
     * @param serviceDefaults   settings the service wants unless a property says otherwise
     * @return the factory
     */
    public <K, V> DefaultKafkaConsumerFactory<K, V> consumerFactory(Deserializer<K> keyDeserializer,
            Deserializer<V> valueDeserializer, Map<String, ?> serviceDefaults) {
        return customized(new DefaultKafkaConsumerFactory<>(consumerConfig(serviceDefaults),
                errorHandling(keyDeserializer), errorHandling(valueDeserializer)));
    }

    /**
     * A consumer factory that delivers the key and the value as Strings.
     *
     * @return the factory
     */
    public DefaultKafkaConsumerFactory<String, String> stringConsumerFactory() {
        return consumerFactory(new StringDeserializer(), new StringDeserializer(), Map.of());
    }

    /**
     * A consumer factory whose value is JSON read into {@code valueType}, ignoring type headers.
     * {@code Object} yields a {@code Map} for a JSON object and a {@code String} for a JSON string.
     * The JSON settings in the configuration ({@code spring.json.trusted.packages},
     * {@code spring.json.type.mapping}) apply; type headers stay off because the target type is
     * fixed by the service.
     *
     * @param <V>       value type
     * @param valueType the target type
     * @return the factory
     */
    public <V> DefaultKafkaConsumerFactory<String, V> jsonConsumerFactory(Class<V> valueType) {
        Map<String, Object> config = consumerConfig();
        Map<String, Object> jsonConfig = new HashMap<>(config);
        jsonConfig.put(USE_TYPE_HEADERS, false);
        return customized(new DefaultKafkaConsumerFactory<>(config,
                errorHandling(new StringDeserializer()),
                errorHandling(configuredJsonDeserializer(valueType, jsonConfig))));
    }

    /**
     * A consumer factory whose value is JSON whose type comes from the {@code __TypeId__} header,
     * resolved through {@code spring.json.type.mapping} and {@code spring.json.trusted.packages}.
     * The service's defaults for those keys apply unless a property sets them.
     *
     * @param serviceDefaults settings the service wants unless a property says otherwise
     * @return the factory
     */
    public DefaultKafkaConsumerFactory<String, Object> typeMappedJsonConsumerFactory(Map<String, ?> serviceDefaults) {
        Map<String, Object> config = consumerConfig(serviceDefaults);
        return customized(new DefaultKafkaConsumerFactory<>(config,
                errorHandling(new StringDeserializer()),
                errorHandling(configuredJsonDeserializer(Object.class, config))));
    }

    // ------------------------------------------------------------------ producers

    /**
     * The producer configuration with no service defaults.
     *
     * @return a new mutable map
     */
    public Map<String, Object> producerConfig() {
        return producerConfig(Map.of());
    }

    /**
     * The producer configuration: {@code serviceDefaults}, then the {@code spring.kafka.*}
     * properties, then the connection details, then {@code enable.idempotence=true} and
     * {@code acks=all}.
     *
     * @param serviceDefaults settings the service wants unless a property says otherwise
     * @return a new mutable map
     * @throws IllegalStateException if a setting contradicts idempotence
     */
    public Map<String, Object> producerConfig(Map<String, ?> serviceDefaults) {
        Map<String, Object> config = new HashMap<>(serviceDefaults);
        config.putAll(properties.buildProducerProperties());
        KafkaConnectionDetails details = connectionDetails.get();
        if (details != null) {
            applyConnection(config, details.getProducer());
        }
        enforceIdempotence(config);
        return config;
    }

    /**
     * A producer factory whose serializers come from the configuration
     * ({@code spring.kafka.producer.key-serializer} and {@code value-serializer}). This is the
     * factory Spring Boot's auto-configured {@code KafkaTemplate} uses when a service declares it
     * as {@code kafkaProducerFactory}.
     *
     * @param <K> key type
     * @param <V> value type
     * @return the factory
     */
    public <K, V> DefaultKafkaProducerFactory<K, V> producerFactory() {
        return producerFactory(Map.of(), null, null);
    }

    /**
     * A producer factory with serializer classes named in code (they win over the properties,
     * because they fix the wire format).
     *
     * @param <K>             key type
     * @param <V>             value type
     * @param serviceDefaults settings the service wants unless a property says otherwise
     * @param keySerializer   the key serializer class, or {@code null} for the configured one
     * @param valueSerializer the value serializer class, or {@code null} for the configured one
     * @return the factory
     */
    @SuppressWarnings("rawtypes")
    public <K, V> DefaultKafkaProducerFactory<K, V> producerFactory(Map<String, ?> serviceDefaults,
            Class<? extends Serializer> keySerializer, Class<? extends Serializer> valueSerializer) {
        Map<String, Object> config = producerConfig(serviceDefaults);
        if (keySerializer != null) {
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer);
        }
        if (valueSerializer != null) {
            config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
        }
        DefaultKafkaProducerFactory<K, V> factory = new DefaultKafkaProducerFactory<>(config);
        String transactionIdPrefix = properties.getProducer().getTransactionIdPrefix();
        if (transactionIdPrefix != null) {
            factory.setTransactionIdPrefix(transactionIdPrefix);
        }
        producerCustomizers.get().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /**
     * A template over {@code producerFactory} with the {@code spring.kafka.template.*} settings.
     *
     * @param <K>             key type
     * @param <V>             value type
     * @param producerFactory the producer factory
     * @return the template
     */
    public <K, V> KafkaTemplate<K, V> kafkaTemplate(ProducerFactory<K, V> producerFactory) {
        KafkaTemplate<K, V> template = new KafkaTemplate<>(producerFactory);
        KafkaProperties.Template settings = properties.getTemplate();
        if (settings.getDefaultTopic() != null) {
            template.setDefaultTopic(settings.getDefaultTopic());
        }
        if (settings.getTransactionIdPrefix() != null) {
            template.setTransactionIdPrefix(settings.getTransactionIdPrefix());
        }
        if (settings.getCloseTimeout() != null) {
            template.setCloseTimeout(settings.getCloseTimeout());
        }
        template.setAllowNonTransactional(settings.isAllowNonTransactional());
        template.setObservationEnabled(settings.isObservationEnabled());
        return template;
    }

    // ------------------------------------------------------------------ admin

    /**
     * The admin client configuration: the {@code spring.kafka.*} properties, then the connection
     * details.
     *
     * @return a new mutable map
     */
    public Map<String, Object> adminConfig() {
        Map<String, Object> config = new HashMap<>(properties.buildAdminProperties());
        KafkaConnectionDetails details = connectionDetails.get();
        if (details != null) {
            applyConnection(config, details.getAdmin());
        }
        return config;
    }

    /**
     * A {@link KafkaAdmin} with the {@code spring.kafka.admin.*} settings.
     *
     * @return the admin
     */
    public KafkaAdmin kafkaAdmin() {
        KafkaAdmin admin = new KafkaAdmin(adminConfig());
        KafkaProperties.Admin settings = properties.getAdmin();
        if (settings.getCloseTimeout() != null) {
            admin.setCloseTimeout((int) settings.getCloseTimeout().getSeconds());
        }
        if (settings.getOperationTimeout() != null) {
            admin.setOperationTimeout((int) settings.getOperationTimeout().getSeconds());
        }
        admin.setFatalIfBrokerNotAvailable(settings.isFailFast());
        admin.setModifyTopicConfigs(settings.isModifyTopicConfigs());
        admin.setAutoCreate(settings.isAutoCreate());
        return admin;
    }

    // ------------------------------------------------------------------ listener containers

    /**
     * A record listener container factory with {@code MANUAL_IMMEDIATE} acknowledgement and the
     * {@code spring.kafka.listener.*} settings. Listeners on it take an {@code Acknowledgment}.
     *
     * @param <K>             key type
     * @param <V>             value type
     * @param consumerFactory the consumer factory
     * @param errorHandler    the error handler, usually {@link #deadLetterErrorHandler}
     * @return the factory
     */
    public <K, V> ConcurrentKafkaListenerContainerFactory<K, V> manualAckListenerContainerFactory(
            ConsumerFactory<K, V> consumerFactory, CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<K, V> factory = listenerContainerFactory(consumerFactory, errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    /**
     * A record listener container factory with the {@code spring.kafka.listener.*} settings,
     * including {@code ack-mode} when it is set (the container commits; never auto-commit).
     *
     * @param <K>             key type
     * @param <V>             value type
     * @param consumerFactory the consumer factory
     * @param errorHandler    the error handler, usually {@link #deadLetterErrorHandler}
     * @return the factory
     */
    public <K, V> ConcurrentKafkaListenerContainerFactory<K, V> listenerContainerFactory(
            ConsumerFactory<K, V> consumerFactory, CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<K, V> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(Objects.requireNonNull(errorHandler, "errorHandler"));
        applyListenerSettings(factory);
        return factory;
    }

    private void applyListenerSettings(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        KafkaProperties.Listener listener = properties.getListener();
        ContainerProperties container = factory.getContainerProperties();
        if (listener.getConcurrency() != null) {
            factory.setConcurrency(listener.getConcurrency());
        }
        factory.setAutoStartup(listener.isAutoStartup());
        if (listener.getChangeConsumerThreadName() != null) {
            factory.setChangeConsumerThreadName(listener.getChangeConsumerThreadName());
        }
        if (listener.getAckMode() != null) {
            container.setAckMode(listener.getAckMode());
        }
        if (listener.getAsyncAcks() != null) {
            container.setAsyncAcks(listener.getAsyncAcks());
        }
        if (listener.getClientId() != null) {
            container.setClientId(listener.getClientId());
        }
        if (listener.getPollTimeout() != null) {
            container.setPollTimeout(listener.getPollTimeout().toMillis());
        }
        if (listener.getNoPollThreshold() != null) {
            container.setNoPollThreshold(listener.getNoPollThreshold());
        }
        if (listener.getIdleBetweenPolls() != null) {
            container.setIdleBetweenPolls(listener.getIdleBetweenPolls().toMillis());
        }
        if (listener.getIdleEventInterval() != null) {
            container.setIdleEventInterval(listener.getIdleEventInterval().toMillis());
        }
        if (listener.getIdlePartitionEventInterval() != null) {
            container.setIdlePartitionEventInterval(listener.getIdlePartitionEventInterval().toMillis());
        }
        if (listener.getMonitorInterval() != null) {
            container.setMonitorInterval((int) listener.getMonitorInterval().getSeconds());
        }
        if (listener.getLogContainerConfig() != null) {
            container.setLogContainerConfig(listener.getLogContainerConfig());
        }
        Duration authRetry = listener.getAuthExceptionRetryInterval();
        if (authRetry != null) {
            container.setAuthExceptionRetryInterval(authRetry);
        }
        container.setMissingTopicsFatal(listener.isMissingTopicsFatal());
        container.setStopImmediate(listener.isImmediateStop());
        container.setObservationEnabled(listener.isObservationEnabled());
    }

    // ------------------------------------------------------------------ error handling

    /**
     * Retries with {@code backOff}, then publishes the record to {@code <topic>.DLT} with the
     * partition left to the producer. The listed exceptions skip the retries.
     *
     * @param template     the producer for the dead-letter record
     * @param backOff      the retry schedule
     * @param notRetryable exceptions that cannot succeed on a retry
     * @return the handler
     */
    @SafeVarargs
    public static DefaultErrorHandler deadLetterErrorHandler(KafkaOperations<?, ?> template, BackOff backOff,
            Class<? extends Exception>... notRetryable) {
        return deadLetterErrorHandler(deadLetterRecoverer(template, DeadLetterPartitioning.PRODUCER_CHOOSES),
                backOff, notRetryable);
    }

    /**
     * Retries with {@code backOff}, then hands the record to {@code recoverer}. The listed
     * exceptions skip the retries.
     *
     * @param recoverer    what happens after the last attempt
     * @param backOff      the retry schedule
     * @param notRetryable exceptions that cannot succeed on a retry
     * @return the handler
     */
    @SafeVarargs
    public static DefaultErrorHandler deadLetterErrorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff,
            Class<? extends Exception>... notRetryable) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        if (notRetryable.length > 0) {
            handler.addNotRetryableExceptions(notRetryable);
        }
        return handler;
    }

    /**
     * A recoverer that publishes a failed record to {@code <topic>.DLT}.
     *
     * <p>A record whose key or value could not be deserialized reaches the recoverer with the
     * original bytes (the {@code ErrorHandlingDeserializer} keeps them). Those go out through a
     * byte-array template, so the dead-letter value is the byte-for-byte original and an operator
     * or a replay tool can read it back without decoding (Q-404). Before, they went through the
     * service's JSON template and arrived as a JSON-quoted base64 string. The byte-array template
     * is derived from {@code template}'s producer factory when that is a
     * {@link DefaultKafkaProducerFactory}: same bootstrap, security, idempotence and transaction
     * settings, only the serializers differ. Any other kind of template (a mock, a custom factory)
     * is used for every record, as before. Values that were deserialized (a record that failed
     * in the listener) still go through {@code template}.
     *
     * <p>The byte-array template owns one producer that lives as long as the process; its sends
     * are awaited by the recoverer, so nothing is lost at shutdown.
     *
     * @param template     the producer for the dead-letter record
     * @param partitioning how the dead-letter record picks its partition
     * @return the recoverer
     */
    public static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaOperations<?, ?> template,
            DeadLetterPartitioning partitioning) {
        Objects.requireNonNull(partitioning, "partitioning");
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        // Order matters: the first key the value is an instance of wins, so byte[] precedes Object.
        byteArrayTemplate(template).ifPresent(bytes -> templates.put(byte[].class, bytes));
        templates.put(Object.class, template);
        return new DeadLetterPublishingRecoverer(templates, (record, ex) -> deadLetterDestination(record, partitioning));
    }

    /**
     * A template for the original bytes of an undeserializable record, on the connection of
     * {@code template}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.Optional<KafkaTemplate<Object, Object>> byteArrayTemplate(KafkaOperations<?, ?> template) {
        ProducerFactory<?, ?> source = template.getProducerFactory();
        if (!(source instanceof DefaultKafkaProducerFactory<?, ?> defaults)) {
            log.warn("Dead-letter records keep no original bytes: the template's producer factory ({}) cannot be "
                    + "copied, so undeserializable records go through the template's own serializer (Q-404)",
                    source == null ? "none" : source.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
        Map<String, Object> config = new HashMap<>(defaults.getConfigurationProperties());
        config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        DefaultKafkaProducerFactory<Object, Object> bytes = new DefaultKafkaProducerFactory<>(config,
                new DeadLetterKeySerializer(), (Serializer) new ByteArraySerializer());
        if (defaults.getTransactionIdPrefix() != null) {
            bytes.setTransactionIdPrefix(defaults.getTransactionIdPrefix());
        }
        defaults.getPostProcessors().forEach(processor -> bytes.addPostProcessor((ProducerPostProcessor) processor));
        defaults.getListeners().forEach(listener -> bytes.addListener((ProducerFactory.Listener) listener));
        KafkaTemplate<Object, Object> copy = new KafkaTemplate<>(bytes);
        if (template instanceof KafkaTemplate<?, ?> original) {
            copy.setAllowNonTransactional(original.isAllowNonTransactional());
        }
        return java.util.Optional.of(copy);
    }

    /**
     * Key serializer of the byte-array dead-letter template. The key of a dead-lettered record is
     * normally the String it was read as; when the key itself failed to deserialize it is the
     * original bytes. Both are written as they are (a String as UTF-8), so the key of the
     * dead-letter record equals the key of the source record.
     */
    public static final class DeadLetterKeySerializer implements Serializer<Object> {

        /** Creates the serializer. */
        public DeadLetterKeySerializer() {
            // stateless
        }

        @Override
        public byte[] serialize(String topic, Object key) {
            if (key == null) {
                return null;
            }
            if (key instanceof byte[] bytes) {
                return bytes;
            }
            return key.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * The dead-letter destination of a record. A negative partition means "no partition", so the
     * producer's partitioner picks one.
     *
     * @param record       the failed record
     * @param partitioning how the dead-letter record picks its partition
     * @return {@code <topic>.DLT} and the partition
     */
    public static TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record,
            DeadLetterPartitioning partitioning) {
        return deadLetterDestination(record.topic(), record.partition(), partitioning);
    }

    /**
     * The dead-letter destination of a source topic and partition. A negative partition means
     * "no partition", so the producer's partitioner picks one.
     *
     * @param topic           the source topic
     * @param sourcePartition the source partition
     * @param partitioning    how the dead-letter record picks its partition
     * @return {@code <topic>.DLT} and the partition
     */
    public static TopicPartition deadLetterDestination(String topic, int sourcePartition,
            DeadLetterPartitioning partitioning) {
        int partition = partitioning == DeadLetterPartitioning.SAME_AS_SOURCE ? sourcePartition : -1;
        return new TopicPartition(topic + DEAD_LETTER_SUFFIX, partition);
    }

    /**
     * The standard retry schedule: 1 s, doubling, at most 30 s apart, for at most 60 s in total.
     *
     * @return a new back-off
     */
    public static BackOff exponentialBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(60_000L);
        return backOff;
    }

    /**
     * A bounded retry schedule: {@code retries} retries, 1 s then doubling.
     *
     * @param retries how many retries after the first attempt
     * @return a new back-off
     */
    public static BackOff exponentialBackOffWithMaxRetries(int retries) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retries);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        return backOff;
    }

    // ------------------------------------------------------------------ internals

    private static void applyConnection(Map<String, Object> config, KafkaConnectionDetails.Configuration details) {
        List<String> servers = details.getBootstrapServers();
        if (servers != null && !servers.isEmpty()) {
            config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, servers);
        }
        if (StringUtils.hasLength(details.getSecurityProtocol())) {
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, details.getSecurityProtocol());
        }
        SslBundle sslBundle = details.getSslBundle();
        if (sslBundle != null) {
            config.put(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG, SslBundleSslEngineFactory.class);
            config.put(SslBundle.class.getName(), sslBundle);
        }
    }

    private static void enforceIdempotence(Map<String, Object> config) {
        Object idempotence = config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        if (idempotence != null && !Boolean.parseBoolean(idempotence.toString())) {
            throw new IllegalStateException("enable.idempotence=false is not allowed: every DLAP producer is "
                    + "idempotent (CLAUDE.md section 11)");
        }
        Object acks = config.get(ProducerConfig.ACKS_CONFIG);
        if (acks != null && !ACKS_ALL.contains(acks.toString().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("acks=" + acks + " is not allowed: an idempotent producer needs acks=all");
        }
        Object retries = config.get(ProducerConfig.RETRIES_CONFIG);
        if (retries != null && Integer.parseInt(retries.toString().trim()) == 0) {
            throw new IllegalStateException("retries=0 is not allowed: an idempotent producer needs retries > 0");
        }
        Object inFlight = config.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
        if (inFlight != null && Integer.parseInt(inFlight.toString().trim()) > MAX_IDEMPOTENT_IN_FLIGHT) {
            throw new IllegalStateException("max.in.flight.requests.per.connection=" + inFlight
                    + " is not allowed: an idempotent producer needs at most " + MAX_IDEMPOTENT_IN_FLIGHT);
        }
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
    }

    private <K, V> DefaultKafkaConsumerFactory<K, V> customized(DefaultKafkaConsumerFactory<K, V> factory) {
        consumerCustomizers.get().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    private static <T> Deserializer<T> errorHandling(Deserializer<T> delegate) {
        return delegate instanceof ErrorHandlingDeserializer<T> ? delegate : new ErrorHandlingDeserializer<>(delegate);
    }

    /**
     * Configures the Jackson 2 JSON deserializer from the configuration map now, so the factory's
     * later {@code configure} call is a no-op and setters never mix with configuration keys
     * (Spring Kafka refuses that mix).
     */
    @SuppressWarnings("removal")
    private static <T> JsonDeserializer<T> configuredJsonDeserializer(Class<T> type, Map<String, Object> config) {
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type);
        deserializer.configure(config, false);
        return deserializer;
    }
}
