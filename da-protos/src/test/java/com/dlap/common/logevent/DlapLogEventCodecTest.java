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

package com.dlap.common.logevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.proto.log.v1.BindingScope;
import com.dlap.proto.log.v1.BindingState;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.RawRecordRef;
import com.dlap.proto.log.v1.ReadCoverage;
import com.dlap.proto.log.v1.RecordCounts;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.util.Timestamps;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Edge cases of the reader and writer that the producer-shape tests do not reach. */
class DlapLogEventCodecTest {

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    @Test
    void emptyPayloadsAreRejected() {
        String bomOnly = String.valueOf((char) 0xFEFF);
        for (Object payload : new Object[] {null, "", "   ", new byte[0], bomOnly, "null", "\"\""}) {
            assertThatThrownBy(() -> codec.decode(payload))
                    .isInstanceOf(LogEventDecodeException.class)
                    .extracting(e -> ((LogEventDecodeException) e).getReason())
                    .isEqualTo(LogEventDecodeException.Reason.EMPTY_PAYLOAD);
        }
    }

    @Test
    void malformedPayloadsNameTheReasonWithoutQuotingContent() {
        assertReason("{\"eventId\": alice@example.com}", LogEventDecodeException.Reason.INVALID_JSON);
        assertReason("{} trailing", LogEventDecodeException.Reason.INVALID_JSON);
        assertReason("[1,2]", LogEventDecodeException.Reason.NOT_AN_OBJECT);
        assertReason("42", LogEventDecodeException.Reason.NOT_AN_OBJECT);
        assertReason("{\"eventTime\":\"2026-10-18T10:00:00\"}", LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH);
        assertReason("{\"eventTime\":1760781600000}", LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH);
        assertReason("{\"targetResource\":\"s3://b\"}", LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH);
        assertReason("{\"targetResource\":{\"sizeBytes\":\"ten\"}}",
                LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH);

        assertThatThrownBy(() -> codec.decode("{\"eventId\": alice@example.com}"))
                .hasMessageNotContaining("alice");
        assertThatThrownBy(() -> codec.decode(List.of("not", "a", "map")))
                .extracting(e -> ((LogEventDecodeException) e).getReason())
                .isEqualTo(LogEventDecodeException.Reason.UNSUPPORTED_PAYLOAD_TYPE);
    }

    @Test
    void stringWrappingIsUnwrappedTwiceAtMost() throws Exception {
        String once = ProducerShapes.MAPPER.writeValueAsString("{\"eventId\":\"e1\"}");
        String twice = ProducerShapes.MAPPER.writeValueAsString(once);
        String thrice = ProducerShapes.MAPPER.writeValueAsString(twice);

        assertThat(codec.decode(once).getEventId()).isEqualTo("e1");
        assertThat(codec.decode(twice).getEventId()).isEqualTo("e1");
        assertReason(thrice, LogEventDecodeException.Reason.NOT_AN_OBJECT);
    }

    @Test
    void forwardCompatibleReadDropsUnknownFieldsAndEnumValues() {
        String future = "{\"eventId\":\"e1\",\"futureField\":{\"x\":1},"
                + "\"targetResource\":{\"id\":\"s3://b\",\"futureNested\":true,\"relation\":\"FUTURE_RELATION\"},"
                + "\"eventCategory\":\"EVENT_CATEGORY_FUTURE\",\"parseWarnings\":[\"PARSE_WARNING_NEWER_MINOR\",99]}";

        DecodedLogEvent d = codec.decodeDetailed(future);

        assertThat(d.event().getEventId()).isEqualTo("e1");
        assertThat(d.event().getTargetResource().getId()).isEqualTo("s3://b");
        assertThat(d.event().getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_UNSPECIFIED);
        assertThat(d.event().getParseWarningsCount()).isEqualTo(1);
        assertThat(d.warnings()).contains(
                "dropped unknown field futureField",
                "dropped unknown field targetResource.futureNested",
                "dropped unknown ResourceRelation value targetResource.relation",
                "dropped unknown EventCategory value eventCategory",
                "dropped unknown ParseWarning value parseWarnings[1]");
        assertThat(d.warnings()).as("warnings name paths, never values")
                .noneMatch(w -> w.contains("FUTURE_RELATION") || w.contains("EVENT_CATEGORY_FUTURE"));
    }

    @Test
    void lenientEnumSpellingsResolveToTheCanonicalName() {
        DlapLogEvent e = codec.decode("{\"sourceProvider\":\"aws\",\"changeType\":\"archive\","
                + "\"eventCategory\":\"DATA_READ_OBSERVED\",\"origin\":\"backfill\",\"resultStatus\":2}");

        assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(e.getChangeType()).isEqualTo(ChangeType.ARCHIVE);
        assertThat(e.getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED);
        assertThat(e.getOriginValue()).isEqualTo(4);
        assertThat(e.getResultStatusValue()).isEqualTo(2);
    }

