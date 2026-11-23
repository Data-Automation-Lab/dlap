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

import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

/**
 * Message converters for the HTTP clients this service builds itself (BOOT-01, ADR-0066).
 *
 * <p>Spring Framework 7 prefers Jackson 3 for JSON whenever it is on the class path, and it always
 * is here because Flyway 12 depends on it. A {@code new RestTemplate()} or {@code RestClient.builder()}
 * would therefore switch from Jackson 2 to Jackson 3, whose defaults differ (dates, property order,
 * nulls for primitives). These helpers keep Spring's default converter list with the Jackson 2 JSON
 * converter that Spring Framework 6 used, so outbound requests and parsed responses stay as before.
 */
public final class Jackson2ClientConverters {

    private Jackson2ClientConverters() {
    }

    /**
     * Applies Spring's default client converters with the Jackson 2 JSON converter.
     *
     * @param builder the converter builder of a {@code RestClient.Builder} or of {@link #converters()}
     */
    @SuppressWarnings("removal")
    public static void apply(HttpMessageConverters.ClientBuilder builder) {
        builder.registerDefaults().withJsonConverter(new MappingJackson2HttpMessageConverter());
    }

    /**
     * Spring's default client converters with the Jackson 2 JSON converter.
     *
     * @return the converters
     */
    public static HttpMessageConverters converters() {
        HttpMessageConverters.ClientBuilder builder = HttpMessageConverters.forClient();
        apply(builder);
        return builder.build();
    }

    /**
     * A {@link RestTemplate} with {@link #converters()}, the replacement of {@code new RestTemplate()}.
     *
     * @return the template
     */
    public static RestTemplate restTemplate() {
        return new RestTemplate(converters());
    }
}
