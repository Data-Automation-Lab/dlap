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

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.parse.bqjobs.BqJobsFormat;
import com.dlap.logingest.parse.bqjobs.BqJobsRunProblem;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads finished BigQuery jobs from {@code INFORMATION_SCHEMA.JOBS_BY_PROJECT} as read and write
 * history of tables (transport id {@code BQ_JOBS_VIEW}, source kind {@code GCP_BQ_JOBS_VIEW}).
 *
 * <p><b>Window.</b> The cursor is an event-time watermark on the job's {@code end_time}. A poll reads
 * {@code [low - overlap, now - settle)}, so a job that the view lists late is still caught, and the
 * event ids (job and table) make the re-read free of duplicates. The window never starts earlier than
 * the 180 days the view retains ({@link BqJobsFormat#RETENTION}); asking for more reads nothing and
 * costs bytes. A stream without {@code backfill.allowed} starts 24 hours back; with it, at the start
 * of the retention window. The query bounds {@code creation_time} by the longest job runtime, which
 * keeps the scan to the partitions that can hold the jobs (the view is partitioned by creation time).
 *
 * <p><b>Column set.</b> The view has no version field, so every run starts with a dry run of
 * {@code SELECT *}, which returns the schema for free. The columns are fingerprinted
 * ({@link BqJobsFormat#columnSetHash}) and the hash is kept in the cursor state. A required column
 * missing from the result set ends the run with one marker record ({@link BqJobsRunProblem}) that the
 * registry quarantines; the cursor does not move, so nothing is skipped while the operator fixes the
 * binding or the parser. The same holds when a load-bearing optional column the last good run saw
 * ({@link BqJobsFormat#LOAD_BEARING_OPTIONAL_COLUMNS}) is gone: a {@code COLUMN_LOST} marker, same
 * cursor rule (Q-396). New columns change the hash, are logged by name, and are ignored: the query
 * selects only the allow-listed columns, so query text, job stages and timeline are never read.
 *
 * <p><b>Binding.</b> The stream's {@code location} is required and must be the region of the datasets
 * and jobs ({@code US}, {@code EU}, {@code us-central1}); BigQuery refuses a query whose location
 * does not match the region qualifier, and that refusal becomes a {@code REGION_MISMATCH} marker.
 * The project whose jobs are read is the binding's {@code scope.project-id}; the project that runs
 * (and pays for) the query is {@code billing-project} when set, else the same project.
 */
@Component
public class BqJobsViewTransport implements LogTransport {

    /** Transport id in a stream's {@code transport:}. */
    public static final String TRANSPORT_ID = "BQ_JOBS_VIEW";

    /** Stream location key: the BigQuery location of the jobs. */
    public static final String LOCATION_KEY = "location";

    /** Stream location key: the project that runs the query, when it is not the scope project. */
    public static final String BILLING_PROJECT_KEY = "billing-project";

    /** The view lists a finished job within minutes; a poll stops this far behind the clock. */
    public static final Duration SETTLE = Duration.ofMinutes(5);

    /** Where a first poll starts when the stream may not backfill. */
    public static final Duration FIRST_RUN_LOOKBACK = Duration.ofHours(24);

    /** Rows per batch: one batch is one transaction of the ingest path and one cursor commit. */
    public static final int BATCH_ROWS = 500;

    /** Cursor state key of the last column-set hash. */
    public static final String STATE_COLUMN_HASH = "column_set_hash";

    /**
     * Cursor state key of the load-bearing optional columns the last good run saw
     * ({@link BqJobsFormat#LOAD_BEARING_OPTIONAL_COLUMNS}), comma-joined; the state holds scalars only.
     */
    public static final String STATE_LOAD_BEARING_COLUMNS = "load_bearing_columns";

    private static final Logger log = LoggerFactory.getLogger(BqJobsViewTransport.class);

    private final JobsViewGateway gateway;
    private final Function<String, Optional<String>> projectOf;

    /**
     * Creates the transport for the running service.
     *
     * @param gateway the BigQuery client
     * @param sources the effective configuration, which holds each binding's scope
     */
    @Autowired
    public BqJobsViewTransport(JobsViewGateway gateway, ObjectProvider<EffectiveLogSources> sources) {
        this(gateway, bindingId -> sources.getIfAvailable() == null ? Optional.empty()
                : sources.getIfAvailable().binding(bindingId)
                        .map(b -> b.scope().get("project-id"))
                        .map(String::valueOf));
    }

    /**
     * Creates the transport with an explicit project lookup.
     *
     * @param gateway   the BigQuery client
     * @param projectOf maps a binding id to its GCP project id
     */
    public BqJobsViewTransport(JobsViewGateway gateway, Function<String, Optional<String>> projectOf) {
        this.gateway = gateway;
        this.projectOf = projectOf;
    }

    @Override
    public TransportDescriptor descriptor() {
        return new TransportDescriptor(TRANSPORT_ID, Set.of(LOCATION_KEY), Set.of(BILLING_PROJECT_KEY),
                TransportDescriptor.CursorKind.TIME, BqJobsFormat.RETENTION, SETTLE);
    }

    @Override
    public RawBatchIterator fetch(StreamContext ctx, String partitionKey, CursorPosition cursor, FetchWindow window) {
        Target target = target(ctx);
        Instant now = ctx.clock().instant();
        Instant from = later(window.from(), now.minus(BqJobsFormat.RETENTION));
        boolean firstRun = cursor.lowWatermark() == null;
        if (firstRun && ctx.mode() != IngestMode.BACKFILL && !ctx.stream().backfillAllowed()) {
            from = later(from, window.to().minus(FIRST_RUN_LOOKBACK));
        }
        Instant to = window.to();
        if (!from.isBefore(to)) {
            return RawBatchIterator.of(List.of());
        }

        List<String> columns;
        try {
            columns = gateway.columns(new JobsViewGateway.Query(target.billing(), target.location(),
                    BqJobsQueries.columnsSql(target.project(), target.location()), Map.of()));
        } catch (JobsViewGateway.ViewFailure e) {
            if (e.kind() == JobsViewGateway.ViewFailure.Kind.LOCATION_MISMATCH) {
                log.warn("BigQuery JOBS binding {} is refused for location {}: the location does not match the region",
                        ctx.stream().bindingId(), target.location());
                return marker(ctx, target, BqJobsRunProblem.regionMismatch(target.location()), cursor, Map.of());
            }
            throw e;
        }

        String hash = BqJobsFormat.columnSetHash(columns);
        String previous = cursor.state().get(STATE_COLUMN_HASH) instanceof String s ? s : null;
        List<String> missing = BqJobsFormat.missingRequired(columns);
        if (!missing.isEmpty()) {
            log.warn("BigQuery JOBS binding {}: required column(s) {} are missing from the view; the run is quarantined",
                    ctx.stream().bindingId(), missing);
            // The recorded hash stays the last good one, so the marker names the same expected hash on every
            // poll of the same problem and the registry quarantines one record, not one per poll.
            return marker(ctx, target, BqJobsRunProblem.columnDrift(target.location(), missing, hash, previous),
                    cursor, Map.of());
        }
        List<String> presentOptional = BqJobsFormat.presentLoadBearing(columns);
        List<String> lost = BqJobsFormat.lostLoadBearing(previousLoadBearing(cursor), columns);
        if (!lost.isEmpty()) {
            log.warn("BigQuery JOBS binding {}: column(s) {} the previous run saw are gone from the view; the run is quarantined",
                    ctx.stream().bindingId(), lost);
            // As for a missing required column: the recorded state stays the last good one, so the marker is the
            // same bytes on every poll of the same problem.
            return marker(ctx, target, BqJobsRunProblem.columnLost(target.location(), lost, hash, previous),
                    cursor, Map.of());
        }
        if (previous != null && !previous.equals(hash)) {
            log.warn("BigQuery JOBS binding {}: the view's column set changed (new columns {}); they are ignored",
                    ctx.stream().bindingId(), unknownColumns(columns));
        }

        List<String> projection = columns.stream()
                .filter(BqJobsFormat.KEPT_COLUMNS::contains)
                .filter(BqJobsFormat::isPlainColumnName)
                .toList();
        Map<String, Instant> parameters = new LinkedHashMap<>();
        parameters.put("from", from);
        parameters.put("to", to);
        parameters.put("creation_floor", from.minus(BqJobsQueries.MAX_JOB_RUNTIME));
        var rows = gateway.rows(new JobsViewGateway.Query(target.billing(), target.location(),
                BqJobsQueries.rowsSql(target.project(), target.location(), projection), parameters));
        Map<String, Object> state = new LinkedHashMap<>(cursor.state());
        state.put(STATE_COLUMN_HASH, hash);
        state.put(STATE_LOAD_BEARING_COLUMNS, String.join(",", presentOptional));
        return new RowBatches(ctx, target, rows, from, to, cursor.highWatermark(), state);
    }

    /** The load-bearing optional columns the last good run saw; empty on a first run or before this state existed. */
    private static List<String> previousLoadBearing(CursorPosition cursor) {
        if (!(cursor.state().get(STATE_LOAD_BEARING_COLUMNS) instanceof String s) || s.isBlank()) {
            return List.of();
        }
        return List.of(s.split(","));
    }

    /** Names of columns that are neither kept, dropped nor ignored: the ones a new column set added. */
    private static List<String> unknownColumns(List<String> columns) {
        TreeSet<String> out = new TreeSet<>();
        for (String c : columns) {
            if (!BqJobsFormat.KEPT_COLUMNS.contains(c) && !BqJobsFormat.DROPPED_COLUMNS.contains(c)
                    && !BqJobsFormat.IGNORED_COLUMNS.contains(c) && !BqJobsFormat.REQUIRED_COLUMNS.contains(c)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    private RawBatchIterator marker(StreamContext ctx, Target target, ObjectNode problem, CursorPosition cursor,
                                    Map<String, Object> stateUpdate) {
        Map<String, Object> state = new LinkedHashMap<>(cursor.state());
        state.putAll(stateUpdate);
        RawRecord record = ctx.record(RawRef.of(TRANSPORT_ID, "bigquery://projects/" + target.project()
                + "/locations/" + target.location() + "/jobs-view", -1)).json(problem).build();
        // The cursor stays where it was: nothing is read, so nothing may be skipped.
        return RawBatchIterator.of(List.of(new RawBatch("", List.of(record), Optional.empty(),
                new CursorPosition(cursor.lowWatermark(), cursor.highWatermark(), state))));
    }

    private Target target(StreamContext ctx) {
        String bindingId = ctx.stream().bindingId();
        String project = BqJobsQueries.requireProject(projectOf.apply(bindingId).orElseThrow(() ->
                new IllegalStateException("binding " + bindingId + " has no scope.project-id")));
        String location = BqJobsQueries.requireLocation(ctx.stream().location().get(LOCATION_KEY));
        String billing = ctx.stream().location().get(BILLING_PROJECT_KEY);
        return new Target(project, location, billing == null || billing.isBlank() ? project
                : BqJobsQueries.requireProject(billing));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private record Target(String project, String location, String billing) {
    }

    /** Batches of a row stream; the last batch moves the watermark to the end of the window. */
    private static final class RowBatches implements RawBatchIterator {

        private final StreamContext ctx;
        private final Target target;
        private final java.util.Iterator<ObjectNode> rows;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final Map<String, Object> state;
        private Instant high;
        private Instant low;
        private boolean done;

        RowBatches(StreamContext ctx, Target target, java.util.Iterator<ObjectNode> rows, Instant windowStart,
                   Instant windowEnd, Instant high, Map<String, Object> state) {
            this.ctx = ctx;
            this.target = target;
            this.rows = rows;
            this.windowStart = windowStart;
            this.low = windowStart;
            this.windowEnd = windowEnd;
            this.high = high;
            this.state = state;
        }

        @Override
        public boolean hasNext() {
            return !done;
        }

        @Override
        public RawBatch next() {
            if (done) {
                throw new NoSuchElementException();
            }
            List<RawRecord> records = new ArrayList<>();
            Instant lastEnd = null;
            while (records.size() < BATCH_ROWS && rows.hasNext()) {
                ObjectNode row = rows.next();
                if (!row.has(BqJobsFormat.LOCATION_COLUMN)) {
                    row.put(BqJobsFormat.LOCATION_COLUMN, target.location());
                }
                Instant end = instant(row.get("end_time"));
                if (end != null) {
                    lastEnd = end;
                    if (high == null || end.isAfter(high)) {
                        high = end;
                    }
                }
                records.add(ctx.record(new RawRef(TRANSPORT_ID, "bigquery://projects/" + target.project()
                        + "/locations/" + target.location() + "/jobs/" + text(row.get("job_id")), -1,
                        text(row.get("end_time")))).json(row).build());
            }
            boolean last = !rows.hasNext();
            done = last;
            if (last) {
                low = windowEnd;
            } else if (lastEnd != null) {
                low = lastEnd;
            }
            return new RawBatch("", records, Optional.empty(), new CursorPosition(low, high, state));
        }

        private static String text(JsonNode node) {
            return node == null || !node.isTextual() ? "unknown" : node.asText();
        }

        private static Instant instant(JsonNode node) {
            if (node == null || !node.isTextual()) {
                return null;
            }
            try {
                return Instant.parse(node.asText());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
