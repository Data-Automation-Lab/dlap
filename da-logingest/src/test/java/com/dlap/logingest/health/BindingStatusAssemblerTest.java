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

package com.dlap.logingest.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.health.HealthSnapshot.BindingSnapshot;
import com.dlap.logingest.health.HealthSnapshot.ObservedVersion;
import com.dlap.logingest.health.HealthSnapshot.StreamSnapshot;
import com.dlap.logingest.metrics.ParseTelemetry.WindowCounts;
import com.dlap.logingest.model.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.ReadCoverage;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The wire status of a binding: state rules, scope mapping and the carried fields. Timestamps are
 * times the service observed; none is invented.
 */
class BindingStatusAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant START = NOW.minus(Duration.ofMinutes(1));

    private final BindingStatusAssembler assembler = new BindingStatusAssembler(LogHealthProperties.defaults());
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    private static final ReadCoverageEvaluator.Evaluation CAPTURED = new ReadCoverageEvaluator.Evaluation(
            ReadCoverage.READ_COVERAGE_CAPTURED, Optional.of(Instant.parse("2026-09-01T00:00:00Z")), 1.0);

    private static BindingSnapshot binding(BindingState state, Map<String, Object> scope, String kind,
                                           Instant low) {
        return new BindingSnapshot("gcp-main", "gcp-audit", kind, Optional.of(SourceProvider.GCP), scope, state,
                state == BindingState.ACTIVE, "a".repeat(64),
                List.of(new StreamSnapshot("logging-api", "LOGGING_API", "gcp-audit-log", "auto", low,
                        NOW.minusSeconds(5), NOW.minusSeconds(20))),
                List.of(new ObservedVersion("gcp-audit-log", "auditlog", "f".repeat(64),
                        Instant.parse("2026-09-01T00:00:00Z"), NOW.minusSeconds(3), 42)));
    }

    private static WindowCounts counts(long parsed, long warnings, long skipped, long quarantined) {
        return new WindowCounts(START, NOW, parsed, warnings, skipped, quarantined, parsed + warnings, 3);
    }

    @Test
    void anActiveBindingCarriesCursorsCountsFormatsAndCoverage() {
        BindingSnapshot b = binding(BindingState.ACTIVE, Map.of("project-id", "dlap-demo", "regions", List.of("us-east1")),
                "GCP_CLOUD_AUDIT", NOW.minusSeconds(90));

        LogBindingStatus s = assembler.assemble(b, counts(90, 5, 3, 2), CAPTURED, true, NOW);

        assertThat(s.getBindingId()).isEqualTo("gcp-main");
        assertThat(s.getState()).isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_ACTIVE);
        assertThat(s.getStateDetail()).isEmpty();
        assertThat(s.getSourceProvider()).isEqualTo(SourceProvider.GCP);
        assertThat(s.getSourceKind()).isEqualTo("GCP_CLOUD_AUDIT");
        assertThat(s.getScope().getScopeType()).isEqualTo("GCP_PROJECT");
        assertThat(s.getScope().getScopeId()).isEqualTo("dlap-demo");
        assertThat(s.getScope().getRegionsList()).containsExactly("us-east1");
        assertThat(s.getConfigHash()).isEqualTo("a".repeat(64));
        assertThat(s.getReportedTime().getSeconds()).isEqualTo(NOW.getEpochSecond());
        assertThat(s.getLowWatermark().getSeconds()).isEqualTo(NOW.minusSeconds(90).getEpochSecond());
        assertThat(s.getHighWatermark().getSeconds()).isEqualTo(NOW.minusSeconds(5).getEpochSecond());
        assertThat(s.getLastPollTime().getSeconds()).isEqualTo(NOW.minusSeconds(20).getEpochSecond());
        assertThat(s.getCounts().getParsed()).isEqualTo(90);
        assertThat(s.getCounts().getParsedWithWarnings()).isEqualTo(5);
        assertThat(s.getCounts().getSkipped()).isEqualTo(3);
        assertThat(s.getCounts().getQuarantined()).isEqualTo(2);
        assertThat(s.getCounts().getPublished()).isEqualTo(95);
        assertThat(s.getCounts().getDuplicates()).isEqualTo(3);
        assertThat(s.getWindowStart().getSeconds()).isEqualTo(START.getEpochSecond());
        assertThat(s.getWindowEnd().getSeconds()).isEqualTo(NOW.getEpochSecond());
        assertThat(s.getObservedFormatsList()).singleElement().satisfies(f -> {
            assertThat(f.getEnvelope()).isEqualTo("gcp-audit-log");
            assertThat(f.getSchemaVersion()).isEqualTo("auditlog");
            assertThat(f.getCount()).isEqualTo(42);
        });
        assertThat(s.getReadCoverage()).isEqualTo(ReadCoverage.READ_COVERAGE_CAPTURED);
        assertThat(s.getReadCoverageSince().getSeconds()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z").getEpochSecond());
        assertThat(s.getSchemaVersion()).isEqualTo("v1");
    }

    @Test
    void theStatusSurvivesTheWireCodec() {
        BindingSnapshot b = binding(BindingState.ACTIVE, Map.of("project-id", "dlap-demo"), "GCP_CLOUD_AUDIT",
                NOW.minusSeconds(90));
        LogBindingStatus s = assembler.assemble(b, counts(1, 0, 0, 0), CAPTURED, true, NOW);

        LogBindingStatus back = codec.decodeMessage(codec.encodeMessage(s), LogBindingStatus.getDefaultInstance());

        assertThat(back).isEqualTo(s);
    }

    @Test
    void coverageWithoutAFirstReadHasNoSinceField() {
        BindingSnapshot b = binding(BindingState.ACTIVE, Map.of("project-id", "p"), "GCP_CLOUD_AUDIT", null);
        ReadCoverageEvaluator.Evaluation none = new ReadCoverageEvaluator.Evaluation(
                ReadCoverage.READ_COVERAGE_NONE_OBSERVED, Optional.empty(), 0.0);

        LogBindingStatus s = assembler.assemble(b, counts(1, 0, 0, 0), none, true, NOW);

        assertThat(s.hasReadCoverageSince()).isFalse();
        assertThat(s.hasLowWatermark()).isFalse();
        assertThat(s.getReadCoverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NONE_OBSERVED);
    }

    @Test
    void anInvalidConfigurationIsFailedAndADisabledOneDisabled() {
        assertThat(assembler.state(binding(BindingState.INVALID, Map.of(), "GCP_CLOUD_AUDIT", null),
                counts(0, 0, 0, 0), true, NOW).state())
                .isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_FAILED);
        assertThat(assembler.state(binding(BindingState.DISABLED, Map.of(), "GCP_CLOUD_AUDIT", null),
                counts(0, 0, 0, 0), true, NOW).state())
                .isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_DISABLED);
        BindingStatusAssembler.State legacy = assembler.state(binding(BindingState.INACTIVE, Map.of(),
                "GCP_CLOUD_AUDIT", null), counts(0, 0, 0, 0), true, NOW);
        assertThat(legacy.state()).isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_DISABLED);
        assertThat(legacy.detail()).contains("legacy");
    }

    @Test
    void aFailedSelfTestDegradesEveryRunningBinding() {
        BindingStatusAssembler.State s = assembler.state(binding(BindingState.ACTIVE, Map.of(), "K", null),
                counts(100, 0, 0, 0), false, NOW);

        assertThat(s.state()).isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_DEGRADED);
        assertThat(s.detail()).contains("self-test");
    }

    @Test
    void aStaleCursorDegradesTheBinding() {
        BindingSnapshot fresh = binding(BindingState.ACTIVE, Map.of(), "K", NOW.minus(Duration.ofMinutes(29)));
        BindingSnapshot stale = binding(BindingState.ACTIVE, Map.of(), "K", NOW.minus(Duration.ofMinutes(31)));

        assertThat(assembler.state(fresh, counts(1, 0, 0, 0), true, NOW).state())
                .isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_ACTIVE);
        BindingStatusAssembler.State s = assembler.state(stale, counts(1, 0, 0, 0), true, NOW);
        assertThat(s.state()).isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_DEGRADED);
        assertThat(s.detail()).contains("PT30M");
    }

    @Test
    void aHighQuarantineShareDegradesOnlyWithEnoughRecords() {
        BindingSnapshot b = binding(BindingState.ACTIVE, Map.of(), "K", null);

        assertThat(assembler.state(b, counts(90, 0, 0, 10), true, NOW).state())
                .as("10 of 100 is above 5%").isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_DEGRADED);
        assertThat(assembler.state(b, counts(95, 0, 0, 5), true, NOW).state())
                .as("5 of 100 is exactly 5%, not above").isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_ACTIVE);
        assertThat(assembler.state(b, counts(1, 0, 0, 5), true, NOW).state())
                .as("6 records are too few to judge").isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_ACTIVE);
    }

    @Test
    void theScopeTypeFollowsTheScopeKeysAndTheKind() {
        assertThat(BindingStatusAssembler.scope(binding(BindingState.ACTIVE, Map.of("account-id", "123456789012"),
                "AWS_CLOUDTRAIL", null)).getScopeType()).isEqualTo("AWS_ACCOUNT");
        assertThat(BindingStatusAssembler.scope(binding(BindingState.ACTIVE, Map.of("subscription-id", "s"),
                "AZURE_ACTIVITY", null)).getScopeType()).isEqualTo("AZURE_SUBSCRIPTION");
        assertThat(BindingStatusAssembler.scope(binding(BindingState.ACTIVE, Map.of("workspace-id", "1"),
                "DATABRICKS_AUDIT", null)).getScopeType()).isEqualTo("DATABRICKS_WORKSPACE");
        assertThat(BindingStatusAssembler.scope(binding(BindingState.ACTIVE, Map.of("account-id", "x"),
                "SNOWFLAKE_ACCESS_HISTORY", null)).getScopeType()).isEqualTo("SNOWFLAKE_ACCOUNT");
        assertThat(BindingStatusAssembler.scope(binding(BindingState.ACTIVE, Map.of("metastore-id", "m"),
                "UNITY_AUDIT", null)).getScopeType()).isEqualTo("UNITY_METASTORE");
        assertThat(BindingStatusAssembler.scope(binding(BindingState.ACTIVE, Map.of(), "K", null)).getScopeId())
                .isEmpty();
    }
}
