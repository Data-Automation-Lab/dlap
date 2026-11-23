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

// This file was added to provide Spring Security configuration for JWT authentication with Keycloak.
// Reason: The da-autodelete service was missing security configuration, causing 401 errors and no response to HTTP requests.
// This configuration enables JWT token validation, public access to health endpoints, and role-based access control for API endpoints.

package com.dlap.autocompliance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Security Configuration for da-autodelete service.
 * 
 * This configuration:
 * - Enables JWT authentication with Keycloak
 * - Provides public access to health and actuator endpoints
 * - Requires authentication for all API endpoints
 * - Enables method-level security for @PreAuthorize annotations
 * - Configures stateless session management
 * - Maps Keycloak roles to Spring Security authorities
 * - Enables CORS for UI integration
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfiguration {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private String allowedOriginsCsv;

    // Production-gated JWT claim validation. Defaults keep validation OFF so the
    // Q-014 dev/sandbox dual-issuer relaxation (signature + timestamp only) is preserved.
    @Value("${dlap.security.jwt.validate-issuer:false}")
    private boolean validateIssuer;

    @Value("${dlap.security.jwt.expected-issuer:}")
    private String expectedIssuer;

    @Value("${dlap.security.jwt.validate-audience:false}")
    private boolean validateAudience;

    @Value("${dlap.security.jwt.expected-audience:}")
    private String expectedAudience;

    /**
     * Configures the security filter chain for the application.
     * 
     * Security rules:
     * - Public access to health endpoints (/actuator/health)
     * - Public access to OpenAPI documentation (/v3/api-docs, /swagger-ui/**)
     * - Authentication required for all other endpoints
     * - JWT token validation using Keycloak issuer
     * - Stateless session management
     * - CSRF disabled for REST API
     * - CORS enabled for UI integration
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Enable CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Disable CSRF for stateless REST APIs
            .csrf(csrf -> csrf.disable())
            
            // Configure stateless session management
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // Configure authorization rules
            .authorizeHttpRequests(authz -> authz
                // Q-086, CLAUDE.md section 9: liveness and readiness answer without a token. Component
                // detail stays behind management.endpoint.health.show-details=when-authorized.
                // /actuator/info and every other actuator endpoint need a token. Prometheus is open on
                // the management port only (prometheusScrapeFilterChain).
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                
                // All other requests require authentication
                .anyRequest().authenticated()
            )
            
            // Configure OAuth2 resource server with JWT
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );
        
        return http.build();
    }

    /**
     * Configures CORS to allow requests from the UI.
     * 
     * CORS configuration:
     * - Allows requests from localhost:3000 (UI)
     * - Allows common HTTP methods (GET, POST, PUT, DELETE, OPTIONS)
     * - Allows common headers including Authorization
     * - Enables credentials
     * 
     * @return CorsConfigurationSource configured for UI integration
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        configuration.setAllowedOriginPatterns(
                Arrays.stream(allowedOriginsCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
        
        // Allow common HTTP methods
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
        ));
        
        // Allow common headers
        configuration.setAllowedHeaders(Arrays.asList(
            "Origin", "Content-Type", "Accept", "Authorization", 
            "X-Requested-With", "Cache-Control", "Pragma"
        ));
        
        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);
        
        // Set max age for preflight requests
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }

    /**
     * Creates a JWT decoder that validates the token SIGNATURE against Keycloak's JWKS.
     *
     * The JWK Set URI is derived from the issuer URI:
     * - Issuer URI: http://localhost:8180/realms/dlap
     * - JWK Set URI: http://localhost:8180/realms/dlap/protocol/openid-connect/certs
     *
     * @return JwtDecoder configured for Keycloak JWT validation
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        // Q-014: validate the token SIGNATURE against Keycloak's JWKS, not a single
        // issuer. The browser UI's token carries iss=http://localhost:8180 while
        // internal service tokens carry iss=http://keycloak:8080; both are signed by
        // the same realm key. Dev/sandbox relaxation - kept as the default.
        //
        // Issuer and audience validation are now available for PRODUCTION via the
        // dlap.security.jwt.* properties (default OFF). With both flags false the
        // decoder validates exactly what it did before: signature (JWKS) + timestamp
        // only, no issuer/audience assertion - so the dual-issuer dev setup keeps working.
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs").build();
        // dlap.security.jwt.expected-issuer may list several issuers separated by commas, so a
        // deployment can accept the internal and the public Keycloak URL at the same time.
        decoder.setJwtValidator(
                tokenValidator(validateIssuer, expectedIssuer, validateAudience, expectedAudience));
        return decoder;
    }

    /**
     * Maps Keycloak {@code realm_access.roles} to Spring authorities with {@code ROLE_} prefix when
     * missing; falls back to top-level {@code roles} (same pattern as {@code da-admin-service}).
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof java.util.Map<?, ?> map) {
                Object roles = map.get("roles");
                if (roles instanceof List<?> rolesList) {
                    return rolesList.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .map(SecurityConfiguration::toRoleAuthority)
                            .collect(Collectors.toList());
                }
            }
            Object topLevelRoles = jwt.getClaim("roles");
            if (topLevelRoles instanceof List<?> rolesList) {
                return rolesList.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(SecurityConfiguration::toRoleAuthority)
                        .collect(Collectors.toList());
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
     * either port, is handled by {@link #filterChain} and needs a token.
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

    /**
     * Builds the claim validators of the JWT decoder from the {@code dlap.security.jwt.*} settings.
     * The timestamp check is always on. The issuer and audience checks are off unless their
     * {@code validate-*} flag is true and the expected value is not blank.
     *
     * @param validateIssuer   whether {@code dlap.security.jwt.validate-issuer} is true
     * @param expectedIssuers  one issuer, or several separated by commas (see {@link #issuerValidator})
     * @param validateAudience whether {@code dlap.security.jwt.validate-audience} is true
     * @param expectedAudience the audience every token must carry
     * @return the combined validator
     */
    static OAuth2TokenValidator<Jwt> tokenValidator(boolean validateIssuer, String expectedIssuers,
            boolean validateAudience, String expectedAudience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        if (validateIssuer && expectedIssuers != null && !expectedIssuers.isBlank()) {
            validators.add(issuerValidator(expectedIssuers));
        }
        if (validateAudience && expectedAudience != null && !expectedAudience.isBlank()) {
            validators.add(new JwtClaimValidator<List<String>>("aud",
                    aud -> aud != null && aud.contains(expectedAudience)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * Builds the issuer check of {@code dlap.security.jwt.expected-issuer}. The value may list several
     * issuers separated by commas, so one deployment accepts both the internal Keycloak URL that
     * service tokens carry (for example {@code http://keycloak:8080/realms/dlap}) and the public URL
     * that browser tokens carry (for example {@code https://auth.example.com/realms/dlap}). A token
     * passes when its {@code iss} claim equals one entry exactly. Entries are trimmed and empty
     * entries are ignored, so a list with no usable entry rejects every token.
     *
     * @param expectedIssuers one issuer, or several separated by commas
     * @return the validator to install on the decoder
     */
    static OAuth2TokenValidator<Jwt> issuerValidator(String expectedIssuers) {
        Set<String> accepted = Arrays.stream(expectedIssuers.split(","))
                .map(String::trim)
                .filter(issuer -> !issuer.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                issuer -> issuer != null && accepted.contains(issuer.toString()));
    }

    private static GrantedAuthority toRoleAuthority(String role) {
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return new SimpleGrantedAuthority(authority);
    }
} 