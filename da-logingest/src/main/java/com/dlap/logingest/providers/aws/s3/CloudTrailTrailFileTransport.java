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

import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.mapping.Framing;
import com.dlap.logingest.parse.core.mapping.RecordFraming;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads CloudTrail trail files from the trail's S3 bucket, transport id {@value #ID}, envelope
 * {@code s3-file} (family {@code cloudtrail}, ADR-0102).
 *
 * <p>CloudTrail writes one gzip JSON object per delivery,
 * {@code <prefix>AWSLogs/[<organization id>/]<account>/CloudTrail/<region>/YYYY/MM/DD/
 * <account>_CloudTrail_<region>_YYYYMMDDTHHmmZ_<unique>.json.gz}, holding {@code {"Records":[...]}}
 * (https://docs.aws.amazon.com/awscloudtrail/latest/userguide/get-and-view-cloudtrail-log-files.html,
 * cloudtrail-log-file-examples). The digest ({@code CloudTrail-Digest}) and Insights
 * ({@code CloudTrail-Insight}) folders sit beside {@code CloudTrail} and are never listed.
 *
 * <p>Each region is a partition with its own cursor: the location key {@code regions} (comma
 * separated), else the binding's single scope region, else every region folder found under the
 * account's {@code CloudTrail/} folder. A poll lists the day folders the window touches and reads the
 * files whose name time lies in the window (see {@link AbstractS3LogTransport}); each file's
 * {@code Records} array is split into one record per element, which the registry parses with the
 * binding's {@code AWS_CLOUDTRAIL} settings. A file without a {@code Records} array is one record and
 * is quarantined by the parser; a file that does not unpack is quarantined whole.
 *
 * <p>Location keys besides the common ones: {@code organization-id} (an organization trail's
 * {@code o-...} folder). The account is the binding's {@code scope.account-id}.
 */
public class CloudTrailTrailFileTransport extends AbstractS3LogTransport {

    /** Transport id. */
    public static final String ID = "S3_TRAIL_FILES";

    private static final FieldPath RECORDS = FieldPath.of("/Records");
    private static final Set<Framing> FRAMING = Set.of(Framing.JSON_OBJECT_ARRAY, Framing.RECORD);
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d{1,2}");
    private static final Pattern ORGANIZATION = Pattern.compile("o-[a-z0-9]{10,32}");
    private static final Pattern FILE = Pattern.compile(
            "(\\d{12})_CloudTrail_([a-z0-9-]+)_(\\d{8}T\\d{4}Z)_[A-Za-z0-9]+\\.json(\\.gz)?");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'");
    private static final TransportDescriptor DESCRIPTOR = new TransportDescriptor(ID, Set.of("bucket"),
            optional(), TransportDescriptor.CursorKind.OBJECT, Duration.ofDays(1), Duration.ZERO);

    /**
     * Creates the transport.
     *
     * @param store the object store
     */
    public CloudTrailTrailFileTransport(S3LogStore store) {
        super(store);
    }

    private static Set<String> optional() {
        Set<String> keys = new HashSet<>(COMMON_LOCATION);
        keys.add("organization-id");
        keys.add("regions");
        return keys;
    }

    @Override
    public TransportDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected String envelope() {
        return CloudTrailFormat.S3_FILE;
    }

    @Override
    public List<String> partitions(StreamContext context) {
        String listed = location(context, "regions");
        if (listed != null) {
            return Arrays.stream(listed.split(",")).map(String::strip).filter(r -> REGION.matcher(r).matches())
                    .distinct().sorted().toList();
        }
        String hinted = context.stream().parse().assetIdHints().region();
        if (hinted != null && REGION.matcher(hinted).matches()) {
            return List.of(hinted);
        }
        String root = trailRoot(context);
        List<String> regions = new ArrayList<>();
        for (String folder : store.folders(context, bucket(context), root)) {
            String name = folder.substring(root.length()).replace("/", "");
            if (REGION.matcher(name).matches()) {
                regions.add(name);
            }
        }
        return regions.stream().distinct().sorted().toList();
    }

    @Override
    protected List<Candidate> candidates(StreamContext context, String bucket, String partition, Instant from,
                                         Instant to) {
        if (!REGION.matcher(partition).matches()) {
            throw new IllegalStateException(ID + " partition '" + partition + "' is not an AWS region");
        }
        String regionFolder = trailRoot(context) + partition + "/";
        List<Candidate> out = new ArrayList<>();
        for (LocalDate d = LocalDate.ofInstant(from, ZoneOffset.UTC); !d.isAfter(LocalDate.ofInstant(to, ZoneOffset.UTC));
             d = d.plusDays(1)) {
            String day = regionFolder + String.format("%04d/%02d/%02d/", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
            for (S3LogObject o : store.list(context, bucket, day, "", "")) {
                fileTime(o.key().substring(Math.min(day.length(), o.key().length())), partition)
                        .filter(t -> !t.isBefore(from) && t.isBefore(to))
                        .ifPresent(t -> out.add(new Candidate(o, t)));
            }
        }
        return out;
    }

    @Override
    protected List<RawRecord> split(StreamContext context, String uri, S3LogObject object, String text) {
        List<RawRecord> records = new ArrayList<>();
        for (RecordFraming.Piece p : RecordFraming.split(text, FRAMING, RECORDS)) {
            RawRecord.Builder b = piece(context, uri, object, p.index());
            records.add(p.text() != null ? b.text(p.text()).build() : b.json(p.json()).build());
        }
        return records;
    }

    /**
     * The account's {@code CloudTrail/} folder.
     *
     * @param context the stream
     * @return {@code <prefix>AWSLogs/[<org>/]<account>/CloudTrail/}
     * @throws IllegalStateException when the binding names no account or the organization id is malformed
     */
    static String trailRoot(StreamContext context) {
        String account = context.stream().parse().assetIdHints().account();
        if (account == null || !account.matches("\\d{12}")) {
            throw new IllegalStateException(ID + " needs the binding's scope.account-id (12 digits)");
        }
        String org = location(context, "organization-id");
        if (org != null && !ORGANIZATION.matcher(org).matches()) {
            throw new IllegalStateException(ID + " location key organization-id must look like o-abcdefghij");
        }
        return folder(location(context, "prefix")) + "AWSLogs/" + (org == null ? "" : org + "/") + account
                + "/CloudTrail/";
    }

    /**
     * The delivery time in a trail file name.
     *
     * @param name   the file name, after its day folder
     * @param region the partition, which the name must repeat
     * @return the time, or empty for another object
     */
    static Optional<Instant> fileTime(String name, String region) {
        Matcher m = FILE.matcher(name);
        if (!m.matches() || !m.group(2).equals(region)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(m.group(3), FILE_TIME).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
