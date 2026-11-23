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
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * Deterministic combining-algorithm coverage for the AuthZEN PDP (ADR-0040,
 * enforcement-contract.md §4): allow, deny-overrides-allow, default-deny,
 * obligation union, purpose/consent conditions, and subject tier-vs-role.
 *
 * <p>Uses the real {@link PolicyDslParser}/{@link PolicyDslEvaluator} and a
 * properties-only resolver so no catalog/network is touched.
 */
@ExtendWith(MockitoExtension.class)
class AccessDecisionServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator();

    /** Properties-only resolver - no catalog. */
    private final IAssetSnapshotResolver resolver = resource -> {
        Map<String, Object> snap = new LinkedHashMap<>();
        String id = resource != null ? resource.getId() : null;
        String uri = null;
        if (resource != null && resource.getProperties() != null) {
            if (resource.getProperties().get("labels") != null) {
                snap.put("labels", resource.getProperties().get("labels"));
            }
            Object u = resource.getProperties().get("assetUri");
            if (u != null) {
                uri = u.toString();
            }
        }
        return new IAssetSnapshotResolver.ResolvedResource(snap, id, uri);
    };

    private final List<Policy> enabled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        enabled.clear();
        when(policyRepository.findByStatus(PolicyStatus.ENABLED)).thenReturn(enabled);
    }

    private AccessDecisionService service(String defaultDecision) {
        return new AccessDecisionService(policyRepository, parser, evaluator, resolver,
                new SimpleMeterRegistry(), ObservationRegistry.create(), defaultDecision);
    }

    private void addPolicy(String yaml) {
        Policy p = new Policy();
        p.setId(UUID.randomUUID());
        p.setName("p-" + enabled.size());
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument(yaml);
        enabled.add(p);
    }

    private static String allowPolicy(String id, String subjectsBlock, String labels,
                                      String conditionBlock, String obligationsBlock) {
        return ""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: " + id + "}\n"
                + "spec:\n"
                + "  subjects: " + subjectsBlock + "\n"
                + "  actions: [read]\n"
                + "  match: {labels: {anyOf: [" + labels + "]}}\n"
                + (conditionBlock == null ? "" : "  condition:\n" + conditionBlock)
                + "  effect: allow\n"
                + (obligationsBlock == null ? "" : "  obligations:\n" + obligationsBlock);
    }

    private static AuthZenEvaluationRequest request(List<String> roles, String tier,
                                                    List<String> labels, Map<String, Object> context) {
        AuthZenEvaluationRequest req = new AuthZenEvaluationRequest();
        AuthZenEvaluationRequest.Subject s = new AuthZenEvaluationRequest.Subject();
        s.setType("agent");
        s.setId("svc-test");
        Map<String, Object> props = new LinkedHashMap<>();
        if (roles != null) {
            props.put("roles", roles);
        }
        if (tier != null) {
            props.put("tier", tier);
        }
        s.setProperties(props);
        req.setSubject(s);
        AuthZenEvaluationRequest.Action a = new AuthZenEvaluationRequest.Action();
        a.setName("read");
        req.setAction(a);
        AuthZenEvaluationRequest.Resource r = new AuthZenEvaluationRequest.Resource();
        r.setType("asset");
        r.setId(UUID.randomUUID().toString());
        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("labels", labels);
        r.setProperties(rp);
        req.setResource(r);
        req.setContext(context);
        return req;
    }

    @Test
    void allow_whenRoleActionLabelMatch() {
        addPolicy(allowPolicy("p-allow", "{roles: [DATA_ANALYST]}", "EU_RESIDENT", null, null));
        AccessDecision d = service("deny")
                .evaluate(request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), null))
                .decision();
        assertThat(d.allow()).isTrue();
        assertThat(d.policyIds()).containsExactly("p-allow");
        assertThat(d.defaultApplied()).isFalse();
    }

    @Test
    void denyOverridesAllow() {
        addPolicy(allowPolicy("p-allow", "{roles: [DATA_ANALYST]}", "EU_RESIDENT", null, null));
        addPolicy(""
                + "apiVersion: dlap/v1\n"
                + "kind: AccessPolicy\n"
                + "metadata: {id: p-deny}\n"
                + "spec:\n"
                + "  subjects: {roles: [DATA_ANALYST]}\n"
                + "  actions: [read]\n"
                + "  match: {labels: {anyOf: [EU_RESIDENT]}}\n"
                + "  effect: deny\n");
        AccessDecision d = service("allow")
                .evaluate(request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), null))
                .decision();
        assertThat(d.allow()).isFalse();
        assertThat(d.policyIds()).containsExactly("p-deny");
    }

    @Test
    void defaultDeny_whenNothingMatches() {
        AccessDecision d = service("deny")
                .evaluate(request(List.of("VIEWER"), null, List.of("PUBLIC"), null))
                .decision();
        assertThat(d.allow()).isFalse();
        assertThat(d.defaultApplied()).isTrue();
        assertThat(d.policyIds()).isEmpty();
    }

    @Test
    void defaultAllow_whenConfiguredAndNothingMatches() {
        AccessDecision d = service("allow")
                .evaluate(request(List.of("VIEWER"), null, List.of("PUBLIC"), null))
                .decision();
        assertThat(d.allow()).isTrue();
        assertThat(d.defaultApplied()).isTrue();
    }

    @Test
    void obligationsUnionAcrossContributingAllows() {
        addPolicy(allowPolicy("p-mask-pii", "{roles: [DATA_ANALYST]}", "EU_RESIDENT", null,
                "    - {type: mask, labels: [PII]}\n"));
        addPolicy(allowPolicy("p-mask-phi", "{roles: [DATA_ANALYST]}", "EU_RESIDENT", null,
                "    - {type: mask, labels: [PHI]}\n"));
        AccessDecision d = service("deny")
                .evaluate(request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), null))
                .decision();
        assertThat(d.allow()).isTrue();
        assertThat(d.policyIds()).containsExactlyInAnyOrder("p-mask-pii", "p-mask-phi");
        assertThat(d.obligations()).hasSize(2);
        assertThat(d.obligations()).extracting(AccessDecision.PolicyDslObligation::labels)
                .containsExactlyInAnyOrder(List.of("PII"), List.of("PHI"));
    }

    @Test
    void condition_purposeAndConsentMustHold() {
        String cond = "    purpose: {anyOf: [campaign-analytics]}\n    consent: true\n";
        addPolicy(allowPolicy("p-cond", "{roles: [DATA_ANALYST]}", "EU_RESIDENT", cond, null));
        AccessDecisionService svc = service("deny");

        // Matching purpose + consent -> allow.
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("purpose", "campaign-analytics");
        ok.put("consent", true);
        assertThat(svc.evaluate(request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), ok))
                .decision().allow()).isTrue();

        // Wrong purpose -> no match -> default deny.
        Map<String, Object> wrongPurpose = new LinkedHashMap<>();
        wrongPurpose.put("purpose", "ad-hoc");
        wrongPurpose.put("consent", true);
        assertThat(svc.evaluate(request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), wrongPurpose))
                .decision().allow()).isFalse();

        // Consent false -> no match -> default deny.
        Map<String, Object> noConsent = new LinkedHashMap<>();
        noConsent.put("purpose", "campaign-analytics");
        noConsent.put("consent", false);
        assertThat(svc.evaluate(request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), noConsent))
                .decision().allow()).isFalse();
    }

    @Test
    void subjectMatch_byTierWhenRoleImpliesTier() {
        // Policy targets the PRACTITIONER tier; subject carries DATA_ENGINEER
        // (a PRACTITIONER-tier role per ADR-0032) and no explicit tier property.
        addPolicy(allowPolicy("p-tier", "{tiers: [PRACTITIONER]}", "EU_RESIDENT", null, null));
        AccessDecision d = service("deny")
                .evaluate(request(List.of("DATA_ENGINEER"), null, List.of("EU_RESIDENT"), null))
                .decision();
        assertThat(d.allow()).isTrue();
        assertThat(d.policyIds()).containsExactly("p-tier");
    }

    @Test
    void subjectMatch_tierPolicyDoesNotMatchWrongTierRole() {
        addPolicy(allowPolicy("p-tier", "{tiers: [PRACTITIONER]}", "EU_RESIDENT", null, null));
        // VIEWER is a READER-tier role - must not match a PRACTITIONER policy.
        AccessDecision d = service("deny")
                .evaluate(request(List.of("VIEWER"), null, List.of("EU_RESIDENT"), null))
                .decision();
        assertThat(d.allow()).isFalse();
        assertThat(d.defaultApplied()).isTrue();
    }

    @Test
    void subjectMatch_byExplicitTierProperty() {
        addPolicy(allowPolicy("p-tier", "{tiers: [EXECUTIVE]}", "EU_RESIDENT", null, null));
        AccessDecision d = service("deny")
                .evaluate(request(null, "EXECUTIVE", List.of("EU_RESIDENT"), null))
                .decision();
        assertThat(d.allow()).isTrue();
    }

    @Test
    void actionMismatch_yieldsDefault() {
        addPolicy(allowPolicy("p-allow", "{roles: [DATA_ANALYST]}", "EU_RESIDENT", null, null));
        AuthZenEvaluationRequest req =
                request(List.of("DATA_ANALYST"), null, List.of("EU_RESIDENT"), null);
        req.getAction().setName("delete"); // policy only names 'read'
        AccessDecision d = service("deny").evaluate(req).decision();
        assertThat(d.allow()).isFalse();
        assertThat(d.defaultApplied()).isTrue();
    }
}
