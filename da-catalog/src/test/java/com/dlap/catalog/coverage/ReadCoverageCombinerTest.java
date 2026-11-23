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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dlap.catalog.coverage.ReadCoverageCombiner.Result;
import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;

/**
 * COV-01: provider configuration and DLAP's bindings must both hold for a complete read history;
 * a GCP-02 JOBS-view binding alone gives BigQuery "job reads only".
 */
class ReadCoverageCombinerTest {

    private static final Instant T = Instant.parse("2026-10-20T06:41:00Z");

    private static ReadCoverageObservation bq(ReadCoverageLevel level, List<String> reasons) {
        return new ReadCoverageObservation("e1", "GCP", "dlap-demo-data", "bq://dlap-demo-data",
                Set.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"), "GCP_CLOUD_AUDIT", level, reasons, T, "gcp@1.0.0");
    }

    private static ReadCoverageObservation gcs(ReadCoverageLevel level) {
        return new ReadCoverageObservation("e2", "GCP", "dlap-demo-data", "gs://dlap-demo-curated",
                Set.of("GCS_BUCKET", "GCS_OBJECT"), "GCP_CLOUD_AUDIT", level, List.of("evidence"), T, "gcp@1.0.0");
    }

    private static BindingStatus binding(String id, String kind, String scope, String state, String coverage) {
        return new BindingStatus(id, "GCP", kind, "GCP_PROJECT", scope, state, coverage, T, T, T, T);
    }

    private static final BindingStatus AUDIT = binding("audit", "GCP_CLOUD_AUDIT", "dlap-demo-data",
            "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED");
    private static final BindingStatus JOBS = binding("jobs", "GCP_BQ_JOBS_VIEW", "dlap-demo-data",
            "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED");

    @Test
    void aCapturingBindingOfTheChannelLetsTheConfigurationDecide() {
        Result r = ReadCoverageCombiner.combine(gcs(ReadCoverageLevel.FULL), List.of(AUDIT));
        assertThat(r.level()).isEqualTo(ReadCoverageLevel.FULL);
        assertThat(r.reasons()).containsExactly("evidence");

        Result unknown = ReadCoverageCombiner.combine(bq(null, List.of("missing permission x on y")), List.of(AUDIT));
        assertThat(unknown.level()).isNull();
        assertThat(unknown.reasons()).containsExactly("missing permission x on y");
    }

    @Test
    void aJobsViewBindingAloneGivesBigQueryJobReadsOnly() {
        Result r = ReadCoverageCombiner.combine(bq(ReadCoverageLevel.FULL, List.of("on by default")), List.of(JOBS));
        assertThat(r.level()).isEqualTo(ReadCoverageLevel.PARTIAL);
        assertThat(r.reasons()).containsExactly("bqjobs only: job reads only");

        Result withGap = ReadCoverageCombiner.combine(bq(ReadCoverageLevel.PARTIAL, List.of("1 principal(s) exempted")),
                List.of(JOBS));
        assertThat(withGap.reasons()).containsExactly("bqjobs only: job reads only", "1 principal(s) exempted");
    }

    @Test
    void aJobsViewBindingDoesNotHelpCloudStorage() {
        Result r = ReadCoverageCombiner.combine(gcs(ReadCoverageLevel.FULL), List.of(JOBS));
        assertThat(r.level()).isEqualTo(ReadCoverageLevel.NONE);
        assertThat(r.reasons()).containsExactly(
                "no DLAP log binding ingests GCP_CLOUD_AUDIT records for GCP scope dlap-demo-data");
    }

    @Test
    void theAuditBindingWinsOverTheJobsView() {
        Result r = ReadCoverageCombiner.combine(bq(ReadCoverageLevel.FULL, List.of("on by default")), List.of(JOBS, AUDIT));
        assertThat(r.level()).isEqualTo(ReadCoverageLevel.FULL);
    }

    @Test
    void aBindingThatDoesNotCaptureReadsYetIsUnknownAndNamed() {
        BindingStatus quiet = binding("audit", "GCP_CLOUD_AUDIT", "dlap-demo-data", "BINDING_STATE_ACTIVE",
                "READ_COVERAGE_NONE_OBSERVED");
        Result r = ReadCoverageCombiner.combine(gcs(ReadCoverageLevel.FULL), List.of(quiet));
        assertThat(r.level()).isNull();
        assertThat(r.reasons()).containsExactly("binding audit (GCP_CLOUD_AUDIT) reports READ_COVERAGE_NONE_OBSERVED");
    }

    @Test
    void disabledBindingsAndOtherScopesAndProvidersDoNotCount() {
        List<BindingStatus> others = List.of(
                binding("old", "GCP_CLOUD_AUDIT", "dlap-demo-data", "BINDING_STATE_DISABLED", "READ_COVERAGE_CAPTURED"),
                binding("elsewhere", "GCP_CLOUD_AUDIT", "another-project", "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED"),
                new BindingStatus("aws", "AWS", "GCP_CLOUD_AUDIT", "AWS_ACCOUNT", "dlap-demo-data", "BINDING_STATE_ACTIVE",
                        "READ_COVERAGE_CAPTURED", T, T, T, T));
        assertThat(ReadCoverageCombiner.combine(gcs(ReadCoverageLevel.FULL), others).level())
                .isEqualTo(ReadCoverageLevel.NONE);
    }

    @Test
    void scopeAndProviderMatchIgnoreCase() {
        BindingStatus upper = binding("audit", "GCP_CLOUD_AUDIT", "DLAP-DEMO-DATA", "BINDING_STATE_ACTIVE",
                "READ_COVERAGE_CAPTURED");
        assertThat(ReadCoverageCombiner.combine(gcs(ReadCoverageLevel.FULL), List.of(upper)).level())
                .isEqualTo(ReadCoverageLevel.FULL);
    }

    @Test
    void aNoneConfigurationStaysNoneWithACapturingBinding() {
        Result r = ReadCoverageCombiner.combine(gcs(ReadCoverageLevel.NONE), List.of(AUDIT));
        assertThat(r.level()).isEqualTo(ReadCoverageLevel.NONE);
    }
}
