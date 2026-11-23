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
import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads S3 server access log objects from the target bucket, transport id {@value #ID}, envelope
 * {@code cwl-json} (family {@code s3accesslog}, ADR-0102, decision D36).
 *
 * <p>S3 delivers the logs of a source bucket as text objects under the target prefix, in one of two
 * key formats (https://docs.aws.amazon.com/AmazonS3/latest/userguide/ServerLogs.html):
 * <ul>
 *   <li>{@code simple} (the default): {@code <prefix>YYYY-mm-DD-HH-MM-SS-<unique>}. A poll lists
 *       from {@code <prefix><from time>}, so it never pages through older objects.</li>
 *   <li>{@code partitioned}: {@code <prefix><account>/<region>/<source bucket>/YYYY/MM/DD/
 *       YYYY-mm-DD-HH-MM-SS-<unique>}. Set {@code prefix} down to the source bucket's folder; a poll
 *       lists the day folders the window touches.</li>
 * </ul>
 * Each line is one record: {@link S3AccessLogLine} turns it into the CloudWatch Logs field names, and
 * the registry parses that object with the binding's {@code AWS_S3_SERVER_ACCESS_LOG} settings, so the
 * parser contract is unchanged. A line that does not split is delivered as its text and quarantined.
 * Server access logs are delivered best effort and within a few hours; the family's overlap
 * ({@code PT6H}) and {@code late-object-lookback} decide how far back a poll looks for late objects.
 *
 * <p>Location keys besides the common ones: {@code key-format} ({@code simple} or
 * {@code partitioned}). One partition per stream.
 */
public class S3ServerAccessLogTransport extends AbstractS3LogTransport {

    /** Transport id. */
    public static final String ID = "S3_SERVER_ACCESS_LOGS";

    /** The simple key format. */
    public static final String SIMPLE = "simple";

    /** The date-partitioned key format. */
    public static final String PARTITIONED = "partitioned";

    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})-[A-Za-z0-9]+");
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final TransportDescriptor DESCRIPTOR = new TransportDescriptor(ID, Set.of("bucket"),
            optional(), TransportDescriptor.CursorKind.OBJECT, Duration.ofDays(1), Duration.ZERO);

    /**
     * Creates the transport.
     *
     * @param store the object store
     */
    public S3ServerAccessLogTransport(S3LogStore store) {
        super(store);
    }

    private static Set<String> optional() {
        Set<String> keys = new HashSet<>(COMMON_LOCATION);
        keys.add("key-format");
        return keys;
    }

    @Override
    public TransportDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected String envelope() {
        return S3AccessLogFormat.CWL_JSON;
    }

    @Override
    protected List<Candidate> candidates(StreamContext context, String bucket, String partition, Instant from,
                                         Instant to) {
        String prefix = folder(location(context, "prefix"));
        String format = keyFormat(context);
        List<Candidate> out = new ArrayList<>();
        if (SIMPLE.equals(format)) {
            String startAfter = prefix + NAME_TIME.format(LocalDateTime.ofInstant(from, ZoneOffset.UTC));
            collect(store.list(context, bucket, prefix, startAfter,
                    prefix + NAME_TIME.format(LocalDateTime.ofInstant(to, ZoneOffset.UTC))), prefix, from, to, out);
        } else {
            for (LocalDate d = LocalDate.ofInstant(from, ZoneOffset.UTC);
                 !d.isAfter(LocalDate.ofInstant(to, ZoneOffset.UTC)); d = d.plusDays(1)) {
                String day = prefix + String.format("%04d/%02d/%02d/", d.getYear(), d.getMonthValue(),
                        d.getDayOfMonth());
                collect(store.list(context, bucket, day, "", ""), day, from, to, out);
            }
        }
        return out;
    }

    private static void collect(List<S3LogObject> listed, String folder, Instant from, Instant to,
                                List<Candidate> out) {
        for (S3LogObject o : listed) {
            if (!o.key().startsWith(folder)) {
                continue;
            }
            nameTime(o.key().substring(folder.length()))
                    .filter(t -> !t.isBefore(from) && t.isBefore(to))
                    .ifPresent(t -> out.add(new Candidate(o, t)));
        }
    }

    @Override
    protected List<RawRecord> split(StreamContext context, String uri, S3LogObject object, String text) {
        List<RawRecord> records = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            Optional<ObjectNode> fields = S3AccessLogLine.parse(lines[i]);
            RawRecord.Builder b = piece(context, uri, object, i);
            records.add(fields.isPresent() ? b.json(fields.get()).build() : b.text(lines[i]).build());
        }
        return records;
    }

    /**
     * The stream's key format.
     *
     * @param context the stream
     * @return {@value #SIMPLE} or {@value #PARTITIONED}
     * @throws IllegalStateException for another value
     */
    static String keyFormat(StreamContext context) {
        String format = location(context, "key-format");
        if (format == null) {
            return SIMPLE;
        }
        String f = format.toLowerCase(Locale.ROOT);
        if (!SIMPLE.equals(f) && !PARTITIONED.equals(f)) {
            throw new IllegalStateException(ID + " location key key-format is simple or partitioned");
        }
        return f;
    }

    /**
     * The time in a log object's name.
     *
     * @param name the name after its folder
     * @return the time, or empty for another object
     */
    static Optional<Instant> nameTime(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(m.group(1), NAME_TIME).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
