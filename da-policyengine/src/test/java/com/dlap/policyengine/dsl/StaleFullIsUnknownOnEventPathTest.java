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
import static org.mockito.Mockito.atLeast;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-03, decision D43 on Q-837: the P2D coverage freshness rule applies on the event path. The
 * catalog's single-asset read now carries readCoverageObservedAt, so a stale FULL counts as UNKNOWN
 * for a destructive policy there, as on the sweep; a missing time keeps the v1.2 behaviour with a WARN.
 */
class StaleFullIsUnknownOnEventPathTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000301");
    private static final String TOPIC = DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED;
    private static final String OLD_READ = "2026-01-01T00:00:00Z";
    private static final String FRESH = "2026-11-01T12:00:00Z";
    private static final String STALE = "2026-10-25T00:00:00Z";

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
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PolicyDslEvaluator.class);
    private LifecyclePolicyConsumer consumer;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        consumer = new LifecyclePolicyConsumer(policies, new PolicyDslParser(), new PolicyDslEvaluator(clock),
                gate, new ObjectMapper(), uncached(catalog, clock), clock, new SimpleMeterRegistry());
        when(gate.submit(any())).thenReturn(ApprovalGate.Route.HELD);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
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

    private void givenCatalogFull(String observedAt) {
        CatalogAssetDTO a = new CatalogAssetDTO();
        a.setAssetId(ASSET);
        a.setAssetUri("gs://b");
        a.setLabels(List.of());
        a.setLastAccessedAt(Instant.parse(OLD_READ));
        a.setReadCoverage("FULL");
        a.setReadCoverageObservedAt(observedAt);
        when(catalog.getAssetById(ASSET)).thenReturn(Optional.of(a));
    }

    private static Map<String, Object> event() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("assetUri", "gs://b");
        snapshot.put("lastAccessedAt", OLD_READ);
        // The event claims fresh coverage; for a destructive policy only the catalog's counts.
        snapshot.put("readCoverage", "FULL");
        snapshot.put("readCoverageObservedAt", FRESH);
        return Map.of("eventId", "e-1", "assetId", ASSET.toString(), "matchSnapshot", snapshot);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aStaleFullFromTheCatalogIsUnknownForADestructivePolicyOnTheEventPath() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalogFull(STALE);

        assertThat(consumer.evaluate(event(), TOPIC)).isZero();
        verify(gate, never()).submit(any());
    }

    @Test
    void aFreshFullFromTheCatalogStillFires() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalogFull(FRESH);

        assertThat(consumer.evaluate(event(), TOPIC)).isEqualTo(1);
        ArgumentCaptor<PolicyActionEvent> sent = ArgumentCaptor.forClass(PolicyActionEvent.class);
        verify(gate, atLeast(1)).submit(sent.capture());
        assertThat(sent.getValue().matchSnapshot()).containsEntry("readCoverageObservedAt", FRESH)
                .containsEntry("readCoverage", "FULL");
    }

    @Test
    void anUnparseableObservationTimeFromTheCatalogCountsAsStale() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalogFull("yesterday");

        assertThat(consumer.evaluate(event(), TOPIC)).isZero();
    }

    @Test
    void aMissingObservationTimeKeepsTheV12BehaviourWithAWarn() {
        givenPolicies(ARCHIVE_STALE);
        givenCatalogFull(null);

        assertThat(consumer.evaluate(event(), TOPIC)).isEqualTo(1);
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(ASSET.toString())
                .contains("without readCoverageObservedAt"));
    }

    @Test
    void aPolicyOutsideTheRuleIgnoresFreshness() {
        givenPolicies(LABEL_STALE);
        givenCatalogFull(STALE);

        assertThat(consumer.evaluate(event(), TOPIC)).isEqualTo(1);
    }
}
