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

import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.drain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.dlap.logingest.providers.aws.s3.AwsS3ForTests.DoneObjects;
import com.dlap.logingest.providers.aws.s3.AwsS3ForTests.FakeS3;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The S3 server access log transport against an in-memory target bucket in both key formats: lines
 * split into the CloudWatch Logs field names and parsed by the {@code s3accesslog} family, listing from
 * the window start, malformed lines and objects quarantined, objects already processed skipped.
 */
class S3ServerAccessLogTransportTest {

    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-11-02T03:00:00Z"),
            Instant.parse("2026-11-02T05:00:00Z"));
    private static final String LOGS = "dlap-access-logs";

    private final FakeS3 s3 = new FakeS3();
    private final DoneObjects done = new DoneObjects();
    private final S3ServerAccessLogTransport transport = new S3ServerAccessLogTransport(s3);

    private StreamContext ctx(Map<String, String> extra) {
        Map<String, String> location = new LinkedHashMap<>(extra);
        location.putIfAbsent("bucket", LOGS);
        return AwsS3ForTests.accessLog(location, done);
    }

    private List<RawBatch> fetch(StreamContext ctx) {
        return drain(transport.fetch(ctx, "", CursorPosition.INITIAL, WINDOW));
    }

    @Test
    void theDescriptorDeclaresAnObjectCursorAndOnePartition() {
        assertThat(transport.descriptor().transportId()).isEqualTo("S3_SERVER_ACCESS_LOGS");
        assertThat(transport.descriptor().requiredLocation()).containsExactly("bucket");
        assertThat(transport.descriptor().optionalLocation()).contains("prefix", "key-format", "region", "endpoint",
                "path-style", "profile", "late-object-lookback", "max-object-bytes");
        assertThat(transport.partitions(ctx(Map.of()))).containsExactly("");
    }

    @Test
    void eachLineOfASimpleFormatObjectBecomesOneRecordThatTheFamilyParses() {
        String logObject = "logs/2026-11-02-03-21-07-0A1B2C3D4E5F6A7B";
        s3.put(logObject, S3AccessLogLineTest.EXPIRE + "\n\n" + S3AccessLogLineTest.PUT_OBJECT + "\r\n");
        StreamContext ctx = ctx(Map.of("prefix", "logs"));

        List<RawBatch> batches = fetch(ctx);

        assertThat(batches).hasSize(2);
        RawBatch object = batches.get(0);
        assertThat(object.object()).hasValueSatisfying(o -> assertThat(o.key()).isEqualTo(logObject));
        assertThat(object.records()).hasSize(2);
        RawRecord expire = object.records().get(0);
        assertThat(expire.sourceKind()).isEqualTo(S3AccessLogFormat.SOURCE_KIND);
        assertThat(expire.envelope()).isEqualTo(S3AccessLogFormat.CWL_JSON);
        assertThat(expire.ref().uri()).isEqualTo("s3://" + LOGS + "/" + logObject);
        assertThat(expire.ref().recordIndex()).isZero();
        assertThat(object.records().get(1).ref().recordIndex()).as("line numbers count blank lines").isEqualTo(2);
        assertThat(expire.body().path("operation").asText()).isEqualTo("S3.EXPIRE.OBJECT");

        var parsed = AwsS3ForTests.parse(ctx, expire);
        assertThat(parsed.result()).isInstanceOfSatisfying(ParseResult.Parsed.class, p -> {
            assertThat(p.events().get(0).changeType()).isEqualTo(ChangeType.DELETE);
            assertThat(p.events().get(0).principal().type()).isEqualTo(PrincipalType.SYSTEM);
        });
        assertThat(object.advanceTo().lowWatermark()).isEqualTo(Instant.parse("2026-11-02T03:21:07Z"));
        assertThat(batches.get(1).advanceTo().lowWatermark()).isEqualTo(WINDOW.to());
    }

    @Test
    void aSimpleFormatPollListsFromTheWindowStartMinusTheLookbackToTheWindowEnd() {
        s3.put("logs/2026-11-02-01-59-59-0000000000000001", S3AccessLogLineTest.EXPIRE);
        s3.put("logs/2026-11-02-02-00-00-0000000000000002", S3AccessLogLineTest.EXPIRE);
        s3.put("logs/2026-11-02-04-59-59-0000000000000003", S3AccessLogLineTest.EXPIRE);
        s3.put("logs/2026-11-02-05-00-00-0000000000000004", S3AccessLogLineTest.EXPIRE);
        s3.put("logs/not-a-log-object", "x");

        List<RawBatch> batches = fetch(ctx(Map.of("prefix", "logs/")));

        assertThat(batches).extracting(b -> b.object().map(RawBatch.ObjectRef::key).orElse("end"))
                .containsExactly("logs/2026-11-02-02-00-00-0000000000000002",
                        "logs/2026-11-02-04-59-59-0000000000000003", "end");
        assertThat(s3.listed).containsExactly("logs/|logs/2026-11-02-02-00-00|logs/2026-11-02-05-00-00");
    }

    @Test
    void thePartitionedFormatListsTheDayFoldersOfTheWindow() {
        String folder = "logs/123456789012/us-east-1/dlap-aws-e2e/";
        s3.put(folder + "2026/11/01/2026-11-01-23-59-00-0000000000000005", S3AccessLogLineTest.EXPIRE);
        s3.put(folder + "2026/11/02/2026-11-02-03-30-00-0000000000000006", S3AccessLogLineTest.EXPIRE);
        FetchWindow midnight = new FetchWindow(Instant.parse("2026-11-01T23:30:00Z"),
                Instant.parse("2026-11-02T04:00:00Z"));

        List<RawBatch> batches = drain(transport.fetch(ctx(Map.of("prefix", folder, "key-format", "Partitioned",
                "late-object-lookback", "PT0S")), "", CursorPosition.INITIAL, midnight));

        assertThat(batches).extracting(b -> b.object().map(RawBatch.ObjectRef::key).orElse("end"))
                .containsExactly(folder + "2026/11/01/2026-11-01-23-59-00-0000000000000005",
                        folder + "2026/11/02/2026-11-02-03-30-00-0000000000000006", "end");
        assertThat(s3.listed).containsExactly(folder + "2026/11/01/||", folder + "2026/11/02/||");
        assertThatThrownBy(() -> fetch(ctx(Map.of("key-format", "hourly")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-format");
    }

    @Test
    void aLineThatDoesNotSplitIsDeliveredAsTextAndQuarantinedAlone() {
        s3.put("2026-11-02-03-21-07-0A1B2C3D4E5F6A7C", "garbage\0 line with \"unclosed\n" + S3AccessLogLineTest.EXPIRE);
        StreamContext ctx = ctx(Map.of());

        RawBatch batch = fetch(ctx).get(0);

        assertThat(batch.records()).hasSize(2);
        assertThat(batch.records().get(0).lenientText()).as("the quarantine sinks drop the NUL (Q-661)")
                .isEqualTo("garbage\0 line with \"unclosed");
        assertThat(AwsS3ForTests.parse(ctx, batch.records().get(0)).result())
                .isInstanceOfSatisfying(ParseResult.Quarantined.class, q -> assertThat(q.reason())
                        .isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_JSON));
        assertThat(AwsS3ForTests.parse(ctx, batch.records().get(1)).result()).isInstanceOf(ParseResult.Parsed.class);
    }

    @Test
    void anObjectThatIsNotUtf8IsQuarantinedWhole() {
        s3.put("2026-11-02-03-21-07-0A1B2C3D4E5F6A7D", new byte[] {(byte) 0xc3, (byte) 0x28, 0x41});
        StreamContext ctx = ctx(Map.of());

        RawBatch batch = fetch(ctx).get(0);

        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.ref().recordIndex()).isEqualTo(-1);
            assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                    q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_ENCODING));
        });
    }

    @Test
    void aProcessedObjectIsSkippedWithoutBeingRead() {
        String logObject = "2026-11-02-03-21-07-0A1B2C3D4E5F6A7E";
        s3.put(logObject, S3AccessLogLineTest.EXPIRE);
        StreamContext ctx = ctx(Map.of());
        RawBatch first = fetch(ctx).get(0);
        done.markDone("aws-test", "s1", logObject, first.object().get().etag(), 1, 0);
        s3.read.clear();

        assertThat(fetch(ctx)).singleElement().satisfies(b -> assertThat(b.object()).isEmpty());
        assertThat(s3.read).isEmpty();
    }

    @Test
    void aCloudTrailStreamCannotUseTheAccessLogTransport() {
        StreamContext wrong = AwsS3ForTests.context(S3ServerAccessLogTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, "us-east-1", Map.of("bucket", LOGS), done);
        assertThatThrownBy(() -> fetch(wrong)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cwl-json");
    }

    @Test
    void objectNamesCarryTheirTime() {
        assertThat(S3ServerAccessLogTransport.nameTime("2026-11-02-03-21-07-0A1B2C3D4E5F6A7B"))
                .contains(Instant.parse("2026-11-02T03:21:07Z"));
        assertThat(S3ServerAccessLogTransport.nameTime("2026-13-02-03-21-07-0A1B")).isEmpty();
        assertThat(S3ServerAccessLogTransport.nameTime("2026/11/02/x")).isEmpty();
    }
}
