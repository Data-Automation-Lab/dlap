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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * The claim checks of the PEP's token decoder (SEC-07): validity period, issuer and audience.
 *
 * <p>Unlike the {@code dlap.security.jwt.validate-*} switches of the other DLAP services, the issuer
 * and audience checks here are not optional. An empty issuer list or an empty audience is a
 * configuration error, reported at start-up, because a PEP that accepted every token of the realm
 * would let any realm client ask for a verdict.
 */
final class JwtTokenValidators {

    private JwtTokenValidators() {
    }

    /**
     * Builds the combined validator.
     *
     * @param expectedIssuers one issuer, or several separated by commas (for example the internal and the
     *                        public Keycloak URL); a token passes when its {@code iss} equals one entry exactly
     * @param expectedAudience the audience a token must list in {@code aud}
     * @return validity period, issuer and audience, all of which must pass
     * @throws IllegalStateException when no usable issuer or no audience is configured
     */
    static OAuth2TokenValidator<Jwt> create(String expectedIssuers, String expectedAudience) {
        Set<String> issuers = issuers(expectedIssuers);
        if (issuers.isEmpty()) {
            throw new IllegalStateException("da-mcp-pep needs dlap.security.jwt.expected-issuer "
                    + "(env DLAP_SECURITY_JWT_EXPECTED_ISSUER): it does not accept tokens of an unnamed issuer");
        }
        if (expectedAudience == null || expectedAudience.isBlank()) {
            throw new IllegalStateException("da-mcp-pep needs dlap.security.jwt.expected-audience "
                    + "(env DLAP_SECURITY_JWT_EXPECTED_AUDIENCE): it does not accept tokens for any audience");
        }
        String audience = expectedAudience.trim();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        validators.add(new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                issuer -> issuer != null && issuers.contains(issuer.toString())));
        validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience)));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static Set<String> issuers(String expectedIssuers) {
        if (expectedIssuers == null) {
            return Set.of();
        }
        return Arrays.stream(expectedIssuers.split(","))
                .map(String::trim)
                .filter(issuer -> !issuer.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
