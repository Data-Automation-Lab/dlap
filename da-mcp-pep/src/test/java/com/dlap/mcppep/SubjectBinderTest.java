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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * PEP-02 unit tests of {@link SubjectBinder}: which of the subject, roles and tier of a request survive
 * for a service account and for a user or agent token, and what is refused.
 */
class SubjectBinderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SubjectBinder binder = new SubjectBinder(registry);

    private static McpAccessRequest body(String principal, List<String> roles, String tier) {
        return new McpAccessRequest(principal, roles, tier, "query", "bq://p/d/t", "asset-1", "read",
                Map.of("purpose", "analytics"));
    }

    private static Authentication token(String subject, Map<String, Object> claims, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject)
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(jwt::claim);
        return new JwtAuthenticationToken(jwt.build(),
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), subject);
    }

    private double count(String source) {
        var counter = registry.find(SubjectBinder.BINDINGS_COUNTER).tag("source", source).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- user or agent token ----

    @Test
    void aUserTokenNamingAnotherPrincipalIsRefused() {
        Authentication user = token("user-1", Map.of(), "ROLE_ADMIN");

        assertThatThrownBy(() -> binder.bind(body("someone-else", List.of("ADMIN"), null), user))
                .isInstanceOf(SubjectMismatchException.class)
                .hasMessageContaining("only a service account may name another principal")
                .hasMessageNotContaining("someone-else");
        assertThat(count("mismatch")).isEqualTo(1);
    }

    @Test
    void aUserTokenKeepsItsOwnSubjectAndTakesRolesAndTierFromTheToken() {
        Authentication user = token("user-1", Map.of("tier", "PRACTITIONER"), "ROLE_ADMIN", "ROLE_DATA_ENGINEER");

        McpAccessRequest bound = binder.bind(body("user-1", List.of("CDO"), "EXECUTIVE"), user);

        assertThat(bound.principalId()).isEqualTo("user-1");
        assertThat(bound.roles()).containsExactly("ADMIN", "DATA_ENGINEER");
        assertThat(bound.tier()).isEqualTo("PRACTITIONER");
        assertThat(bound.tool()).isEqualTo("query");
        assertThat(bound.resourceUri()).isEqualTo("bq://p/d/t");
        assertThat(bound.assetId()).isEqualTo("asset-1");
        assertThat(bound.action()).isEqualTo("read");
        assertThat(bound.context()).containsEntry("purpose", "analytics");
        assertThat(count("token")).isEqualTo(1);
    }

    @Test
    void aUserTokenNamingNobodyIsBoundToTheTokenSubject() {
        Authentication user = token("user-1", Map.of(), "ROLE_ADMIN");

        assertThat(binder.bind(body(null, null, null), user).principalId()).isEqualTo("user-1");
        assertThat(binder.bind(body("  ", null, null), user).principalId()).isEqualTo("user-1");
    }

    @Test
    void aUserTokenWithoutATierClaimHasNoTierWhateverTheBodySays() {
        Authentication user = token("user-1", Map.of(), "ROLE_ADMIN");

        assertThat(binder.bind(body("user-1", List.of(), "EXECUTIVE"), user).tier()).isNull();
    }

    @Test
    void aBlankTierClaimIsNoTier() {
        Authentication user = token("user-1", Map.of("tier", " "), "ROLE_ADMIN");

        assertThat(binder.bind(body(null, null, "EXECUTIVE"), user).tier()).isNull();
    }

    @Test
    void onlyRoleAuthoritiesAreRolesOfTheSubject() {
        // Spring Security 7 adds FACTOR_BEARER to a bearer-token authentication; it is not a realm role.
        Authentication user = token("user-1", Map.of(), "FACTOR_BEARER", "ROLE_ADMIN", "ROLE_", "ADMIN");

        assertThat(binder.bind(body(null, null, null), user).roles()).containsExactly("ADMIN");
    }

    @Test
    void theSubjectIsCaseSensitive() {
        Authentication user = token("User-1", Map.of(), "ROLE_ADMIN");

        assertThatThrownBy(() -> binder.bind(body("user-1", null, null), user))
                .isInstanceOf(SubjectMismatchException.class);
    }

    @Test
    void aTokenWithoutASubjectCannotBeBound() {
        Authentication noSubject = new UsernamePasswordAuthenticationToken("", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThatThrownBy(() -> binder.bind(body("user-1", null, null), noSubject))
                .isInstanceOf(SubjectMismatchException.class);
    }

    // ---- service account ----

    @Test
    void aServiceAccountNamesTheSubjectRolesAndTier() {
        Authentication gateway = token("gateway", Map.of("tier", "EXECUTIVE"), "ROLE_SERVICE_ACCOUNT");
        McpAccessRequest request = body("agent-9", List.of("DATA_ANALYST"), "READER");

        McpAccessRequest bound = binder.bind(request, gateway);

        assertThat(bound).isSameAs(request);
        assertThat(count("body")).isEqualTo(1);
    }

    @Test
    void aServiceAccountNamingNobodyIsBoundToItsOwnSubject() {
        Authentication gateway = token("gateway", Map.of(), "ROLE_SERVICE_ACCOUNT");

        McpAccessRequest bound = binder.bind(body(null, List.of("VIEWER"), "READER"), gateway);

        assertThat(bound.principalId()).isEqualTo("gateway");
        assertThat(bound.roles()).containsExactly("VIEWER");
        assertThat(bound.tier()).isEqualTo("READER");
        assertThat(count("service-account")).isEqualTo(1);
    }

    @Test
    void aServiceAccountWithNoSubjectAnywhereIsRefused() {
        Authentication gateway = new UsernamePasswordAuthenticationToken(" ", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ACCOUNT")));

        assertThatThrownBy(() -> binder.bind(body(null, null, null), gateway))
                .isInstanceOf(SubjectMismatchException.class);
    }

    @Test
    void theServiceAccountRoleOnlyCountsWithItsPrefixedAuthority() {
        // An authority that merely contains the words is not the machine role.
        Authentication lookalike = token("user-1", Map.of(), "SERVICE_ACCOUNT_VIEWER", "ROLE_ADMIN");

        assertThatThrownBy(() -> binder.bind(body("other", null, null), lookalike))
                .isInstanceOf(SubjectMismatchException.class);
    }
}
