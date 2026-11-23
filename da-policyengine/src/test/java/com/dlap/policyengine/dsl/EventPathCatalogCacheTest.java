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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.EventPathCatalogLookup;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-05 (decision D45, Q-437) on the event path: a burst of events about one asset costs one pair of
 * catalog calls, the cache never serves a catalog answer older than its TTL, and an asset the catalog could
 * not identify is looked up again on the next event. Uses the real {@link LifecyclePolicyConsumer} and the
 * real {@link EventPathCatalogLookup}; only the catalog client is a mock.
 */
class EventPathCatalogCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final String URI = "gs://prod-customer-exports";
    private static final String TOPIC = DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED;

    private static final String ALERT_ON_PII_ACCESS = ""
            + "apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: alert-pii-access, name: Alert PII access}\n"
            + "spec:\n  triggers: [{type: event, lifecycle: accessed}]\n"
            + "  match: {cloudProvider: GCP, labels: {anyOf: [PII]}}\n"
            + "  action: {type: alert.webhook, dryRun: true}\n";

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final ApprovalGate gate = mock(ApprovalGate.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock();
    private LifecyclePolicyConsumer consumer;

    @BeforeEach
    void setUp() {
        EventPathCatalogLookup lookup = new EventPathCatalogLookup(catalog, clock, registry, TTL, 10_000);
        consumer = new LifecyclePolicyConsumer(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock), gate,
                new ObjectMapper(), lookup, clock, registry);
        Policy p = new Policy();
        p.setId(UUID.randomUUID());
        p.setName("p");
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument(ALERT_ON_PII_ACCESS);
        List<Policy> list = new ArrayList<>(List.of(p));
        when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(list);
        when(gate.submit(any())).thenReturn(ApprovalGate.Route.PUBLISHED);
    }

    private static CatalogAssetDTO catalogAsset(String... labels) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setAssetUri(URI);
        a.setCloudProvider("GCP");
        a.setServiceType("GCS_BUCKET");
        a.setAssetType("GCS_BUCKET");
        a.setLabels(List.of(labels));
        return a;
    }

    private static Map<String, Object> accessEvent(String eventId) {
        return Map.of(
                "eventId", eventId,
                "sourceProvider", "GCP",
                "targetResource", Map.of("kind", "gcs_bucket", "name", "prod-customer-exports", "canonicalUri", URI));
    }

    private double counter(String lookup, String result) {
        return registry.get(EventPathCatalogLookup.COUNTER).tag("lookup", lookup).tag("result", result).counter()
                .count();
    }

    @Test
    void aBurstOfEventsAboutOneAssetCostsOnePairOfCatalogCalls() {
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset("PII")));

        for (int i = 0; i < 50; i++) {
            assertThat(consumer.evaluate(accessEvent("e" + i), TOPIC)).isEqualTo(1);
            clock.advance(Duration.ofMillis(100));
        }

        verify(catalog, times(1)).resolveAssetIdByUri(URI);
        verify(catalog, times(1)).getAssetById(ASSET);
        verify(gate, times(50)).submit(any());
        assertThat(counter("uri", "miss")).isEqualTo(1.0);
        assertThat(counter("uri", "hit")).isEqualTo(49.0);
        assertThat(counter("asset", "miss")).isEqualTo(1.0);
        assertThat(counter("asset", "hit")).isEqualTo(49.0);
    }

    @Test
    void theCatalogIsAskedAgainOnceTheAnswerIsAsOldAsTheTtlAndTheNewAnswerDecides() {
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset("PII")),
                Optional.of(catalogAsset("PUBLIC")));

        assertThat(consumer.evaluate(accessEvent("e1"), TOPIC)).isEqualTo(1);
        clock.advance(TTL.minusMillis(1));
        assertThat(consumer.evaluate(accessEvent("e2"), TOPIC)).isEqualTo(1);
        verify(catalog, times(1)).getAssetById(ASSET);

        clock.advance(Duration.ofMillis(1));
        // The label was removed in the catalog: the answer served now is the new one, not the cached one.
        assertThat(consumer.evaluate(accessEvent("e3"), TOPIC)).isZero();

        verify(catalog, times(2)).getAssetById(ASSET);
        verify(catalog, times(2)).resolveAssetIdByUri(URI);
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, times(2)).submit(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(e -> assertThat(e.assetId()).isEqualTo(ASSET.toString()));
    }

    @Test
    void anAssetTheCatalogCouldNotIdentifyIsLookedUpAgainOnTheNextEvent() {
        when(catalog.resolveAssetIdByUri(URI)).thenReturn(Optional.empty(), Optional.of(ASSET));
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(catalogAsset("PII")));

        assertThat(consumer.evaluate(accessEvent("e1"), TOPIC)).isZero();
        verify(gate, never()).submit(any());

        clock.advance(Duration.ofSeconds(1));
        assertThat(consumer.evaluate(accessEvent("e2"), TOPIC)).isEqualTo(1);

        verify(catalog, times(2)).resolveAssetIdByUri(URI);
        verify(gate, times(1)).submit(any());
    }

    @Test
    void anEventThatNoPolicyListensToMakesNoCatalogCallAndTouchesNoCache() {
        consumer.evaluate(accessEvent("e1"), DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED);

        verify(catalog, never()).resolveAssetIdByUri(any());
        verify(catalog, never()).getAssetById(any());
    }
}
