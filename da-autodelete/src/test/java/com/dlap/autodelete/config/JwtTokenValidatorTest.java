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

package com.dlap.autodelete.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * OBS-01 acceptance for da-autodelete: {@code dlap.security.jwt.expected-issuer} accepts the public issuer
 * next to the internal one (critique item 12), and the other claim checks keep their behaviour.
 *
 * <p>Scope: the validator the service installs on its JWT decoder, called directly with hand-built
 * tokens. Signature checking is the decoder's job and is not under test here.
 */
class JwtTokenValidatorTest {

    private static final String INTERNAL = "http://keycloak:8080/realms/dlap";
    private static final String PUBLIC = "https://auth.example.com/realms/dlap";
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private static Jwt token(String issuer, List<String> audience, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("s")
                .issuedAt(LONG_AGO.minusSeconds(3600)).expiresAt(expiresAt);
        if (issuer != null) {
            builder.claim(JwtClaimNames.ISS, issuer);
        }
        if (audience != null) {
            builder.claim(JwtClaimNames.AUD, audience);
        }
        return builder.build();
    }

    private static Jwt token(String issuer) {
        return token(issuer, null, Instant.parse("2999-01-01T00:00:00Z"));
    }

    private static boolean accepts(OAuth2TokenValidator<Jwt> validator, Jwt jwt) {
        return !validator.validate(jwt).hasErrors();
    }

    /** The dev default: the flag is off, so any issuer passes (Q-014). */
    @Test
    void anyIssuerPassesWhileIssuerValidationIsOff() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(false, PUBLIC, false, "");

        assertThat(accepts(validator, token(INTERNAL))).isTrue();
        assertThat(accepts(validator, token("https://elsewhere.example.org/realms/x"))).isTrue();
    }

    /** One issuer, as before: the exact value passes and anything else fails. */
    @Test
    void aSingleExpectedIssuerStillWorks() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(true, PUBLIC, false, "");

        assertThat(accepts(validator, token(PUBLIC))).isTrue();
        assertThat(accepts(validator, token(INTERNAL))).isFalse();
        assertThat(accepts(validator, token(PUBLIC + "/"))).as("a trailing slash is another issuer").isFalse();
    }

    /** The public and the internal issuer are both accepted when the env value lists them. */
    @Test
    void aListAcceptsTheInternalAndThePublicIssuer() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(true, PUBLIC + "," + INTERNAL, false, "");

        assertThat(accepts(validator, token(PUBLIC))).isTrue();
        assertThat(accepts(validator, token(INTERNAL))).isTrue();
        assertThat(accepts(validator, token("https://elsewhere.example.org/realms/x"))).isFalse();
    }

    /** Spaces around the entries do not matter, so an env file may break the line or add a space. */
    @Test
    void listEntriesAreTrimmed() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(true, " " + PUBLIC + " ,  " + INTERNAL + " ", false, "");

        assertThat(accepts(validator, token(PUBLIC))).isTrue();
        assertThat(accepts(validator, token(INTERNAL))).isTrue();
    }

    /** A list with no usable entry fails closed instead of accepting everything. */
    @Test
    void aListWithNoUsableEntryRejectsEveryToken() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(true, " , ,", false, "");

        assertThat(accepts(validator, token(PUBLIC))).isFalse();
        assertThat(accepts(validator, token(INTERNAL))).isFalse();
    }

    /** With the check on, a token that names no issuer is rejected. */
    @Test
    void aTokenWithoutAnIssuerIsRejectedWhileTheCheckIsOn() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(true, PUBLIC + "," + INTERNAL, false, "");

        assertThat(accepts(validator, token(null))).isFalse();
    }

    /** A blank expected value keeps the check off, as it always did, even with the flag on. */
    @Test
    void aBlankExpectedIssuerKeepsTheCheckOff() {
        assertThat(accepts(SecurityConfiguration.tokenValidator(true, "", false, ""), token(INTERNAL))).isTrue();
        assertThat(accepts(SecurityConfiguration.tokenValidator(true, null, false, ""), token(INTERNAL))).isTrue();
    }

    /** The audience check is independent of the issuer check and unchanged. */
    @Test
    void theAudienceCheckStillApplies() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfiguration.tokenValidator(true, PUBLIC + "," + INTERNAL, true, "dlap-api");
        Instant future = Instant.parse("2999-01-01T00:00:00Z");

        assertThat(accepts(validator, token(PUBLIC, List.of("dlap-api"), future))).isTrue();
        assertThat(accepts(validator, token(PUBLIC, List.of("other"), future))).isFalse();
        assertThat(accepts(validator, token(PUBLIC, null, future))).isFalse();
    }

    /** An expired token fails whatever the issuer settings are. */
    @Test
    void anExpiredTokenIsRejectedWhateverTheIssuerSettings() {
        Jwt expired = token(PUBLIC, null, LONG_AGO);

        assertThat(accepts(SecurityConfiguration.tokenValidator(false, "", false, ""), expired)).isFalse();
        assertThat(accepts(SecurityConfiguration.tokenValidator(true, PUBLIC, false, ""), expired)).isFalse();
    }
}
