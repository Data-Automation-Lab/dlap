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

package com.dlap.common.auth;

import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
/**
 * Auto-configuration for service-to-service (M2M) authentication
 * (P0-K / ADR-0021).
 *
 * <p>Lives in {@code da-protos} so every service that depends on the
 * protos gets the same wiring. Opt-in via
 * {@code dlap.service-auth.enabled=true} (defaults to false so test
 * profiles aren't surprised); turn on in compose / Kubernetes for
 * production deployments.
 *
 * <p>Wires two beans:
 *
 * <ul>
 *   <li>{@link ServiceTokenProvider} - fetches + caches a Keycloak
 *       client-credentials access token.</li>
 *   <li>A Feign {@link RequestInterceptor} that attaches
 *       {@code Authorization: Bearer <token>} to every outbound
 *       Spring Cloud OpenFeign call. Beans of type {@code RequestInterceptor}
 *       are auto-applied to all clients in the application context.</li>
 * </ul>
 *
 * <p>Required configuration when enabled:
 *
 * <pre>
 * dlap.service-auth.enabled=true
 * dlap.service-auth.token-uri=${KEYCLOAK_AUTH_SERVER_URL}/realms/dlap/protocol/openid-connect/token
 * dlap.service-auth.client-id=${DLAP_SERVICE_CLIENT_ID:dlap-services}
 * dlap.service-auth.client-secret=${DLAP_SERVICE_CLIENT_SECRET}
 * </pre>
 *
 * <p>The Keycloak realm-import side (creating the {@code dlap-services}
 * client with {@code Service Accounts Enabled = ON} and an appropriate
 * client secret) is owner setup and tracked separately. In dev profiles
 * the property defaults to {@code false} so existing local-test flows
 * keep working without Keycloak being primed.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "dlap.service-auth.enabled", havingValue = "true")
@ConditionalOnClass(RequestInterceptor.class)
public class ServiceAuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ServiceAuthAutoConfiguration.class);

    @Bean
    public ServiceTokenProvider dlapServiceTokenProvider(
            @Value("${dlap.service-auth.token-uri}") String tokenUri,
            @Value("${dlap.service-auth.client-id:dlap-services}") String clientId,
            @Value("${dlap.service-auth.client-secret}") String clientSecret) {
        log.info("Service-auth enabled: tokenUri={} clientId={}", tokenUri, clientId);
        return new ServiceTokenProvider(tokenUri, clientId, clientSecret);
    }

    /**
     * Attaches an {@code Authorization: Bearer <token>} header to every
     * outbound Feign call. Spring Cloud OpenFeign auto-applies beans
     * of type {@link RequestInterceptor}.
     */
    @Bean
    public RequestInterceptor dlapServiceAuthFeignInterceptor(ServiceTokenProvider provider) {
        return template -> {
            try {
                String token = provider.getAccessToken();
                template.header("Authorization", "Bearer " + token);
            } catch (Exception e) {
                log.warn("Skipping bearer-token attachment for Feign call: {}", e.getMessage());
            }
        };
    }
}
