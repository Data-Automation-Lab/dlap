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

package com.dlap.discovery.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * BOOT-01: the HTTP clients this service builds itself keep Jackson 2 for JSON, although Spring
 * Framework 7 would pick Jackson 3, which Flyway puts on the class path.
 */
class Jackson2ClientConvertersTest {

    private static final String JACKSON_3_CONVERTER = "org.springframework.http.converter.json.JacksonJsonHttpMessageConverter";

    @Test
    @SuppressWarnings("removal")
    void restTemplateWritesJsonWithJackson2() {
        RestTemplate template = Jackson2ClientConverters.restTemplate();

        assertThat(template.getMessageConverters())
                .anyMatch(MappingJackson2HttpMessageConverter.class::isInstance)
                .noneMatch(converter -> converter.getClass().getName().equals(JACKSON_3_CONVERTER));
    }

    @Test
    void aPlainRestTemplateWouldHavePickedJackson3() {
        // The reason for the helper: without it Spring Framework 7 chooses Jackson 3 here.
        assertThat(new RestTemplate().getMessageConverters())
                .anyMatch(converter -> converter.getClass().getName().equals(JACKSON_3_CONVERTER));
    }

    @Test
    @SuppressWarnings("removal")
    void restClientBuilderGetsTheSameConverters() {
        List<HttpMessageConverter<?>> seen = new ArrayList<>();
        RestClient.builder()
                .configureMessageConverters(Jackson2ClientConverters::apply)
                .configureMessageConverters(builder -> builder.configureMessageConvertersList(seen::addAll))
                .build();

        assertThat(seen)
                .anyMatch(MappingJackson2HttpMessageConverter.class::isInstance)
                .noneMatch(converter -> converter.getClass().getName().equals(JACKSON_3_CONVERTER));
    }
}
