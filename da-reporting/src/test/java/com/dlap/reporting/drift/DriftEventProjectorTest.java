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

package com.dlap.reporting.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.support.KafkaNull;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.dlap.reporting.identity.IdentityProviderScope;
import com.dlap.reporting.identity.PrincipalIdentityResolver;
import com.dlap.reporting.identity.ResolvedPrincipal;
import com.google.protobuf.Timestamp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Projection of the two drift inputs: event time only, canonical asset keys, mapped principals,
 * and one counted skip reason for every event that does not join.
 */
class DriftEventProjectorTest {

    /** The service clock in these tests. */
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    /** When the read and the decision happened: a day before {@link #NOW}, so no wall clock can stand in. */
    private static final Instant EVENT_TIME = Instant.parse("2026-10-19T08:30:00Z");

    private final PrincipalIdentityResolver identities = mock(PrincipalIdentityResolver.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DriftEventProjector projector;

    private static DriftEventProjector projector(PrincipalIdentityResolver identities, SimpleMeterRegistry meters,
                                                 long skewSeconds) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        return new DriftEventProjector(identities, meters, beans.getBeanProvider(Clock.class), skewSeconds);
    }

    @BeforeEach
    void setUp() {
        projector = projector(identities, meters, 300L);
        when(identities.resolve(any(), anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(1);
            return raw.isBlank() ? Optional.empty() : Optional.of(new ResolvedPrincipal("mapped:" + raw.strip(), raw, true));
        });
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    /** A GCS data read as the registry pipeline emits it. */
    private static DlapLogEvent.Builder gcsRead() {
        return DlapLogEvent.newBuilder()
                .setEventId("evt-gcs-1")
                .setEventTime(ts(EVENT_TIME))
                .setIngestTime(ts(NOW))
                .setSourceProvider(SourceProvider.GCP)
                .setChangeType(ChangeType.ACCESS)
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ)
                .setResultStatus(ResultStatus.SUCCESS)
                .setParseStatus(ParseStatus.PARSE_STATUS_FULL)
                .setOperation("storage.objects.get")
                .setPrincipal(Principal.newBuilder().setId("agent@acme-prod.iam.gserviceaccount.com"))
                .setTargetResource(TargetResource.newBuilder()
                        .setId("projects/_/buckets/sales-exports/objects/2026/q3.csv")
                        .setCanonicalUri("gs://sales-exports/2026/q3.csv"));
    }

    private static String wire(DlapLogEvent.Builder event) {
        return DlapLogEventCodec.defaults().encode(event.build());
    }

