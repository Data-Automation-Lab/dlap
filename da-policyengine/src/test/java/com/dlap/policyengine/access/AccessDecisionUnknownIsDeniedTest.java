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

import org.junit.jupiter.api.BeforeEach;
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
 * POL-01 through the PDP: the catalog snapshot carries the lifecycle fields, and a deny-if-stale
 * AccessPolicy denies data whose age is unknown (plan section 2, fact 5), in both default modes.
 */
class AccessDecisionUnknownIsDeniedTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final UUID ASSET = UUID.fromString("9f6d0000-0000-4000-8000-000000000002");

    private static final String ALLOW_EU = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-eu}\n"
            + "spec:\n  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
            + "  match: {labels: {anyOf: [EU_RESIDENT]}}\n  effect: allow\n";
    private static final String DENY_STALE = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: deny-stale}\n"
            + "spec:\n  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
            + "  match: {lastAccessedAt: {olderThan: P365D}}\n  effect: deny\n";

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final List<Policy> enabled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        for (String yaml : List.of(ALLOW_EU, DENY_STALE)) {
            Policy p = new Policy();
            p.setId(UUID.randomUUID());
            p.setName("p-" + enabled.size());
            p.setStatus(PolicyStatus.ENABLED);
            p.setPolicyDocument(yaml);
            enabled.add(p);
        }
        when(repository.findByStatus(PolicyStatus.ENABLED)).thenReturn(enabled);
    }

    private AccessDecision decide(String defaultDecision, Instant lastRead, String coverage) {
        CatalogAssetDTO asset = new CatalogAssetDTO();
        asset.setAssetId(ASSET);
        asset.setLabels(List.of("EU_RESIDENT"));
        asset.setLastAccessedAt(lastRead);
        asset.setReadCoverage(coverage);
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

    @Test
    void anAssetWithNoRecordedReadIsDeniedByTheStalenessRule() {
        AccessDecision d = decide("deny", null, null);
        assertThat(d.allow()).isFalse();
        assertThat(d.policyIds()).containsExactly("deny-stale");
    }

    @Test
    void theDenyHoldsInObserveModeToo() {
        assertThat(decide("allow", null, null).allow()).isFalse();
    }

    @Test
    void anOldReadUnderPartialCoverageIsDenied() {
        assertThat(decide("deny", Instant.parse("2025-01-01T00:00:00Z"), "PARTIAL").allow()).isFalse();
    }

    @Test
    void aRecentReadIsDecidedAndTheAllowApplies() {
        AccessDecision d = decide("deny", Instant.parse("2026-10-01T00:00:00Z"), null);
        assertThat(d.allow()).isTrue();
        assertThat(d.policyIds()).containsExactly("allow-eu");
    }
}
