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

package com.dlap.fixtures.gen.scale;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Cuts a {@link CloudTrailScale} stream into gzip trail files named the way CloudTrail names them:
 * {@code AWSLogs/<account>/CloudTrail/<region>/yyyy/MM/dd/<account>_CloudTrail_<region>_yyyyMMddTHHmmZ_<unique>.json.gz}.
 * File {@code n} holds records {@code [n * perFile, (n + 1) * perFile)}; its delivery time is the
 * minute after its last event, the way a trail delivers a file shortly after the calls.
 */
public final class TrailFiles {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'")
            .withZone(ZoneOffset.UTC);
    private static final int LANE_FILE = 50;

    private final CloudTrailScale trail;
    private final int perFile;

    /**
     * Creates the file layout.
     *
     * @param trail   the records
     * @param perFile records per file, at least 1
     */
    public TrailFiles(CloudTrailScale trail, int perFile) {
        if (perFile < 1) {
            throw new IllegalArgumentException("perFile must be at least 1");
        }
        this.trail = trail;
        this.perFile = perFile;
    }

    /**
     * The number of files.
     *
     * @return {@code ceil(count / perFile)}
     */
    public int fileCount() {
        long count = trail.window().count();
        return (int) ((count + perFile - 1) / perFile);
    }

    /**
     * Index of the first record of a file.
     *
     * @param file the file index
     * @return the first record index
     */
    public long firstRecord(int file) {
        checkFile(file);
        return (long) file * perFile;
    }

    /**
     * Index after the last record of a file.
     *
     * @param file the file index
     * @return the end index, exclusive
     */
    public long endRecord(int file) {
        checkFile(file);
        return Math.min(trail.window().count(), (long) (file + 1) * perFile);
    }

    /**
     * When the file is delivered: the minute after its last event.
     *
     * @param file the file index
     * @return the delivery time, to the minute
     */
    public Instant deliveredAt(int file) {
        Instant last = trail.window().eventTime(endRecord(file) - 1);
        return last.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    }

    /**
     * The object key of a file.
     *
     * @param file the file index
     * @return the key
     */
    public String key(int file) {
        Instant at = deliveredAt(file);
        String acct = trail.accountId();
        String region = trail.region();
        return "AWSLogs/" + acct + "/CloudTrail/" + region + "/" + DAY.format(at) + "/" + acct + "_CloudTrail_"
                + region + "_" + STAMP.format(at) + "_"
                + ScaleRandom.hex(trail.window().seed(), file, LANE_FILE, 16).toLowerCase(Locale.ROOT) + ".json.gz";
    }

    /**
     * The JSON body of a file, {@code {"Records":[...]}}.
     *
     * @param file the file index
     * @return the JSON text
     */
    public String body(int file) {
        long from = firstRecord(file);
        long to = endRecord(file);
        StringBuilder sb = new StringBuilder((int) ((to - from) * 1100 + 16));
        sb.append("{\"Records\":[");
        for (long i = from; i < to; i++) {
            if (i > from) {
                sb.append(',');
            }
            sb.append(trail.record(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * The gzip bytes of a file.
     *
     * @param file the file index
     * @return the compressed body
     */
    public byte[] gzip(int file) {
        byte[] raw = body(file).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 8 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void checkFile(int file) {
        if (file < 0 || file >= fileCount()) {
            throw new IllegalArgumentException("file " + file + " is outside 0.." + (fileCount() - 1));
        }
    }
}
