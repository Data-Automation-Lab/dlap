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

package com.dlap.logingest.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The limits every quarantine sink applies before data leaves the service. */
class QuarantineBoundsTest {

    private static LogQuarantineRecord record(String excerpt, String detail) {
        return QuarantineFixtures.exact("dlap-rest-aws", "{}", QuarantineReason.QUARANTINE_REASON_INVALID_JSON, 0,
                Instant.parse("2026-10-20T12:00:00Z")).toBuilder().setRawExcerpt(excerpt).setReasonDetail(detail)
                .build();
    }

    @Test
    void aNulCharacterIsRemovedFromEveryTextFieldABoundedRecordCarries() {
        char nul = 0;
        LogQuarantineRecord r = record("{\"a\":\"x" + nul + "y\"}", "bad" + nul + "detail").toBuilder()
                .setNativeEventId("n" + nul + "1")
                .setRawRef(com.dlap.proto.log.v1.RawRecordRef.newBuilder().setTransport("S3" + nul + "_X")
                        .setUri("s3://b/k" + nul + "ey").setCursor("e" + nul + "tag").setRecordIndex(-1))
                .build();

        LogQuarantineRecord out = QuarantineBounds.bounded(r, 2048);

        assertThat(out.getRawExcerpt()).isEqualTo("{\"a\":\"xy\"}");
        assertThat(out.getReasonDetail()).isEqualTo("baddetail");
        assertThat(out.getNativeEventId()).isEqualTo("n1");
        assertThat(out.getRawRef().getTransport()).isEqualTo("S3_X");
        assertThat(out.getRawRef().getUri()).isEqualTo("s3://b/key");
        assertThat(out.getRawRef().getCursor()).isEqualTo("etag");
        assertThat(out.toString()).doesNotContain(String.valueOf(nul));
        assertThat(out.getRawRef().getRecordIndex()).isEqualTo(-1);
    }

    @Test
    void emailsAndAddressesAreMaskedAgainstAnyProducer() {
        LogQuarantineRecord r = record("{\"user\":\"alice@example.com\",\"ip\":\"203.0.113.10\","
                + "\"v6\":\"2001:db8::1\"}", "d");

        LogQuarantineRecord out = QuarantineBounds.bounded(r, 2048);

        assertThat(out.getRawExcerpt()).doesNotContain("alice@example.com", "203.0.113.10", "2001:db8::1")
                .contains("<email>", "<ip>");
    }

    @Test
    void theExcerptIsCutAtACharacterBoundaryAndFlaggedTruncated() {
        String text = "é".repeat(100);

        LogQuarantineRecord out = QuarantineBounds.bounded(record(text, "d"), 51);

        assertThat(out.getRawExcerpt().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(51);
        assertThat(out.getRawExcerpt()).isEqualTo("é".repeat(25));
        assertThat(out.getRawTruncated()).isTrue();
    }

    @Test
    void anExcerptThatFitsKeepsItsTruncatedFlag() {
        assertThat(QuarantineBounds.bounded(record("{}", "d"), 2048).getRawTruncated()).isFalse();
        assertThat(QuarantineBounds.bounded(record("{}", "d").toBuilder().setRawTruncated(true).build(), 2048)
                .getRawTruncated()).as("the mapper cut it already").isTrue();
    }

    @Test
    void theDetailIsBoundedTo1024BytesAndTheSchemaVersionIsV1() {
        LogQuarantineRecord out = QuarantineBounds.bounded(record("{}", "x".repeat(5000)).toBuilder()
                .setSchemaVersion("").build(), 2048);

        assertThat(out.getReasonDetail().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
        assertThat(out.getSchemaVersion()).isEqualTo("v1");
    }

    @Test
    void aZeroLimitKeepsNoExcerptAtAll() {
        LogQuarantineRecord out = QuarantineBounds.bounded(record("{\"a\":1}", "d"), 0);

        assertThat(out.getRawExcerpt()).isEmpty();
        assertThat(out.getRawTruncated()).isTrue();
    }

    @Test
    void reasonCodesRoundTripWithAndWithoutThePrefix() {
        for (QuarantineReason r : QuarantineReason.values()) {
            if (r == QuarantineReason.QUARANTINE_REASON_UNSPECIFIED || r == QuarantineReason.UNRECOGNIZED) {
                continue;
            }
            String code = QuarantineBounds.reasonCode(r);
            assertThat(code).doesNotStartWith("QUARANTINE_REASON_");
            assertThat(QuarantineBounds.parseReason(code)).isEqualTo(r);
            assertThat(QuarantineBounds.parseReason("QUARANTINE_REASON_" + code)).isEqualTo(r);
        }
        assertThat(QuarantineBounds.reasonCode(QuarantineReason.QUARANTINE_REASON_UNSPECIFIED)).isEqualTo("UNSPECIFIED");
    }

    @Test
    void anUnknownReasonIsRefused() {
        assertThatThrownBy(() -> QuarantineBounds.parseReason("NOT_A_REASON")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown quarantine reason");
        assertThatThrownBy(() -> QuarantineBounds.parseReason("UNSPECIFIED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
