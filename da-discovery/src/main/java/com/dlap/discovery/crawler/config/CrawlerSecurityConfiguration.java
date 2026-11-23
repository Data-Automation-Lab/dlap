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

package com.dlap.discovery.crawler.config;

import static org.springframework.security.config.Customizer.*;
import static org.springframework.security.oauth2.core.oidc.StandardClaimNames.*;

import com.dlap.discovery.common.service.AuthoritiesConstants;
import com.dlap.discovery.crawler.service.CrawlerJwtGrantedAuthorityConverter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "dlap.security.jwt.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class CrawlerSecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    @Value("${spring.security.oauth2.client.provider.oidc.issuer-uri}")
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

    public CrawlerSecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, PathPatternRequestMatcher.Builder mvc) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(authz ->
                authz
                    .requestMatchers(mvc.matcher("/api/authenticate"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/api/auth-info"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/api/admin/**"))
                    .hasAuthority(AuthoritiesConstants.ADMIN)
                    .requestMatchers(mvc.matcher("/api/**"))
                    .authenticated()
                    .requestMatchers(mvc.matcher("/v3/api-docs/**"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/swagger-ui/**"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/swagger-ui.html"))
                    .permitAll()
                    // Q-086, CLAUDE.md section 9: liveness and readiness answer without a token.
                    // /actuator/info and every other actuator endpoint need a token (nothing below
                    // matches them, and an unmatched request is denied). Prometheus is open on the
                    // management port only (SecurityConfiguration#prometheusScrapeFilterChain).
                    .requestMatchers(mvc.matcher("/actuator/health"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/actuator/health/**"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/management/health"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/management/health/**"))
                    .permitAll()
                    .requestMatchers(mvc.matcher("/management/**"))
                    .hasAuthority(AuthoritiesConstants.ADMIN)
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())))
            .oauth2Client(withDefaults());
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
            Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList(
            "Origin", "Content-Type", "Accept", "Authorization",
            "X-Requested-With", "Cache-Control", "Pragma"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Request matchers for the chain above. Spring Security 7 removed {@code MvcRequestMatcher}; the
     * path-pattern builder matches the same servlet paths (BOOT-01).
     *
     * @return the matcher builder
     */
    @Bean
    PathPatternRequestMatcher.Builder mvc() {
        return PathPatternRequestMatcher.withDefaults();
    }

    Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(new CrawlerJwtGrantedAuthorityConverter());
        jwtAuthenticationConverter.setPrincipalClaimName(PREFERRED_USERNAME);
        return jwtAuthenticationConverter;
    }

    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        final OidcUserService delegate = new OidcUserService();

        return userRequest -> {
            OidcUser oidcUser = delegate.loadUser(userRequest);
            return new DefaultOidcUser(oidcUser.getAuthorities(), oidcUser.getIdToken(), oidcUser.getUserInfo(), PREFERRED_USERNAME);
        };
    }

    @Bean
    JwtDecoder jwtDecoder() {
        // Q-014 (dev/sandbox relaxation): signature-only JWT validation by default.
        // The browser obtains tokens from the host-facing Keycloak (iss=http://localhost:8180/realms/dlap)
        // while services resolve Keycloak internally (iss=http://keycloak:8080/realms/dlap). Both tokens are
        // signed by the same realm keys, so we validate the signature against the JWK set and skip the
        // issuer/audience assertions that would reject the host-issued token. Matches the other DLAP services'
        // SecurityConfiguration. See OPEN_QUESTIONS Q-014.
        //
        // Issuer and audience validation are now available for PRODUCTION via the
        // dlap.security.jwt.* properties (default OFF). With both flags false the
        // decoder validates exactly what it did before: signature (JWKS) + timestamp
        // only, no issuer/audience assertion - so the dual-issuer dev setup keeps working.
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
            .build();
        // dlap.security.jwt.expected-issuer may list several issuers separated by commas, so a
        // deployment can accept the internal and the public Keycloak URL at the same time.
        decoder.setJwtValidator(
            tokenValidator(validateIssuer, expectedIssuer, validateAudience, expectedAudience));
        return decoder;
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
}
