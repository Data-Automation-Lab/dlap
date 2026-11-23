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

package com.dlap.logingest.providers.gcp.bigquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.parse.bqjobs.BqJobsFormat;
import com.dlap.logingest.parse.bqjobs.BqJobsRunProblem;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The BigQuery JOBS transport: the window it reads (the 180-day bound, the first run, the overlap
 * the runner applies), what it selects, how it batches and moves the cursor, and what it does when
 * the view's column set or location is wrong.
 */
class BqJobsViewTransportTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant TO = NOW.minus(BqJobsViewTransport.SETTLE);
    private static final String BINDING = "acme-gcp-jobs";

    private final FakeJobsViewGateway gateway = new FakeJobsViewGateway();
    private final BqJobsViewTransport transport = new BqJobsViewTransport(gateway,
            id -> BINDING.equals(id) ? Optional.of("proj-x") : Optional.empty());

    private StreamContext context(boolean backfillAllowed, IngestMode mode, Map<String, String> location) {
        BindingConfig parse = BindingConfig.auto(BINDING, BqJobsFormat.SOURCE_KIND).withAssetIdHints(AssetIdHints.none());
        EffectiveStream stream = new EffectiveStream(BINDING, "bq-jobs-view", BqJobsViewTransport.TRANSPORT_ID,
                BqJobsFormat.ENVELOPE, location, parse, StreamFilters.NONE, Duration.ofMinutes(30), Duration.ofMinutes(5),
                null, null, backfillAllowed, false, false);
        return new StreamContext(stream, BqJobsFormat.SOURCE_KIND, mode, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ProcessedObjectStore.class));
    }

    private StreamContext context(boolean backfillAllowed) {
        return context(backfillAllowed, IngestMode.STREAM, Map.of("location", "US"));
    }

    private static List<RawBatch> drain(RawBatchIterator it) {
        List<RawBatch> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    // ------------------------------------------------------------------
    // Descriptor
    // ------------------------------------------------------------------

    @Test
    void theDescriptorNamesTheTransportItsLocationKeysAndItsClock() {
        TransportDescriptor d = transport.descriptor();

        assertThat(d.transportId()).isEqualTo("BQ_JOBS_VIEW");
        assertThat(d.requiredLocation()).containsExactly("location");
        assertThat(d.optionalLocation()).containsExactly("billing-project");
        assertThat(d.cursorKind()).isEqualTo(TransportDescriptor.CursorKind.TIME);
        assertThat(d.initialLookback()).isEqualTo(Duration.ofDays(180));
        assertThat(d.settle()).isEqualTo(Duration.ofMinutes(5));
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    @Test
    void aBackfillNeverAsksForMoreThanTheRetentionWindow() {
        Instant tooOld = ago(Duration.ofDays(400));

        drain(transport.fetch(context(true, IngestMode.BACKFILL, Map.of("location", "US")), "", CursorPosition.INITIAL,
                new FetchWindow(tooOld, TO)));

        assertThat(gateway.rowQueries).hasSize(1);
        assertThat(gateway.rowQueries.get(0).timestamps().get("from")).isEqualTo(ago(Duration.ofDays(180)));
        assertThat(gateway.rowQueries.get(0).timestamps().get("to")).isEqualTo(TO);
    }

    @Test
    void aStreamWithoutBackfillStartsTwentyFourHoursBack() {
        drain(transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofDays(180)), TO)));

        assertThat(gateway.rowQueries.get(0).timestamps().get("from")).isEqualTo(TO.minus(Duration.ofHours(24)));
    }

    @Test
    void aStreamThatMayBackfillStartsAtTheRetentionBound() {
        drain(transport.fetch(context(true), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofDays(180)), TO)));

        assertThat(gateway.rowQueries.get(0).timestamps().get("from")).isEqualTo(ago(Duration.ofDays(180)));
    }

    @Test
    void aLaterPollKeepsTheWindowTheRunnerGaveIt() {
        Instant low = ago(Duration.ofMinutes(20));
        Instant from = low.minus(Duration.ofMinutes(30));
        drain(transport.fetch(context(false), "", new CursorPosition(low, low, Map.of()), new FetchWindow(from, TO)));

        assertThat(gateway.rowQueries.get(0).timestamps().get("from")).isEqualTo(from);
    }

    @Test
    void thePartitionScanIsBoundedByTheLongestJob() {
        drain(transport.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(gateway.rowQueries.get(0).timestamps().get("creation_floor"))
                .isEqualTo(gateway.rowQueries.get(0).timestamps().get("from").minus(Duration.ofHours(6)));
    }

    @Test
    void anEmptyWindowReadsNothingAndDoesNotTouchBigQuery() {
        assertThat(drain(transport.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(TO, TO)))).isEmpty();
        assertThat(gateway.columnQueries).isEmpty();
        assertThat(gateway.rowQueries).isEmpty();
    }

    // ------------------------------------------------------------------
    // What is selected
    // ------------------------------------------------------------------

    @Test
    void theQuerySelectsOnlyKeptColumnsSoQueryTextIsNeverRead() {
        drain(transport.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO)));

        String sql = gateway.rowQueries.get(0).sql();
        assertThat(sql).contains("j.job_id", "j.end_time", "j.referenced_tables", "j.destination_table");
        assertThat(sql).doesNotContain("j.query", "j.job_stages", "j.timeline", "j.brand_new_column", "j.continuous");
        assertThat(sql).contains("INFORMATION_SCHEMA.JOBS_BY_PROJECT").contains("`region-us`").contains("`proj-x`");
        assertThat(sql).contains("j.state = 'DONE'").contains("NOT EXISTS").contains("c.parent_job_id = j.job_id")
                .contains("ORDER BY j.end_time, j.job_id");
    }

    @Test
    void theColumnSetIsReadByADryRunOfSelectStar() {
        drain(transport.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO)));

        assertThat(gateway.columnQueries).hasSize(1);
        assertThat(gateway.columnQueries.get(0).sql()).isEqualTo(
                "SELECT * FROM `proj-x`.`region-us`.INFORMATION_SCHEMA.JOBS_BY_PROJECT");
        assertThat(gateway.columnQueries.get(0).location()).isEqualTo("US");
    }

    @Test
    void theBillingProjectDefaultsToTheScopeProjectAndCanBeSet() {
        drain(transport.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO)));
        drain(transport.fetch(context(false, IngestMode.STREAM, Map.of("location", "EU", "billing-project", "proj-bill")),
                "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO)));

        assertThat(gateway.rowQueries.get(0).billingProject()).isEqualTo("proj-x");
        assertThat(gateway.rowQueries.get(1).billingProject()).isEqualTo("proj-bill");
        assertThat(gateway.rowQueries.get(1).sql()).contains("`proj-x`.`region-eu`");
        assertThat(gateway.rowQueries.get(1).location()).isEqualTo("EU");
    }

    @Test
    void unsafeProjectsAndLocationsNeverReachTheSql() {
        BqJobsViewTransport evil = new BqJobsViewTransport(gateway, id -> Optional.of("proj`; DROP"));
        assertThatThrownBy(() -> evil.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.fetch(context(false, IngestMode.STREAM, Map.of("location", "US` OR 1=1")), "",
                CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.fetch(context(false, IngestMode.STREAM, Map.of()), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(1)), TO))).isInstanceOf(IllegalArgumentException.class);
        assertThat(gateway.columnQueries).isEmpty();
    }

    @Test
    void aBindingWithoutAProjectScopeFailsLoudly() {
        BqJobsViewTransport none = new BqJobsViewTransport(gateway, id -> Optional.empty());
        assertThatThrownBy(() -> none.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(1)), TO)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(BINDING);
    }

    // ------------------------------------------------------------------
    // Records, batches and the cursor
    // ------------------------------------------------------------------

    @Test
    void recordsCarryTheLocationTheBindingAndAnIdentifyingReference() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00.250Z"));

        List<RawBatch> batches = drain(transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        RawRecord record = batches.get(0).records().get(0);
        assertThat(record.bindingId()).isEqualTo(BINDING);
        assertThat(record.sourceKind()).isEqualTo("GCP_BQ_JOBS_VIEW");
        assertThat(record.envelope()).isEqualTo("jobs-view-row");
        assertThat(record.mode()).isEqualTo(IngestMode.STREAM);
        assertThat(record.ref().uri()).isEqualTo("bigquery://projects/proj-x/locations/US/jobs/job_1");
        assertThat(record.ref().cursor()).isEqualTo("2026-10-20T11:00:00.250Z");
        assertThat(record.body().get("location").asText()).isEqualTo("US");
        assertThat(record.body().get("job_id").asText()).isEqualTo("job_1");
        assertThat(record.body().has("query")).as("the query text never reaches a record").isFalse();
        assertThat(record.lenientText()).doesNotContain("secret_marker");
    }

    @Test
    void rowsComeInBatchesAndOnlyTheLastBatchMovesTheWatermarkToTheEndOfTheWindow() {
        for (int i = 0; i < 1203; i++) {
            gateway.jobs.add(FakeJobsViewGateway.job("job_" + i, Instant.parse("2026-10-20T08:00:00Z").plusSeconds(i).toString()));
        }

        List<RawBatch> batches = drain(transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(6)), TO)));

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0).records()).hasSize(BqJobsViewTransport.BATCH_ROWS);
        assertThat(batches.get(1).records()).hasSize(BqJobsViewTransport.BATCH_ROWS);
        assertThat(batches.get(2).records()).hasSize(203);
        assertThat(batches.get(0).advanceTo().lowWatermark()).isEqualTo(Instant.parse("2026-10-20T08:00:00Z").plusSeconds(499));
        assertThat(batches.get(1).advanceTo().lowWatermark()).isEqualTo(Instant.parse("2026-10-20T08:00:00Z").plusSeconds(999));
        assertThat(batches.get(2).advanceTo().lowWatermark()).isEqualTo(TO);
        assertThat(batches.get(2).advanceTo().highWatermark()).isEqualTo(Instant.parse("2026-10-20T08:00:00Z").plusSeconds(1202));
    }

    @Test
    void anEmptyResultStillMovesTheCursorToTheEndOfTheWindow() {
        List<RawBatch> batches = drain(transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).records()).isEmpty();
        assertThat(batches.get(0).advanceTo().lowWatermark()).isEqualTo(TO);
    }

    @Test
    void theColumnSetHashIsKeptInTheCursorState() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));

        List<RawBatch> batches = drain(transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(batches.get(0).advanceTo().state()).containsEntry(BqJobsViewTransport.STATE_COLUMN_HASH,
                BqJobsFormat.columnSetHash(FakeJobsViewGateway.FULL_COLUMNS));
    }

    @Test
    void newColumnsChangeTheHashButTheRunContinues() {
        String before = BqJobsFormat.columnSetHash(FakeJobsViewGateway.FULL_COLUMNS);
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        gateway.columns.add("another_new_column");
        CursorPosition previous = new CursorPosition(ago(Duration.ofHours(1)), ago(Duration.ofHours(1)),
                Map.of(BqJobsViewTransport.STATE_COLUMN_HASH, before));

        List<RawBatch> batches = drain(transport.fetch(context(false), "", previous,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(batches.get(0).records()).hasSize(1);
        assertThat(batches.get(0).advanceTo().state().get(BqJobsViewTransport.STATE_COLUMN_HASH)).isNotEqualTo(before);
        assertThat(gateway.rowQueries.get(0).sql()).doesNotContain("another_new_column");
    }

    // ------------------------------------------------------------------
    // Drift and refusals
    // ------------------------------------------------------------------

    @Test
    void aRequiredColumnMissingFromTheViewEndsTheRunWithOneMarkerAndKeepsTheCursor() {
        gateway.columns.remove("referenced_tables");
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        Instant low = ago(Duration.ofHours(1));
        CursorPosition previous = new CursorPosition(low, low, Map.of(BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash"));

        List<RawBatch> batches = drain(transport.fetch(context(false), "", previous,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(gateway.rowQueries).as("no rows are read from a drifted view").isEmpty();
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).records()).hasSize(1);
        RawRecord marker = batches.get(0).records().get(0);
        assertThat(BqJobsRunProblem.isMarker(marker.body())).isTrue();
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("kind").asText()).isEqualTo("COLUMN_DRIFT");
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("missing").get(0).asText()).isEqualTo("referenced_tables");
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("expected_column_set_hash").asText()).isEqualTo("old-hash");
        assertThat(batches.get(0).advanceTo().lowWatermark()).as("nothing was read, nothing is skipped").isEqualTo(low);
        assertThat(batches.get(0).advanceTo().state()).as("the recorded hash stays the last good one")
                .containsEntry(BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash");
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("column_set_hash").asText())
                .isEqualTo(BqJobsFormat.columnSetHash(gateway.columns));
    }

    private static final String ALL_OPTIONAL = "cache_hit,destination_table,error_result,parent_job_id,statement_type";

    @Test
    void theLoadBearingOptionalColumnsTheViewHasAreKeptInTheCursorState() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));

        List<RawBatch> batches = drain(transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(batches.get(0).advanceTo().state())
                .containsEntry(BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS, ALL_OPTIONAL);
    }

    @Test
    void aLoadBearingOptionalColumnThePreviousRunSawEndsTheRunWithAColumnLostMarkerAndKeepsTheCursor() {
        gateway.columns.remove("cache_hit");
        gateway.columns.remove("error_result");
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        Instant low = ago(Duration.ofHours(1));
        CursorPosition previous = new CursorPosition(low, low, Map.of(
                BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash",
                BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS, ALL_OPTIONAL));

        List<RawBatch> batches = drain(transport.fetch(context(false), "", previous,
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(gateway.rowQueries).as("no rows are read from a view that lost a column").isEmpty();
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).records()).hasSize(1);
        RawRecord marker = batches.get(0).records().get(0);
        assertThat(BqJobsRunProblem.isMarker(marker.body())).isTrue();
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("kind").asText()).isEqualTo("COLUMN_LOST");
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("lost")).extracting(JsonNode::asText)
                .containsExactly("cache_hit", "error_result");
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("expected_column_set_hash").asText()).isEqualTo("old-hash");
        assertThat(batches.get(0).advanceTo().lowWatermark()).as("nothing was read, nothing is skipped").isEqualTo(low);
        assertThat(batches.get(0).advanceTo().state()).as("the recorded state stays the last good one")
                .containsEntry(BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash")
                .containsEntry(BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS, ALL_OPTIONAL);
    }

    @Test
    void theColumnLostMarkerIsTheSameBytesOnEveryPollOfTheSameProblem() {
        CursorPosition seen = new CursorPosition(ago(Duration.ofHours(1)), ago(Duration.ofHours(1)), Map.of(
                BqJobsViewTransport.STATE_COLUMN_HASH, BqJobsFormat.columnSetHash(FakeJobsViewGateway.FULL_COLUMNS),
                BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS, "cache_hit,statement_type"));
        gateway.columns.remove("statement_type");

        List<RawBatch> first = drain(transport.fetch(context(false), "", seen, new FetchWindow(ago(Duration.ofHours(2)), TO)));
        List<RawBatch> second = drain(transport.fetch(context(false), "", first.get(0).advanceTo(),
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(first.get(0).records().get(0).body().get(BqJobsRunProblem.KEY).get("kind").asText())
                .isEqualTo("COLUMN_LOST");
        assertThat(second.get(0).records().get(0).body()).isEqualTo(first.get(0).records().get(0).body());
        assertThat(second.get(0).records().get(0).sha256()).isEqualTo(first.get(0).records().get(0).sha256());
    }

    @Test
    void aLoadBearingColumnThePreviousRunNeverSawIsNotLostAndTheRunContinues() {
        gateway.columns.remove("parent_job_id");
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        // A cursor from before the state existed, and one whose last run never saw parent_job_id.
        CursorPosition older = new CursorPosition(ago(Duration.ofHours(1)), ago(Duration.ofHours(1)),
                Map.of(BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash"));
        CursorPosition without = new CursorPosition(ago(Duration.ofHours(1)), ago(Duration.ofHours(1)), Map.of(
                BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash",
                BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS, "cache_hit,statement_type"));

        List<RawBatch> first = drain(transport.fetch(context(false), "", older, new FetchWindow(ago(Duration.ofHours(2)), TO)));
        List<RawBatch> second = drain(transport.fetch(context(false), "", without, new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(first.get(0).records()).hasSize(1);
        assertThat(BqJobsRunProblem.isMarker(first.get(0).records().get(0).body())).isFalse();
        assertThat(first.get(0).advanceTo().state().get(BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS))
                .isEqualTo("cache_hit,destination_table,error_result,statement_type");
        assertThat(BqJobsRunProblem.isMarker(second.get(0).records().get(0).body())).isFalse();
    }

    @Test
    void aColumnThatComesBackEndsTheQuarantineAndTheRunReads() {
        CursorPosition seen = new CursorPosition(ago(Duration.ofHours(1)), ago(Duration.ofHours(1)), Map.of(
                BqJobsViewTransport.STATE_COLUMN_HASH, "old-hash",
                BqJobsViewTransport.STATE_LOAD_BEARING_COLUMNS, "cache_hit"));
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        gateway.columns.remove("cache_hit");
        List<RawBatch> lost = drain(transport.fetch(context(false), "", seen, new FetchWindow(ago(Duration.ofHours(2)), TO)));
        assertThat(lost.get(0).records().get(0).body().get(BqJobsRunProblem.KEY).get("kind").asText()).isEqualTo("COLUMN_LOST");

        gateway.columns.add("cache_hit");
        List<RawBatch> back = drain(transport.fetch(context(false), "", lost.get(0).advanceTo(),
                new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(back.get(0).records()).hasSize(1);
        assertThat(BqJobsRunProblem.isMarker(back.get(0).records().get(0).body())).isFalse();
    }

    @Test
    void theDriftMarkerIsTheSameBytesOnEveryPollOfTheSameProblem() {
        gateway.columns.remove("end_time");
        List<RawBatch> first = drain(transport.fetch(context(false), "", CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(2)), TO)));
        CursorPosition after = first.get(0).advanceTo();
        List<RawBatch> second = drain(transport.fetch(context(false), "", after, new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(second.get(0).records().get(0).body()).isEqualTo(first.get(0).records().get(0).body());
        assertThat(second.get(0).records().get(0).sha256()).isEqualTo(first.get(0).records().get(0).sha256());
        assertThat(second.get(0).records().get(0).ref()).isEqualTo(first.get(0).records().get(0).ref());
    }

    @Test
    void aLocationBigQueryRefusesBecomesARegionMismatchMarker() {
        gateway.columnsFailure = new JobsViewGateway.ViewFailure(JobsViewGateway.ViewFailure.Kind.LOCATION_MISMATCH, "x", null);

        List<RawBatch> batches = drain(transport.fetch(context(false, IngestMode.STREAM, Map.of("location", "EU")), "",
                CursorPosition.INITIAL, new FetchWindow(ago(Duration.ofHours(2)), TO)));

        assertThat(batches).hasSize(1);
        RawRecord marker = batches.get(0).records().get(0);
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("kind").asText()).isEqualTo("REGION_MISMATCH");
        assertThat(marker.body().get(BqJobsRunProblem.KEY).get("location").asText()).isEqualTo("EU");
        assertThat(batches.get(0).advanceTo().lowWatermark()).isNull();
    }

    @Test
    void aMissingPermissionIsNotHiddenAsAMarker() {
        gateway.columnsFailure = new JobsViewGateway.ViewFailure(JobsViewGateway.ViewFailure.Kind.PERMISSION_DENIED, "denied", null);

        assertThatThrownBy(() -> transport.fetch(context(false), "", CursorPosition.INITIAL,
                new FetchWindow(ago(Duration.ofHours(2)), TO)))
                .isInstanceOfSatisfying(JobsViewGateway.ViewFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(JobsViewGateway.ViewFailure.Kind.PERMISSION_DENIED));
    }
}
