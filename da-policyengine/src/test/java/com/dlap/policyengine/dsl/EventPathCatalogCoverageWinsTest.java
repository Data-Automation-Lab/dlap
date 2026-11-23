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

package com.dlap.policyengine.dsl;

import static com.dlap.policyengine.dsl.CatalogLookups.uncached;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-02, decision D42 on Q-471: on the event path, a policy that requires full read coverage is
 * judged on the catalog's readCoverage, never the event's, and on the later of the event's and the
 * catalog's lastAccessedAt. Other policies keep the event's values (v1.2).
 */
class EventPathCatalogCoverageWinsTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000071");
    private static final String TOPIC = DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED;
    private static final String OLD_READ = "2026-01-01T00:00:00Z";
    private static final String RECENT_READ = "2026-10-15T00:00:00Z";

    private static final String ARCHIVE_STALE = "apiVersion: dlap/v1\nkind: Policy\n"
            + "metadata: {id: archive-stale, name: Archive stale}\n"
            + "spec:\n  triggers: [{type: event, lifecycle: accessed}]\n"
            + "  match: {lastAccessedAt: {olderThan: P90D}}\n  action: {type: archive}\n";
    private static final String LABEL_STALE = ARCHIVE_STALE.replace("archive-stale", "label-stale")
            .replace("{type: archive}", "{type: addLabel, parameters: {labelName: stale}}");

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final ApprovalGate gate = mock(ApprovalGate.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private LifecyclePolicyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new LifecyclePolicyConsumer(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock),
                gate, new ObjectMapper(), uncached(catalog, clock), clock, new SimpleMeterRegistry());
        when(gate.submit(any())).thenReturn(ApprovalGate.Route.HELD);
    }

    private void givenPolicies(String... yamls) {
        List<Policy> list = new java.util.ArrayList<>();
        for (String y : yamls) {
            Policy p = new Policy();
            p.setId(UUID.randomUUID());
            p.setName("p");
            p.setStatus(PolicyStatus.ENABLED);
            p.setPolicyDocument(y);
            list.add(p);
        }
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(list);
    }

    private void givenCatalog(String lastRead, String coverage) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setAssetUri("gs://b");
        a.setLabels(List.of());
        a.setLastAccessedAt(lastRead != null ? Instant.parse(lastRead) : null);
        a.setReadCoverage(coverage);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(a));
    }

    private static Map<String, Object> event(String lastRead, String coverage) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("assetUri", "gs://b");
        snapshot.put("lastAccessedAt", lastRead);
        if (coverage != null) {
            snapshot.put("readCoverage", coverage);
        }
        return Map.of("eventId", "e-1", "assetId", ASSET.toString(), "matchSnapshot", snapshot);
    }

    private List<PolicyActionEvent> fired() {
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, org.mockito.Mockito.atLeast(0)).submit(sent.capture());
        return sent.getAllValues();
    }

    @Test
    void anEventClaimOfFullCoverageDoesNotOverrideTheCatalogsPartial() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalog(OLD_READ, "PARTIAL");

        assertThat(consumer.evaluate(event(OLD_READ, "FULL"), TOPIC)).isZero();
        verify(gate, never()).submit(any());
    }

    @Test
    void theLaterOfTheTwoRecordedReadsIsJudged() {
        // The event carries an old read; the catalog has since recorded a recent one.
        givenPolicies(ARCHIVE_STALE);
        givenCatalog(RECENT_READ, "FULL");

        assertThat(consumer.evaluate(event(OLD_READ, "FULL"), TOPIC)).isZero();
        verify(gate, never()).submit(any());
    }

    @Test
    void theEventsLaterReadWinsOverAnOlderCatalogRead() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalog(OLD_READ, "FULL");

        assertThat(consumer.evaluate(event(RECENT_READ, null), TOPIC)).isZero();
    }

    @Test
    void theCatalogsFullCoverageAndAnOldReadOnBothSidesFire() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalog(OLD_READ, "FULL");

        assertThat(consumer.evaluate(event(OLD_READ, "PARTIAL"), TOPIC)).isEqualTo(1);
        PolicyActionEvent fire = fired().get(0);
        assertThat(fire.matchSnapshot()).containsEntry("readCoverage", "FULL")
                .containsEntry("lastAccessedAt", OLD_READ);
    }

    @Test
    void aCatalogThatDoesNotAnswerLeavesCoverageUnknownForADestructivePolicy() {
        givenPolicies(ARCHIVE_STALE);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.empty());

        assertThat(consumer.evaluate(event(OLD_READ, "FULL"), TOPIC)).isZero();
        verify(gate, never()).submit(any());
    }

    @Test
    void aPolicyThatDoesNotNeedFullCoverageKeepsTheEventsValues() {
        givenPolicies(LABEL_STALE);
        givenCatalog(RECENT_READ, "PARTIAL");

        assertThat(consumer.evaluate(event(OLD_READ, "FULL"), TOPIC)).isEqualTo(1);
        assertThat(fired().get(0).matchSnapshot()).containsEntry("lastAccessedAt", OLD_READ)
                .containsEntry("readCoverage", "FULL");
    }

    @Test
    void withCatalogCoverageDoesNotChangeItsInputs() {
        Map<String, Object> eventSnapshot = new HashMap<>(Map.of("readCoverage", "FULL", "lastAccessedAt", OLD_READ,
                "readCoverageObservedAt", "2026-10-20T00:00:00Z"));
        Map<String, Object> catalogView = Map.of("lastAccessedAt", RECENT_READ);

        Map<String, Object> judged = AssetSnapshots.withCatalogCoverage(eventSnapshot, catalogView);

        assertThat(judged).doesNotContainKeys("readCoverage", "readCoverageObservedAt")
                .containsEntry("lastAccessedAt", RECENT_READ);
        assertThat(eventSnapshot).containsEntry("readCoverage", "FULL").containsEntry("lastAccessedAt", OLD_READ);
        assertThat(AssetSnapshots.withCatalogCoverage(eventSnapshot, null))
                .containsEntry("lastAccessedAt", OLD_READ).doesNotContainKey("readCoverage");
        assertThat(AssetSnapshots.withCatalogCoverage(Map.of("lastAccessedAt", "garbage"), catalogView))
                .containsEntry("lastAccessedAt", RECENT_READ);
    }
}
