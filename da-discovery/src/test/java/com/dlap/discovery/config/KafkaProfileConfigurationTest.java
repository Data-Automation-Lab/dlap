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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * FU-3B (Q-334): da-discovery reads its Kafka settings from {@code spring.kafka.*} only, the prod
 * profile has no default for the bootstrap servers, and the prod producer tuning that the dead
 * {@code kafka.*} block used to declare reaches the clients.
 *
 * <p>The files are loaded as Spring Boot orders them (profile file, then {@code config/application.yml},
 * then the classpath-root {@code application.properties}) into a private property-source list with no
 * process environment, so a variable set in the developer's shell cannot change the outcome. The
 * forked-JVM start without the variable is {@code ProdProfileContextTest}.
 */
class KafkaProfileConfigurationTest {

    private static final String BOOTSTRAP = "spring.kafka.bootstrap-servers";

    private static PropertySource<?> yaml(String name) throws IOException {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource("config/" + name)).get(0);
    }

    private static PropertySource<?> properties() throws IOException {
        return new PropertiesPropertySourceLoader().load("application.properties",
                new ClassPathResource("application.properties")).get(0);
    }

    private static MutablePropertySources sources(String profile, Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        if (!environment.isEmpty()) {
            sources.addLast(new MapPropertySource("environment", environment));
        }
        if (profile != null) {
            sources.addLast(yaml("application-" + profile + ".yml"));
        }
        sources.addLast(yaml("application.yml"));
        sources.addLast(properties());
        return sources;
    }

    /** Q-334: the prod profile has no default, so a deploy without the variable cannot start. */
    @Test
    void prodHasNoDefaultForTheBootstrapServers() throws IOException {
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources("prod", Map.of()));

        assertThatThrownBy(() -> resolver.getProperty(BOOTSTRAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_KAFKA_BOOTSTRAP_SERVERS");
    }

    /** With the variable set, the prod profile uses it. */
    @Test
    void prodUsesTheVariableWhenItIsSet() throws IOException {
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(
                sources("prod", Map.of("SPRING_KAFKA_BOOTSTRAP_SERVERS", "broker-1:9092,broker-2:9092")));

        assertThat(resolver.getProperty(BOOTSTRAP)).isEqualTo("broker-1:9092,broker-2:9092");
    }

    /** The dev profile and the file-less default keep the compose address, so the quickstart still starts. */
    @Test
    void devAndTheDefaultKeepTheComposeAddress() throws IOException {
        assertThat(new PropertySourcesPropertyResolver(sources("dev", Map.of())).getProperty(BOOTSTRAP))
                .isEqualTo("kafka:29092");
        assertThat(new PropertySourcesPropertyResolver(sources(null, Map.of())).getProperty(BOOTSTRAP))
                .isEqualTo("kafka:29092");
    }

    /** Nothing under the root {@code kafka} key is a Spring Kafka setting any more (Q-334). */
    @Test
    void noFileCarriesAKafkaClientSettingOutsideSpringKafka() throws IOException {
        for (String file : new String[] {"application.yml", "application-dev.yml", "application-prod.yml",
                "application-shell.yml"}) {
            Map<String, Object> flat = flatten(yaml(file));
            assertThat(flat.keySet()).as(file).noneMatch(k -> k.equals("kafka.bootstrap-servers")
                    || k.startsWith("kafka.consumer.") || k.startsWith("kafka.producer.")
                    || k.startsWith("kafka.admin.auto-create-topics") || k.startsWith("kafka.listener.")
                    || k.startsWith("kafka.output."));
        }
    }

    /** The prod producer tuning moved to {@code spring.kafka.producer.*} and reaches the producer config. */
    @Test
    void theProdProducerTuningReachesTheProducerConfiguration() throws IOException {
        MutablePropertySources prod = sources("prod", Map.of("SPRING_KAFKA_BOOTSTRAP_SERVERS", "broker-1:9092"));
        MutablePropertySources resolved = new MutablePropertySources();
        resolved.addFirst(new MapPropertySource("resolved",
                resolveSpringKafka(new PropertySourcesPropertyResolver(prod), prod)));
        KafkaProperties kafka = new Binder(ConfigurationPropertySources.from(resolved))
                .bindOrCreate("spring.kafka", KafkaProperties.class);

        Map<String, Object> producer = kafka.buildProducerProperties();

        assertThat(producer).containsEntry("retries", 5)
                .containsEntry("compression.type", "lz4")
                .containsEntry("batch.size", 32768)
                .containsEntry("buffer.memory", 67108864L)
                .containsEntry("linger.ms", "20")
                .containsEntry("max.in.flight.requests.per.connection", "5");
        assertThat(kafka.getBootstrapServers()).containsExactly("broker-1:9092");
        assertThat(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG).isEqualTo("bootstrap.servers");
    }

    /** The codec the prod profile asks for is on the runtime classpath, or the first send would fail. */
    @Test
    void theLz4CodecIsOnTheRuntimeClasspath() {
        assertThat(Thread.currentThread().getContextClassLoader().getResource("net/jpountz/lz4/LZ4Factory.class"))
                .as("lz4-java (kafka-clients runtime dependency)").isNotNull();
    }

    /** Shell mode switches the listeners off through the setting the listener factories read. */
    @Test
    void shellModeDisablesTheListenersThroughSpringKafka() throws IOException {
        MutablePropertySources shell = sources("shell", Map.of());
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(shell);

        assertThat(resolver.getProperty("spring.kafka.listener.auto-startup")).isEqualTo("false");
    }

    private static Map<String, Object> resolveSpringKafka(PropertySourcesPropertyResolver resolver,
            MutablePropertySources sources) {
        Map<String, Object> resolved = new java.util.LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("spring.kafka.") && !resolved.containsKey(name)) {
                        resolved.put(name, resolver.getProperty(name));
                    }
                }
            }
        }
        return resolved;
    }

    private static Map<String, Object> flatten(PropertySource<?> source) {
        Map<String, Object> flat = new java.util.LinkedHashMap<>();
        for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames()) {
            flat.put(name, source.getProperty(name));
        }
        return flat;
    }
}
