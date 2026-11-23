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

package com.dlap.logingest.parse.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Raw records keep exact bytes, decode on demand, and excerpts are redacted and bounded. */
class RawRecordTest {

    @Test
    void exactBytesAreHashedAndDecodedLazily() {
        RawRecord r = Stubs.record("{\"a\": 1}");
        assertThat(r.sha256()).isEqualTo(LogEventIds.sha256Hex("{\"a\": 1}".getBytes(StandardCharsets.UTF_8)));
        assertThat(r.sizeBytes()).isEqualTo(8);
        assertThat(r.decode().ok()).isTrue();
        assertThat(r.body().get("a").asInt()).isEqualTo(1);
        assertThat(r.mode()).isEqualTo(IngestMode.STREAM);
        assertThat(r.observedTime()).isEmpty();
    }

    @Test
    void aJsonPayloadHashesItsCompactForm() throws Exception {
        RawRecord a = RawRecord.builder().bindingId("b").sourceKind("K").envelope("e").ref(RawRef.of("T", "u", 1))
                .fetchedAt(Stubs.T0).json(new ObjectMapper().readTree("{ \"x\" : [1, 2] }")).build();
        assertThat(new String(a.bytes(), StandardCharsets.UTF_8)).isEqualTo("{\"x\":[1,2]}");
        assertThat(a.body().get("x").size()).isEqualTo(2);
    }

    @Test
    void decodeFailuresAreReasonsNotExceptions() {
        RawRecord badUtf8 = Stubs.record(new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertThat(badUtf8.decode().failure()).isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_ENCODING);
        assertThatThrownBy(badUtf8::body).isInstanceOf(IllegalStateException.class);
        RawRecord badJson = Stubs.record("{\"secret\":\"do-not-echo\"");
        assertThat(badJson.decode().failure()).isEqualTo(QuarantineReason.QUARANTINE_REASON_INVALID_JSON);
        assertThat(badJson.decode().detail()).doesNotContain("do-not-echo");
    }

    @Test
    void identifiersAreValidatedAndAPayloadIsRequired() {
        assertThatThrownBy(() -> RawRecord.builder().bindingId("b").sourceKind("bad kind").envelope("e")
                .ref(RawRef.of("T", "u", 0)).fetchedAt(Stubs.T0).text("{}").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawRecord.builder().bindingId("b").sourceKind("K").envelope("e")
                .ref(RawRef.of("T", "u", 0)).fetchedAt(Stubs.T0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawRef.of("T", "u", -2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RawRef.of("S3_TRAIL_FILES", "s3://b/k", 3).toProto().getRecordIndex()).isEqualTo(3);
    }

    @Test
    void excerptsMaskEmailsAndAddressesAndKeepTimes() {
        String text = "{\"who\":\"bob.smith+x@corp.example.org\",\"ip\":\"198.51.100.23\",\"v6\":\"fe80::1ff:fe23:4567:890a\","
                + "\"mac\":\"00:1a:2b:3c:4d:5e\",\"at\":\"2026-01-01T00:05:00Z\",\"ver\":\"1.11\"}";
        RawExcerpts.Excerpt e = RawExcerpts.excerpt(text, 4096);
        assertThat(e.text()).doesNotContain("bob.smith", "198.51.100.23", "fe80::1ff", "00:1a:2b")
                .contains("2026-01-01T00:05:00Z", "\"1.11\"");
        assertThat(e.truncated()).isFalse();
    }

    @Test
    void excerptsAreBoundedWithoutSplittingCharactersOrLeakingAHalfAddress() {
        String text = "é".repeat(100) + "carol@example.net";
        RawExcerpts.Excerpt e = RawExcerpts.excerpt(text, 205);
        assertThat(e.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(205);
        assertThat(e.truncated()).isTrue();
        assertThat(e.text()).doesNotContain("carol");
        assertThat(RawExcerpts.excerpt("", 10).text()).isEmpty();
    }

    @Test
    void detailsAreBoundedToTheContractLimit() {
        String bounded = ParseResult.boundDetail("中".repeat(1000));
        assertThat(bounded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ParseResult.MAX_DETAIL_BYTES);
        assertThat(bounded).endsWith("...");
        assertThat(ParseResult.boundDetail("short")).isEqualTo("short");
    }

    @Test
    void outcomeCodesMatchTheFixtureVocabulary() {
        assertThat(ParseResult.Parsed.of(Stubs.event("a")).outcomeCode()).isEqualTo("PARSED");
        assertThat(new ParseResult.Skipped(SkipReason.NOT_AN_EVENT, "").outcomeCode()).isEqualTo("SKIPPED:NOT_AN_EVENT");
        assertThat(ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD, "").outcomeCode())
                .isEqualTo("QUARANTINED:OVERSIZE_RECORD");
        assertThatThrownBy(() -> ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_UNSPECIFIED, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseResult.Parsed(java.util.List.of(), java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