    @Test
    void protoFieldNamesAndJsonNamesAreBothAcceptedButNotTwice() {
        DecodedLogEvent d = codec.decodeDetailed(
                "{\"event_id\":\"a\",\"eventId\":\"b\",\"target_resource\":{\"canonical_uri\":\"s3://b\"}}");

        assertThat(d.event().getEventId()).isEqualTo("a");
        assertThat(d.event().getTargetResource().getCanonicalUri()).isEqualTo("s3://b");
        assertThat(d.warnings()).containsExactly("dropped duplicate field eventId");
    }

    @Test
    void legacyFlatKeysMapOntoTheTarget() {
        DecodedLogEvent d = codec.decodeDetailed("{\"assetUri\":\"gs://b/o\",\"cloud_provider\":\"GCP\","
                + "\"technicalMetadata\":{\"storageClass\":\"NEARLINE\",\"sizeBytes\":1024,\"public\":false,"
                + "\"policy\":{\"a\":1}}}");

        TargetResource t = d.event().getTargetResource();
        assertThat(t.getId()).isEqualTo("gs://b/o");
        assertThat(d.event().getSourceProvider()).isEqualTo(SourceProvider.GCP);
        assertThat(t.getTechnicalMetadataMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "storageClass", "NEARLINE", "sizeBytes", "1024", "public", "false", "policy", "{\"a\":1}"));
        assertThat(d.warnings()).contains("mapped legacy key assetUri to targetResource.id",
                "mapped legacy key cloud_provider to sourceProvider",
                "mapped legacy key technicalMetadata to targetResource.technicalMetadata");
    }

    @Test
    void nullsAndBlankScalarsMeanAbsent() {
        DecodedLogEvent d = codec.decodeDetailed("{\"eventId\":\"e\",\"eventTime\":null,\"ingestTime\":\"\","
                + "\"changeType\":\"\",\"rawEvent\":null,\"targetResource\":{\"sizeBytes\":\"\",\"labels\":null}}");

        assertThat(d.event().hasEventTime()).isFalse();
        assertThat(d.event().hasIngestTime()).isFalse();
        assertThat(d.event().hasRawEvent()).isFalse();
        assertThat(d.event().getTargetResource().hasSizeBytes()).isFalse();
        assertThat(d.warnings()).isEmpty();
    }

    @Test
    void zeroSizeIsARealSize() {
        DlapLogEvent e = DlapLogEvent.newBuilder()
                .setTargetResource(TargetResource.newBuilder().setId("s3://b/empty").setSizeBytes(0)).build();

        DlapLogEvent back = codec.decode(codec.encode(e));

        assertThat(back.getTargetResource().hasSizeBytes()).isTrue();
        assertThat(back.getTargetResource().getSizeBytes()).isZero();
        assertThat(codec.decode(codec.encode(DlapLogEvent.getDefaultInstance())).getTargetResource()
                .hasSizeBytes()).isFalse();
    }

    @Test
    void rawEventInOtherShapesIsKeptNotDropped() {
        DecodedLogEvent fromString = codec.decodeDetailed("{\"rawEvent\":\"{\\\"eventID\\\":\\\"x\\\"}\"}");
        DecodedLogEvent fromText = codec.decodeDetailed("{\"rawEvent\":\"plain text line\"}");

        assertThat(fromString.event().getRawEvent().getFieldsOrThrow("eventID").getStringValue()).isEqualTo("x");
        assertThat(fromString.warnings()).containsExactly("parsed string-encoded object rawEvent");
        assertThat(fromText.event().getRawEvent().getFieldsOrThrow("value").getStringValue())
                .isEqualTo("plain text line");
    }

    @Test
    void encodeFillsSchemaVersionAndWritesEveryNonMessageField() throws Exception {
        String json = codec.encode(DlapLogEvent.newBuilder().setEventId("e").build());

        Map<String, Object> map = ProducerShapes.MAPPER.readValue(json, Map.class);
        assertThat(map).containsEntry("schemaVersion", "v1")
                .containsEntry("changeType", "CHANGE_TYPE_UNSPECIFIED")
                .containsEntry("relatedResources", List.of())
                .doesNotContainKeys("eventTime", "targetResource", "rawEvent");
        assertThat(codec.encodeToBytes(DlapLogEvent.getDefaultInstance()))
                .isEqualTo(codec.encode(DlapLogEvent.getDefaultInstance()).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void strictReaderRejectsWhatTheLenientReaderRepairs() throws Exception {
        String canonical = codec.encode(DlapLogEvent.newBuilder().setEventId("e").build());

        assertThat(codec.decodeStrict(canonical).getEventId()).isEqualTo("e");
        assertThatThrownBy(() -> codec.decodeStrict(ProducerShapes.MAPPER.writeValueAsString(canonical)))
                .extracting(e -> ((LogEventDecodeException) e).getReason())
                .isEqualTo(LogEventDecodeException.Reason.NOT_AN_OBJECT);
        assertThatThrownBy(() -> codec.decodeStrict("{\"source\":\"crawler\"}"))
                .extracting(e -> ((LogEventDecodeException) e).getReason())
                .isEqualTo(LogEventDecodeException.Reason.UNKNOWN_FIELD);
        assertThatThrownBy(() -> codec.decodeStrict("{\"changeType\":\"BOGUS\"}"))
                .isInstanceOf(LogEventDecodeException.class);
    }

    @Test
    void messageInstancesPassThrough() {
        DlapLogEvent e = DlapLogEvent.newBuilder().setEventId("e").build();

        DecodedLogEvent d = codec.decodeDetailed(e);

        assertThat(d.event()).isSameAs(e);
        assertThat(d.encoding()).isEqualTo(PayloadEncoding.MESSAGE);
        assertThat(d.isLegacy()).isFalse();
    }

    @Test
    void quarantineRecordRoundTripsInBothEncodings() throws Exception {
        RawRecordRef ref = RawRecordRef.newBuilder().setTransport("S3_TRAIL_FILES")
                .setUri("s3://trail/AWSLogs/x.json.gz").setRecordIndex(7).build();
        String rawSha = "ab".repeat(32);
        LogQuarantineRecord record = LogQuarantineRecord.newBuilder()
                .setQuarantineId(LogEventIds.quarantineId("b-1", ref, rawSha))
                .setBindingId("b-1").setSourceProvider(SourceProvider.AWS).setSourceKind("AWS_CLOUDTRAIL")
                .setEnvelope("s3-file").setDetectedVersion("2.0")
                .setReason(QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION)
                .setReasonDetail("eventVersion major 2 is not supported").setRawRef(ref)
                .setRawExcerpt("{\"eventVersion\":\"2.0\"").setRawTruncated(true).setRawSha256(rawSha)
                .setRawSizeBytes(4096).setQuarantinedTime(Timestamps.parse("2026-10-18T10:00:00Z"))
                .setSchemaVersion("v1").build();

        String json = codec.encodeMessage(record);

        assertThat(codec.decodeMessage(json, LogQuarantineRecord.getDefaultInstance())).isEqualTo(record);
        assertThat(codec.decodeMessage(ProducerShapes.doubleEncoded(json), LogQuarantineRecord.getDefaultInstance()))
                .isEqualTo(record);
        assertThat(codec.decodeMessage(record, LogQuarantineRecord.getDefaultInstance())).isSameAs(record);
        assertThat(record.getQuarantineId())
                .isEqualTo("50a0e932c8023b3e052f1ea177b4e8cfefb6e379b2879497b93a8100669d4c67");
    }

    @Test
    void bindingStatusRoundTripsAndToleratesNewerFields() throws Exception {
        LogBindingStatus status = LogBindingStatus.newBuilder()
                .setBindingId("gcp-dlap-demo").setSourceProvider(SourceProvider.GCP).setSourceKind("GCP_CLOUD_AUDIT")
                .setScope(BindingScope.newBuilder().setScopeType("GCP_PROJECT").setScopeId("dlap-demo"))
                .setState(BindingState.BINDING_STATE_ACTIVE).setConfigHash("cd".repeat(32))
                .setReportedTime(Timestamps.parse("2026-10-18T10:00:00Z"))
                .setCounts(RecordCounts.newBuilder().setParsed(120).setQuarantined(1).setDuplicates(4))
                .setReadCoverage(ReadCoverage.READ_COVERAGE_CAPTURED)
                .setReadCoverageSince(Timestamps.parse("2026-10-01T00:00:00Z"))
                .setSchemaVersion("v1").build();

        String json = codec.encodeMessage(status);
        String newer = json.substring(0, json.length() - 1) + ",\"fieldFromALaterBuild\":1}";

        assertThat(codec.decodeMessage(json, LogBindingStatus.getDefaultInstance())).isEqualTo(status);
        assertThat(codec.decodeMessage(newer, LogBindingStatus.getDefaultInstance())).isEqualTo(status);
    }

    private void assertReason(String payload, LogEventDecodeException.Reason reason) {
        assertThatThrownBy(() -> codec.decode(payload))
                .as(payload)
                .isInstanceOf(LogEventDecodeException.class)
                .extracting(e -> ((LogEventDecodeException) e).getReason())
                .isEqualTo(reason);
    }
}
