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

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Enables {@code @PreAuthorize} / {@code @PostAuthorize} globally. The HTTP
 * {@link org.springframework.security.web.SecurityFilterChain} is defined in
 * {@link com.dlap.discovery.crawler.config.CrawlerSecurityConfiguration} (JHipster-style paths).
 * The one chain defined here serves the Prometheus scrape on the management port (OBS-01).
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfiguration {

    /**
     * Opens {@code /actuator/prometheus} without a token, and only on the management server.
     *
     * <p>The chain exists only when {@code management.server.port} (env {@code MANAGEMENT_SERVER_PORT})
     * names a port other than the application port. {@link EndpointRequest} matches the management
     * server alone in that case, so a request to the application port never reaches this chain and
     * the scrape path stays behind the token requirement there. Every other actuator endpoint, on
     * either port, is handled by the main chain.
     *
     * @param http the security builder
     * @return the chain that serves the Prometheus scrape
     * @throws Exception when the chain cannot be built
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    public SecurityFilterChain prometheusScrapeFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(EndpointRequest.to("prometheus"))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz.anyRequest().permitAll());
        return http.build();
    }
}
