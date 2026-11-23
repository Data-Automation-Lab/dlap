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

package com.dlap.logingest.providers.aws.s3;

import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the two S3 log transports share: object listing by name time, one batch per object, object
 * checkpoints, and the handling of objects that cannot be read as text.
 *
 * <p>A poll lists the candidate objects whose name time lies in {@code [from - late-object-lookback,
 * to)}, reads them oldest first (key order on a tie), skips the versions {@code processed_object}
 * already holds, and returns one batch per object with its records. Each batch moves the low
 * watermark to its object's name time; a final empty batch moves it to the window end, so the next
 * poll starts at {@code to - overlap - late-object-lookback} and a file written late is still found.
 *
 * <p>An object that cannot be read as UTF-8 text (a gzip file that does not unpack, bytes that are not
 * UTF-8, more than {@code max-object-bytes}) is delivered as one record holding its bytes (at most
 * {@code max-record-bytes + 1} of them), which the registry quarantines as {@code INVALID_ENCODING},
 * {@code INVALID_JSON} or {@code OVERSIZE_RECORD}; {@code raw_ref} names the object version for the
 * exact content. A NUL in those bytes needs no handling here: the quarantine sinks remove it from the
 * excerpt (Q-661). The batch then commits like any other, so a malformed object is quarantined once
 * and never retried. A failure to list or read (network, permissions) is thrown as an
 * {@link ObjectReadException}: the stream runner keeps the cursor and the next poll tries again, up
 * to {@code dlap.logingest.transports.max-read-attempts} times, after which it quarantines the object
 * as {@code READ_FAILED} and moves on (Q-665).
 *
 * <p>Location keys of both transports: {@code bucket} (required), {@code prefix},
 * {@code late-object-lookback} (ISO-8601, default {@value #DEFAULT_LATE_LOOKBACK_TEXT}),
 * {@code max-object-bytes} (default 64 MiB; a value below the binding's {@code max-record-bytes} is raised
 * to it, so an oversize object is always quarantined as {@code OVERSIZE_RECORD}, Q-705) and the client
 * keys of {@link SdkS3LogStore} ({@code region}, {@code endpoint}, {@code path-style}, {@code profile}, and for an assumed role
 * {@code external-id} and {@code sts-endpoint}).
 */
abstract class AbstractS3LogTransport implements LogTransport {

    /** The default lookback for objects written after a later object was already read. */
    static final String DEFAULT_LATE_LOOKBACK_TEXT = "PT1H";

    static final Duration DEFAULT_LATE_LOOKBACK = Duration.parse(DEFAULT_LATE_LOOKBACK_TEXT);
    static final long DEFAULT_MAX_OBJECT_BYTES = 64L * 1024 * 1024;

    /** Location keys every S3 log transport reads besides its own. */
    static final List<String> COMMON_LOCATION = List.of("prefix", "late-object-lookback", "max-object-bytes",
            SdkS3LogStore.REGION, SdkS3LogStore.ENDPOINT, SdkS3LogStore.PATH_STYLE, SdkS3LogStore.PROFILE,
            SdkS3LogStore.EXTERNAL_ID, SdkS3LogStore.STS_ENDPOINT);

    private static final Logger log = LoggerFactory.getLogger(AbstractS3LogTransport.class);
    private static final int GZIP_MAGIC_1 = 0x1f;
    private static final int GZIP_MAGIC_2 = 0x8b;

    /** An object to read and the time its name carries. */
    record Candidate(S3LogObject object, Instant time) {
    }

    /**
     * The text of an object, or why it has none.
     *
     * @param text    the UTF-8 text, or {@code null}
     * @param problem why the object is malformed, or {@code null}
     * @param sample  the bytes to quarantine for a malformed object, or {@code null}
     */
    record Unpacked(String text, String problem, byte[] sample) {

        static Unpacked text(String text) {
            return new Unpacked(text, null, null);
        }

        static Unpacked malformed(String problem, byte[] sample) {
            return new Unpacked(null, problem, sample);
        }

        boolean ok() {
            return text != null;
        }
    }

    /** The store the transport lists and reads. */
    protected final S3LogStore store;

    /**
     * Creates the transport.
     *
     * @param store the object store
     */
    protected AbstractS3LogTransport(S3LogStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * The envelope the transport's records are in; a stream that names another is refused.
     *
     * @return the envelope id
     */
    protected abstract String envelope();

    /**
     * The objects of one partition whose name time lies in {@code [from, to)}, in any order.
     *
     * @param context   the stream
     * @param bucket    the bucket
     * @param partition the partition
     * @param from      the earliest name time, inclusive
     * @param to        the latest name time, exclusive
     * @return the candidates
     */
    protected abstract List<Candidate> candidates(StreamContext context, String bucket, String partition, Instant from,
                                                  Instant to);

    /**
     * Splits the text of one object into records.
     *
     * @param context the stream
     * @param uri     the object's {@code s3://} URI, for {@link RawRef}
     * @param object  the object
     * @param text    its text
     * @return the records
     */
    protected abstract List<RawRecord> split(StreamContext context, String uri, S3LogObject object, String text);

    @Override
    public RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor, FetchWindow window) {
        String id = descriptor().transportId();
        if (!envelope().equals(context.stream().envelope())) {
            throw new IllegalStateException(id + " delivers envelope " + envelope() + ", not "
                    + context.stream().envelope());
        }
        String bucket = bucket(context);
        Duration lookback = duration(location(context, "late-object-lookback"), DEFAULT_LATE_LOOKBACK);
        long maxObjectBytes = bytes(location(context, "max-object-bytes"), DEFAULT_MAX_OBJECT_BYTES);
        Instant from = window.from().minus(lookback);
        List<Candidate> objects = new ArrayList<>(candidates(context, bucket, partitionKey, from, window.to()));
        objects.sort(Comparator.comparing(Candidate::time).thenComparing(c -> c.object().key()));
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
                while (next < objects.size()) {
                    Candidate c = objects.get(next++);
                    S3LogObject o = c.object();
                    if (context.alreadyProcessed(o.key(), o.etag())) {
                        continue;
                    }
                    List<RawRecord> records;
                    try {
                        records = records(context, bucket, o, maxObjectBytes);
                    } catch (RuntimeException e) {
                        throw new ObjectReadException(new RawBatch.ObjectRef(o.key(), o.etag()),
                                "s3://" + bucket + "/" + o.key(), e);
                    }
                    low = max(low, c.time());
                    high = max(high, c.time());
                    Map<String, Object> state = new TreeMap<>();
                    state.put("lastObject", o.key());
                    return new RawBatch(partitionKey, records, Optional.of(new RawBatch.ObjectRef(o.key(), o.etag())),
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
     * Reads one object into records, or into the one record that quarantines it.
     *
     * @param context        the stream
     * @param bucket         the bucket
     * @param object         the object
     * @param maxObjectBytes the most bytes an object may hold, packed or unpacked
     * @return the records
     */
    List<RawRecord> records(StreamContext context, String bucket, S3LogObject object, long maxObjectBytes) {
        String uri = "s3://" + bucket + "/" + object.key();
        int sampleBytes = sampleBytes(context);
        // An object that small is still one readable record, so max-object-bytes never goes below the
        // record limit: an oversize object then always yields a sample above the record limit and is
        // quarantined as OVERSIZE_RECORD, never as INVALID_JSON (Q-705).
        long limit = effectiveMaxObjectBytes(maxObjectBytes, context);
        Unpacked unpacked;
        if (object.size() > limit) {
            unpacked = Unpacked.malformed("the object is " + object.size() + " bytes, above max-object-bytes "
                    + limit, store.read(context, bucket, object, sampleBytes));
        } else {
            unpacked = unpack(store.read(context, bucket, object, limit), limit, sampleBytes);
        }
        if (unpacked.ok()) {
            return split(context, uri, object, unpacked.text());
        }
        log.warn("{} object {} cannot be read as text ({}); it is quarantined as one record",
                descriptor().transportId(), uri, unpacked.problem());
        return List.of(quarantine(context, uri, object, unpacked.sample()));
    }

    /**
     * The record that carries a whole malformed object to the quarantine.
     *
     * @param context the stream
     * @param uri     the object URI
     * @param object  the object
     * @param sample  its first bytes
     * @return the record, index {@code -1} (the whole object)
     */
    RawRecord quarantine(StreamContext context, String uri, S3LogObject object, byte[] sample) {
        return context.record(new RawRef(descriptor().transportId(), uri, -1, object.etag()))
                .observedTime(object.lastModified())
                .bytes(sample)
                .build();
    }

    /**
     * Starts a record of one piece of an object.
     *
     * @param context the stream
     * @param uri     the object URI
     * @param object  the object
     * @param index   the piece's index: array element or line number
     * @return the builder; set the payload and build
     */
    RawRecord.Builder piece(StreamContext context, String uri, S3LogObject object, long index) {
        return context.record(new RawRef(descriptor().transportId(), uri, index, object.etag()))
                .observedTime(object.lastModified());
    }

    /**
     * How many bytes of a malformed object to quarantine: one more than the binding's record limit,
     * so an object larger than that is quarantined as oversize, not as a truncated document.
     *
     * @param context the stream
     * @return the sample size
     */
    static int sampleBytes(StreamContext context) {
        long limit = context.stream().parse().maxRecordBytes();
        return (int) Math.min(Integer.MAX_VALUE - 16L, limit + 1);
    }

    /**
     * The object size limit in force: {@code max-object-bytes}, raised to the binding's record limit
     * when it is lower (Q-705).
     *
     * @param maxObjectBytes the configured limit
     * @param context        the stream
     * @return the larger of the two
     */
    static long effectiveMaxObjectBytes(long maxObjectBytes, StreamContext context) {
        return Math.max(maxObjectBytes, context.stream().parse().maxRecordBytes());
    }

    /**
     * Unpacks gzip and decodes UTF-8, within a size limit.
     *
     * @param raw         the object bytes
     * @param maxBytes    the most bytes the text may hold
     * @param sampleBytes the most bytes a malformed object keeps
     * @return the text, or why there is none
     */
    static Unpacked unpack(byte[] raw, long maxBytes, int sampleBytes) {
        byte[] bytes = raw;
        if (raw.length >= 2 && (raw[0] & 0xff) == GZIP_MAGIC_1 && (raw[1] & 0xff) == GZIP_MAGIC_2) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    out.write(buf, 0, n);
                    if (total > maxBytes) {
                        return Unpacked.malformed("the object unpacks to more than max-object-bytes " + maxBytes,
                                head(out.toByteArray(), sampleBytes));
                    }
                }
                bytes = out.toByteArray();
            } catch (IOException e) {
                return Unpacked.malformed("the object is not a readable gzip file", head(raw, sampleBytes));
            }
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return Unpacked.text(text);
        } catch (CharacterCodingException e) {
            return Unpacked.malformed("the object is not UTF-8 text", head(bytes, sampleBytes));
        }
    }

    private static byte[] head(byte[] bytes, int n) {
        return bytes.length <= n ? bytes : Arrays.copyOf(bytes, n);
    }

    /**
     * The required bucket of a stream.
     *
     * @param context the stream
     * @return the bucket name
     * @throws IllegalStateException when the stream names none
     */
    String bucket(StreamContext context) {
        String bucket = location(context, "bucket");
        if (bucket == null) {
            throw new IllegalStateException(descriptor().transportId() + " needs the location key bucket");
        }
        return bucket;
    }

    /**
     * A location key, stripped.
     *
     * @param context the stream
     * @param key     the key
     * @return the value, or {@code null} when absent or blank
     */
    static String location(StreamContext context, String key) {
        String v = context.stream().location().get(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /**
     * A key prefix as a folder: no leading {@code /}, a trailing {@code /} unless empty.
     *
     * @param prefix the configured prefix, or {@code null}
     * @return the folder
     */
    static String folder(String prefix) {
        String p = prefix == null ? "" : prefix.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }

    /**
     * Parses an ISO-8601 duration.
     *
     * @param text     the text, or {@code null}
     * @param fallback the value for a missing, negative or bad text
     * @return the duration
     */
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

    /**
     * Parses a positive byte count.
     *
     * @param text     the text, or {@code null}
     * @param fallback the value for a missing, non-positive or bad text
     * @return the count
     */
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

    private static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
    }
}
