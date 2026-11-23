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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.dlap.policyengine.client.dto.CatalogActivityRowDTO;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;

/**
 * POL-03 (closes the engine half of Q-434): under FULL coverage that counts, readCoverageSince is
 * the start since which every read is recorded. With no recorded read since the start, the start
 * bounds the true last read from above, so an asset never read since coverage began long enough
 * ago is TRUE for {@code olderThan} and FALSE for {@code youngerThan}.
 */
class ReadCoverageSinceProvesNeverReadTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final String FRESH = "2026-11-01T12:00:00Z";
    private static final String STALE = "2026-10-20T00:00:00Z";
    /** 120 days before NOW. */
    private static final String LONG_AGO = "2026-07-05T00:00:00Z";
    /** 10 days before NOW. */
    private static final String RECENT = "2026-10-23T00:00:00Z";

    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));
    private final PolicyDslParser parser = new PolicyDslParser();

    private PolicyDsl lifecycle(String action, String clause) {
        return parser.parse("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: p}\nspec:\n"
                + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n  match: {lastAccessedAt: " + clause + "}\n"
                + "  action: {type: " + action + "}\n");
    }

    private static Map<String, Object> asset(String coverage, String observedAt, String since, String lastRead) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put(AssetSnapshots.ASSET_ID, "a-1");
        put(s, AssetSnapshots.READ_COVERAGE, coverage);
        put(s, AssetSnapshots.READ_COVERAGE_OBSERVED_AT, observedAt);
        put(s, AssetSnapshots.READ_COVERAGE_SINCE, since);
        put(s, AssetSnapshots.LAST_ACCESSED_AT, lastRead);
        return s;
    }

    private static void put(Map<String, Object> s, String k, Object v) {
        if (v != null) {
            s.put(k, v);
        }
    }

    private MatchOutcome outcome(PolicyDsl policy, Map<String, Object> asset) {
        return evaluator.evaluate(policy, asset, evaluator.options(NOW, policy, new HashSet<>())).outcome();
    }

    @Nested
    class UnderTheFullCoverageRule {

        @Test
        void readCoverageSinceProvesNeverRead() {
            PolicyDsl archive = lifecycle("archive", "{olderThan: P90D}");

            assertThat(outcome(archive, asset("FULL", FRESH, LONG_AGO, null))).isEqualTo(MatchOutcome.TRUE);
            // Without the start the engine cannot tell never-read from unknown (v1.3).
            assertThat(outcome(archive, asset("FULL", FRESH, null, null))).isEqualTo(MatchOutcome.UNKNOWN);
        }

        /** Now is 2026-11-02; every recorded read here lies before the start, so it is a lower bound. */
        @ParameterizedTest(name = "{0}, start {1}, last read {2}: {3}")
        @CsvSource({
            "'{olderThan: P90D}',   2026-07-05T00:00:00Z, ,                     TRUE",
            "'{olderThan: P90D}',   2026-10-23T00:00:00Z, ,                     UNKNOWN",
            "'{olderThan: P90D}',   2026-10-23T00:00:00Z, 2026-07-05T00:00:00Z, UNKNOWN",
            "'{olderThan: P30D}',   2026-10-23T00:00:00Z, 2026-10-20T00:00:00Z, FALSE",
            "'{olderThan: P5D}',    2026-10-23T00:00:00Z, 2026-10-20T00:00:00Z, TRUE",
            "'{youngerThan: P90D}', 2026-07-05T00:00:00Z, ,                     FALSE",
            "'{youngerThan: P90D}', 2026-10-23T00:00:00Z, ,                     UNKNOWN",
            "'{youngerThan: P30D}', 2026-10-23T00:00:00Z, 2026-10-20T00:00:00Z, TRUE",
            "'{olderThan: P90D, youngerThan: P200D}', 2026-07-05T00:00:00Z, ,   UNKNOWN",
            "'{olderThan: P90D}',   2026-08-04T00:00:00Z, ,                     TRUE",
            "'{olderThan: P90D}',   2026-08-04T00:00:01Z, ,                     UNKNOWN",
        })
        void theStartBoundsAnUnrecordedLastRead(String clause, String start, String lastRead, MatchOutcome expected) {
            assertThat(outcome(lifecycle("delete", clause), asset("FULL", FRESH, start, lastRead))).isEqualTo(expected);
        }

        @Test
        void aRecordedReadBeforeTheStartIsOnlyALowerBound() {
            // v1.3 took the 120-day-old read at face value and archived; a read between it and the
            // start 10 days ago would not have been recorded, so now it is UNKNOWN.
            PolicyDsl archive = lifecycle("archive", "{olderThan: P90D}");

            assertThat(outcome(archive, asset("FULL", FRESH, RECENT, LONG_AGO))).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(archive, asset("FULL", FRESH, null, LONG_AGO))).isEqualTo(MatchOutcome.TRUE);
        }

        @Test
        void aRecordedReadAtOrAfterTheStartIsExact() {
            PolicyDsl archive = lifecycle("archive", "{olderThan: P90D}");

            assertThat(outcome(archive, asset("FULL", FRESH, LONG_AGO, LONG_AGO))).isEqualTo(MatchOutcome.TRUE);
            assertThat(outcome(archive, asset("FULL", FRESH, LONG_AGO, RECENT))).isEqualTo(MatchOutcome.FALSE);
        }

        @Test
        void theStartIsIgnoredWhenTheFullDoesNotCount() {
            PolicyDsl archive = lifecycle("archive", "{olderThan: P90D}");

            assertThat(outcome(archive, asset("FULL", STALE, LONG_AGO, null))).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(archive, asset("PARTIAL", FRESH, LONG_AGO, null))).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(archive, asset(null, FRESH, LONG_AGO, null))).isEqualTo(MatchOutcome.UNKNOWN);
        }

        @Test
        void anUnparseableStartCountsAsAbsent() {
            PolicyDsl archive = lifecycle("archive", "{olderThan: P90D}");

            assertThat(outcome(archive, asset("FULL", FRESH, "last spring", null))).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(archive, asset("FULL", FRESH, "last spring", LONG_AGO))).isEqualTo(MatchOutcome.TRUE);
        }
    }

    @Nested
    class OutsideTheRule {

        @Test
        void theStartIsUsedOnlyWhenNoReadIsRecorded() {
            PolicyDsl label = lifecycle("addLabel", "{olderThan: P90D}");

            // FULL at face value, even when stale: the rule does not apply to addLabel.
            assertThat(outcome(label, asset("FULL", STALE, LONG_AGO, null))).isEqualTo(MatchOutcome.TRUE);
            // A recorded read keeps its face value, even one before the start.
            assertThat(outcome(label, asset("FULL", FRESH, RECENT, LONG_AGO))).isEqualTo(MatchOutcome.TRUE);
            assertThat(outcome(label, asset("PARTIAL", FRESH, LONG_AGO, null))).isEqualTo(MatchOutcome.UNKNOWN);
        }
    }

    @Nested
    class OnEveryPath {

        private CatalogAssetDTO catalogAsset(String since) {
            CatalogAssetDTO a = new CatalogAssetDTO();
            a.setAssetId(UUID.fromString("9f6d0000-0000-4000-8000-000000000401"));
            a.setLabels(List.of());
            a.setReadCoverage("FULL");
            a.setReadCoverageObservedAt(FRESH);
            a.setReadCoverageSince(since);
            return a;
        }

        @Test
        void theCatalogAssetCarriesTheStartToTheEventAndPdpPaths() {
            Map<String, Object> snapshot = AssetSnapshots.fromCatalogAsset(catalogAsset(LONG_AGO));

            assertThat(snapshot).containsEntry(AssetSnapshots.READ_COVERAGE_SINCE, LONG_AGO)
                    .containsEntry(AssetSnapshots.READ_COVERAGE_OBSERVED_AT, FRESH);
            assertThat(outcome(lifecycle("delete", "{olderThan: P90D}"), snapshot)).isEqualTo(MatchOutcome.TRUE);
            assertThat(AssetSnapshots.fromCatalogAsset(catalogAsset(" "))).doesNotContainKey(
                    AssetSnapshots.READ_COVERAGE_SINCE);
        }

        @Test
        void theActivityRowCarriesTheStartToTheSweep() {
            CatalogActivityRowDTO row = new CatalogActivityRowDTO(UUID.randomUUID(), "gs://b", "b", "GCP",
                    "GCS_BUCKET", "GCS_BUCKET", "eu", "ACTIVE", null, null, null, "FULL", null, null, null, false,
                    List.of(), NOW, FRESH, LONG_AGO);

            Map<String, Object> snapshot = AssetSnapshots.fromActivityRow(row);

            assertThat(snapshot).containsEntry(AssetSnapshots.READ_COVERAGE_SINCE, LONG_AGO);
            assertThat(outcome(lifecycle("archive", "{olderThan: P90D}"), snapshot)).isEqualTo(MatchOutcome.TRUE);
        }

        @Test
        void onTheEventPathTheCatalogsStartReplacesTheEvents() {
            Map<String, Object> event = asset("FULL", FRESH, LONG_AGO, null);
            Map<String, Object> catalogView = AssetSnapshots.fromCatalogAsset(catalogAsset(RECENT));

            Map<String, Object> judged = AssetSnapshots.withCatalogCoverage(event, catalogView);

            assertThat(judged).containsEntry(AssetSnapshots.READ_COVERAGE_SINCE, RECENT);
            assertThat(outcome(lifecycle("archive", "{olderThan: P90D}"), judged)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(AssetSnapshots.withCatalogCoverage(event, null))
                    .doesNotContainKey(AssetSnapshots.READ_COVERAGE_SINCE);
        }

        @Test
        void aDetailsKeyNeverStandsInForTheStart() {
            assertThat(AssetSnapshots.LIFECYCLE_KEYS).contains(AssetSnapshots.READ_COVERAGE_SINCE);
        }
    }
}
