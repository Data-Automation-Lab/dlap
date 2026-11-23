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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * PEP-03 (Q-461, D42): the {@link DecisionCache} key covers everything the verdict depends on, so a
 * verdict is never served to a request whose roles, tier or context differ from the cached one.
 */
class DecisionCacheKeyTest {

    private static McpPepProperties props() {
        McpPepProperties p = new McpPepProperties();
        p.getMcppep().getCache().setTtlSeconds(60L);
        return p;
    }

    private static McpAccessRequest req(List<String> roles, String tier, Map<String, Object> context) {
        return new McpAccessRequest("p1", roles, tier, "query", "bq://p/d/t", "asset-1", "read", context);
    }

    private static McpAccessRequest base() {
        return req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of("purpose", "analytics"));
    }

    private static DecisionCache cacheHolding(McpAccessRequest cached) {
        DecisionCache cache = new DecisionCache(props(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        cache.put(cached, McpDecision.allow("allowed", List.of()));
        return cache;
    }

    private static McpPolicyEnforcer enforcerOver(PdpClient pdp) {
        McpPepProperties p = props();
        return new McpPolicyEnforcer(pdp, new DecisionCache(p), p, new SimpleMeterRegistry());
    }

    // ---- different roles, tier or context are misses ----

    @Test
    void differentRolesAreACacheMiss() {
        DecisionCache cache = cacheHolding(base());

        assertThat(cache.get(req(List.of("VIEWER"), "PRACTITIONER", Map.of("purpose", "analytics")))).isEmpty();
        assertThat(cache.get(req(List.of("DATA_ANALYST", "ADMIN"), "PRACTITIONER",
                Map.of("purpose", "analytics")))).isEmpty();
        assertThat(cache.get(req(List.of(), "PRACTITIONER", Map.of("purpose", "analytics")))).isEmpty();
        assertThat(cache.get(req(null, "PRACTITIONER", Map.of("purpose", "analytics")))).isEmpty();
    }

    @Test
    void aDifferentTierIsACacheMiss() {
        DecisionCache cache = cacheHolding(base());

        assertThat(cache.get(req(List.of("DATA_ANALYST"), "EXECUTIVE", Map.of("purpose", "analytics")))).isEmpty();
        assertThat(cache.get(req(List.of("DATA_ANALYST"), null, Map.of("purpose", "analytics")))).isEmpty();
    }

    @Test
    void aDifferentContextIsACacheMiss() {
        DecisionCache cache = cacheHolding(base());

        assertThat(cache.get(req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of("purpose", "marketing")))).isEmpty();
        assertThat(cache.get(req(List.of("DATA_ANALYST"), "PRACTITIONER",
                Map.of("purpose", "analytics", "consent", "granted")))).isEmpty();
        assertThat(cache.get(req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of()))).isEmpty();
        assertThat(cache.get(req(List.of("DATA_ANALYST"), "PRACTITIONER",
                Map.of("purpose", Map.of("name", "analytics"))))).isEmpty();
        assertThat(cache.get(req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of("purpose", List.of("analytics")))))
                .isEmpty();
    }

    @Test
    void aNestedContextValueThatDiffersIsACacheMiss() {
        Map<String, Object> consentGranted = Map.of("consent", Map.of("marketing", true, "regions", List.of("eu")));
        Map<String, Object> consentWithdrawn = Map.of("consent", Map.of("marketing", false, "regions", List.of("eu")));
        Map<String, Object> otherRegion = Map.of("consent", Map.of("marketing", true, "regions", List.of("us")));
        DecisionCache cache = cacheHolding(req(List.of("A"), "T", consentGranted));

        assertThat(cache.get(req(List.of("A"), "T", consentGranted))).isPresent();
        assertThat(cache.get(req(List.of("A"), "T", consentWithdrawn))).isEmpty();
        assertThat(cache.get(req(List.of("A"), "T", otherRegion))).isEmpty();
    }

    @Test
    void contextValuesOfDifferentTypesAreNotConfused() {
        DecisionCache cache = cacheHolding(req(List.of(), null, Map.of("age", "18")));

        assertThat(cache.get(req(List.of(), null, Map.of("age", 18)))).isEmpty();
        assertThat(cache.get(req(List.of(), null, Map.of("age", true)))).isEmpty();
        assertThat(cache.get(req(List.of(), null, Map.of("age", "true")))).isEmpty();
    }

    @Test
    void shiftingCharactersBetweenKeyPartsDoesNotCollide() {
        McpAccessRequest a = new McpAccessRequest("p", List.of("a", "b"), "t", "tool", "u", null, "read", Map.of());
        McpAccessRequest b = new McpAccessRequest("p", List.of("a,b"), "t", "tool", "u", null, "read", Map.of());
        McpAccessRequest c = new McpAccessRequest("p", List.of("a"), "bt", "tool", "u", null, "read", Map.of());
        McpAccessRequest d = new McpAccessRequest("p", List.of("ab"), "t", "tool", "u", null, "read", Map.of());

        assertThat(List.of(DecisionCache.key(a), DecisionCache.key(b), DecisionCache.key(c), DecisionCache.key(d)))
                .doesNotHaveDuplicates();
    }

    // ---- identical requests are hits ----

    @Test
    void anIdenticalRequestIsACacheHit() {
        DecisionCache cache = cacheHolding(base());

        McpAccessRequest same = req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of("purpose", "analytics"));
        assertThat(cache.get(same)).isPresent();
        assertThat(cache.get(same).get().allow()).isTrue();
    }

    @Test
    void roleOrderAndContextKeyOrderDoNotMatter() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("purpose", "analytics");
        first.put("consent", Map.of("marketing", true, "analytics", true));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("consent", new LinkedHashMap<>(Map.of("analytics", true, "marketing", true)));
        second.put("purpose", "analytics");
        DecisionCache cache = cacheHolding(req(List.of("B", "A", "C"), "T", first));

        assertThat(cache.get(req(List.of("C", "A", "B"), "T", second))).isPresent();
        assertThat(cache.get(req(new ArrayList<>(List.of("A", "B", "C")), "T", second))).isPresent();
    }

    @Test
    void anAbsentAndAnEmptyContextAreTheSameRequest() {
        DecisionCache cache = cacheHolding(req(List.of(), null, null));

        assertThat(cache.get(req(List.of(), null, Map.of()))).isPresent();
        assertThat(cache.get(req(null, "", null))).isPresent();
    }

    @Test
    void theContextIsKeyedByItsSha256DigestNotStoredInTheKey() {
        assertThat(DecisionCache.contextDigest(Map.of())).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(DecisionCache.contextDigest(null)).isEqualTo(DecisionCache.contextDigest(Map.of()));
        assertThat(DecisionCache.contextDigest(Map.of("a", 1))).isNotEqualTo(DecisionCache.contextDigest(Map.of()));
        assertThat(DecisionCache.key(base())).doesNotContain("analytics");
    }

    @Test
    void primitiveArraysAndOtherObjectsInTheContextAreDigestedByContent() {
        Map<String, Object> a = Map.of("ids", new int[] {1, 2}, "when", Instant.EPOCH);
        Map<String, Object> same = Map.of("ids", new int[] {1, 2}, "when", Instant.EPOCH);
        Map<String, Object> other = Map.of("ids", new int[] {1, 3}, "when", Instant.EPOCH);

        assertThat(DecisionCache.contextDigest(a)).isEqualTo(DecisionCache.contextDigest(same));
        assertThat(DecisionCache.contextDigest(a)).isNotEqualTo(DecisionCache.contextDigest(other));
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("k", null);
        assertThat(DecisionCache.contextDigest(withNull)).isNotEqualTo(DecisionCache.contextDigest(Map.of()));
        Object[] objects = {"x", 2};
        assertThat(DecisionCache.contextDigest(Map.of("v", objects)))
                .isEqualTo(DecisionCache.contextDigest(Map.of("v", List.of("x", 2))));
    }

    // ---- through the enforcer: the PDP is asked again when the request differs ----

    @Test
    void theEnforcerAsksThePdpAgainWhenRolesTierOrContextDiffer() {
        PdpClient pdp = mock(PdpClient.class);
        when(pdp.evaluate(Mockito.any()))
                .thenReturn(McpDecision.allow("allowed", List.of()))
                .thenReturn(McpDecision.deny("roles changed"))
                .thenReturn(McpDecision.deny("tier changed"))
                .thenReturn(McpDecision.deny("context changed"));
        McpPolicyEnforcer enforcer = enforcerOver(pdp);

        McpDecision granted = enforcer.enforce(base());
        McpDecision otherRoles = enforcer.enforce(
                req(List.of("VIEWER"), "PRACTITIONER", Map.of("purpose", "analytics")));
        McpDecision otherTier = enforcer.enforce(
                req(List.of("DATA_ANALYST"), "EXECUTIVE", Map.of("purpose", "analytics")));
        McpDecision otherContext = enforcer.enforce(
                req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of("purpose", "marketing")));

        assertThat(granted.allow()).isTrue();
        assertThat(List.of(otherRoles, otherTier, otherContext)).allSatisfy(d -> {
            assertThat(d.allow()).isFalse();
            assertThat(d.fromCache()).isFalse();
        });
        verify(pdp, times(4)).evaluate(Mockito.any());
    }

    @Test
    void theEnforcerServesAnIdenticalRequestFromTheCache() {
        PdpClient pdp = mock(PdpClient.class);
        when(pdp.evaluate(Mockito.any())).thenReturn(McpDecision.allow("allowed", List.of()));
        McpPolicyEnforcer enforcer = enforcerOver(pdp);

        enforcer.enforce(base());
        McpDecision second = enforcer.enforce(
                req(List.of("DATA_ANALYST"), "PRACTITIONER", Map.of("purpose", "analytics")));

        assertThat(second.fromCache()).isTrue();
        verify(pdp, times(1)).evaluate(Mockito.any());
    }

    // ---- a token-bound caller is unchanged ----

    @Test
    void aTokenBoundCallerIsCachedByItsOwnSubjectRolesAndTier() {
        PdpClient pdp = mock(PdpClient.class);
        when(pdp.evaluate(Mockito.any())).thenReturn(McpDecision.allow("allowed", List.of()));
        McpPolicyEnforcer enforcer = enforcerOver(pdp);
        SubjectBinder binder = new SubjectBinder(new SimpleMeterRegistry());
        Authentication user = userToken("user-1", "PRACTITIONER", "ROLE_DATA_ANALYST");

        // Whatever roles and tier the body asserts, the binder replaces them with the token's, so the
        // key is the same and the second call is a hit exactly as before this change.
        McpDecision first = enforcer.enforce(binder.bind(
                new McpAccessRequest(null, List.of("ADMIN"), "EXECUTIVE", "query", "bq://p/d/t", "asset-1", "read",
                        Map.of("purpose", "analytics")), user));
        McpDecision second = enforcer.enforce(binder.bind(
                new McpAccessRequest("user-1", List.of("CDO"), null, "query", "bq://p/d/t", "asset-1", "read",
                        Map.of("purpose", "analytics")), user));

        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        verify(pdp, times(1)).evaluate(Mockito.any());
    }

    @Test
    void aTokenBoundCallerWhoseRolesChangeGetsAFreshVerdict() {
        PdpClient pdp = mock(PdpClient.class);
        when(pdp.evaluate(Mockito.any())).thenReturn(McpDecision.allow("allowed", List.of()));
        McpPolicyEnforcer enforcer = enforcerOver(pdp);
        SubjectBinder binder = new SubjectBinder(new SimpleMeterRegistry());
        McpAccessRequest body = new McpAccessRequest(null, null, null, "query", "bq://p/d/t", "asset-1", "read",
                Map.of());

        enforcer.enforce(binder.bind(body, userToken("user-1", "PRACTITIONER", "ROLE_DATA_ANALYST")));
        McpDecision afterDemotion = enforcer.enforce(
                binder.bind(body, userToken("user-1", "PRACTITIONER", "ROLE_VIEWER")));

        assertThat(afterDemotion.fromCache()).isFalse();
        verify(pdp, times(2)).evaluate(Mockito.any());
    }

    private static Authentication userToken(String subject, String tier, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).claim("tier", tier)
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), subject);
    }
}
