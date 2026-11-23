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

package com.dlap.autolabel.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;

/**
 * Proves the production-gated audience predicate wired into
 * {@link SecurityConfiguration#jwtDecoder()}: a {@code JwtClaimValidator} on the
 * {@code aud} claim succeeds when the expected audience is present and fails
 * otherwise. This is the exact predicate the decoder installs when
 * {@code dlap.security.jwt.validate-audience=true}.
 */
class JwtAudienceValidatorTest {

    private static final String EXPECTED_AUDIENCE = "dlap-api";

    private final JwtClaimValidator<List<String>> audienceValidator =
            new JwtClaimValidator<>("aud",
                    aud -> aud != null && aud.contains(EXPECTED_AUDIENCE));

    private static Jwt jwtWithAudience(List<String> aud) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("aud", aud)
                .subject("s")
                .build();
    }

    @Test
    void succeedsWhenAudienceContainsExpectedValue() {
        Jwt jwt = jwtWithAudience(List.of(EXPECTED_AUDIENCE));

        OAuth2TokenValidatorResult result = audienceValidator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void succeedsWhenAudienceContainsExpectedValueAmongOthers() {
        Jwt jwt = jwtWithAudience(List.of("other-service", EXPECTED_AUDIENCE));

        OAuth2TokenValidatorResult result = audienceValidator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void failsWhenAudienceMissingExpectedValue() {
        Jwt jwt = jwtWithAudience(List.of("some-other-audience"));

        OAuth2TokenValidatorResult result = audienceValidator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }
}
