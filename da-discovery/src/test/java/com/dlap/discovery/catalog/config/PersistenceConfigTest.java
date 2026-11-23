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

package com.dlap.discovery.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DEP-01: the hand-built entity-manager factory (every profile except dev) must carry the same schema
 * and naming settings that Spring Boot's own factory applies in dev. Without them the prod profile looked
 * for the tables in the wrong schema and expected camelCase column names, so
 * {@code ddl-auto=validate} aborted start-up.
 */
class PersistenceConfigTest {

    private static final class Config extends PersistenceConfig {
        Properties properties() {
            return additionalJpaProperties();
        }
    }

    private static Config configWith(MockEnvironment env) {
        Config config = new Config();
        ReflectionTestUtils.setField(config, "env", env);
        return config;
    }

    /** The default schema, as application.properties sets it for the service. */
    @Test
    void forwardsTheDefaultSchema() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.jpa.properties.hibernate.default_schema", "dlap_discovery");

        assertThat(configWith(env).properties().getProperty("hibernate.default_schema"))
                .isEqualTo("dlap_discovery");
    }

    /** Boot applies snake_case column names; the factory has to as well. */
    @Test
    void forwardsTheNamingStrategies() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.jpa.hibernate.naming.physical-strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .withProperty("spring.jpa.hibernate.naming.implicit-strategy",
                        "org.springframework.boot.hibernate.SpringImplicitNamingStrategy");

        Properties properties = configWith(env).properties();

        assertThat(properties.getProperty("hibernate.physical_naming_strategy"))
                .isEqualTo("org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        assertThat(properties.getProperty("hibernate.implicit_naming_strategy"))
                .isEqualTo("org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
    }

    /** Nothing configured means nothing set, so Hibernate keeps its own defaults (the old behaviour). */
    @Test
    void setsNothingWhenNothingIsConfigured() {
        Properties properties = configWith(new MockEnvironment()).properties();

        assertThat(properties).doesNotContainKeys(
                "hibernate.default_schema", "hibernate.physical_naming_strategy", "hibernate.implicit_naming_strategy");
    }

    /** Blank values are ignored, so an unset variable cannot blank the schema. */
    @Test
    void ignoresBlankValues() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.jpa.properties.hibernate.default_schema", " ");

        assertThat(configWith(env).properties()).doesNotContainKey("hibernate.default_schema");
    }
}
