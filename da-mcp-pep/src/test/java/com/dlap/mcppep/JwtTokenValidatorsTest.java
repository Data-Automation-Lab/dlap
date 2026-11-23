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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * SEC-07: the claim checks of the PEP's token decoder, without a server. The decoder wiring (signature,
 * the 401 answer) is covered by {@code McpPepAuthenticationTest}.
 */
class JwtTokenValidatorsTest {

    private static final String ISSUER = "https://auth.example.test/realms/dlap";
    private static final String OTHER_ISSUER = "http://keycloak:8080/realms/dlap";
    private static final String AUDIENCE = "dlap-mcp-pep";

    private final OAuth2TokenValidator<Jwt> validator = JwtTokenValidators.create(ISSUER, AUDIENCE);

    private static Jwt jwt(String issuer, Object audience, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("caller")
                .issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(expiresAt);
        if (issuer != null) {
            builder.claim("iss", issuer);
        }
        if (audience != null) {
            builder.claim("aud", audience);
        }
        return builder.build();
    }

    private static Jwt valid() {
        return jwt(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300));
    }

    /** A token with the right issuer and audience that has not expired passes. */
    @Test
    void acceptsTheExpectedIssuerAndAudience() {
        assertThat(validator.validate(valid()).hasErrors()).isFalse();
    }

    /** The audience may be one entry among several. */
    @Test
    void acceptsTheAudienceAmongSeveral() {
        Jwt token = jwt(ISSUER, List.of("account", AUDIENCE), Instant.now().plusSeconds(300));

        assertThat(validator.validate(token).hasErrors()).isFalse();
    }

    /** An issuer that is not configured is rejected, even for a well-formed token. */
    @Test
    void rejectsAnotherIssuer() {
        Jwt token = jwt(OTHER_ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300));

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    /** A token without an issuer claim is rejected. */
    @Test
    void rejectsAMissingIssuer() {
        Jwt token = jwt(null, List.of(AUDIENCE), Instant.now().plusSeconds(300));

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    /** A prefix or a variant of the issuer is not the issuer: the comparison is exact. */
    @Test
    void comparesTheIssuerExactly() {
        for (String variant : new String[] {ISSUER + "/", ISSUER + ".evil.test", "https://AUTH.example.test/realms/dlap"}) {
            Jwt token = jwt(variant, List.of(AUDIENCE), Instant.now().plusSeconds(300));

            assertThat(validator.validate(token).hasErrors()).as(variant).isTrue();
        }
    }

    /** A token minted for another audience is rejected. */
    @Test
    void rejectsAnotherAudience() {
        Jwt token = jwt(ISSUER, List.of("account"), Instant.now().plusSeconds(300));

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    /** A token with no audience is rejected, not treated as valid for everything. */
    @Test
    void rejectsAMissingAudience() {
        Jwt token = jwt(ISSUER, null, Instant.now().plusSeconds(300));

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    /** An expired token is rejected, beyond the clock skew the timestamp check allows. */
    @Test
    void rejectsAnExpiredToken() {
        Jwt token = jwt(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(3600));

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    /** A comma separated list accepts each entry, so the internal and the public Keycloak URL both work. */
    @Test
    void acceptsEveryIssuerOfAList() {
        OAuth2TokenValidator<Jwt> both = JwtTokenValidators.create(" " + ISSUER + " , " + OTHER_ISSUER + ",", AUDIENCE);

        assertThat(both.validate(valid()).hasErrors()).isFalse();
        assertThat(both.validate(jwt(OTHER_ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300))).hasErrors())
                .isFalse();
        assertThat(both.validate(jwt("https://elsewhere.test", List.of(AUDIENCE), Instant.now().plusSeconds(300)))
                .hasErrors()).isTrue();
    }

    /** No issuer configured is a start-up error, never "accept every issuer". */
    @Test
    void refusesToBuildWithoutAnIssuer() {
        for (String blank : new String[] {null, "", "   ", " , ,"}) {
            assertThatThrownBy(() -> JwtTokenValidators.create(blank, AUDIENCE))
                    .as("issuer [" + blank + "]")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DLAP_SECURITY_JWT_EXPECTED_ISSUER");
        }
    }

    /** No audience configured is a start-up error, never "accept every audience". */
    @Test
    void refusesToBuildWithoutAnAudience() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> JwtTokenValidators.create(ISSUER, blank))
                    .as("audience [" + blank + "]")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DLAP_SECURITY_JWT_EXPECTED_AUDIENCE");
        }
    }
}
