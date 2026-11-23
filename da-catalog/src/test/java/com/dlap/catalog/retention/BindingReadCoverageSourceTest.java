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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAssetStore.StoredOutcome;
import com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys;
import com.dlap.common.model.RetentionStatus;

/**
 * The read signal is found through the asset's provider and scope, and counts only when every
 * enabled binding of that scope captures reads (log-event.md section 10: an asset may be called
 * not read only when its binding captures reads). Anything the evaluator cannot place in a
 * binding gets no coverage, which keeps its last-access rules UNKNOWN.
 */
class BindingReadCoverageSourceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant W = Instant.parse("2026-10-20T00:00:00Z");

    private static BindingStatus binding(String id, String provider, String scope, String state, String coverage,
                                         Instant since, Instant low) {
        return new BindingStatus(id, provider, "GCP_CLOUD_AUDIT", "GCP_PROJECT", scope, state, coverage, since, low,
                low, W);
    }

    private static BindingStatus captured(String id, String provider, String scope, Instant since, Instant low) {
        return binding(id, provider, scope, "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED", since, low);
    }

    private static AssetRow asset(String provider, String uri, TechnicalKeys keys) {
        return new AssetRow(UUID.randomUUID(), uri, provider, "GCS_BUCKET", "ACTIVE", null, null, null, null, false,
                keys, new StoredOutcome(RetentionStatus.UNKNOWN, null, null, null, null, null, null));
    }

    private static TechnicalKeys keys(String project, String account, String subscription, String workspace) {
        return new TechnicalKeys(null, null, project, account, subscription, workspace);
    }

    @Test
    void theConstantsAreTheProtoEnumNames() {
        assertThat(BindingReadCoverageSource.CAPTURED)
                .isEqualTo(com.dlap.proto.log.v1.ReadCoverage.READ_COVERAGE_CAPTURED.name());
        assertThat(BindingReadCoverageSource.DISABLED)
                .isEqualTo(com.dlap.proto.log.v1.BindingState.BINDING_STATE_DISABLED.name());
    }

    @Test
    void aCapturedBindingGivesCoverageFromItsStartThroughItsLowWatermark() {
        ReadCoverageSource.View view = BindingReadCoverageSource.view(List.of(captured("b1", "GCP", "proj-a", T0, W)));

        Optional<ReadCoverage> c = view.coverageFor(asset("GCP", "bq://proj-a/ds/t", keys(null, null, null, null)));

        assertThat(c).contains(new ReadCoverage(T0, W));
    }

    @Test
    void theScopeComesFromTheUriOrFromTechnicalMetadataAndMatchesWithoutCase() {
        ReadCoverageSource.View view = BindingReadCoverageSource.view(List.of(captured("b1", "GCP", "Proj-A", T0, W)));

        // bq:// carries the project; a bucket gets it from technical metadata.
        assertThat(view.coverageFor(asset("GCP", "bq://proj-a/ds/t", keys(null, null, null, null)))).isPresent();
        assertThat(view.coverageFor(asset("GCP", "gs://bucket", keys("PROJ-A", null, null, null)))).isPresent();
        // A bucket with no project in its metadata cannot be placed: no coverage.
        assertThat(view.coverageFor(asset("GCP", "gs://bucket", keys(null, null, null, null)))).isEmpty();
        // Another project, and another provider with the same id, do not match.
        assertThat(view.coverageFor(asset("GCP", "gs://bucket", keys("proj-b", null, null, null)))).isEmpty();
        assertThat(view.coverageFor(asset("AWS", "s3://bucket", keys(null, "proj-a", null, null)))).isEmpty();
    }

    @Test
    void awsAzureDatabricksAndSnowflakeAssetsAreFoundByTheirOwnIdentifiers() {
        ReadCoverageSource.View view = BindingReadCoverageSource.view(List.of(
                captured("aws", "AWS", "123456789012", T0, W),
                captured("az", "AZURE", "sub-1", T0, W),
                captured("dbx", "DATABRICKS", "777", T0, W),
                captured("sf", "SNOWFLAKE", "acme-xy1", T0, W)));

        assertThat(view.coverageFor(asset("AWS", "arn:aws:s3:::bucket", keys(null, "123456789012", null, null))))
                .isPresent();
        assertThat(view.coverageFor(asset("AWS", "arn:aws:glue:us-east-1:123456789012:table/d/t",
                keys(null, null, null, null)))).isPresent();
        assertThat(view.coverageFor(asset("AZURE",
                "/subscriptions/SUB-1/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/sa",
                keys(null, null, null, null)))).isPresent();
        assertThat(view.coverageFor(asset("DATABRICKS", "dbx://x", keys(null, null, null, "777")))).isPresent();
        assertThat(view.coverageFor(asset("SNOWFLAKE", "sf://x", keys(null, "ACME-XY1", null, null)))).isPresent();
        assertThat(view.coverageFor(asset("DATABRICKS", "dbx://x", keys(null, null, null, "778")))).isEmpty();
    }

    @Test
    void everyEnabledBindingOfTheScopeMustCaptureReads() {
        Instant later = Instant.parse("2026-03-01T00:00:00Z");
        Instant earlierWatermark = Instant.parse("2026-10-10T00:00:00Z");
        ReadCoverageSource.View both = BindingReadCoverageSource.view(List.of(
                captured("audit", "GCP", "p", T0, W), captured("jobs", "GCP", "p", later, earlierWatermark)));
        ReadCoverageSource.View oneNone = BindingReadCoverageSource.view(List.of(
                captured("audit", "GCP", "p", T0, W),
                binding("jobs", "GCP", "p", "BINDING_STATE_ACTIVE", "READ_COVERAGE_NONE_OBSERVED", null, W)));
        AssetRow bucket = asset("GCP", "gs://b", keys("p", null, null, null));

        // Start is the latest start, completeness the earliest low watermark.
        assertThat(both.coverageFor(bucket)).contains(new ReadCoverage(later, earlierWatermark));
        assertThat(oneNone.coverageFor(bucket)).isEmpty();
    }

    @Test
    void aDisabledBindingIsIgnoredButAScopeWithOnlyDisabledBindingsHasNoCoverage() {
        ReadCoverageSource.View mixed = BindingReadCoverageSource.view(List.of(
                captured("audit", "GCP", "p", T0, W),
                binding("old", "GCP", "p", "BINDING_STATE_DISABLED", "READ_COVERAGE_NOT_CAPTURED", null, null)));
        ReadCoverageSource.View onlyDisabled = BindingReadCoverageSource.view(List.of(
                binding("old", "GCP", "p", "BINDING_STATE_DISABLED", "READ_COVERAGE_CAPTURED", T0, W)));
        AssetRow bucket = asset("GCP", "gs://b", keys("p", null, null, null));

        assertThat(mixed.coverageFor(bucket)).isPresent();
        assertThat(onlyDisabled.coverageFor(bucket)).isEmpty();
    }

    @Test
    void capturedWithoutAStartOrAWatermarkIsNotCoverage() {
        AssetRow bucket = asset("GCP", "gs://b", keys("p", null, null, null));

        assertThat(BindingReadCoverageSource.view(List.of(captured("b", "GCP", "p", null, W))).coverageFor(bucket))
                .isEmpty();
        assertThat(BindingReadCoverageSource.view(List.of(captured("b", "GCP", "p", T0, null))).coverageFor(bucket))
                .isEmpty();
    }

    @Test
    void everyOtherCoverageStateIsNotCoverage() {
        AssetRow bucket = asset("GCP", "gs://b", keys("p", null, null, null));

        for (String state : List.of("READ_COVERAGE_NONE_OBSERVED", "READ_COVERAGE_NOT_CAPTURED",
                "READ_COVERAGE_UNSPECIFIED")) {
            ReadCoverageSource.View view = BindingReadCoverageSource.view(List.of(
                    binding("b", "GCP", "p", "BINDING_STATE_ACTIVE", state, T0, W)));
            assertThat(view.coverageFor(bucket)).as(state).isEmpty();
        }
    }

    @Test
    void anAssetWithNoBindingAtAllHasNoCoverage() {
        assertThat(ReadCoverageSource.View.none().coverageFor(asset("GCP", "gs://b", keys("p", null, null, null))))
                .isEmpty();
        assertThat(BindingReadCoverageSource.view(List.of())
                .coverageFor(asset("GCP", "gs://b", keys("p", null, null, null)))).isEmpty();
    }
}
