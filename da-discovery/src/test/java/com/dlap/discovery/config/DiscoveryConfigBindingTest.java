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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * INST-01: pins the install-path contract of {@code config/application.yml} without booting
 * the application. The YAML is loaded the way Spring Boot loads it and environment
 * variables are simulated, so the test fails if the documented variables stop reaching the
 * properties the code reads.
 */
class DiscoveryConfigBindingTest {

    /**
     * Builds an environment from the real {@code config/application.yml}. {@code envVars} plays
     * the part of the process environment and wins over the YAML, as it does at runtime.
     */
    private static StandardEnvironment environment(Map<String, Object> envVars) throws IOException {
        return environment(envVars, false);
    }

    /**
     * As {@link #environment(Map)}. With {@code withDevProfile} the real
     * {@code config/application-dev.yml} is layered above the base file, the way an active
     * {@code dev} profile is.
     */
    private static StandardEnvironment environment(Map<String, Object> envVars, boolean withDevProfile)
            throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));

        List<PropertySource<?>> highestFirst = new ArrayList<>();
        if (withDevProfile) {
            highestFirst.addAll(load("application-dev.yml", "config/application-dev.yml"));
        }
        highestFirst.addAll(load("application.yml", "config/application.yml"));
        highestFirst.forEach(sources::addLast);
        return env;
    }

    /** Loads every YAML document of a file, later documents first because they override earlier ones. */
    private static List<PropertySource<?>> load(String name, String classpathLocation) throws IOException {
        List<PropertySource<?>> documents = new ArrayList<>(
                new YamlPropertySourceLoader().load(name, new ClassPathResource(classpathLocation)));
        Collections.reverse(documents);
        return documents;
    }

    @Test
    void awsIsOffByDefaultAndUsesTheDefaultRegion() throws IOException {
        StandardEnvironment env = environment(Map.of());

        assertThat(env.getProperty("cloud.provider.aws.enabled")).isEqualTo("false");
        assertThat(env.getProperty("cloud.provider.aws.endpoint")).isEmpty();
        assertThat(env.getProperty("cloud.provider.aws.access-key")).isEmpty();
        assertThat(env.getProperty("cloud.provider.aws.secret-key")).isEmpty();
        assertThat(env.getProperty("aws.region")).isEqualTo("us-east-1");
    }

    @Test
    void documentedAwsVariablesReachThePropertiesTheCodeReads() throws IOException {
        StandardEnvironment env = environment(Map.of(
                "DLAP_AWS_ENABLED", "true",
                "DLAP_AWS_REGION", "eu-west-1",
                "DLAP_AWS_ENDPOINT", "http://localstack:4566",
                "DLAP_AWS_ACCESS_KEY", "test",
                "DLAP_AWS_SECRET_KEY", "test-secret"));

        assertThat(env.getProperty("cloud.provider.aws.enabled")).isEqualTo("true");
        assertThat(env.getProperty("cloud.provider.aws.endpoint")).isEqualTo("http://localstack:4566");
        assertThat(env.getProperty("cloud.provider.aws.access-key")).isEqualTo("test");
        assertThat(env.getProperty("cloud.provider.aws.secret-key")).isEqualTo("test-secret");
        assertThat(env.getProperty("aws.region")).isEqualTo("eu-west-1");
    }

    @Test
    void anExplicitCloudProviderVariableStillOverridesTheDocumentedOne() throws IOException {
        StandardEnvironment env = environment(Map.of(
                "DLAP_AWS_ENABLED", "false",
                "CLOUD_PROVIDER_AWS_ENABLED", "true"));

        assertThat(env.getProperty("cloud.provider.aws.enabled")).isEqualTo("true");
    }

    /**
     * The dev compose runs the dev profile, whose file pins {@code cloud.provider.aws.enabled}
     * above the base YAML. Only an environment variable outranks it, which is why the dev
     * compose maps {@code DLAP_AWS_ENABLED} onto {@code CLOUD_PROVIDER_AWS_ENABLED}.
     */
    @Test
    void theEnvironmentVariableEnablesAwsEvenUnderTheDevProfile() throws IOException {
        StandardEnvironment env = environment(Map.of(
                "CLOUD_PROVIDER_AWS_ENABLED", "true",
                "DLAP_AWS_REGION", "eu-west-1",
                "DLAP_AWS_ENDPOINT", "http://localstack:4566"), true);

        assertThat(env.getProperty("cloud.provider.aws.enabled")).isEqualTo("true");
        assertThat(env.getProperty("cloud.provider.aws.endpoint")).isEqualTo("http://localstack:4566");
        assertThat(env.getProperty("aws.region")).isEqualTo("eu-west-1");
    }

    @Test
    void theDeadSpringCloudProviderAwsKeysAreGone() throws IOException {
        StandardEnvironment env = environment(Map.of("DLAP_AWS_ENABLED", "true"));

        assertThat(env.containsProperty("spring.cloud.provider.aws.enabled")).isFalse();
        assertThat(env.containsProperty("spring.cloud.provider.aws.endpoint")).isFalse();
    }

    @Test
    void healthIsServedUnderActuatorLikeEveryOtherService() throws IOException {
        StandardEnvironment env = environment(Map.of(), true);

        assertThat(env.getProperty("management.endpoints.web.base-path")).isEqualTo("/actuator");
    }
}
