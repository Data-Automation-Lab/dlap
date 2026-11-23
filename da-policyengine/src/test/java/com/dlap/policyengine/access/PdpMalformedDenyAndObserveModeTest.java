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

package com.dlap.policyengine.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * POL-02 through the PDP. Q-441: a deny AccessPolicy with a malformed clause denies (it no longer
 * fails open), whatever the default decision. Q-431: in observe mode
 * ({@code dlap.pdp.default-decision=allow}) an allow AccessPolicy whose match is UNKNOWN does not
 * contribute and the operator's explicit allow default applies; there is no implicit deny.
 */
class PdpMalformedDenyAndObserveModeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID ASSET = UUID.fromString("9f6d0000-0000-4000-8000-000000000042");

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);

    private AccessDecision decide(String defaultDecision, List<String> yamls, CatalogAssetDTO asset) {
        List<Policy> enabled = new ArrayList<>();
        for (String yaml : yamls) {
            Policy p = new Policy();
            p.setId(UUID.randomUUID());
            p.setName("p-" + enabled.size());
            p.setStatus(PolicyStatus.ENABLED);
            p.setPolicyDocument(yaml);
            enabled.add(p);
        }
        when(repository.findByStatus(PolicyStatus.ENABLED)).thenReturn(enabled);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(asset));
        AccessDecisionService service = new AccessDecisionService(repository, new PolicyDslParser(),
                new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)), new CatalogAssetSnapshotResolver(catalog),
                new SimpleMeterRegistry(), ObservationRegistry.create(), defaultDecision);

        AuthZenEvaluationRequest req = new AuthZenEvaluationRequest();
        AuthZenEvaluationRequest.Subject s = new AuthZenEvaluationRequest.Subject();
        s.setType("agent");
        s.setId("svc");
        s.setProperties(Map.of("roles", List.of("DATA_ANALYST")));
        req.setSubject(s);
        AuthZenEvaluationRequest.Action a = new AuthZenEvaluationRequest.Action();
        a.setName("read");
        req.setAction(a);
        AuthZenEvaluationRequest.Resource r = new AuthZenEvaluationRequest.Resource();
        r.setType("asset");
        r.setId(ASSET.toString());
        req.setResource(r);
        return service.evaluate(req).decision();
    }

    private static String accessPolicy(String id, String effect, String match) {
        return "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: " + id + "}\nspec:\n"
                + "  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
                + "  match: " + match + "\n  effect: " + effect + "\n";
    }

    private static CatalogAssetDTO asset(List<String> labels, Instant lastRead) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setLabels(labels);
        a.setLastAccessedAt(lastRead);
        return a;
    }

    @Nested
    class MalformedDeny {

        /** A typo in the duration: before POL-02 the clause was FALSE and this deny never applied. */
        private final String denyWithTypo = accessPolicy("deny-stale-typo", "deny",
                "{lastAccessedAt: {olderThan: 365 days}}");
        private final String allowAll = accessPolicy("allow-pii", "allow", "{labels: {anyOf: [PII]}}");

        @Test
        void aDenyWithAMalformedClauseDeniesEvenWhenAnAllowMatches() {
            AccessDecision d = decide("deny", List.of(allowAll, denyWithTypo),
                    asset(List.of("PII"), Instant.parse("2026-10-30T00:00:00Z")));

            assertThat(d.allow()).isFalse();
            assertThat(d.policyIds()).containsExactly("deny-stale-typo");
        }

        @Test
        void itDeniesInObserveModeToo() {
            AccessDecision d = decide("allow", List.of(denyWithTypo), asset(List.of(), null));

            assertThat(d.allow()).isFalse();
            assertThat(d.defaultApplied()).isFalse();
        }

        @Test
        void aWellFormedFalseClauseStillKeepsTheDenyOut() {
            String denyPiiWithTypo = accessPolicy("deny-pii-typo", "deny",
                    "{labels: {anyOf: [PII]}, lastAccessedAt: {olderThan: 365 days}}");

            AccessDecision d = decide("allow", List.of(denyPiiWithTypo), asset(List.of("PUBLIC"), null));

            assertThat(d.allow()).isTrue();
            assertThat(d.defaultApplied()).isTrue();
        }
    }

    @Nested
    class ObserveMode {

        private final String allowRecentEu = accessPolicy("allow-recent-eu", "allow",
                "{labels: {anyOf: [EU_RESIDENT]}, lastAccessedAt: {youngerThan: P30D}}");

        @Test
        void anUnknownAllowFallsToTheExplicitAllowDefault() {
            // The last read is unknown, so the allow's match is UNKNOWN and it does not contribute.
            AccessDecision d = decide("allow", List.of(allowRecentEu), asset(List.of("EU_RESIDENT"), null));

            assertThat(d.allow()).isTrue();
            assertThat(d.defaultApplied()).isTrue();
            assertThat(d.policyIds()).isEmpty();
        }

        @Test
        void theSameRequestIsDeniedUnderTheShippedDenyDefault() {
            AccessDecision d = decide("deny", List.of(allowRecentEu), asset(List.of("EU_RESIDENT"), null));

            assertThat(d.allow()).isFalse();
            assertThat(d.defaultApplied()).isTrue();
        }
    }
}