    private static Map<String, Object> decision() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("decisionId", "dec-1");
        e.put("timestamp", EVENT_TIME.toString());
        e.put("subjectType", "agent");
        e.put("subjectId", "svc-marketing-agent");
        e.put("action", "read");
        e.put("assetId", "9f6d2a1e-0000-4000-8000-000000000001");
        e.put("assetUri", "gs://sales-exports");
        e.put("decision", "ALLOW");
        return e;
    }

    private double skipped(DriftSkipReason reason) {
        return meters.find(DriftEventSkips.SKIPPED_COUNTER).tag("reason", reason.tag()).counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    // ---------------------------------------------------------------- reads

    @Test
    void read_takesEventTime_canonicalAssetAndMappedSubject() {
        DriftEventProjector.Projection<ReadRecord> p = projector.projectRead(wire(gcsRead()));

        assertThat(p.isAccepted()).isTrue();
        assertThat(p.record()).isEqualTo(new ReadRecord("evt-gcs-1",
                "mapped:agent@acme-prod.iam.gserviceaccount.com", "gs://sales-exports", EVENT_TIME));
        verify(identities).resolve(IdentityProviderScope.GCP, "agent@acme-prod.iam.gserviceaccount.com");
        assertThat(meters.counter(DriftEventProjector.ASSET_KEYS_COUNTER, "stream", "read", "form", "canonical")
                .count()).isEqualTo(1.0);
    }

    @Test
    void read_acceptsTheDeserializedMapAndTheLegacyDoubleEncoding() {
        Map<String, Object> asMap = DlapLogEventCodec.defaults().toMap(gcsRead().build());
        String doubleEncoded = '"' + wire(gcsRead()).replace("\\", "\\\\").replace("\"", "\\\"") + '"';

        assertThat(projector.projectRead(asMap).record().assetId()).isEqualTo("gs://sales-exports");
        assertThat(projector.projectRead(doubleEncoded).record().assetId()).isEqualTo("gs://sales-exports");
        assertThat(projector.projectRead(wire(gcsRead()).getBytes(StandardCharsets.UTF_8)).isAccepted()).isTrue();
    }

    @Test
    void legacyRead_withoutCategoryOrStatus_isAReadOnItsChangeType() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("eventId", "evt-legacy");
        legacy.put("eventTime", EVENT_TIME.toString());
        legacy.put("changeType", "ACCESS");
        legacy.put("targetResource", Map.of("id", "asset-1"));
        legacy.put("principal", Map.of("id", "svc-a"));

        DriftEventProjector.Projection<ReadRecord> p = projector.projectRead(legacy);

        assertThat(p.record()).isEqualTo(new ReadRecord("evt-legacy", "mapped:svc-a", "asset-1", EVENT_TIME));
        verify(identities).resolve(null, "svc-a");
        assertThat(meters.counter(DriftEventProjector.ASSET_KEYS_COUNTER, "stream", "read", "form", "native")
                .count()).isEqualTo(1.0);
    }

    @Test
    void read_withoutEventTime_isSkipped_neverStampedWithTheClock() {
        DriftEventProjector.Projection<ReadRecord> p = projector.projectRead(wire(gcsRead().clearEventTime()));

        assertThat(p.isAccepted()).isFalse();
        assertThat(p.skip()).isEqualTo(DriftSkipReason.MISSING_EVENT_TIME);
        assertThat(p.ref()).isEqualTo("evt-gcs-1");
    }

    @Test
    void read_furtherAheadThanTheSkew_isSkipped_butWithinTheSkewJoins() {
        assertThat(projector.projectRead(wire(gcsRead().setEventTime(ts(NOW.plusSeconds(301))))).skip())
                .isEqualTo(DriftSkipReason.FUTURE_EVENT_TIME);
        assertThat(projector.projectRead(wire(gcsRead().setEventTime(ts(NOW.plusSeconds(300))))).isAccepted())
                .isTrue();
    }

    @Test
    void negativeSkew_disablesTheFutureCheck() {
        DriftEventProjector lenient = projector(identities, meters, -1L);

        assertThat(lenient.projectRead(wire(gcsRead().setEventTime(ts(NOW.plusSeconds(86_400))))).isAccepted())
                .isTrue();
    }

    @Test
    void reads_thatAreNotRecordedSuccessfulDataReads_areSkippedWithTheirReason() {
        assertThat(projector.projectRead(wire(gcsRead().setResultStatus(ResultStatus.FAILURE))).skip())
                .isEqualTo(DriftSkipReason.NOT_SUCCESSFUL);
        assertThat(projector.projectRead(wire(gcsRead().setResultStatus(ResultStatus.PENDING))).skip())
                .isEqualTo(DriftSkipReason.NOT_SUCCESSFUL);
        assertThat(projector.projectRead(wire(gcsRead()
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED))).skip())
                .as("an inventory last-access time is not a request with a principal")
                .isEqualTo(DriftSkipReason.NOT_A_DATA_READ);
        assertThat(projector.projectRead(wire(gcsRead()
                .setEventCategory(EventCategory.EVENT_CATEGORY_MANAGEMENT))).skip())
                .isEqualTo(DriftSkipReason.NOT_A_DATA_READ);
        assertThat(projector.projectRead(wire(gcsRead().setParseStatus(ParseStatus.PARSE_STATUS_PARTIAL))).skip())
                .isEqualTo(DriftSkipReason.PARTIAL_PARSE);
    }

    @Test
    void reads_missingAJoinKey_areSkippedWithTheirReason() {
        assertThat(projector.projectRead(wire(gcsRead().setEventId(" "))).skip())
                .isEqualTo(DriftSkipReason.MISSING_EVENT_ID);
        assertThat(projector.projectRead(wire(gcsRead().setTargetResource(TargetResource.getDefaultInstance()))).skip())
                .isEqualTo(DriftSkipReason.MISSING_ASSET);
        assertThat(projector.projectRead(wire(gcsRead().setPrincipal(Principal.getDefaultInstance()))).skip())
                .isEqualTo(DriftSkipReason.MISSING_PRINCIPAL);
    }

    @Test
    void reads_withOversizedIds_areSkipped() {
        assertThat(projector.projectRead(wire(gcsRead().setEventId("e".repeat(201)))).skip())
                .isEqualTo(DriftSkipReason.FIELD_TOO_LONG);
        assertThat(projector.projectRead(wire(gcsRead().setTargetResource(
                TargetResource.newBuilder().setId("x".repeat(513))))).skip())
                .isEqualTo(DriftSkipReason.FIELD_TOO_LONG);
        assertThat(projector.projectRead(wire(gcsRead().setPrincipal(
                Principal.newBuilder().setId("p".repeat(600))))).skip())
                .isEqualTo(DriftSkipReason.FIELD_TOO_LONG);
    }

    @Test
    void undecodableOrEmptyReads_areSkipped() {
        assertThat(projector.projectRead(null).skip()).isEqualTo(DriftSkipReason.NULL_PAYLOAD);
        assertThat(projector.projectRead(KafkaNull.INSTANCE).skip()).isEqualTo(DriftSkipReason.NULL_PAYLOAD);
        assertThat(projector.projectRead("not json").skip()).isEqualTo(DriftSkipReason.UNDECODABLE);
        assertThat(projector.projectRead("[1,2]").skip()).isEqualTo(DriftSkipReason.UNDECODABLE);
        assertThat(projector.projectRead(42).skip()).isEqualTo(DriftSkipReason.UNDECODABLE);
        verifyNoInteractions(identities);
    }

    // ------------------------------------------------------------ decisions

    @Test
    void decision_takesItsTimestamp_andTheCanonicalAssetUri() {
        DriftEventProjector.Projection<DecisionRecord> p = projector.projectDecision(decision());

        assertThat(p.record()).isEqualTo(new DecisionRecord("dec-1", "svc-marketing-agent", "gs://sales-exports",
                true, EVENT_TIME));
        assertThat(meters.counter(DriftEventProjector.ASSET_KEYS_COUNTER, "stream", "decision", "form", "canonical")
                .count()).isEqualTo(1.0);
    }

    @Test
    void decision_asJsonText_andDeny_andOffsetTimestamp() {
        String json = "{\"decisionId\":\"dec-2\",\"timestamp\":\"2026-10-19T10:30:00+02:00\","
                + "\"subjectId\":\"svc-a\",\"assetUri\":\"bq://p/d/t\",\"decision\":\"deny\"}";

        DriftEventProjector.Projection<DecisionRecord> text = projector.projectDecision(json);
        DriftEventProjector.Projection<DecisionRecord> wrapped =
                projector.projectDecision('"' + json.replace("\"", "\\\"") + '"');

        assertThat(text.record()).isEqualTo(new DecisionRecord("dec-2", "svc-a", "bq://p/d/t", false, EVENT_TIME));
        assertThat(wrapped.record()).isEqualTo(text.record());
        assertThat(projector.projectDecision(json.getBytes(StandardCharsets.UTF_8)).record()).isEqualTo(text.record());
    }

    @Test
    void decision_withoutATimestamp_isSkipped_neverStampedWithTheClock() {
        Map<String, Object> e = decision();
        e.remove("timestamp");
        assertThat(projector.projectDecision(e).skip()).isEqualTo(DriftSkipReason.MISSING_EVENT_TIME);

        e.put("timestamp", "yesterday");
        assertThat(projector.projectDecision(e).skip()).isEqualTo(DriftSkipReason.UNPARSABLE_EVENT_TIME);

        e.put("timestamp", NOW.plusSeconds(3_600).toString());
        assertThat(projector.projectDecision(e).skip()).isEqualTo(DriftSkipReason.FUTURE_EVENT_TIME);
    }

    @Test
    void decision_thatIsNeitherAllowNorDeny_isSkipped_notTreatedAsDeny() {
        Map<String, Object> e = decision();
        e.put("decision", "INDETERMINATE");
        assertThat(projector.projectDecision(e).skip()).isEqualTo(DriftSkipReason.UNKNOWN_DECISION);

        e.remove("decision");
        assertThat(projector.projectDecision(e).skip()).isEqualTo(DriftSkipReason.UNKNOWN_DECISION);
    }

    @Test
    void decisions_missingAJoinKey_areSkippedWithTheirReason() {
        Map<String, Object> noId = decision();
        noId.remove("decisionId");
        Map<String, Object> noSubject = decision();
        noSubject.put("subjectId", " ");
        Map<String, Object> noAsset = decision();
        noAsset.remove("assetId");
        noAsset.remove("assetUri");
        Map<String, Object> longId = decision();
        longId.put("decisionId", "d".repeat(201));
        Map<String, Object> longSubject = decision();
        longSubject.put("subjectId", "s".repeat(513));

        assertThat(projector.projectDecision(noId).skip()).isEqualTo(DriftSkipReason.MISSING_DECISION_ID);
        assertThat(projector.projectDecision(noSubject).skip()).isEqualTo(DriftSkipReason.MISSING_SUBJECT);
        assertThat(projector.projectDecision(noAsset).skip()).isEqualTo(DriftSkipReason.MISSING_ASSET);
        assertThat(projector.projectDecision(longId).skip()).isEqualTo(DriftSkipReason.FIELD_TOO_LONG);
        assertThat(projector.projectDecision(longSubject).skip()).isEqualTo(DriftSkipReason.FIELD_TOO_LONG);
    }

    @Test
    void decision_withOnlyACatalogUuid_keepsIt_asANativeKey() {
        Map<String, Object> e = decision();
        e.remove("assetUri");

        assertThat(projector.projectDecision(e).record().assetId()).isEqualTo("9f6d2a1e-0000-4000-8000-000000000001");
        assertThat(meters.counter(DriftEventProjector.ASSET_KEYS_COUNTER, "stream", "decision", "form", "native")
                .count()).isEqualTo(1.0);
    }

    @Test
    void undecodableOrEmptyDecisions_areSkipped() {
        assertThat(projector.projectDecision(null).skip()).isEqualTo(DriftSkipReason.NULL_PAYLOAD);
        assertThat(projector.projectDecision(KafkaNull.INSTANCE).skip()).isEqualTo(DriftSkipReason.NULL_PAYLOAD);
        assertThat(projector.projectDecision("{oops").skip()).isEqualTo(DriftSkipReason.UNDECODABLE);
        assertThat(projector.projectDecision("[]").skip()).isEqualTo(DriftSkipReason.UNDECODABLE);
        assertThat(projector.projectDecision(7).skip()).isEqualTo(DriftSkipReason.UNDECODABLE);
    }

    // ---------------------------------------------------------------- skips

    @Test
    void skipCounter_isTaggedByStreamAndReason() {
        DriftEventSkips skips = new DriftEventSkips(meters);

        skips.count(DriftEventSkips.Stream.READ, DriftSkipReason.MISSING_EVENT_TIME);
        skips.count(DriftEventSkips.Stream.DECISION, DriftSkipReason.MISSING_EVENT_TIME);
        skips.count(DriftEventSkips.Stream.DECISION, DriftSkipReason.UNKNOWN_DECISION);

        assertThat(meters.counter(DriftEventSkips.SKIPPED_COUNTER, "stream", "read", "reason", "missing_event_time")
                .count()).isEqualTo(1.0);
        assertThat(meters.counter(DriftEventSkips.SKIPPED_COUNTER, "stream", "decision", "reason", "unknown_decision")
                .count()).isEqualTo(1.0);
        assertThat(skipped(DriftSkipReason.MISSING_EVENT_TIME)).isEqualTo(2.0);
    }

    @Test
    void readProvider_isPassedToTheIdentityMap_forEveryCloud() {
        for (SourceProvider provider : new SourceProvider[] {SourceProvider.AWS, SourceProvider.AZURE,
                SourceProvider.OCI, SourceProvider.UNITY, SourceProvider.DATABRICKS, SourceProvider.SNOWFLAKE}) {
            projector.projectRead(wire(gcsRead().setSourceProvider(provider)));
            verify(identities).resolve(eq(IdentityProviderScope.valueOf(provider.name())), anyString());
        }
    }
}
