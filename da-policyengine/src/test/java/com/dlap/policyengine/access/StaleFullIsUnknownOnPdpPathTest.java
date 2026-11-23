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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * POL-03, decision D43 on Q-837: the P2D coverage freshness rule applies in the PDP. The catalog's
 * single-asset read carries readCoverageObservedAt into the snapshot, so a stale FULL is UNKNOWN for
 * every AccessPolicy: an allow that needs it does not apply and a deny that needs it applies. A
 * missing time keeps the v1.2 behaviour with one WARN per asset per refresh.
 */
class StaleFullIsUnknownOnPdpPathTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID ASSET = UUID.fromString("9f6d0000-0000-4000-8000-000000000201");
    private static final Instant OLD_READ = Instant.parse("2026-01-01T00:00:00Z");
    private static final String FRESH = "2026-11-01T12:00:00Z";
    private static final String STALE = "2026-10-25T00:00:00Z";

    /** Readers may read data nobody has read for 90 days (needs fresh FULL to prove it). */
    private static final String ALLOW_COLD = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-cold}\n"
            + "spec:\n  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
            + "  match: {lastAccessedAt: {olderThan: P90D}}\n  effect: allow\n";
    /** Deny data read in the last 30 days (a recent read under stale coverage may be missing). */
    private static final String DENY_HOT = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: deny-hot}\n"
            + "spec:\n  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
            + "  match: {lastAccessedAt: {youngerThan: P30D}}\n  effect: deny\n";
    private static final String ALLOW_ALL = "apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: allow-all}\n"
            + "spec:\n  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n  effect: allow\n";

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PolicyDslEvaluator.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private AccessDecisionService pdp(List<String> yamls, String observedAt) {
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
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setLabels(List.of());
        a.setLastAccessedAt(OLD_READ);
        a.setReadCoverage("FULL");
        a.setReadCoverageObservedAt(observedAt);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(a));
        AccessDecisionService service = new AccessDecisionService(repository, new PolicyDslParser(),
                new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)), new CatalogAssetSnapshotResolver(catalog),
                new SimpleMeterRegistry(), ObservationRegistry.create(), "deny");
        service.refresh();
        return service;
    }

    private static AccessDecision read(AccessDecisionService service) {
        AuthZenEvaluationRequest req = new AuthZenEvaluationRequest();
        AuthZenEvaluationRequest.Subject s = new AuthZenEvaluationRequest.Subject();
        s.setType("user");
        s.setId("u-1");
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

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aStaleFullIsUnknownSoAnAllowThatNeedsItDoesNotApply() {
        AccessDecision fresh = read(pdp(List.of(ALLOW_COLD), FRESH));
        AccessDecision stale = read(pdp(List.of(ALLOW_COLD), STALE));

        assertThat(fresh.allow()).isTrue();
        assertThat(fresh.policyIds()).containsExactly("allow-cold");
        assertThat(stale.allow()).isFalse();
        assertThat(stale.defaultApplied()).isTrue();
    }

    @Test
    void aStaleFullIsUnknownSoADenyThatNeedsItApplies() {
        AccessDecision fresh = read(pdp(List.of(ALLOW_ALL, DENY_HOT), FRESH));
        AccessDecision stale = read(pdp(List.of(ALLOW_ALL, DENY_HOT), STALE));

        assertThat(fresh.allow()).isTrue();
        assertThat(stale.allow()).isFalse();
        assertThat(stale.policyIds()).containsExactly("deny-hot");
    }

    @Test
    void anUnparseableObservationTimeCountsAsStale() {
        assertThat(read(pdp(List.of(ALLOW_COLD), "not-a-time")).allow()).isFalse();
    }

    @Test
    void aMissingObservationTimeKeepsTheV12BehaviourWithOneWarnPerAssetPerRefresh() {
        AccessDecisionService service = pdp(List.of(ALLOW_COLD), null);

        assertThat(read(service).allow()).isTrue();
        assertThat(read(service).allow()).isTrue();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(ASSET.toString()).contains("without readCoverageObservedAt");

        service.refresh();
        read(service);
        assertThat(warnings()).hasSize(2);
    }

    @Test
    void theWarnSetIsBounded() {
        AccessDecisionService.BoundedKeySet set = new AccessDecisionService.BoundedKeySet(2);

        assertThat(set.add("a")).isTrue();
        assertThat(set.add("a")).isFalse();
        assertThat(set.add("b")).isTrue();
        assertThat(set.add("c")).isFalse();
        assertThat(set).containsExactlyInAnyOrder("a", "b").hasSize(2);
        assertThat(set.contains("c")).isFalse();
    }
}
