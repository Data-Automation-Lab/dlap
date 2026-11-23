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
import com.google.logging.v2.LogEntry;
import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pulls Cloud Audit Logs through the Cloud Logging API ({@code entries.list}), transport id
 * {@value #ID}, envelope {@code api-proto}.
 *
 * <p>One poll reads the window {@code [from, to)} of one project with the filter
 * {@code logName:"cloudaudit.googleapis.com" AND timestamp>="from" AND timestamp<"to"} (plus the
 * stream's {@code filter} location key), ordered by timestamp, one page per request. Every request
 * first takes a permit of the project's {@link RequestRateLimiter}: the API allows 60 requests per
 * minute per project, so all streams of a project share the limit. Each page is one batch; its cursor
 * moves the low watermark to the page's newest timestamp (entries with that timestamp may continue on
 * the next page, and event-id dedup absorbs the re-read), and the last page moves it to the end of the
 * window.
 *
 * <p>Location keys: {@code project} (default: the binding's {@code scope.project-id}),
 * {@code filter} (an extra Logging query clause), {@code page-size} (default 1000). Credentials come
 * from the stream's {@code credentials-ref} ({@link GcpCredentials}). The project must grant
 * {@code roles/logging.viewer}, and {@code roles/logging.privateLogViewer} for Data Access logs.
 */
public class LoggingApiTransport implements LogTransport {

    /** Transport id. */
    public static final String ID = "LOGGING_API";

    /** Default page size. */
    static final int DEFAULT_PAGE_SIZE = 1000;

    private static final TransportDescriptor DESCRIPTOR = new TransportDescriptor(ID, Set.of(),
            Set.of("project", "filter", "page-size"), TransportDescriptor.CursorKind.TIME, Duration.ofHours(1),
            Duration.ofMinutes(2));

    /** One page of {@code entries.list}. */
    record Page(List<LogEntry> entries, String nextPageToken) {
    }

    /** Fetches pages; the production implementation wraps the GAPIC {@code LoggingClient}. */
    interface EntriesPager {
        /**
         * Reads one page.
         *
         * @param context   the stream, for its credentials
         * @param project   the project id
         * @param filter    the Logging filter
         * @param pageToken the page token, {@code ""} for the first page
         * @param pageSize  the page size
         * @return the page
         */
        Page page(StreamContext context, String project, String filter, String pageToken, int pageSize);
    }

    private final EntriesPager pager;
    private final RequestRateLimiter limiter;

    /**
     * Creates the transport.
     *
     * @param pager   how pages are fetched
     * @param limiter the per-project request limiter
     */
    public LoggingApiTransport(EntriesPager pager, RequestRateLimiter limiter) {
        this.pager = Objects.requireNonNull(pager, "pager");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    public TransportDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor, FetchWindow window) {
        String project = project(context);
        String filter = filter(context, window);
        int pageSize = pageSize(context);
        Double rps = context.stream().requestsPerSecond();
        return new RawBatchIterator() {
            private String token = "";
            private boolean done;
            private Instant high = cursor.highWatermark();
            private long index;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public RawBatch next() {
                if (done) {
                    throw new NoSuchElementException();
                }
                try {
                    limiter.acquire(project, rps);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the Cloud Logging quota", e);
                }
                Page page = pager.page(context, project, filter, token, pageSize);
                List<RawRecord> records = new ArrayList<>();
                Instant newest = null;
                for (LogEntry e : page.entries()) {
                    RawRef ref = new RawRef(ID, "logging://projects/" + project + "/entries", index++, token);
                    records.add(context.record(ref).text(LogEntryJson.print(e)).build());
                    if (e.hasTimestamp()) {
                        Instant t = instant(e.getTimestamp());
                        newest = newest == null || t.isAfter(newest) ? t : newest;
                    }
                }
                if (newest != null && (high == null || newest.isAfter(high))) {
                    high = newest;
                }
                token = page.nextPageToken() == null ? "" : page.nextPageToken();
                done = token.isEmpty();
                Instant low = done ? window.to() : (newest != null ? newest : window.from());
                Map<String, Object> state = new TreeMap<>();
                if (!done) {
                    state.put("pageToken", token);
                }
                return new RawBatch(partitionKey, records, Optional.empty(),
                        new CursorPosition(low, high == null ? low : high, state));
            }
        };
    }

    static String project(StreamContext context) {
        String p = context.stream().location().get("project");
        if (p == null || p.isBlank()) {
            p = context.stream().parse().assetIdHints().project();
        }
        if (p == null || p.isBlank()) {
            throw new IllegalStateException("stream " + context.stream().bindingId() + "/" + context.stream().streamId()
                    + " has no project: set scope.project-id or the location key project");
        }
        return p.strip();
    }

    static String filter(StreamContext context, FetchWindow window) {
        String base = "logName:\"cloudaudit.googleapis.com\" AND timestamp>=\"" + window.from() + "\" AND timestamp<\""
                + window.to() + "\"";
        String extra = context.stream().location().get("filter");
        return extra == null || extra.isBlank() ? base : base + " AND (" + extra.strip() + ")";
    }

    static int pageSize(StreamContext context) {
        String s = context.stream().location().get("page-size");
        if (s == null || s.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int n = Integer.parseInt(s.strip());
            return Math.max(1, Math.min(n, 10_000));
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    private static Instant instant(Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }
}
