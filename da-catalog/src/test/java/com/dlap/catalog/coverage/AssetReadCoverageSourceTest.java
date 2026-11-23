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

package com.dlap.catalog.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.retention.ReadCoverage;
import com.dlap.catalog.retention.ReadCoverageSource.View;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys;
import com.dlap.catalog.retention.RetentionCalculator;
import com.dlap.catalog.retention.RetentionEvaluation;
import com.dlap.catalog.retention.RetentionEvidence;
import com.dlap.catalog.retention.RetentionRule;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * COV-01 with the retention evaluator (critique correction 2): reads are captured for an asset only
 * when its own coverage is {@code FULL} and its binding captures them, and the proof ends at the
 * last observation.
 */
class AssetReadCoverageSourceTest {

    private static final Instant BINDING_SINCE = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant FULL_SINCE = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-10-19T06:41:00Z");

    private static AssetRow row(UUID id) {
        return new AssetRow(id, "gs://dlap-demo-curated", "GCP", "GCS_BUCKET", "ACTIVE", null, null, null, null, false,
                new TechnicalKeys(null, null, "dlap-demo-data", null, null, null), null);
    }

    private static final View BINDING = asset -> Optional.of(new ReadCoverage(BINDING_SINCE, WATERMARK));

    private static View view(Map<UUID, AssetReadCoverageSource.Full> full, View bindings, Duration grace) {
        ReadCoverageProperties p = new ReadCoverageProperties();
        p.setValidityGrace(grace);
        return AssetReadCoverageSource.view(full, bindings, p);
    }

    @Test
    void fullCoverageAndACapturingBindingGiveTheIntersectionOfTheirWindows() {
        UUID id = UUID.randomUUID();
        Optional<ReadCoverage> c = view(Map.of(id, new AssetReadCoverageSource.Full(FULL_SINCE, OBSERVED)), BINDING,
                Duration.ZERO).coverageFor(row(id));
        assertThat(c).contains(new ReadCoverage(FULL_SINCE, OBSERVED));
    }

    @Test
    void theGraceExtendsTheProofButNeverPastTheWatermark() {
        UUID id = UUID.randomUUID();
        Map<UUID, AssetReadCoverageSource.Full> full = Map.of(id, new AssetReadCoverageSource.Full(FULL_SINCE, OBSERVED));
        assertThat(view(full, BINDING, Duration.ofHours(24)).coverageFor(row(id)).orElseThrow().validThrough())
                .isEqualTo(WATERMARK);
        assertThat(view(full, BINDING, Duration.ofHours(1)).coverageFor(row(id)).orElseThrow().validThrough())
                .isEqualTo(OBSERVED.plus(Duration.ofHours(1)));
    }

    @Test
    void theLaterStartWins() {
        UUID id = UUID.randomUUID();
        Instant early = Instant.parse("2024-01-01T00:00:00Z");
        assertThat(view(Map.of(id, new AssetReadCoverageSource.Full(early, OBSERVED)), BINDING, Duration.ZERO)
                .coverageFor(row(id)).orElseThrow().since()).isEqualTo(BINDING_SINCE);
    }

    @Test
    void anAssetWithoutFullCoverageHasNoReadCoverageEvenWithACapturingBinding() {
        assertThat(view(Map.of(), BINDING, Duration.ZERO).coverageFor(row(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void fullCoverageWithoutACapturingBindingHasNone() {
        UUID id = UUID.randomUUID();
        assertThat(view(Map.of(id, new AssetReadCoverageSource.Full(FULL_SINCE, OBSERVED)), View.none(), Duration.ZERO)
                .coverageFor(row(id))).isEmpty();
    }

    @Test
    void aPartialAssetsLastAccessRuleStaysUnknownWhereTheBindingAloneWouldCallItDue() {
        UUID id = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-10-20T00:00:00Z");
        RetentionEvidence evidence = new RetentionEvidence(Instant.parse("2023-01-01T00:00:00Z"), null,
                Instant.parse("2025-03-01T00:00:00Z"), null, false, false, false);
        List<RetentionRule> rules = List.of(RetentionRule.ofPolicy(RetentionBasis.LAST_ACCESSED, 90, UUID.randomUUID()));

        RetentionEvaluation bindingOnly = RetentionCalculator.evaluate(asOf, evidence, rules,
                BINDING.coverageFor(row(id)).orElse(null));
        assertThat(bindingOnly.status()).as("CAT-03 behaviour: the binding alone proves it unread")
                .isEqualTo(RetentionStatus.DUE);

        RetentionEvaluation withCoverage = RetentionCalculator.evaluate(asOf, evidence, rules,
                view(Map.of(), BINDING, Duration.ZERO).coverageFor(row(id)).orElse(null));
        assertThat(withCoverage.status()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(withCoverage.reason()).isEqualTo(RetentionEvaluation.READ_COVERAGE_NOT_CAPTURED);
    }
}
