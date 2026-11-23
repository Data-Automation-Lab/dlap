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

import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.ACCOUNT;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.BUCKET;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.drain;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.gzip;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.lifecycleDelete;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.trailFile;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.trailKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.dlap.logingest.providers.aws.s3.AwsS3ForTests.DoneObjects;
import com.dlap.logingest.providers.aws.s3.AwsS3ForTests.FakeS3;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The CloudTrail trail-file transport against an in-memory bucket laid out the way CloudTrail writes
 * it: {@code AWSLogs/<account>/CloudTrail/<region>/YYYY/MM/DD/} folders, gzip {@code {"Records":[...]}}
 * objects, digest files beside them, files written late, malformed and oversize objects.
 */
class CloudTrailTrailFileTransportTest {

    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-11-02T03:00:00Z"),
            Instant.parse("2026-11-02T05:00:00Z"));

    private final FakeS3 s3 = new FakeS3();
    private final DoneObjects done = new DoneObjects();
    private final CloudTrailTrailFileTransport transport = new CloudTrailTrailFileTransport(s3);

    private StreamContext ctx(Map<String, String> extra) {
        Map<String, String> location = new LinkedHashMap<>(extra);
        location.putIfAbsent("bucket", BUCKET);
        return AwsS3ForTests.trail(location, done);
    }

    private List<RawBatch> fetch(StreamContext ctx, String region) {
        return drain(transport.fetch(ctx, region, CursorPosition.INITIAL, WINDOW));
    }

    @Test
    void anObjectThatCannotBeReadNamesItsObjectAndTheIteratorIsPositionedPastIt() {
        String first = trailKey("", "us-east-1", "20261102T0310Z", "FirstFile00000001");
        String second = trailKey("", "us-east-1", "20261102T0320Z", "SecondFile0000001");
        s3.put(first, gzip(trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000b0001",
                "2026-11-02T03:10:07Z", "raw/a.parquet"))));
        s3.put(second, gzip(trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000b0002",
                "2026-11-02T03:20:07Z", "raw/b.parquet"))));
        S3LogStore denying = new S3LogStore() {
            @Override
            public List<S3LogObject> list(StreamContext c, String b, String p, String s, String e) {
                return s3.list(c, b, p, s, e);
            }

            @Override
            public List<String> folders(StreamContext c, String b, String p) {
                return s3.folders(c, b, p);
            }

            @Override
            public byte[] read(StreamContext c, String b, S3LogObject o, long max) {
                if (o.key().equals(first)) {
                    throw new IllegalStateException("Access Denied (kms:Decrypt)");
                }
                return s3.read(c, b, o, max);
            }
        };
        RawBatchIterator it = new CloudTrailTrailFileTransport(denying).fetch(ctx(Map.of()), "us-east-1",
                CursorPosition.INITIAL, WINDOW);

        assertThatThrownBy(it::next).isInstanceOfSatisfying(ObjectReadException.class, e -> {
            assertThat(e.object().key()).isEqualTo(first);
            assertThat(e.uri()).isEqualTo("s3://" + BUCKET + "/" + first);
            assertThat(e.getCause()).hasMessageContaining("Access Denied");
        });
        RawBatch next = it.next();
        assertThat(next.object().orElseThrow().key()).isEqualTo(second);
        assertThat(next.records()).hasSize(1);
    }

    @Test
    void aNulInATextPieceOrAMalformedObjectIsLeftToTheQuarantineSinks() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "NulObject0000001");
        byte[] bytes = new byte[] {'{', 0, 'x', 0, (byte) 0xff, '}'};
        s3.put(key, bytes);

        List<RawBatch> batches = fetch(ctx(Map.of()), "us-east-1");

        RawBatch file = batches.get(0);
        assertThat(file.records()).hasSize(1);
        assertThat(file.records().get(0).bytes()).as("the transport keeps the exact bytes").isEqualTo(bytes);
    }

    @Test
    void theDescriptorDeclaresAnObjectCursorAndItsLocationKeys() {
        assertThat(transport.descriptor().transportId()).isEqualTo("S3_TRAIL_FILES");
        assertThat(transport.descriptor().requiredLocation()).containsExactly("bucket");
        assertThat(transport.descriptor().optionalLocation()).contains("prefix", "organization-id", "regions",
                "region", "endpoint", "path-style", "profile", "late-object-lookback", "max-object-bytes",
                "external-id", "sts-endpoint");
        assertThat(transport.descriptor().cursorKind())
                .isEqualTo(com.dlap.logingest.transport.TransportDescriptor.CursorKind.OBJECT);
    }

    @Test
    void aTrailFileIsSplitIntoOneRecordPerElementOfRecordsAndParsesAsALifecycleDelete() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "AbCdEf0123456789");
        s3.put(key, gzip(trailFile(
                lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000a0001", "2026-11-02T03:14:07Z", "raw/a.parquet"),
                lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000a0002", "2026-11-02T03:14:08Z", "raw/b.parquet"))));
        StreamContext ctx = ctx(Map.of());

        List<RawBatch> batches = fetch(ctx, "us-east-1");

        assertThat(batches).hasSize(2);
        RawBatch file = batches.get(0);
        assertThat(file.object()).hasValueSatisfying(o -> {
            assertThat(o.key()).isEqualTo(key);
            assertThat(o.etag()).startsWith("etag-");
        });
        assertThat(file.records()).hasSize(2);
        RawRecord first = file.records().get(0);
        assertThat(first.sourceKind()).isEqualTo(CloudTrailFormat.SOURCE_KIND);
        assertThat(first.envelope()).isEqualTo(CloudTrailFormat.S3_FILE);
        assertThat(first.ref().transport()).isEqualTo("S3_TRAIL_FILES");
        assertThat(first.ref().uri()).isEqualTo("s3://" + BUCKET + "/" + key);
        assertThat(first.ref().recordIndex()).isZero();
        assertThat(file.records().get(1).ref().recordIndex()).isEqualTo(1);
        assertThat(first.observedTime()).contains(AwsS3ForTests.NOW);

        ParseOutcome outcome = AwsS3ForTests.parse(ctx, first);
        assertThat(outcome.result()).isInstanceOf(ParseResult.Parsed.class);
        var event = ((ParseResult.Parsed) outcome.result()).events().get(0);
        assertThat(event.changeType()).isEqualTo(ChangeType.DELETE);
        assertThat(event.principal().type()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(event.target().canonicalUri()).isEqualTo("s3://dlap-aws-e2e/raw/a.parquet");

        // The cursor stands at the file's delivery time, then at the window end.
        assertThat(file.advanceTo().lowWatermark()).isEqualTo(Instant.parse("2026-11-02T03:15:00Z"));
        assertThat(file.advanceTo().state()).containsEntry("lastObject", key);
        assertThat(batches.get(1).records()).isEmpty();
        assertThat(batches.get(1).advanceTo().lowWatermark()).isEqualTo(WINDOW.to());
    }

    @Test
    void filesAreReadOldestFirstAcrossDayFoldersAndOnlyInsideTheWindowAndLookback() {
        // The window starts at 03:00; the default late-object lookback reaches back to 02:00.
        String tooOld = trailKey("", "us-east-1", "20261102T0159Z", "Old0000000000000");
        String late = trailKey("", "us-east-1", "20261102T0230Z", "Late000000000000");
        String inWindow = trailKey("", "us-east-1", "20261102T0400Z", "In00000000000000");
        String tooNew = trailKey("", "us-east-1", "20261102T0500Z", "New0000000000000");
        for (String k : List.of(tooOld, late, inWindow, tooNew)) {
            s3.put(k, gzip(trailFile()));
        }
        // Not trail files: a digest file, another region's file in this folder, a stray object.
        s3.put("AWSLogs/" + ACCOUNT + "/CloudTrail-Digest/us-east-1/2026/11/02/" + ACCOUNT
                + "_CloudTrail-Digest_us-east-1_trail_us-east-1_20261102T0400Z.json.gz", gzip("{}"));
        s3.put("AWSLogs/" + ACCOUNT + "/CloudTrail/us-east-1/2026/11/02/" + ACCOUNT
                + "_CloudTrail_eu-west-1_20261102T0400Z_Other00000000000.json.gz", gzip(trailFile()));
        s3.put("AWSLogs/" + ACCOUNT + "/CloudTrail/us-east-1/2026/11/02/readme.txt", "x");

        List<RawBatch> batches = fetch(ctx(Map.of()), "us-east-1");

        assertThat(batches).extracting(b -> b.object().map(RawBatch.ObjectRef::key).orElse("end"))
                .containsExactly(late, inWindow, "end");
        assertThat(s3.listed).containsExactly("AWSLogs/" + ACCOUNT + "/CloudTrail/us-east-1/2026/11/02/||");
    }

    @Test
    void aWindowAcrossMidnightListsBothDayFolders() {
        String before = trailKey("", "us-east-1", "20261101T2350Z", "Before0000000000");
        String after = trailKey("", "us-east-1", "20261102T0005Z", "After00000000000");
        s3.put(before, gzip(trailFile())).put(after, gzip(trailFile()));
        FetchWindow midnight = new FetchWindow(Instant.parse("2026-11-01T23:30:00Z"),
                Instant.parse("2026-11-02T00:30:00Z"));

        List<RawBatch> batches = drain(transport.fetch(ctx(Map.of("late-object-lookback", "PT0S")), "us-east-1",
                CursorPosition.INITIAL, midnight));

        assertThat(batches).extracting(b -> b.object().map(RawBatch.ObjectRef::key).orElse("end"))
                .containsExactly(before, after, "end");
        assertThat(s3.listed).hasSize(2);
    }

    @Test
    void anObjectAlreadyProcessedInThisVersionIsSkippedAndARewrittenOneIsReadAgain() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "Once000000000000");
        String file = trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000a0003", "2026-11-02T03:14:07Z",
                "raw/c.parquet"));
        s3.put(key, gzip(file));
        StreamContext ctx = ctx(Map.of());
        RawBatch first = fetch(ctx, "us-east-1").get(0);
        done.markDone("aws-test", "s1", first.object().get().key(), first.object().get().etag(), 1, 0);
        s3.read.clear();

        assertThat(fetch(ctx, "us-east-1")).hasSize(1).allSatisfy(b -> assertThat(b.object()).isEmpty());
        assertThat(s3.read).isEmpty();

        // The same key with other content has another etag: it is a new version and is read.
        s3.put(key, gzip(file + " "));
        assertThat(fetch(ctx, "us-east-1")).hasSize(2);
    }

    @Test
    void anOrganizationTrailAndATrailPrefixAreFollowed() {
        String key = "audit/AWSLogs/o-abcdefghij/" + trailKey("", "us-east-1", "20261102T0315Z", "Org0000000000000")
                .substring("AWSLogs/".length());
        s3.put(key, gzip(trailFile()));

        List<RawBatch> batches = fetch(ctx(Map.of("prefix", "/audit", "organization-id", "o-abcdefghij")),
                "us-east-1");

        assertThat(batches.get(0).object()).hasValueSatisfying(o -> assertThat(o.key()).isEqualTo(key));
        assertThatThrownBy(() -> fetch(ctx(Map.of("organization-id", "not-an-org")), "us-east-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("organization-id");
    }

    @Test
    void partitionsComeFromTheRegionsKeyTheScopeRegionOrTheRegionFolders() {
        assertThat(transport.partitions(ctx(Map.of("regions", "us-west-2, eu-west-1,bogus,us-west-2"))))
                .containsExactly("eu-west-1", "us-west-2");
        assertThat(transport.partitions(ctx(Map.of()))).containsExactly("us-east-1");

        s3.put(trailKey("", "ap-southeast-2", "20261102T0315Z", "A000000000000000"), gzip(trailFile()));
        s3.put(trailKey("", "eu-central-1", "20261102T0315Z", "B000000000000000"), gzip(trailFile()));
        s3.put("AWSLogs/" + ACCOUNT + "/CloudTrail/not-a-region/x", "x");
        StreamContext noRegion = AwsS3ForTests.context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, null, Map.of("bucket", BUCKET), done);
        assertThat(transport.partitions(noRegion)).containsExactly("ap-southeast-2", "eu-central-1");
        assertThatThrownBy(() -> drain(transport.fetch(noRegion, "", CursorPosition.INITIAL, WINDOW)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not an AWS region");
    }

    @Test
    void aCorruptGzipFileIsDeliveredAsOneRecordThatTheRegistryQuarantines() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "Corrupt000000000");
        byte[] gz = gzip(trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000a0004", "2026-11-02T03:14:07Z",
                "raw/d.parquet")));
        s3.put(key, java.util.Arrays.copyOf(gz, gz.length / 2));
        StreamContext ctx = ctx(Map.of());

        RawBatch batch = fetch(ctx, "us-east-1").get(0);

        assertThat(batch.object()).isPresent();
        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.ref().recordIndex()).isEqualTo(-1);
            assertThat(gz).as("a gzip header holds NUL bytes").contains((byte) 0);
            assertThat(r.bytes()).as("the transport keeps the exact bytes; the quarantine sinks drop the NUL (Q-661)")
                    .contains((byte) 0);
            ParseOutcome o = AwsS3ForTests.parse(ctx, r);
            assertThat(o.result()).isInstanceOfSatisfying(ParseResult.Quarantined.class, q ->
                    assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_ENCODING));
        });
    }

    @Test
    void anObjectAboveMaxObjectBytesIsReadOnlyInPartAndQuarantinedAsOversize() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "Big0000000000000");
        String big = trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000a0005", "2026-11-02T03:14:07Z",
                "raw/" + "e".repeat(3000) + ".parquet"));
        s3.put(key, big);
        StreamContext ctx = AwsS3ForTests.context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, "us-east-1", Map.of("bucket", BUCKET, "max-object-bytes", "2048"), done, 1024,
                null);

        RawBatch batch = fetch(ctx, "us-east-1").get(0);

        assertThat(s3.readLimits).containsExactly(1025L);
        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.sizeBytes()).isEqualTo(1025);
            assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                    q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD));
        });
    }

    @Test
    void aGzipFileThatUnpacksAboveTheLimitIsQuarantinedAsOversize() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "Bomb000000000000");
        s3.put(key, gzip(trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000a0006", "2026-11-02T03:14:07Z",
                "raw/" + "f".repeat(200_000) + ".parquet"))));
        StreamContext ctx = AwsS3ForTests.context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, "us-east-1", Map.of("bucket", BUCKET, "max-object-bytes", "100000"), done,
                4096, null);

        RawBatch batch = fetch(ctx, "us-east-1").get(0);

        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.sizeBytes()).isEqualTo(4097);
            assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                    q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD));
        });
    }

    @Test
    void aFileThatIsNotJsonOrHasNoRecordsArrayIsOneRecordThatIsQuarantined() {
        String text = trailKey("", "us-east-1", "20261102T0315Z", "Text000000000000");
        String noRecords = trailKey("", "us-east-1", "20261102T0316Z", "NoRec00000000000");
        String latin1 = trailKey("", "us-east-1", "20261102T0317Z", "Latin00000000000");
        s3.put(text, gzip("this is not json"));
        s3.put(noRecords, gzip("{\"Other\":[]}"));
        s3.put(latin1, "{\"Records\":[\"café\"]}".getBytes(StandardCharsets.ISO_8859_1));
        StreamContext ctx = ctx(Map.of());

        List<RawBatch> batches = fetch(ctx, "us-east-1");

        assertThat(AwsS3ForTests.parse(ctx, batches.get(0).records().get(0)).result())
                .isInstanceOfSatisfying(ParseResult.Quarantined.class, q -> assertThat(q.reason())
                        .isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_JSON));
        assertThat(batches.get(1).records()).singleElement().satisfies(r ->
                assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOf(ParseResult.Quarantined.class));
        assertThat(batches.get(2).records()).singleElement().satisfies(r ->
                assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                        q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_ENCODING)));
    }

    @Test
    void anEmptyRecordsArrayCommitsTheFileWithNoRecords() {
        String key = trailKey("", "us-east-1", "20261102T0315Z", "Empty00000000000");
        s3.put(key, gzip(trailFile()));

        RawBatch batch = fetch(ctx(Map.of()), "us-east-1").get(0);

        assertThat(batch.object()).isPresent();
        assertThat(batch.records()).isEmpty();
    }

    @Test
    void aStreamOfAnotherEnvelopeOrWithoutAnAccountIsRefused() {
        StreamContext wrongEnvelope = AwsS3ForTests.context(CloudTrailTrailFileTransport.ID,
                S3AccessLogFormat.SOURCE_KIND, S3AccessLogFormat.CWL_JSON, "us-east-1", Map.of("bucket", BUCKET), done);
        assertThatThrownBy(() -> fetch(wrongEnvelope, "us-east-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivers envelope s3-file");

        StreamContext noBucket = AwsS3ForTests.trail(Map.of(), done);
        assertThatThrownBy(() -> fetch(noBucket, "us-east-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket");

        StreamContext noAccount = new StreamContext(new com.dlap.logingest.config.sources.EffectiveStream("b", "s",
                CloudTrailTrailFileTransport.ID, CloudTrailFormat.S3_FILE, Map.of("bucket", BUCKET),
                com.dlap.logingest.parse.core.BindingConfig.auto("b", CloudTrailFormat.SOURCE_KIND), null, null, null,
                null, null, false, false, false), CloudTrailFormat.SOURCE_KIND, null,
                java.time.Clock.systemUTC(), done);
        assertThatThrownBy(() -> fetch(noAccount, "us-east-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope.account-id");
    }

    @Test
    void fileNamesParseOnlyForTheirOwnRegion() {
        assertThat(CloudTrailTrailFileTransport.fileTime(ACCOUNT + "_CloudTrail_us-east-1_20261102T0315Z_Ab12.json.gz",
                "us-east-1")).contains(Instant.parse("2026-11-02T03:15:00Z"));
        assertThat(CloudTrailTrailFileTransport.fileTime(ACCOUNT + "_CloudTrail_us-east-1_20261102T0315Z_Ab12.json",
                "us-east-1")).isPresent();
        assertThat(CloudTrailTrailFileTransport.fileTime(ACCOUNT + "_CloudTrail_us-east-1_20261102T0315Z_Ab12.json.gz",
                "eu-west-1")).isEmpty();
        assertThat(CloudTrailTrailFileTransport.fileTime(ACCOUNT + "_CloudTrail_us-east-1_20261399T0315Z_Ab12.json.gz",
                "us-east-1")).isEmpty();
    }
}
