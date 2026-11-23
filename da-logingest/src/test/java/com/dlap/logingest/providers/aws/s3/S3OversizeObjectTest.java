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

import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.BUCKET;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.drain;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.gzip;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.lifecycleDelete;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.trailFile;
import static com.dlap.logingest.providers.aws.s3.AwsS3ForTests.trailKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.providers.aws.s3.AwsS3ForTests.DoneObjects;
import com.dlap.logingest.providers.aws.s3.AwsS3ForTests.FakeS3;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.proto.log.v1.QuarantineReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Q-705: an oversize object is quarantined as {@code OVERSIZE_RECORD}, never as {@code INVALID_JSON},
 * also when {@code max-object-bytes} is set below the binding's {@code max-record-bytes}. Before the
 * fix the sample of such an object was cut at {@code max-object-bytes}, a prefix of a JSON document
 * that is shorter than the record limit, so the registry reported it as invalid JSON.
 */
class S3OversizeObjectTest {

    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-11-02T03:00:00Z"),
            Instant.parse("2026-11-02T05:00:00Z"));
    private static final long RECORD_LIMIT = 1024;

    private final FakeS3 s3 = new FakeS3();
    private final DoneObjects done = new DoneObjects();
    private final CloudTrailTrailFileTransport transport = new CloudTrailTrailFileTransport(s3);

    private StreamContext ctx(String maxObjectBytes) {
        return AwsS3ForTests.context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, "us-east-1", Map.of("bucket", BUCKET, "max-object-bytes", maxObjectBytes),
                done, RECORD_LIMIT, null);
    }

    private List<RawBatch> fetch(StreamContext ctx) {
        return drain(transport.fetch(ctx, "us-east-1", CursorPosition.INITIAL, WINDOW));
    }

    private String bigTrailFile(int padding) {
        return trailFile(lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000000c0001", "2026-11-02T03:14:07Z",
                "raw/" + "e".repeat(padding) + ".parquet"));
    }

    @Test
    void anObjectAboveAMaxObjectBytesThatIsBelowTheRecordLimitIsQuarantinedAsOversizeNotInvalidJson() {
        s3.put(trailKey("", "us-east-1", "20261102T0315Z", "Small00000000001"), bigTrailFile(3000));
        StreamContext ctx = ctx("500");

        RawBatch batch = fetch(ctx).get(0);

        assertThat(s3.readLimits).as("the sample is one byte above the record limit, not max-object-bytes")
                .containsExactly(RECORD_LIMIT + 1);
        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.ref().recordIndex()).isEqualTo(-1);
            assertThat(r.sizeBytes()).isEqualTo(RECORD_LIMIT + 1);
            assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                    q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD));
        });
    }

    @Test
    void aMaxObjectBytesEqualToTheRecordLimitStillGivesAnOversizeSample() {
        s3.put(trailKey("", "us-east-1", "20261102T0315Z", "Equal00000000001"), bigTrailFile(3000));
        StreamContext ctx = ctx(Long.toString(RECORD_LIMIT));

        RawBatch batch = fetch(ctx).get(0);

        assertThat(batch.records()).singleElement().satisfies(r ->
                assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                        q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD)));
    }

    @Test
    void aGzipFileThatUnpacksAboveAMaxObjectBytesBelowTheRecordLimitIsQuarantinedAsOversize() {
        s3.put(trailKey("", "us-east-1", "20261102T0315Z", "Bomb000000000001"), gzip(bigTrailFile(200_000)));
        StreamContext ctx = ctx("300");

        RawBatch batch = fetch(ctx).get(0);

        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.sizeBytes()).isEqualTo(RECORD_LIMIT + 1);
            assertThat(AwsS3ForTests.parse(ctx, r).result()).isInstanceOfSatisfying(ParseResult.Quarantined.class,
                    q -> assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD));
        });
    }

    @Test
    void anObjectBetweenMaxObjectBytesAndTheRecordLimitIsReadNormally() {
        // About 700 bytes: above max-object-bytes 500, below the record limit 1024, so it is no oversize
        // object and no oversize record: it is split and parsed like any other.
        s3.put(trailKey("", "us-east-1", "20261102T0315Z", "Middle0000000001"), bigTrailFile(100));
        StreamContext ctx = ctx("500");

        RawBatch batch = fetch(ctx).get(0);

        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.ref().recordIndex()).isZero();
            assertThat(AwsS3ForTests.parse(ctx, r).result()).isNotInstanceOf(ParseResult.Quarantined.class);
        });
    }

    @Test
    void theEffectiveLimitNeverGoesBelowTheRecordLimit() {
        StreamContext ctx = ctx("10");

        assertThat(AbstractS3LogTransport.effectiveMaxObjectBytes(10, ctx)).isEqualTo(RECORD_LIMIT);
        assertThat(AbstractS3LogTransport.effectiveMaxObjectBytes(5000, ctx)).isEqualTo(5000);
    }
}
