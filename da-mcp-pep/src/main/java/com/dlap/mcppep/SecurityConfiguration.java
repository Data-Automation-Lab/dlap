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

package com.dlap.mcppep;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for the reference MCP PEP (SEC-07).
 *
 * <p>The PEP is a JWT resource server. Every request except the liveness and readiness probes
 * needs a bearer token that:
 * <ul>
 *   <li>carries a valid signature from the Keycloak realm key set ({@code jwk-set-uri}) and is
 *       within its validity period;</li>
 *   <li>names one of the configured issuers ({@code dlap.security.jwt.expected-issuer}, a comma
 *       separated list);</li>
 *   <li>names the configured audience ({@code dlap.security.jwt.expected-audience}).</li>
 * </ul>
 * A request without a valid token is answered 401 with a {@code WWW-Authenticate: Bearer}
 * challenge. {@code POST /mcp/v1/authorize} then admits any caller whose token carries the PEP
 * audience, whatever its roles ({@link PepAudienceGate}, Q-459, D29); a caller that is not a service
 * account is asked about as itself ({@link SubjectBinder}, ADR-0084).
 *
 * <p>Unlike the other DLAP services the issuer and audience checks have no off switch and no
 * development default: the PEP refuses to start when either is not configured
 * ({@link JwtTokenValidators#create}), so a PEP that would accept any token of the realm cannot
 * run by omission.
 *
 * <p>Actuator: {@code /actuator/health} and its probe groups are open, so orchestrators can probe
 * the process. Every other actuator endpoint is denied to every caller. Prometheus is open on the
 * management port only ({@link #prometheusScrapeFilterChain}).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfiguration {

    private static final String JWKS_PROPERTY = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

    /**
     * Builds the main chain: health is open, other actuator endpoints are denied, everything else
     * needs a valid bearer token.
     *
     * @param http the security builder
     * @param jwtDecoder the decoder that checks signature, validity period, issuer and audience
     * @param jwtAuthenticationConverter maps the Keycloak role claims to authorities
     * @return the chain that serves the application port
     * @throws Exception when the chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CLAUDE.md section 9: liveness and readiness answer without a token and show no component
            // detail (management.endpoint.health.show-details=when-authorized). Every other actuator
            // endpoint is closed to all callers. Prometheus is open on the management port only
            // (prometheusScrapeFilterChain). Anything else, including a path that does not exist and
            // the API description, needs a token, so an anonymous caller learns nothing of the surface.
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(EndpointRequest.to("health")).permitAll()
                .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * The decoder: signature against the realm key set, then validity period, issuer and audience.
     * The key set is fetched on the first token, so the PEP starts while Keycloak is down and
     * denies every call until the key set can be read.
     *
     * @param jwkSetUri {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
     * @param expectedIssuers one issuer, or several separated by commas
     * @param expectedAudience the audience every token must carry
     * @return the decoder
     * @throws IllegalStateException when the key set address, the issuer or the audience is not configured
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${" + JWKS_PROPERTY + ":}") String jwkSetUri,
            @Value("${dlap.security.jwt.expected-issuer:}") String expectedIssuers,
            @Value("${dlap.security.jwt.expected-audience:}") String expectedAudience) {
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalStateException("da-mcp-pep needs " + JWKS_PROPERTY
                    + " (env KEYCLOAK_AUTH_SERVER_URL or SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI): "
                    + "it cannot verify a token signature without the realm key set");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtTokenValidators.create(expectedIssuers, expectedAudience));
        return decoder;
    }

    /**
     * Maps Keycloak {@code realm_access.roles} to Spring authorities with the {@code ROLE_} prefix,
     * falling back to a top-level {@code roles} claim (the same mapping as the other DLAP services).
     *
     * @return the converter
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
                return toAuthorities(roles);
            }
            if (jwt.getClaim("roles") instanceof List<?> roles) {
                return toAuthorities(roles);
            }
            return List.of();
        });
        return converter;
    }

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

    private static List<GrantedAuthority> toAuthorities(List<?> roles) {
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
