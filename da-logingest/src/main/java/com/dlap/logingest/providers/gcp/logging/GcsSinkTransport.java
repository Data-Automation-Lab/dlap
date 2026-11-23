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
import com.dlap.logingest.parse.core.mapping.Framing;
import com.dlap.logingest.parse.core.mapping.RecordFraming;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the files a Cloud Storage Log Router sink writes, transport id {@value #ID}, envelope
 * {@code json} (https://docs.cloud.google.com/logging/docs/export/storage).
 *
 * <p>A sink writes {@code BUCKET/LOG_ID/YYYY/MM/DD/HH:MM:SS_HH:MM:SS_Sn.json} once an hour, and late
 * entries of an hour into addendum shards {@code ..._An:<unix seconds>.json}. The log id is a path:
 * {@code cloudaudit.googleapis.com/data_access} nests two directories. Files hold "a list of LogEntry
 * objects", as a JSON array or newline-delimited; both are read, and a gzip file is unpacked. Entries
 * in a shard are not sorted, and overlapping sinks duplicate them; the event id absorbs both.
 *
 * <p>Each log id is a partition with its own cursor. A poll lists the day folders from the window
 * start minus {@code late-shard-lookback} (default one day, so addenda written later are found) to
 * the window end, and returns one batch per shard object, oldest hour first, regular shard before its
 * addenda. A shard already read in this version (its generation) is skipped
 * ({@code processed_object}). A final empty batch moves the low watermark to the window end.
 *
 * <p>Location keys: {@code bucket} (required), {@code prefix} (a folder in the bucket), {@code log-ids}
 * (comma separated, default the four audit logs), {@code late-shard-lookback} (ISO-8601 duration),
 * {@code max-object-bytes} (default 256 MiB; a larger shard is skipped with an error log) and
 * {@code project} (the bucket's project, for the client). The sink bucket should be writable only by
 * the sink's writer identity (Q-187, threat B7-S1).
 */
public class GcsSinkTransport implements LogTransport {

    /** Transport id. */
    public static final String ID = "GCS_SINK";

    /** The audit log ids, as sink folders. */
    static final List<String> AUDIT_LOG_IDS = List.of(
            "cloudaudit.googleapis.com/activity", "cloudaudit.googleapis.com/data_access",
            "cloudaudit.googleapis.com/system_event", "cloudaudit.googleapis.com/policy");

    static final Duration DEFAULT_LATE_LOOKBACK = Duration.ofDays(1);
    static final long DEFAULT_MAX_OBJECT_BYTES = 256L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(GcsSinkTransport.class);
    private static final Pattern SHARD = Pattern.compile(
            "(\\d{4})/(\\d{2})/(\\d{2})/(\\d{2}):(\\d{2}):(\\d{2})_(\\d{2}):(\\d{2}):(\\d{2})_([SA])(\\d+)(?::(\\d+))?\\.json(\\.gz)?");
    private static final Set<Framing> FRAMING = Set.of(Framing.JSON_ARRAY, Framing.JSON_LINES);
    private static final TransportDescriptor DESCRIPTOR = new TransportDescriptor(ID, Set.of("bucket"),
            Set.of("prefix", "log-ids", "late-shard-lookback", "max-object-bytes", "project"),
            TransportDescriptor.CursorKind.OBJECT, Duration.ofDays(1), Duration.ZERO);

    /** One object of the sink bucket. */
    record ShardObject(String name, String generation, long size) {
    }

    /** Lists and reads sink objects; the production implementation wraps the Cloud Storage client. */
    interface ShardStore {
        /**
         * Lists objects under a prefix.
         *
         * @param context the stream, for its credentials
         * @param bucket  the bucket
         * @param prefix  the prefix
         * @return the objects
         */
        List<ShardObject> list(StreamContext context, String bucket, String prefix);

        /**
         * Reads one object version.
         *
         * @param context the stream
         * @param bucket  the bucket
         * @param object  the object
         * @return its bytes
         */
        byte[] read(StreamContext context, String bucket, ShardObject object);
    }

    /** A shard name, parsed. */
    record Shard(ShardObject object, Instant start, Instant end, boolean addendum, int number, long writtenAt) {
    }

    private final ShardStore store;

    /**
     * Creates the transport.
     *
     * @param store the object store
     */
    public GcsSinkTransport(ShardStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public TransportDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> partitions(StreamContext context) {
        String ids = context.stream().location().get("log-ids");
        if (ids == null || ids.isBlank()) {
            return AUDIT_LOG_IDS;
        }
        return Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor, FetchWindow window) {
        String bucket = context.stream().location().get("bucket");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("GCS_SINK needs the location key bucket");
        }
        String folder = folder(context.stream().location().get("prefix"), partitionKey);
        Duration lookback = duration(context.stream().location().get("late-shard-lookback"), DEFAULT_LATE_LOOKBACK);
        long maxBytes = bytes(context.stream().location().get("max-object-bytes"), DEFAULT_MAX_OBJECT_BYTES);
        Instant from = window.from().minus(lookback);
        List<Shard> shards = new ArrayList<>();
        for (LocalDate d = LocalDate.ofInstant(from, ZoneOffset.UTC); !d.isAfter(LocalDate.ofInstant(window.to(), ZoneOffset.UTC));
             d = d.plusDays(1)) {
            String day = folder + String.format("%04d/%02d/%02d/", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
            for (ShardObject o : store.list(context, bucket.strip(), day)) {
                parse(o, folder).filter(s -> s.start().isBefore(window.to()) && !s.end().isBefore(from))
                        .ifPresent(shards::add);
            }
        }
        shards.sort(Comparator.comparing(Shard::start).thenComparing(Shard::addendum)
                .thenComparingLong(Shard::writtenAt).thenComparingInt(Shard::number));
        return new RawBatchIterator() {
            private int next;
            private boolean finished;
            private Instant low = cursor.lowWatermark();
            private Instant high = cursor.highWatermark();

            @Override
            public boolean hasNext() {
                return !finished;
            }

            @Override
            public RawBatch next() {
                while (next < shards.size()) {
                    Shard s = shards.get(next++);
                    if (context.alreadyProcessed(s.object().name(), s.object().generation())) {
                        continue;
                    }
                    if (s.object().size() > maxBytes) {
                        log.error("Sink shard gs://{}/{} is {} bytes, above max-object-bytes {}; skipped",
                                bucket, s.object().name(), s.object().size(), maxBytes);
                        continue;
                    }
                    List<RawRecord> records = new ArrayList<>();
                    String uri = "gs://" + bucket.strip() + "/" + s.object().name();
                    try {
                        String text = text(store.read(context, bucket.strip(), s.object()), maxBytes,
                                s.object().name());
                        for (RecordFraming.Piece p : RecordFraming.split(text, FRAMING, null)) {
                            RawRecord.Builder b = context.record(new RawRef(ID, uri, p.index(),
                                    s.object().generation()));
                            records.add(p.text() != null ? b.text(p.text()).build() : b.json(p.json()).build());
                        }
                    } catch (RuntimeException e) {
                        // The runner counts the failure; at the cap it quarantines the shard and moves on (Q-665).
                        throw new ObjectReadException(new RawBatch.ObjectRef(s.object().name(),
                                s.object().generation()), uri, e);
                    }
                    low = max(low, s.end());
                    high = max(high, s.end());
                    Map<String, Object> state = new TreeMap<>();
                    state.put("lastObject", s.object().name());
                    return new RawBatch(partitionKey, records,
                            Optional.of(new RawBatch.ObjectRef(s.object().name(), s.object().generation())),
                            new CursorPosition(low, high, state));
                }
                if (finished) {
                    throw new NoSuchElementException();
                }
                finished = true;
                low = max(low, window.to());
                high = max(high, low);
                return new RawBatch(partitionKey, List.of(), Optional.empty(), new CursorPosition(low, high, Map.of()));
            }
        };
    }

    /**
     * Parses a shard object name under a log id folder.
     *
     * @param o      the object
     * @param folder the log id folder, ending in {@code /}
     * @return the shard, or empty for another object
     */
    static Optional<Shard> parse(ShardObject o, String folder) {
        if (!o.name().startsWith(folder)) {
            return Optional.empty();
        }
        Matcher m = SHARD.matcher(o.name().substring(folder.length()));
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            LocalDate day = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            LocalTime start = LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            LocalTime end = LocalTime.of(Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)), Integer.parseInt(m.group(9)));
            Instant s = day.atTime(start).toInstant(ZoneOffset.UTC);
            Instant e = day.atTime(end).toInstant(ZoneOffset.UTC);
            if (e.isBefore(s)) {
                e = e.plus(Duration.ofDays(1));
            }
            boolean addendum = "A".equals(m.group(10));
            long written = m.group(12) == null ? 0 : Long.parseLong(m.group(12));
            return Optional.of(new Shard(o, s, e, addendum, Integer.parseInt(m.group(11)), written));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    static String folder(String prefix, String logId) {
        String p = prefix == null ? "" : prefix.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        String id = logId.strip();
        return p + (id.endsWith("/") ? id : id + "/");
    }

    private static String text(byte[] bytes, long maxBytes, String name) {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IllegalStateException("sink shard " + name + " unpacks to more than max-object-bytes");
                    }
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("sink shard " + name + " is not a readable gzip file", e);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
    }

    static Duration duration(String text, Duration fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            Duration d = Duration.parse(text.strip());
            return d.isNegative() ? fallback : d;
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    static long bytes(String text, long fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            long v = Long.parseLong(text.strip());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
