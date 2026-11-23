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

package com.dlap.logingest.parse.dlapnormalized;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code DLAP_NORMALIZED} pass-through family: a normalized DTO becomes a canonical event with
 * the legacy pipeline's {@code event_id}, the spec's derived category marked heuristic, a
 * normalized target, the caller's {@code rawEvent}, and a pass-through quarantine when the change
 * type or result is missing or unknown.
 */
class DlapNormalizedParserTest {

    private static final Instant T = Instant.parse("2026-07-05T10:00:00Z");
    private final DefaultParserRegistry registry = new DefaultParserRegistry(
            DlapNormalizedFormat.providers().stream().map(DlapNormalizedParser::new).toList(),
            DlapNormalizedFormat.providers().stream().map(p -> (VersionDetector) new DlapNormalizedDetector(p)).toList());
    private final CanonicalEventMapper mapper = new CanonicalEventMapper(
            Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));

    private static final String DTO = """
            {"sourceProvider":"AWS","sourceService":"s3.amazonaws.com","operation":"PutBucket","changeType":"CREATE",
             "resultStatus":"SUCCESS","eventTime":"2026-07-05T10:00:00Z","principalId":"AIDAEXAMPLE0001",
             "principalType":"IAMUser","principalDisplayName":"alice","principalAccountId":"123456789012",
             "targetResourceKind":"AWS::S3::Bucket","targetResourceId":"s3://dlap-aws-e2e-logs",
             "targetResourceName":"dlap-aws-e2e-logs","targetProject":"123456789012","targetRegion":"us-east-1",
             "callerIp":"203.0.113.10","userAgent":"aws-cli/2.15.0","requestId":"aws-evt-0001-create-logs",
             "rawEvent":{"eventSource":"s3.amazonaws.com","eventName":"CreateBucket"}}
            """;

    private ParseOutcome parse(String json, IngestMode mode) {
        RawRecord r = RawRecord.builder().bindingId("dlap-rest-aws").sourceKind("DLAP_NORMALIZED_AWS")
                .envelope("ingest-dto").ref(RawRef.of("REST", "rest:/api/v1/logingest/events", -1)).mode(mode)
                .fetchedAt(Instant.parse("2026-10-19T00:00:00Z")).text(json).build();
        return registry.parse(r, BindingConfig.auto("dlap-rest-aws", "DLAP_NORMALIZED_AWS"));
    }

    /** The legacy formula of {@code LogIngestServiceImpl.computeEventId}, written out independently. */
    private static String legacyEventId(String provider, String key) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest((provider + "|" + key).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h);
    }

    @Test
    void aDtoBecomesACanonicalEventWithTheLegacyEventId() throws Exception {
        ParseOutcome o = parse(DTO, IngestMode.STREAM);

        assertThat(o.outcomeCode()).isEqualTo("PARSED_WITH_WARNINGS");
        DlapLogEvent e = mapper.events(o).get(0);
        assertThat(e.getEventId()).isEqualTo(legacyEventId("AWS", "aws-evt-0001-create-logs"));
        assertThat(e.getNativeEventId()).isEqualTo("aws-evt-0001-create-logs");
        assertThat(e.getSourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(e.getSourceFormat()).isEqualTo("DLAP_NORMALIZED_AWS/ingest-dto");
        assertThat(e.getSourceSchemaVersion()).isEqualTo("v1");
        assertThat(e.getParserVersion()).isEqualTo("dlap-normalized@1.0.0");
        assertThat(e.getBindingId()).isEqualTo("dlap-rest-aws");
        assertThat(e.getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(e.getChangeType()).isEqualTo(ChangeType.CREATE);
        assertThat(e.getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(e.getResultStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(e.getParseStatus()).isEqualTo(ParseStatus.PARSE_STATUS_WARNINGS);
        assertThat(e.getParseWarningsList()).containsExactly(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        assertThat(e.getEventTime().getSeconds()).isEqualTo(T.getEpochSecond());
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.USER);
        assertThat(e.getPrincipal().getId()).isEqualTo("AIDAEXAMPLE0001");
        assertThat(e.getTargetResource().getId()).isEqualTo("s3://dlap-aws-e2e-logs");
        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://dlap-aws-e2e-logs");
        assertThat(e.getRequestMetadata().getRequestId()).isEqualTo("aws-evt-0001-create-logs");
        assertThat(e.getRawEvent().getFieldsMap()).containsOnlyKeys("eventSource", "eventName");
        assertThat(DlapLogEventCodec.defaults().decodeStrict(DlapLogEventCodec.defaults().encode(e)).getEventId())
                .isEqualTo(e.getEventId());
    }

    @Test
    void withoutARequestIdTheKeyIsTheLegacyFallback() throws Exception {
        ParseOutcome o = parse(DTO.replace("\"requestId\":\"aws-evt-0001-create-logs\",", ""), IngestMode.STREAM);

        String key = "PutBucket|" + T + "|s3://dlap-aws-e2e-logs|AIDAEXAMPLE0001";
        assertThat(mapper.events(o).get(0).getEventId()).isEqualTo(legacyEventId("AWS", key));
        assertThat(mapper.events(o).get(0).getNativeEventId()).isEqualTo(key);
    }

    @Test
    void accessIsADerivedDataReadAndPermissionAPermission() {
        assertThat(mapper.events(parse(DTO.replace("\"CREATE\"", "\"ACCESS\""), IngestMode.STREAM)).get(0)
                .getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
        assertThat(mapper.events(parse(DTO.replace("\"CREATE\"", "\"permission\""), IngestMode.STREAM)).get(0)
                .getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_PERMISSION);
    }

    @Test
    void replayModeStampsTheReplayOrigin() {
        assertThat(mapper.events(parse(DTO, IngestMode.REPLAY)).get(0).getOrigin()).isEqualTo(Origin.ORIGIN_REPLAY);
    }

    @Test
    void aMissingResultIsQuarantinedWithAPassThroughThatNeverRoutes() {
        ParseOutcome o = parse(DTO.replace("\"resultStatus\":\"SUCCESS\",", ""), IngestMode.STREAM);

        assertThat(o.outcomeCode()).isEqualTo("QUARANTINED:MISSING_REQUIRED_FIELD");
        List<DlapLogEvent> events = mapper.events(o);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getParseStatus()).isEqualTo(ParseStatus.PARSE_STATUS_PARTIAL);
            assertThat(e.getChangeType()).isEqualTo(ChangeType.CHANGE_TYPE_UNSPECIFIED);
        });
        assertThat(mapper.quarantine(o)).hasValueSatisfying(q -> {
            assertThat(q.getReasonDetail()).isEqualTo("resultStatus is missing");
            assertThat(q.getPassThroughEventId()).isEqualTo(events.get(0).getEventId());
        });
    }

    @Test
    void anUnknownChangeTypeIsQuarantinedAsAMismatch() {
        assertThat(parse(DTO.replace("\"CREATE\"", "\"EXPLODE\""), IngestMode.STREAM).outcomeCode())
                .isEqualTo("QUARANTINED:FIELD_TYPE_MISMATCH");
    }

    @Test
    void noEventTimeIsSkippedAndABadOneQuarantined() {
        assertThat(parse(DTO.replace("\"eventTime\":\"2026-07-05T10:00:00Z\",", ""), IngestMode.STREAM).outcomeCode())
                .isEqualTo("SKIPPED:MISSING_EVENT_TIME");
        assertThat(parse(DTO.replace("2026-07-05T10:00:00Z", "yesterday"), IngestMode.STREAM).outcomeCode())
                .isEqualTo("QUARANTINED:FIELD_TYPE_MISMATCH");
    }

    @Test
    void aRecordOfAnotherProviderIsQuarantined() {
        assertThat(parse(DTO.replace("\"sourceProvider\":\"AWS\"", "\"sourceProvider\":\"GCP\""), IngestMode.STREAM)
                .outcomeCode()).isEqualTo("QUARANTINED:FIELD_TYPE_MISMATCH");
    }

    @Test
    void anOverLongRequestIdIsQuarantinedNotThrown() {
        assertThat(parse(DTO.replace("aws-evt-0001-create-logs", "x".repeat(5000)), IngestMode.STREAM).outcomeCode())
                .isEqualTo("QUARANTINED:FIELD_TYPE_MISMATCH");
    }

    @Test
    void onePairOfBeansIsRegisteredPerProvider() {
        new ApplicationContextRunner().withUserConfiguration(DlapNormalizedConfiguration.class).run(ctx -> {
            assertThat(ctx.getBeansOfType(DlapNormalizedParser.class)).hasSize(DlapNormalizedFormat.providers().size());
            assertThat(ctx.getBeansOfType(DlapNormalizedDetector.class)).hasSize(DlapNormalizedFormat.providers().size());
        });
        assertThat(DlapNormalizedFormat.provider("aws")).contains(SourceProvider.AWS);
        assertThat(DlapNormalizedFormat.provider("IBM")).isEmpty();
    }
}
