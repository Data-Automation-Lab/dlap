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

import static com.dlap.logingest.parse.core.Stubs.event;
import static com.dlap.logingest.parse.core.Stubs.record;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Wire mapping of parse outcomes: events, quarantine records and the usage-dedup key. */
class CanonicalEventMapperTest {

    private static final Instant NOW = Instant.parse("2030-06-01T00:00:00Z");
    private static final CanonicalEventMapper MAPPER = new CanonicalEventMapper(Clock.fixed(NOW, ZoneOffset.UTC));
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    private static ParseOutcome parsed(RawRecord r, ParseResult result, Set<ParseWarning> recordWarnings) {
        ParseResult res = result instanceof ParseResult.Parsed p ? p.withWarnings(recordWarnings) : result;
        return new ParseOutcome(r, r.bindingId(), res, Optional.of(Stubs.KEY.at("1.13")),
                Optional.of(Stubs.KEY.at("1.11")), Optional.empty(), Optional.of("stub-parser@1.0.0"),
                Optional.of(SourceProvider.AWS), Optional.empty());
    }

    @Test
    void aParsedEventCarriesTheRegistryContextAndPassesDecodeStrict() {
        RawRecord r = RawRecord.builder().bindingId(Stubs.BINDING).sourceKind(Stubs.KIND).envelope(Stubs.ENVELOPE)
                .ref(RawRef.of("S3_TRAIL_FILES", "s3://trail/AWSLogs/f.json.gz", 3)).mode(IngestMode.BACKFILL)
                .fetchedAt(NOW).observedTime(Instant.parse("2026-03-01T12:05:00Z"))
                .text("{\"v\":\"1.13\",\"n\":12345678901234,\"deep\":{\"a\":[1,true,null,\"x\"]}}").build();
        CanonicalEvent e = event("evt-1").toBuilder()
                .target(CanonicalEvent.Resource.builder().id("arn:aws:s3:::bucket-b/k").kind("AWS::S3::Object")
                        .canonicalFrom("arn:aws:s3:::bucket-b/k", AssetIdHints.of(AssetKind.S3_OBJECT)).sizeBytes(0L).build())
                .addRelated(CanonicalEvent.Resource.builder().id("bucket-b").canonicalUri("s3://bucket-b")
                        .relation(ResourceRelation.RESOURCE_RELATION_PARENT).build())
                .sharedEventId("shared-1")
                .supersedesNativeKey("evt-0")
                .warning(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION)
                .build();
        List<DlapLogEvent> out = MAPPER.events(parsed(r, ParseResult.Parsed.of(e), Set.of(ParseWarning.PARSE_WARNING_NEWER_MINOR)));
        assertThat(out).hasSize(1);
        DlapLogEvent w = out.get(0);
        assertThat(CODEC.decodeStrict(CODEC.encode(w))).isEqualTo(w);
        assertThat(w.getEventId()).isEqualTo(LogEventIds.eventId(SourceProvider.AWS, "evt-1"));
        assertThat(w.getNativeEventId()).isEqualTo("evt-1");
        assertThat(w.getSourceFormat()).isEqualTo("TEST_AUDIT/json-record");
        assertThat(w.getSourceSchemaVersion()).as("the version detected on the record").isEqualTo("1.13");
        assertThat(w.getParserVersion()).isEqualTo("stub-parser@1.0.0");
        assertThat(w.getBindingId()).isEqualTo(Stubs.BINDING);
        assertThat(w.getOrigin()).isEqualTo(Origin.ORIGIN_BACKFILL);
        assertThat(w.getParseStatus()).isEqualTo(ParseStatus.PARSE_STATUS_WARNINGS);
        assertThat(w.getParseWarningsList()).containsExactly(ParseWarning.PARSE_WARNING_NEWER_MINOR,
                ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        assertThat(w.getIngestTime().getSeconds()).isEqualTo(NOW.getEpochSecond());
        assertThat(w.getEventTime().getSeconds()).isEqualTo(Stubs.T0.getEpochSecond());
        assertThat(w.getObservedTime().getSeconds()).as("observed time from the transport")
                .isEqualTo(Instant.parse("2026-03-01T12:05:00Z").getEpochSecond());
        assertThat(w.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-b/k");
        assertThat(w.getTargetResource().hasSizeBytes()).as("0 is a real size, sent with presence").isTrue();
        assertThat(w.getRelatedResources(0).getRelation()).isEqualTo(ResourceRelation.RESOURCE_RELATION_PARENT);
        assertThat(w.getSupersedesEventId()).isEqualTo(LogEventIds.eventId(SourceProvider.AWS, "evt-0"));
        assertThat(w.getSharedEventId()).isEqualTo("shared-1");
        assertThat(w.getSchemaVersion()).isEqualTo("v1");
        assertThat(w.getRawEvent().getFieldsOrThrow("n").getNumberValue()).isEqualTo(12345678901234.0);
        assertThat(w.getRawEvent().getFieldsOrThrow("deep").getStructValue().getFieldsOrThrow("a")
                .getListValue().getValuesCount()).isEqualTo(4);
    }

    @Test
    void parseStatusIsFullWithoutWarnings() {
        RawRecord r = record("{\"v\":\"1.0\"}");
        DlapLogEvent w = MAPPER.events(parsed(r, ParseResult.Parsed.of(event("a")), Set.of())).get(0);
        assertThat(w.getParseStatus()).isEqualTo(ParseStatus.PARSE_STATUS_FULL);
        assertThat(w.getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(w.hasObservedTime()).isFalse();
        assertThat(w.getTargetResource().hasSizeBytes()).as("unknown size is absent").isFalse();
    }

    @Test
    void aNonObjectRawValueIsWrapped() {
        RawRecord r = record("{\"v\":\"1.0\"}");
        CanonicalEvent e = event("a").toBuilder().raw(JsonNodeFactory.instance.textNode("line")).build();
        DlapLogEvent w = MAPPER.events(parsed(r, ParseResult.Parsed.of(e), Set.of())).get(0);
        assertThat(w.getRawEvent().getFieldsOrThrow("value").getStringValue()).isEqualTo("line");
    }

    @Test
    void usageDedupUsesSharedEventIdPlusTheObjectWhenPresent() {
        // Critic item 14: the requester's and the bucket owner's copies of one cross-account read
        // carry different eventIDs and one sharedEventID; they count once per object.
        RawRecord r = record("{\"v\":\"1.0\"}");
        CanonicalEvent.Resource obj = CanonicalEvent.Resource.builder().id("arn:aws:s3:::bucket-b/k")
                .canonicalFrom("arn:aws:s3:::bucket-b/k", AssetIdHints.of(AssetKind.S3_OBJECT)).build();
        CanonicalEvent.Resource other = CanonicalEvent.Resource.builder().id("arn:aws:s3:::bucket-b/k2")
                .canonicalFrom("arn:aws:s3:::bucket-b/k2", AssetIdHints.of(AssetKind.S3_OBJECT)).build();
        DlapLogEvent requester = wire(r, event("id-requester").toBuilder().target(obj).sharedEventId("s-1").build());
        DlapLogEvent owner = wire(r, event("id-owner").toBuilder().target(obj).sharedEventId("s-1").build());
        DlapLogEvent otherObject = wire(r, event("id-other").toBuilder().target(other).sharedEventId("s-1").build());
        DlapLogEvent unshared = wire(r, event("id-plain").toBuilder().target(obj).build());
        assertThat(requester.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-b/k");
        assertThat(otherObject.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-b/k2");
        assertThat(requester.getEventId()).isNotEqualTo(owner.getEventId());
        assertThat(CanonicalEventMapper.usageDedupKey(requester)).isEqualTo(CanonicalEventMapper.usageDedupKey(owner));
        assertThat(CanonicalEventMapper.usageDedupKey(otherObject)).isNotEqualTo(CanonicalEventMapper.usageDedupKey(owner));
        assertThat(CanonicalEventMapper.usageDedupKey(unshared)).isEqualTo(unshared.getEventId());
    }

    @ParameterizedTest(name = "{0} counts as usage: {1}")
    @CsvSource({"GetObject, true", "BatchGetItem, true", "ListBuckets, false", "DescribeTable, false",
        "GetBucketPolicy, false", "tabledataservice.list, false"})
    void onlyAGuessedContentReadCountsAsUsage(String operation, boolean usage) {
        CanonicalEvent e = event("op-" + operation).toBuilder().operation(operation)
                .classification(VerbHeuristics.classify(operation)).build();
        DlapLogEvent w = MAPPER.events(parsed(record("{\"v\":\"1.0\"}"), ParseResult.Parsed.of(e), Set.of())).get(0);
        assertThat(w.getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        assertThat(LogEventSemantics.isSuccessfulRead(w)).as("may move last_accessed_at").isEqualTo(usage);
        assertThat(LogEventSemantics.countsAsUsage(w)).isEqualTo(usage);
    }

    @Test
    void aSkippedRecordPublishesNothing() {
        RawRecord r = record("{}");
        ParseOutcome o = new ParseOutcome(r, r.bindingId(), new ParseResult.Skipped(SkipReason.NOT_AN_EVENT, ""),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(MAPPER.events(o)).isEmpty();
        assertThat(MAPPER.quarantine(o)).isEmpty();
    }

    @Test
    void aQuarantineRecordIsDeterministicRedactedAndBounded() {
        String raw = "{\"v\":\"9.0\",\"user\":\"alice@example.com\",\"ip\":\"203.0.113.7\",\"v6\":\"2001:db8::1\",\"pad\":\""
                + "x".repeat(5000) + "\"}";
        RawRecord r = record(raw);
        ParseOutcome o = new DefaultParserRegistry(List.of(new Stubs.Parser()), List.of(new Stubs.Detector()))
                .parse(r, Stubs.binding().pinned("1.08", OnMismatch.PARSE_WITH_DETECTED));
        LogQuarantineRecord q = MAPPER.quarantine(o).orElseThrow();
        assertThat(q.getReason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION);
        assertThat(q.getQuarantineId()).isEqualTo(LogEventIds.quarantineId(Stubs.BINDING, r.ref().toProto(), r.sha256()));
        assertThat(MAPPER.quarantine(o).orElseThrow().getQuarantineId()).isEqualTo(q.getQuarantineId());
        assertThat(q.getSourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(q.getSourceKind()).isEqualTo(Stubs.KIND);
        assertThat(q.getEnvelope()).isEqualTo(Stubs.ENVELOPE);
        assertThat(q.getDetectedVersion()).isEqualTo("9.0");
        assertThat(q.getPinnedVersion()).isEqualTo("1.08");
        assertThat(q.getRawSha256()).isEqualTo(r.sha256());
        assertThat(q.getRawSizeBytes()).isEqualTo(r.sizeBytes());
        assertThat(q.getRawTruncated()).isTrue();
        assertThat(q.getRawExcerpt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(RawExcerpts.DEFAULT_MAX_BYTES);
        assertThat(q.getRawExcerpt()).doesNotContain("alice@example.com", "203.0.113.7", "2001:db8::1")
                .contains(RawExcerpts.EMAIL_MASK, RawExcerpts.IP_MASK);
        assertThat(q.getReasonDetail().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ParseResult.MAX_DETAIL_BYTES);
        assertThat(q.getReasonDetail()).doesNotContain("alice");
        assertThat(q.getQuarantinedTime().getSeconds()).isEqualTo(NOW.getEpochSecond());
        assertThat(q.getSchemaVersion()).isEqualTo("v1");
        assertThat(CODEC.decodeMessage(CODEC.encodeMessage(q), LogQuarantineRecord.getDefaultInstance())).isEqualTo(q);
    }

    @Test
    void aPassThroughIsPublishedAsPartialAndNamedByTheQuarantineRecord() {
        RawRecord r = record("{\"v\":\"1.0\"}");
        CanonicalEvent pass = CanonicalEvent.builder().nativeKey("evt-9").provider(SourceProvider.AWS).operation("GetObject")
                .eventTime(Stubs.T0).passThrough().build();
        ParseOutcome o = parsed(r, new ParseResult.Quarantined(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                "target.id is missing", Optional.of(pass)), Set.of());
        DlapLogEvent w = MAPPER.events(o).get(0);
        assertThat(w.getParseStatus()).isEqualTo(ParseStatus.PARSE_STATUS_PARTIAL);
        assertThat(w.getChangeTypeValue()).isZero();
        assertThat(com.dlap.common.logevent.LogEventSemantics.mayChangeState(w)).isFalse();
        LogQuarantineRecord q = MAPPER.quarantine(o).orElseThrow();
        assertThat(q.getPassThroughEventId()).isEqualTo(w.getEventId());
        assertThat(q.getNativeEventId()).isEqualTo("evt-9");
        assertThat(q.getEventTime().getSeconds()).isEqualTo(Stubs.T0.getEpochSecond());
    }

    private static DlapLogEvent wire(RawRecord r, CanonicalEvent e) {
        return MAPPER.events(parsed(r, ParseResult.Parsed.of(e), Set.of())).get(0);
    }
}
