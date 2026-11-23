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

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Q-459 unit tests of {@link PepAudienceGate}: the gate of {@code POST /mcp/v1/authorize} admits a
 * verified token that lists the PEP audience and nothing else, whatever the roles.
 */
class PepAudienceGateTest {

    private static final String AUDIENCE = "dlap-mcp-pep";

    private final PepAudienceGate gate = new PepAudienceGate(AUDIENCE);

    private static Authentication token(List<String> audience, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300));
        if (audience != null) {
            jwt.audience(audience);
        }
        return new JwtAuthenticationToken(jwt.build(),
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), "user-1");
    }

    @Test
    void aTokenWithThePepAudienceIsAdmittedWithoutAnyRole() {
        assertThat(gate.carriesPepAudience(token(List.of(AUDIENCE)))).isTrue();
    }

    @Test
    void aTokenWithThePepAudienceAmongOthersIsAdmitted() {
        assertThat(gate.carriesPepAudience(token(List.of("account", AUDIENCE), "ROLE_VIEWER"))).isTrue();
    }

    @Test
    void anAdminTokenForAnotherAudienceIsRefused() {
        assertThat(gate.carriesPepAudience(token(List.of("account"), "ROLE_ADMIN", "ROLE_SERVICE_ACCOUNT"))).isFalse();
    }

    @Test
    void aTokenWithoutAnAudienceIsRefused() {
        assertThat(gate.carriesPepAudience(token(null, "ROLE_ADMIN"))).isFalse();
    }

    @Test
    void theAudienceIsMatchedExactly() {
        assertThat(gate.carriesPepAudience(token(List.of("dlap-mcp-pep-extra")))).isFalse();
        assertThat(gate.carriesPepAudience(token(List.of("DLAP-MCP-PEP")))).isFalse();
    }

    @Test
    void anAuthenticationThatIsNotAVerifiedJwtIsRefused() {
        Authentication password = UsernamePasswordAuthenticationToken.authenticated("admin", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(gate.carriesPepAudience(password)).isFalse();
        assertThat(gate.carriesPepAudience(null)).isFalse();
    }

    @Test
    void anUnsetAudienceAdmitsNobody() {
        assertThat(new PepAudienceGate("").carriesPepAudience(token(List.of(AUDIENCE)))).isFalse();
        assertThat(new PepAudienceGate("  ").carriesPepAudience(token(List.of(AUDIENCE)))).isFalse();
        assertThat(new PepAudienceGate(null).carriesPepAudience(token(List.of(AUDIENCE)))).isFalse();
    }

    @Test
    void theConfiguredAudienceIsTrimmed() {
        assertThat(new PepAudienceGate(" " + AUDIENCE + " ").carriesPepAudience(token(List.of(AUDIENCE)))).isTrue();
    }
}
