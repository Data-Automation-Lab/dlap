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

package com.dlap.logingest.providers.gcp.logging;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads the tables a BigQuery Log Router sink writes, transport id {@value #ID}, envelope
 * {@code bq-sink-row} (https://docs.cloud.google.com/logging/docs/export/bigquery).
 *
 * <p>A sink writes one table per log, named from the log id with every non-alphanumeric character
 * as {@code _}: {@code cloudaudit_googleapis_com_data_access} and so on. The table is partitioned
 * (one table, name as is) or date-sharded ({@code ..._YYYYMMDD}, one table per day). Each table name
 * is a partition with its own cursor. A poll lists the dataset, picks the table itself when it exists
 * and otherwise the day shards that overlap the window, and runs
 * {@code SELECT * ... WHERE timestamp >= @from AND timestamp < @to ORDER BY timestamp} on each, one
 * batch per result page. The sink's {@code export_errors} table (a row per entry the sink could not
 * write, a format-broke signal) is read the same way unless {@code include-export-errors} is
 * {@code false}; its rows reach the quarantine as {@code UNKNOWN_ENVELOPE}.
 *
 * <p>Queries are billed: on-demand pricing bills at least 10 MB per table read, so poll a sink stream
 * every few minutes, not every minute ({@code poll-interval}). Results come through the BigQuery REST
 * API, not the Storage Read API, so the JVM needs no {@code --add-opens} for Arrow (Q-293).
 *
 * <p>Location keys: {@code dataset} (required), {@code project} (default: the binding's
 * {@code scope.project-id}), {@code tables} (comma separated base names, default the four audit
 * tables), {@code include-export-errors} ({@code true} by default), {@code page-size} (default 1000).
 * Identifiers are checked against BigQuery's naming rules before they enter a query.
 */
public class BqSinkTransport implements LogTransport {

    /** Transport id. */
    public static final String ID = "BQ_SINK";

    /** The audit tables of a sink. */
    static final List<String> AUDIT_TABLES = List.of(
            "cloudaudit_googleapis_com_activity", "cloudaudit_googleapis_com_data_access",
            "cloudaudit_googleapis_com_system_event", "cloudaudit_googleapis_com_policy");

    /** The sink's error table. */
    static final String EXPORT_ERRORS = "export_errors";

    private static final Pattern PROJECT = Pattern.compile("(?:[a-z0-9][a-z0-9.-]*[a-z0-9]:)?[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final Pattern DATASET = Pattern.compile("[A-Za-z0-9_]{1,1024}");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_]{1,1024}");
    private static final Pattern SHARD_SUFFIX = Pattern.compile("_(\\d{8})");
    private static final TransportDescriptor DESCRIPTOR = new TransportDescriptor(ID, Set.of("dataset"),
            Set.of("project", "tables", "include-export-errors", "page-size"), TransportDescriptor.CursorKind.TIME,
            Duration.ofHours(1), Duration.ofMinutes(2));

    /** One result page: rows as the envelope's JSON objects, and the page token that fetched it. */
    record Page(List<ObjectNode> rows, String token) {
    }

    /** Lists sink tables and runs the window queries; the production implementation wraps the BigQuery client. */
    interface SinkTables {
        /**
         * Lists the tables of a dataset.
         *
         * @param context the stream, for its credentials
         * @param project the project
         * @param dataset the dataset
         * @return table ids
         */
        List<String> list(StreamContext context, String project, String dataset);

        /**
         * Runs one window query.
         *
         * @param context  the stream
         * @param project  the project that runs the query
         * @param sql      the query, with {@code @from} and {@code @to} parameters
         * @param from     the window start
         * @param to       the window end
         * @param pageSize rows per page
         * @return the pages, fetched lazily
         */
        Iterator<Page> query(StreamContext context, String project, String sql, Instant from, Instant to, int pageSize);
    }

    private final SinkTables tables;

    /**
     * Creates the transport.
     *
     * @param tables the BigQuery access
     */
    public BqSinkTransport(SinkTables tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    @Override
    public TransportDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> partitions(StreamContext context) {
        String configured = context.stream().location().get("tables");
        List<String> out = new ArrayList<>(configured == null || configured.isBlank() ? AUDIT_TABLES
                : Arrays.stream(configured.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        out.remove(EXPORT_ERRORS);
        if (!"false".equalsIgnoreCase(context.stream().location().getOrDefault("include-export-errors", "true").strip())) {
            out.add(EXPORT_ERRORS);
        }
        return List.copyOf(out);
    }

    @Override
    public RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor, FetchWindow window) {
        String project = require(LoggingApiTransport.project(context).toLowerCase(java.util.Locale.ROOT), PROJECT, "project");
        String dataset = require(context.stream().location().getOrDefault("dataset", "").strip(), DATASET, "dataset");
        String base = require(partitionKey, TABLE, "table");
        int pageSize = LoggingApiTransport.pageSize(context);
        List<String> chosen = select(tables.list(context, project, dataset), base, window);
        boolean errors = EXPORT_ERRORS.equals(base);
        return new RawBatchIterator() {
            private int table;
            private Iterator<Page> pages;
            private String current;
            private boolean finished;
            private long index;
            private Instant low = cursor.lowWatermark();
            private Instant high = cursor.highWatermark();

            @Override
            public boolean hasNext() {
                return !finished;
            }

            @Override
            public RawBatch next() {
                while (pages == null || !pages.hasNext()) {
                    if (table >= chosen.size()) {
                        if (finished) {
                            throw new NoSuchElementException();
                        }
                        finished = true;
                        low = later(low, window.to());
                        high = later(high, low);
                        return new RawBatch(partitionKey, List.of(), Optional.empty(), new CursorPosition(low, high, Map.of()));
                    }
                    current = chosen.get(table++);
                    String sql = "SELECT * FROM `" + project + "." + dataset + "." + current + "` WHERE timestamp >= @from "
                            + "AND timestamp < @to ORDER BY timestamp" + (errors ? "" : ", insertId");
                    pages = tables.query(context, project, sql, window.from(), window.to(), pageSize);
                }
                Page page = pages.next();
                List<RawRecord> records = new ArrayList<>();
                Instant newest = null;
                String uri = "bq://" + project + "/" + dataset + "/" + current;
                for (ObjectNode row : page.rows()) {
                    records.add(context.record(new RawRef(ID, uri, index++, page.token())).json(row).build());
                    Instant t = time(row.get("timestamp"));
                    if (t != null && (newest == null || t.isAfter(newest))) {
                        newest = t;
                    }
                }
                if (newest != null) {
                    low = later(low, newest);
                    high = later(high, newest);
                }
                Map<String, Object> state = new TreeMap<>();
                state.put("table", current);
                return new RawBatch(partitionKey, records, Optional.empty(),
                        new CursorPosition(low == null ? window.from() : low, high == null ? window.from() : high, state));
            }
        };
    }

    /**
     * The tables a window reads for one base name: the table itself when the sink writes a partitioned
     * table, otherwise the day shards that overlap the window, oldest first.
     *
     * @param existing the dataset's table ids
     * @param base     the base name
     * @param window   the window
     * @return table ids
     */
    static List<String> select(List<String> existing, String base, FetchWindow window) {
        if (existing.contains(base)) {
            return List.of(base);
        }
        LocalDate first = LocalDate.ofInstant(window.from(), ZoneOffset.UTC);
        LocalDate last = LocalDate.ofInstant(window.to(), ZoneOffset.UTC);
        List<String> out = new ArrayList<>();
        for (String t : existing.stream().sorted().toList()) {
            if (!t.startsWith(base) || !SHARD_SUFFIX.matcher(t.substring(base.length())).matches()) {
                continue;
            }
            String d = t.substring(base.length() + 1);
            LocalDate day = LocalDate.of(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(4, 6)),
                    Integer.parseInt(d.substring(6, 8)));
            if (!day.isBefore(first) && !day.isAfter(last)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String require(String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalStateException("BQ_SINK " + what + " is not a valid BigQuery " + what + " id");
        }
        return value;
    }

    private static Instant time(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
    }
}
