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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogPageDTO;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-02, decision D42 on Q-475: for a destructive decision, a readCoverage FULL observed longer ago
 * than {@code dlap.policy.coverage.max-age} (default P2D) counts as UNKNOWN; a null
 * readCoverageObservedAt keeps the v1.2 behaviour with one WARN per asset per run; the activity
 * row's field is read by name and defensively.
 */
class StaleFullCoverageIsUnknownTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final String OLD_READ = "2026-01-01T00:00:00Z";

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PolicyDslEvaluator.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private PolicyDsl policy(String action) {
        return parser.parse("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: s}\nspec:\n"
                + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
                + "  match: {lastAccessedAt: {olderThan: P90D}}\n  action: {type: " + action + "}\n");
    }

    private static Map<String, Object> fullObservedAt(String assetId, String observedAt) {
        Map<String, Object> s = new HashMap<>();
        s.put("assetId", assetId);
        s.put("lastAccessedAt", OLD_READ);
        s.put("readCoverage", "FULL");
        if (observedAt != null) {
            s.put("readCoverageObservedAt", observedAt);
        }
        return s;
    }

    private long coverageWarnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("readCoverageObservedAt")).count();
    }

    @ParameterizedTest(name = "FULL observed at {0} is {1} for archive")
    @CsvSource({
            "2026-11-01T00:00:00Z, TRUE",      // 1 day old
            "2026-10-31T00:00:00Z, TRUE",      // exactly the 2-day bound
            "2026-10-30T23:59:59Z, UNKNOWN",   // just past the bound
            "2026-10-01T00:00:00Z, UNKNOWN",   // a month old
            "2026-11-03T00:00:00Z, TRUE",      // collector clock ahead of ours
            "not-a-time,           UNKNOWN"    // unreadable: not proof of freshness
    })
    void aFullOlderThanTheMaxAgeIsUnknownForADestructiveDecision(String observedAt, MatchOutcome expected) {
        PolicyDsl archive = policy("archive");

        MatchOutcome outcome = evaluator.evaluate(archive, fullObservedAt("a1", observedAt),
                evaluator.options(NOW, archive, new HashSet<>())).outcome();

        assertThat(outcome).isEqualTo(expected);
    }

    @Test
    void retentionShorteningAndAccessPoliciesUseTheSameBound() {
        PolicyDsl retention = policy("setRetention");
        Map<String, Object> stale = fullObservedAt("a1", "2026-10-01T00:00:00Z");

        assertThat(evaluator.evaluate(retention, stale, evaluator.optionsFor(retention)).outcome())
                .isEqualTo(MatchOutcome.UNKNOWN);
        PolicyDsl deny = parser.parse("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: d}\nspec:\n"
                + "  subjects: {roles: [X]}\n  actions: [read]\n  match: {lastAccessedAt: {olderThan: P90D}}\n"
                + "  effect: deny\n");
        assertThat(evaluator.evaluate(deny, stale, evaluator.optionsFor(deny)).outcome())
                .isEqualTo(MatchOutcome.UNKNOWN);
    }

    @Test
    void aNonDestructiveDecisionIgnoresTheAgeOfTheCoverage() {
        PolicyDsl label = policy("addLabel");

        assertThat(evaluator.evaluate(label, fullObservedAt("a1", "2026-01-02T00:00:00Z"),
                evaluator.optionsFor(label)).outcome()).isEqualTo(MatchOutcome.TRUE);
    }

    @Test
    void theMaxAgeIsConfigurable() {
        PolicyDslEvaluator hourly = new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC), "PT1H");
        PolicyDsl archive = policy("archive");

        assertThat(hourly.coverageMaxAge()).isEqualTo(Duration.ofHours(1));
        assertThat(hourly.evaluate(archive, fullObservedAt("a1", "2026-11-01T22:00:00Z"),
                hourly.optionsFor(archive)).outcome()).isEqualTo(MatchOutcome.UNKNOWN);
        assertThat(evaluator.coverageMaxAge()).isEqualTo(Duration.ofDays(2));
        assertThatThrownBy(() -> new PolicyDslEvaluator(Clock.systemUTC(), Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFullWithoutAnObservationTimeKeepsTodaysBehaviourWithOneWarnPerAssetPerRun() {
        PolicyDsl archive = policy("archive");
        PolicyDsl delete = policy("delete");
        Set<String> run = new HashSet<>();

        assertThat(evaluator.evaluate(archive, fullObservedAt("a1", null), evaluator.options(NOW, archive, run))
                .outcome()).isEqualTo(MatchOutcome.TRUE);
        evaluator.evaluate(delete, fullObservedAt("a1", null), evaluator.options(NOW, delete, run));
        evaluator.evaluate(archive, fullObservedAt("a2", null), evaluator.options(NOW, archive, run));
        assertThat(coverageWarnings()).isEqualTo(2);

        Set<String> nextRun = new HashSet<>();
        evaluator.evaluate(archive, fullObservedAt("a1", null), evaluator.options(NOW, archive, nextRun));
        assertThat(coverageWarnings()).isEqualTo(3);
    }

    @Test
    void thePdpPathDoesNotWarnPerRequest() {
        PolicyDsl archive = policy("archive");

        evaluator.evaluate(archive, fullObservedAt("a1", null), evaluator.optionsFor(archive));
        evaluator.evaluate(archive, fullObservedAt("a1", null), evaluator.optionsFor(archive));

        assertThat(coverageWarnings()).isZero();
    }

    @Nested
    class ActivityRow {

        private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

        private CatalogActivityRowDTO read(String extra) throws Exception {
            return json.readValue("{\"assetId\":\"1f2d3a4b-0000-4000-8000-000000000075\",\"assetUri\":\"gs://b\","
                    + "\"readCoverage\":\"FULL\",\"lastAccessedAt\":\"" + OLD_READ + "\",\"labels\":[]" + extra + "}",
                    CatalogActivityRowDTO.class);
        }

        @Test
        void theFieldIsReadByName() throws Exception {
            CatalogActivityRowDTO row = read(",\"readCoverageObservedAt\":\"2026-11-01T06:00:00Z\"");

            assertThat(row.readCoverageObservedAt()).isEqualTo("2026-11-01T06:00:00Z");
            assertThat(AssetSnapshots.fromActivityRow(row))
                    .containsEntry(AssetSnapshots.READ_COVERAGE_OBSERVED_AT, "2026-11-01T06:00:00Z");
        }

        @Test
        void aRowWithoutTheFieldStillReads() throws Exception {
            CatalogActivityRowDTO row = read("");

            assertThat(row.readCoverageObservedAt()).isNull();
            assertThat(AssetSnapshots.fromActivityRow(row)).doesNotContainKey(AssetSnapshots.READ_COVERAGE_OBSERVED_AT);
        }

        @Test
        void anUnexpectedValueNeverFailsThePage() throws Exception {
            assertThat(read(",\"readCoverageObservedAt\":null").readCoverageObservedAt()).isNull();
            assertThat(read(",\"readCoverageObservedAt\":\"  \"").readCoverageObservedAt()).isBlank();
            CatalogActivityRowDTO garbage = read(",\"readCoverageObservedAt\":\"yesterday\"");
            assertThat(AssetSnapshots.fromActivityRow(garbage))
                    .containsEntry(AssetSnapshots.READ_COVERAGE_OBSERVED_AT, "yesterday");
            assertThat(AssetSnapshots.fromActivityRow(read(",\"readCoverageObservedAt\":\"  \"")))
                    .doesNotContainKey(AssetSnapshots.READ_COVERAGE_OBSERVED_AT);
        }
    }

    @Nested
    class Sweep {

        private final PolicyRepository policies = mock(PolicyRepository.class);
        private final PolicyScheduleStore store = mock(PolicyScheduleStore.class);
        private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
        private final ApprovalGate gate = mock(ApprovalGate.class);
        private final Instant slotNow = Instant.parse("2026-11-02T03:00:00Z");
        private final Clock clock = Clock.fixed(slotNow, ZoneOffset.UTC);

        private CatalogActivityRowDTO row(String uri, String observedAt) {
            return new CatalogActivityRowDTO(UUID.nameUUIDFromBytes(uri.getBytes()), uri, uri, "GCP", "GCS_BUCKET",
                    "GCS_BUCKET", "us", "ACTIVE", null, 1L, Instant.parse(OLD_READ), "FULL", null, null, null, false,
                    List.of(), slotNow, observedAt);
        }

        @Test
        void aSweepActsOnlyOnFreshFullCoverageAndWarnsOncePerUnstampedAsset() {
            Policy p = new Policy();
            p.setId(UUID.randomUUID());
            p.setName("p");
            p.setStatus(PolicyStatus.ENABLED);
            p.setPolicyDocument("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: s}\nspec:\n"
                    + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
                    + "  match: {lastAccessedAt: {olderThan: P90D}}\n  action: {type: archive}\n");
            Policy twin = new Policy();
            twin.setId(UUID.randomUUID());
            twin.setName("twin");
            twin.setStatus(PolicyStatus.ENABLED);
            twin.setPolicyDocument(p.getPolicyDocument().replace("id: s", "id: twin"));
            when(policies.findByStatus(PolicyStatus.ENABLED)).thenReturn(List.of(p, twin));
            when(store.find(any())).thenAnswer(inv -> Optional.of(new PolicyScheduleStore.State(inv.getArgument(0),
                    Instant.parse("2026-11-02T01:00:00Z"), Instant.parse("2026-11-02T01:00:00Z"))));
            when(catalog.getAssetActivity(anyInt(), anyInt(), anyString(), anyList(), any()))
                    .thenReturn(Optional.of(new CatalogPageDTO<>(List.of(
                            row("gs://fresh", "2026-11-01T12:00:00Z"),
                            row("gs://stale", "2026-10-20T00:00:00Z"),
                            row("gs://unstamped", null)), 0, 1, true, null)));
            when(gate.submitTracked(any())).thenReturn(new ApprovalGate.Submission(ApprovalGate.Route.HELD,
                    CompletableFuture.completedFuture(true)));
            ScheduledPolicySweeper sweeper = new ScheduledPolicySweeper(policies, parser,
                    new PolicyDslEvaluator(clock), store, catalog, gate,
                    new PolicyScheduleProperties(true, "UTC", 200, 100_000, List.of("ACTIVE"), null), clock,
                    new SimpleMeterRegistry());

            ScheduledPolicySweeper.SweepReport report = sweeper.runDue();

            // Two policies each act on fresh and unstamped; stale is unknown for both.
            verify(gate, times(4)).submitTracked(any());
            assertThat(report.fires()).allSatisfy(f -> {
                assertThat(f.counts().matched()).isEqualTo(2);
                assertThat(f.counts().unknown()).isEqualTo(1);
            });
            assertThat(coverageWarnings()).isEqualTo(1);
            verify(store, never()).insertBaseline(any(), any(), any());
        }
    }
}
