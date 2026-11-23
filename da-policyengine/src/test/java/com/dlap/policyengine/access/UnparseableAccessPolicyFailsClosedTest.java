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
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyKind;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * POL-03, decision D43 on Q-842: an ENABLED AccessPolicy whose stored document does not parse can no
 * longer fail open. A deny (or an unreadable effect) is kept as an invalid deny of its readable scope
 * (reason policy-invalid), or of every request when no scope can be read (reason
 * policy-store-invalid); an unparseable allow is skipped.
 */
class UnparseableAccessPolicyFailsClosedTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID PII_ASSET = UUID.fromString("9f6d0000-0000-4000-8000-000000000101");
    private static final UUID PUBLIC_ASSET = UUID.fromString("9f6d0000-0000-4000-8000-000000000102");

    /** A valid allow: analysts and engineers may read anything. */
    private static final String ALLOW_ALL = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-read}\n"
            + "spec:\n  subjects: {roles: [DATA_ANALYST, DATA_ENGINEER]}\n  actions: [read]\n  effect: allow\n";

    /** A deny of PII for analysts that does not parse: spec.actions is missing. */
    private static final String BROKEN_DENY_PII = "apiVersion: dlap/v1\nkind: AccessPolicy\n"
            + "metadata: {id: deny-pii-broken}\nspec:\n  subjects: {roles: [DATA_ANALYST]}\n"
            + "  match: {labels: {anyOf: [PII]}}\n  effect: deny\n";

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<Policy> enabled = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessDecisionService.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(repository.findByStatus(PolicyStatus.ENABLED)).thenReturn(enabled);
        when(catalog.getAssetById(PII_ASSET)).thenReturn(Optional.of(asset(PII_ASSET, "PII")));
        when(catalog.getAssetById(PUBLIC_ASSET)).thenReturn(Optional.of(asset(PUBLIC_ASSET, "PUBLIC")));
        when(catalog.resolveAssetIdByUri(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static CatalogAssetDTO asset(UUID id, String label) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(id);
        a.setLabels(List.of(label));
        return a;
    }

    private Policy store(String yaml) {
        Policy p = new Policy();
        p.setId(UUID.randomUUID());
        p.setName("p-" + enabled.size());
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument(yaml);
        enabled.add(p);
        return p;
    }

    private Policy storeRow(PolicyKind kind, String yaml) {
        Policy p = store(yaml);
        p.setPolicyKind(kind);
        return p;
    }

    private AccessDecisionService service(String defaultDecision) {
        AccessDecisionService s = new AccessDecisionService(repository, new PolicyDslParser(),
                new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)), new CatalogAssetSnapshotResolver(catalog),
                registry, ObservationRegistry.create(), defaultDecision);
        s.refresh();
        return s;
    }

    private static AccessDecision decide(AccessDecisionService service, String role, String action, UUID asset) {
        AuthZenEvaluationRequest req = new AuthZenEvaluationRequest();
        AuthZenEvaluationRequest.Subject s = new AuthZenEvaluationRequest.Subject();
        s.setType("user");
        s.setId("u-1");
        s.setProperties(Map.of("roles", List.of(role)));
        req.setSubject(s);
        AuthZenEvaluationRequest.Action a = new AuthZenEvaluationRequest.Action();
        a.setName(action);
        req.setAction(a);
        AuthZenEvaluationRequest.Resource r = new AuthZenEvaluationRequest.Resource();
        r.setType("asset");
        r.setId(asset.toString());
        req.setResource(r);
        return service.evaluate(req).decision();
    }

    private double gauge(String scope) {
        return registry.get(AccessDecisionService.INVALID_DENY_GAUGE).tag("scope", scope).gauge().value();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Nested
    class ReadableScope {

        @Test
        void anUnparseableDenyWithAScopeDeniesInsideTheScopeAndAllowsOutsideIt() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII);
            AccessDecisionService pdp = service("deny");

            AccessDecision inside = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            AccessDecision otherResource = decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET);
            AccessDecision otherSubject = decide(pdp, "DATA_ENGINEER", "read", PII_ASSET);

            assertThat(inside.allow()).isFalse();
            assertThat(inside.reason()).isEqualTo("policy-invalid: deny-pii-broken");
            assertThat(inside.policyIds()).containsExactly("deny-pii-broken");
            assertThat(inside.defaultApplied()).isFalse();
            assertThat(otherResource.allow()).isTrue();
            assertThat(otherResource.policyIds()).containsExactly("allow-read");
            assertThat(otherSubject.allow()).isTrue();
            assertThat(otherSubject.policyIds()).containsExactly("allow-read");
        }

        @Test
        void itDeniesEveryActionInsideTheScopeBecauseTheActionsAreNotRead() {
            store(ALLOW_ALL.replace("actions: [read]", "actions: [read, write]"));
            store(BROKEN_DENY_PII.replace("  match:", "  actions: read-only-typo\n  match:"));
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "write", PII_ASSET).allow()).isFalse();
            assertThat(decide(pdp, "DATA_ANALYST", "write", PUBLIC_ASSET).allow()).isTrue();
        }

        @Test
        void anUnreadableEffectIsADenyOfItsScope() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII.replace("effect: deny", "effect: denny"));
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).reason())
                    .isEqualTo("policy-invalid: deny-pii-broken");
            assertThat(decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET).allow()).isTrue();
        }

        @Test
        void aMissingEffectIsADenyOfItsScope() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII.replace("  effect: deny\n", ""));
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).allow()).isFalse();
            assertThat(decide(pdp, "DATA_ENGINEER", "read", PII_ASSET).allow()).isTrue();
        }

        @Test
        void anUnreadableSubjectSelectorWidensTheScopeToEverySubject() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII.replace("subjects: {roles: [DATA_ANALYST]}", "subjects: {roles: DATA_ANALYST}"));
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ENGINEER", "read", PII_ASSET).allow()).isFalse();
            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isTrue();
        }

        @Test
        void anUnreadableResourceSelectorWidensTheScopeToEveryResource() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII.replace("match: {labels: {anyOf: [PII]}}", "match: [labels, PII]"));
            AccessDecisionService pdp = service("deny");

            AccessDecision analystPublic = decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET);
            assertThat(analystPublic.allow()).isFalse();
            assertThat(analystPublic.reason()).isEqualTo("policy-invalid: deny-pii-broken");
            assertThat(decide(pdp, "DATA_ENGINEER", "read", PII_ASSET).allow()).isTrue();
        }

        @Test
        void aMalformedClauseInTheReadableSelectorApplies() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII.replace("match: {labels: {anyOf: [PII]}}", "match: {labels: {any: [PII]}}"));
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET).allow()).isFalse();
            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isTrue();
        }

        @Test
        void itAppliesInObserveModeToo() {
            store(BROKEN_DENY_PII);
            AccessDecisionService pdp = service("allow");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).allow()).isFalse();
            AccessDecision outside = decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET);
            assertThat(outside.allow()).isTrue();
            assertThat(outside.defaultApplied()).isTrue();
        }

        @Test
        void theRowIdNamesADocumentWithoutAReadableMetadataId() {
            store(ALLOW_ALL);
            Policy broken = store(BROKEN_DENY_PII.replace("metadata: {id: deny-pii-broken}", "metadata: {}"));
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            assertThat(d.reason()).isEqualTo("policy-invalid: " + broken.getId());
            assertThat(d.policyIds()).containsExactly(broken.getId().toString());
        }

        @Test
        void theReasonListsInvalidDeniesBeforeValidOnes() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII);
            store("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: deny-pii-ok}\nspec:\n"
                    + "  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n  match: {labels: {anyOf: [PII]}}\n"
                    + "  effect: deny\n");
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            assertThat(d.reason()).isEqualTo("policy-invalid: deny-pii-broken; denied by deny-pii-ok");
            assertThat(d.policyIds()).containsExactly("deny-pii-broken", "deny-pii-ok");
        }
    }

    @Nested
    class UnreadableScope {

        @Test
        void anUnparseableDenyWithoutAReadableScopeDeniesEverything() {
            store(ALLOW_ALL);
            store("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: deny-nothing-readable}\n"
                    + "spec:\n  subjects: {roles: [[nested]]}\n  effect: deny\n");
            AccessDecisionService pdp = service("deny");

            for (String role : List.of("DATA_ANALYST", "DATA_ENGINEER", "ADMIN")) {
                for (UUID asset : List.of(PII_ASSET, PUBLIC_ASSET)) {
                    AccessDecision d = decide(pdp, role, "read", asset);
                    assertThat(d.allow()).isFalse();
                    assertThat(d.reason()).isEqualTo("policy-store-invalid: deny-nothing-readable");
                    assertThat(d.policyIds()).containsExactly("deny-nothing-readable");
                }
            }
        }

        @Test
        void aDocumentThatIsNotYamlDeniesEverythingInObserveModeTooNamedByItsRowId() {
            store(ALLOW_ALL);
            Policy garbage = store("apiVersion: [dlap/v1\n  kind: {AccessPolicy\n");
            AccessDecisionService pdp = service("allow");

            AccessDecision d = decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET);
            assertThat(d.allow()).isFalse();
            assertThat(d.reason()).isEqualTo("policy-store-invalid: " + garbage.getId());
            assertThat(d.defaultApplied()).isFalse();
        }

        @Test
        void anEmptyMatchAndNoSubjectsLeaveNoReadableScope() {
            store(ALLOW_ALL);
            store("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: deny-empty}\nspec:\n"
                    + "  match: {}\n  actions: [read]\n  effect: deny\n");
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).reason())
                    .isEqualTo("policy-store-invalid: deny-empty");
        }

        @Test
        void fixingTheDocumentLiftsTheDenyAtTheNextRefresh() {
            store(ALLOW_ALL);
            Policy broken = store("not: [yaml");
            AccessDecisionService pdp = service("deny");
            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isFalse();

            broken.setPolicyDocument(ALLOW_ALL.replace("allow-read", "allow-read-2"));
            pdp.refresh();

            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isTrue();
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
        }
    }

    @Nested
    class SkippedAndIgnored {

        @Test
        void anUnparseableAllowIsSkipped() {
            // The broken allow would cover analysts on PII; it is skipped, so the default applies.
            store("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-pii-broken}\nspec:\n"
                    + "  subjects: {roles: [DATA_ANALYST]}\n  match: {labels: {anyOf: [PII]}}\n  effect: Allow\n");
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            assertThat(d.allow()).isFalse();
            assertThat(d.defaultApplied()).isTrue();
            assertThat(d.policyIds()).isEmpty();
            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isZero();
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
        }

        @Test
        void anUnparseableAllowNeverDeniesWhatAValidAllowPermits() {
            store(ALLOW_ALL);
            store("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-broken}\nspec:\n"
                    + "  subjects: {roles: [DATA_ANALYST]}\n  effect: allow\n");
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            assertThat(d.allow()).isTrue();
            assertThat(d.policyIds()).containsExactly("allow-read");
        }

        @Test
        void anUnparseableLifecyclePolicyIsIgnoredByThePdp() {
            store(ALLOW_ALL);
            store("apiVersion: dlap/v1\nkind: policy\nmetadata: {id: archive-broken}\nspec:\n"
                    + "  match: {cloudProvider: GCP}\n");
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).allow()).isTrue();
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
        }
    }

    @Nested
    class MetricAndLog {

        @Test
        void theGaugeCountsInvalidDeniesByScopeAndEachRefreshLogsThemOnce() {
            store(ALLOW_ALL);
            store(BROKEN_DENY_PII);
            Policy garbage = store("{{{");
            store("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-broken}\nspec:\n"
                    + "  subjects: {roles: [DATA_ANALYST]}\n  effect: allow\n");
            AccessDecisionService pdp = service("deny");

            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isEqualTo(1.0);
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isEqualTo(1.0);
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().get(0)).contains("2 invalid deny policies fail closed")
                    .contains("1 with an unreadable scope").contains("deny-pii-broken")
                    .contains("scope subjects+resources").contains(garbage.getId().toString())
                    .contains("scope unreadable").contains("1 unparseable allow policies are skipped")
                    .contains("allow-broken");

            decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET);
            assertThat(warnings()).hasSize(1);

            pdp.refresh();
            assertThat(warnings()).hasSize(2);
        }

        @Test
        void aCleanStoreLogsNoWarningAndCountsZero() {
            store(ALLOW_ALL);
            service("deny");

            assertThat(warnings()).isEmpty();
            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isZero();
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
        }
    }

    /** POL-04 (Q-870): a deny whose kind alone is wrong is still a deny. */
    @Nested
    class LifecycleKindWithAnEffect {

        /** Kind Policy but a deny in every other respect: it does not parse (no actions). */
        private static final String DENY_PII_KIND_POLICY = BROKEN_DENY_PII.replace("kind: AccessPolicy",
                "kind: Policy");

        @Test
        void kindPolicyPlusEffectDenyIsKeptAsAnInvalidDeny() {
            store(ALLOW_ALL);
            store(DENY_PII_KIND_POLICY);
            AccessDecisionService pdp = service("deny");

            AccessDecision inside = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            assertThat(inside.allow()).isFalse();
            assertThat(inside.reason()).isEqualTo("policy-invalid: deny-pii-broken");
            assertThat(decide(pdp, "DATA_ANALYST", "read", PUBLIC_ASSET).allow()).isTrue();
            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isEqualTo(1.0);
        }

        @Test
        void kindPolicyPlusEffectDenyWithNoReadableScopeDeniesEverything() {
            store(ALLOW_ALL);
            store("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: deny-all}\nspec:\n  effect: DENY\n");
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET);
            assertThat(d.allow()).isFalse();
            assertThat(d.reason()).isEqualTo("policy-store-invalid: deny-all");
        }

        @Test
        void kindPolicyPlusAnUnreadableEffectIsAnInvalidDenyToo() {
            store(ALLOW_ALL);
            store(DENY_PII_KIND_POLICY.replace("effect: deny", "effect: [deny]"));
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).reason())
                    .isEqualTo("policy-invalid: deny-pii-broken");
        }

        @Test
        void kindPolicyWithoutAnEffectIsStillIgnored() {
            store(ALLOW_ALL);
            store("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: archive-broken}\nspec:\n"
                    + "  subjects: {roles: [DATA_ANALYST]}\n  match: {cloudProvider: GCP}\n");
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).allow()).isTrue();
            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isZero();
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
        }

        @Test
        void kindPolicyPlusEffectAllowIsSkippedNotDenied() {
            store(DENY_PII_KIND_POLICY.replace("effect: deny", "effect: allow"));
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ANALYST", "read", PII_ASSET);
            assertThat(d.defaultApplied()).isTrue();
            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isZero();
        }
    }

    /** POL-04 (Q-871): an ENABLED DSL row without a document denies; a legacy rule row does not. */
    @Nested
    class BlankDocument {

        @Test
        void aBlankEnabledDslRowDeniesEverything() {
            store(ALLOW_ALL);
            Policy blanked = storeRow(PolicyKind.DSL, "   \n\t ");
            AccessDecisionService pdp = service("deny");

            for (String role : List.of("DATA_ANALYST", "DATA_ENGINEER", "ADMIN")) {
                for (UUID asset : List.of(PII_ASSET, PUBLIC_ASSET)) {
                    AccessDecision d = decide(pdp, role, "read", asset);
                    assertThat(d.allow()).isFalse();
                    assertThat(d.reason()).isEqualTo("policy-store-invalid: " + blanked.getId());
                    assertThat(d.policyIds()).containsExactly(blanked.getId().toString());
                    assertThat(d.defaultApplied()).isFalse();
                }
            }
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isEqualTo(1.0);
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().get(0)).contains("1 with an unreadable scope").contains(blanked.getId().toString())
                    .contains("missing or blank");
        }

        @Test
        void aNullDocumentOnAnEnabledDslRowDeniesEverythingInObserveModeToo() {
            store(ALLOW_ALL);
            Policy blanked = storeRow(PolicyKind.DSL, null);
            AccessDecisionService pdp = service("allow");

            AccessDecision d = decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET);
            assertThat(d.allow()).isFalse();
            assertThat(d.reason()).isEqualTo("policy-store-invalid: " + blanked.getId());
        }

        @Test
        void aRowCreatedWithoutAKindIsJudgedAsDsl() {
            store(ALLOW_ALL);
            store("");
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isFalse();
        }

        @Test
        void legacyRuleRowsAreNotAffected() {
            store(ALLOW_ALL);
            storeRow(PolicyKind.LEGACY_RULE, null);
            storeRow(PolicyKind.LEGACY_RULE, "");
            storeRow(PolicyKind.LEGACY_RULE, "  ");
            AccessDecisionService pdp = service("deny");

            AccessDecision d = decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET);
            assertThat(d.allow()).isTrue();
            assertThat(d.policyIds()).containsExactly("allow-read");
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
            assertThat(gauge(AccessDecisionService.SCOPE_READABLE)).isZero();
            assertThat(warnings()).isEmpty();
        }

        @Test
        void aLegacyRuleRowThatHoldsADocumentIsStillJudgedByTheDocument() {
            store(ALLOW_ALL);
            storeRow(PolicyKind.LEGACY_RULE, BROKEN_DENY_PII);
            AccessDecisionService pdp = service("deny");

            assertThat(decide(pdp, "DATA_ANALYST", "read", PII_ASSET).reason())
                    .isEqualTo("policy-invalid: deny-pii-broken");
        }

        @Test
        void restoringTheDocumentLiftsTheDenyAtTheNextRefresh() {
            store(ALLOW_ALL);
            Policy blanked = storeRow(PolicyKind.DSL, "");
            AccessDecisionService pdp = service("deny");
            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isFalse();

            blanked.setPolicyDocument(ALLOW_ALL.replace("allow-read", "allow-read-2"));
            pdp.refresh();

            assertThat(decide(pdp, "DATA_ENGINEER", "read", PUBLIC_ASSET).allow()).isTrue();
            assertThat(gauge(AccessDecisionService.SCOPE_UNREADABLE)).isZero();
        }
    }
}
