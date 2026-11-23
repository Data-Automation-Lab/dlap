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

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * LOG-01 acceptance: the codec round-trips all three producer shapes (plus the
 * dormant analyzer {@code LogEvent}) and its output parses with {@code JsonFormat}. Each shape is read from the legacy
 * double encoding, from a single-encoded object (what the router forwards to
 * the typed topics) and from a Map (what Spring's JsonDeserializer hands
 * listeners); all three must give the same event.
 */
class DlapLogEventCodecProducerShapesTest {

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    static Stream<Arguments> shapes() throws Exception {
        return Stream.of(
                Arguments.of("logingest GCS read", ProducerShapes.logIngestGcsRead()),
                Arguments.of("logingest CloudTrail", ProducerShapes.logIngestCloudTrail()),
                Arguments.of("crawler bridge Unity table", ProducerShapes.crawlerBridgeUnityTable()),
                Arguments.of("crawler bridge unknown provider", ProducerShapes.crawlerBridgeUnknownProvider()),
                Arguments.of("log analyzer UPDATE", ProducerShapes.logAnalyzerChange("UPDATE")),
                Arguments.of("log analyzer UNKNOWN", ProducerShapes.logAnalyzerChange("UNKNOWN")),
                Arguments.of("dormant analyzer LogEvent", ProducerShapes.dormantAnalyzerLogEvent()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void everyEncodingOfAShapeDecodesToTheSameEvent(String name, String json) throws Exception {
        DecodedLogEvent wrapped = codec.decodeDetailed(ProducerShapes.doubleEncoded(json));
        DecodedLogEvent object = codec.decodeDetailed(json.getBytes(StandardCharsets.UTF_8));
        DecodedLogEvent map = codec.decodeDetailed(
                ProducerShapes.MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { }));
        DecodedLogEvent tree = codec.decodeDetailed(ProducerShapes.MAPPER.readTree(json));

        assertThat(wrapped.encoding()).isEqualTo(PayloadEncoding.STRING_WRAPPED_JSON);
        assertThat(object.encoding()).isEqualTo(PayloadEncoding.JSON_OBJECT);
        assertThat(map.encoding()).isEqualTo(PayloadEncoding.TREE);
        assertThat(object.event()).isEqualTo(wrapped.event()).isEqualTo(map.event()).isEqualTo(tree.event());
        assertThat(wrapped.isLegacy()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void encodedShapeRoundTripsAndParsesWithJsonFormat(String name, String json) throws Exception {
        DlapLogEvent decoded = codec.decode(ProducerShapes.doubleEncoded(json));

        String encoded = codec.encode(decoded);
        DlapLogEvent.Builder strict = DlapLogEvent.newBuilder();
        JsonFormat.parser().merge(encoded, strict);

        assertThat(strict.build()).isEqualTo(decoded);
        assertThat(codec.decodeStrict(encoded)).isEqualTo(decoded);
        assertThat(codec.decode(encoded)).isEqualTo(decoded);
        assertThat(codec.encode(codec.decode(encoded))).isEqualTo(encoded);
        JsonNode root = ProducerShapes.MAPPER.readTree(encoded);
        assertThat(root.isObject()).as("single encoding: a JSON object, not a string").isTrue();
        assertThat(codec.decodeDetailed(encoded).warnings()).isEmpty();
    }

    @Test
    void logIngestGcsReadMapsEveryField() throws Exception {
        DlapLogEvent e = codec.decode(ProducerShapes.doubleEncoded(ProducerShapes.logIngestGcsRead()));

        assertThat(e.getEventId()).isEqualTo(ProducerShapes.GCP_EVENT_ID);
        assertThat(Timestamps.toString(e.getEventTime())).isEqualTo("2026-10-18T09:15:02.123456Z");
        assertThat(Timestamps.toString(e.getIngestTime())).isEqualTo("2026-10-18T09:16:00.500Z");
        assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.GCP);
        assertThat(e.getSourceService()).isEqualTo("storage.googleapis.com");
        assertThat(e.getChangeType()).isEqualTo(ChangeType.ACCESS);
        assertThat(e.getOperation()).isEqualTo("storage.objects.get");
        assertThat(e.getResultStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.USER);
        assertThat(e.getPrincipal().getId()).isEqualTo("alice@example.com");
        assertThat(e.getPrincipal().getAccountId()).isEqualTo("dlap-demo");
        assertThat(e.getTargetResource().getId()).isEqualTo(ProducerShapes.GCS_OBJECT);
        assertThat(e.getTargetResource().getKind()).isEqualTo("gcs_bucket");
        assertThat(e.getTargetResource().getProject()).isEqualTo("dlap-demo");
        assertThat(e.getRawEvent().getFieldsOrThrow("insertId").getStringValue()).isEqualTo("1a2b3c4d5e");
        assertThat(e.getRawEvent().getFieldsOrThrow("protoPayload").getStructValue()
                .getFieldsOrThrow("methodName").getStringValue()).isEqualTo("storage.objects.get");
        assertThat(e.getSchemaVersion()).isEqualTo("v1");
        // Legacy pipeline: nothing reported about origin, format or category.
        assertThat(e.getOrigin()).isEqualTo(Origin.ORIGIN_UNSPECIFIED);
        assertThat(e.getSourceFormat()).isEmpty();
        assertThat(LogEventSemantics.effectiveCategory(e)).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
    }

    @Test
    void gcpEventIdOfTheShapeIsTheFrozenNativeKeyHash() {
        String nativeKey = LogEventIds.gcpAuditNativeKey(
                "projects/dlap-demo/logs/cloudaudit.googleapis.com%2Fdata_access",
                Instant.parse("2026-10-18T09:15:02.123456Z"), "1a2b3c4d5e");

        assertThat(LogEventIds.eventId(SourceProvider.GCP, nativeKey)).isEqualTo(ProducerShapes.GCP_EVENT_ID);
    }

    @Test
    void logIngestEmptyPrincipalTypeBecomesUnspecifiedWithoutWarning() throws Exception {
        DecodedLogEvent d = codec.decodeDetailed(ProducerShapes.doubleEncoded(ProducerShapes.logIngestCloudTrail()));

        assertThat(d.event().getPrincipal().getType()).isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
        assertThat(d.event().getSourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(d.event().getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(d.event().getRawEvent().getFieldsOrThrow("cloudTrailEvent").getStringValue())
                .startsWith("{\"eventVersion\":\"1.11\"");
        assertThat(d.warnings()).isEmpty();
    }

    @Test
    void crawlerBridgeKeepsTechnicalMetadataAndMapsTheProducerMarker() throws Exception {
        DecodedLogEvent d = codec.decodeDetailed(
                ProducerShapes.doubleEncoded(ProducerShapes.crawlerBridgeUnityTable()));
        DlapLogEvent e = d.event();

        assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.UNITY);
        assertThat(e.getChangeType()).isEqualTo(ChangeType.CREATE);
        assertThat(e.getOperation()).isEqualTo("DISCOVERY");
        assertThat(e.getOrigin()).isEqualTo(Origin.ORIGIN_CRAWLER);
        assertThat(e.getSourceFormat()).isEqualTo(SourceFormats.DLAP_CRAWLER);
        assertThat(e.getTargetResource().getId()).isEqualTo("main.sales.orders");
        assertThat(e.getTargetResource().getKind()).isEqualTo("UNITY_TABLE");
        assertThat(e.getTargetResource().getTechnicalMetadataMap())
                .containsEntry("tableType", "MANAGED").containsEntry("dataSourceFormat", "DELTA");
        assertThat(e.hasIngestTime()).as("the codec never invents a DLAP clock").isFalse();
        assertThat(d.warnings()).containsExactly("mapped legacy key source to origin and sourceFormat");
    }

    @Test
    void unknownProviderAndChangeTypeBecomeUnspecifiedWithAWarning() throws Exception {
        DecodedLogEvent crawler = codec.decodeDetailed(ProducerShapes.crawlerBridgeUnknownProvider());
        DecodedLogEvent analyzer = codec.decodeDetailed(ProducerShapes.logAnalyzerChange("UNKNOWN"));

        assertThat(crawler.event().getSourceProvider()).isEqualTo(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED);
        assertThat(crawler.event().getEventId()).isEqualTo("s3://dlap-demo-bucket:UPDATE:2026-10-18T07:31:00Z");
        assertThat(crawler.warnings()).contains("dropped unknown SourceProvider value sourceProvider");
        assertThat(analyzer.event().getChangeType()).isEqualTo(ChangeType.CHANGE_TYPE_UNSPECIFIED);
        assertThat(analyzer.event().getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(analyzer.event().getSourceFormat()).isEqualTo(SourceFormats.DLAP_LOGANALYZER);
        assertThat(LogEventSemantics.mayChangeState(analyzer.event())).isFalse();
    }

    @Test
    void dormantAnalyzerLogEventDecodesButChangesNoState() throws Exception {
        DecodedLogEvent d = codec.decodeDetailed(ProducerShapes.dormantAnalyzerLogEvent());
        DlapLogEvent e = d.event();

        assertThat(e.getEventId()).isEqualTo("8a3c5e7f-1b2d-4e6f-8a9b-0c1d2e3f4a5b");
        assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(e.getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(e.getSourceFormat()).isEqualTo(SourceFormats.DLAP_LOGANALYZER);
        // eventTimestamp is the analyzer's clock, not the provider's: never business time.
        assertThat(e.hasEventTime()).isFalse();
        assertThat(e.getChangeType()).isEqualTo(ChangeType.CHANGE_TYPE_UNSPECIFIED);
        assertThat(LogEventSemantics.mayChangeState(e)).isFalse();
        assertThat(d.warnings()).contains("dropped unknown field eventTimestamp", "dropped unknown field accountId",
                "dropped unknown field payload", "mapped legacy key cloudProvider to sourceProvider");
    }

    @Test
    void providerIdentityTypesFromTheReplayFixturesMapToPrincipalTypes() {
        // e2e-tests/fixtures/replay/*.json send CloudTrail userIdentity.type and Azure
        // principal types verbatim through REST ingest.
        Map<String, PrincipalType> expected = Map.of(
                "IAMUser", PrincipalType.USER,
                "Root", PrincipalType.USER,
                "AssumedRole", PrincipalType.ROLE,
                "AWSService", PrincipalType.SYSTEM,
                "SERVICE_PRINCIPAL", PrincipalType.SERVICE_ACCOUNT,
                "ServicePrincipal", PrincipalType.SERVICE_ACCOUNT,
                "serviceAccount", PrincipalType.SERVICE_ACCOUNT,
                "user", PrincipalType.USER);
        expected.forEach((spelling, type) -> {
            DecodedLogEvent d = codec.decodeDetailed("{\"principal\":{\"type\":\"" + spelling + "\"}}");
            assertThat(d.event().getPrincipal().getType()).as(spelling).isEqualTo(type);
            assertThat(d.warnings()).as(spelling).containsExactly("normalized PrincipalType value principal.type");
        });
        DecodedLogEvent unknown = codec.decodeDetailed("{\"principal\":{\"type\":\"AWSAccount\"}}");
        assertThat(unknown.event().getPrincipal().getType()).isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
        assertThat(unknown.warnings()).containsExactly("dropped unknown PrincipalType value principal.type");
    }

    @Test
    void encodedEventKeepsTheKeysTodaysMapConsumersRead() throws Exception {
        DlapLogEvent e = codec.decode(ProducerShapes.doubleEncoded(ProducerShapes.crawlerBridgeUnityTable()));

        Map<String, Object> map = codec.toMap(e);

        // DiscoveryLogEventProcessor reads changeType/eventId/targetResource.id;
        // DiscoveryAssetDiscoveredListener reads sourceProvider, kind, name,
        // technicalMetadata and eventTime.
        assertThat(map).containsEntry("changeType", "CREATE").containsEntry("eventId", e.getEventId())
                .containsEntry("sourceProvider", "UNITY").containsEntry("eventTime", "2026-10-18T07:30:00Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) map.get("targetResource");
        assertThat(target).containsEntry("id", "main.sales.orders").containsEntry("kind", "UNITY_TABLE")
                .containsEntry("name", "orders");
        assertThat(target.get("technicalMetadata")).isEqualTo(Map.of("dataSourceFormat", "DELTA",
                "tableType", "MANAGED"));
    }
}
