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

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * SEC-03 (Q-028): the test configuration pairs credentials with concrete origins, methods and
 * headers. A wildcard origin with credentials is invalid, and a test file that shows it gets copied
 * into real configuration.
 */
class TestCorsConfigTest {

    @Test
    void theTestCorsConfigurationHasNoWildcardAndNamesConcreteOrigins() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-test.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("jhipster.cors.allow-credentials")).isEqualTo("true");
        for (String key : new String[] {"allowed-origins", "allowed-methods", "allowed-headers"}) {
            assertThat(properties.getProperty("jhipster.cors." + key)).as("jhipster.cors." + key).isNotBlank()
                    .doesNotContain("*");
        }
        assertThat(properties.getProperty("jhipster.cors.allowed-origins")).startsWith("http://localhost:");
    }
}
