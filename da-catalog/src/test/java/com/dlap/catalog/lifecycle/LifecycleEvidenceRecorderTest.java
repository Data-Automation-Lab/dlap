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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.catalog.coverage.ReadCoverageLevel;
import com.dlap.catalog.coverage.ReadCoverageObservation;
import com.dlap.catalog.kafka.CatalogEvent;
import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.kafka.CatalogIngestMetrics;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The evidence rule of {@link LifecycleEvidenceRecorder}: an event is evidence of a field exactly
 * when the value it offers equals the value the row holds after the catalog's rules ran. It never
 * computes a value and never records an id that is not a Log Event id.
 */
class LifecycleEvidenceRecorderTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final Instant T = Instant.parse("2026-10-20T10:00:00Z");
    private static final String ENGINE_PRINCIPAL = "dlap-executor@example.iam.gserviceaccount.com";

    private final CatalogEventDecoder decoder = new CatalogEventDecoder(new CatalogIngestProperties(),
            new CatalogIngestMetrics(new SimpleMeterRegistry()));
    private LifecycleEvidenceStore store;
    private LifecycleEvidenceRecorder recorder;
    private Asset asset;

    @BeforeEach
    void setUp() {
        store = mock(LifecycleEvidenceStore.class);
        LifecycleStateProperties properties = new LifecycleStateProperties();
        properties.setEnginePrincipals(List.of(ENGINE_PRINCIPAL));
        recorder = new LifecycleEvidenceRecorder(store, properties);
        asset = new Asset("gs://lse01-unit", "GCP", "GCS_BUCKET");
        asset.setAssetId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
    }

    @Test
    void aReadThatSetTheMaximumIsEvidenceWithItsChannelAndBinding() {
        asset.setLastAccessedAt(T);
        recorder.onApplied(applied(event("read", ChangeType.ACCESS, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ).setBindingId("gcp-audit-1")
                .setSourceFormat("GCP_CLOUD_AUDIT/audit-log")), true));

        LifecycleEvidenceStore.Evidence e = recorded();
        assertThat(e.field()).isEqualTo("lastReadAt");
        assertThat(e.valueKey()).isEqualTo(T.toString());
        assertThat(e.eventId()).isEqualTo(id("read"));
        assertThat(e.channel()).isEqualTo("AUDIT_LOG:GCP_CLOUD_AUDIT");
        assertThat(e.bindingId()).isEqualTo("gcp-audit-1");
    }

    @Test
    void anOlderReadOrAFailedReadIsNotEvidence() {
        asset.setLastAccessedAt(T.plusSeconds(60));
        recorder.onApplied(applied(event("older", ChangeType.ACCESS, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ)), true));
        asset.setLastAccessedAt(T);
        recorder.onApplied(applied(event("denied", ChangeType.ACCESS, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ).setResultStatus(ResultStatus.FAILURE)), true));

        verify(store, never()).record(any(), any(), anyInt());
    }

    @Test
    void aRolledUpObjectWriteIsEvidenceOfTheContainersLastWrite() {
        asset.setLastModifiedAt(T);
        CatalogEvent write = event("obj-write", ChangeType.UPDATE, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_WRITE));
        recorder.onApplied(new AppliedLogEvent(asset, write, T, EventCategory.EVENT_CATEGORY_DATA_WRITE, false));

        assertThat(recorded().field()).isEqualTo("lastWrittenAt");
    }

    @Test
    void aCrawlersReportedCreationTimeAndSizeAreEvidence() {
        Instant created = Instant.parse("2021-01-01T00:00:00Z");
        asset.setCloudCreatedAt(created);
        asset.setSizeBytes(1234L);
        asset.setSizeObservedAt(T);
        recorder.onApplied(applied(event("crawl", ChangeType.CREATE, T, b -> b.setOrigin(Origin.ORIGIN_CRAWLER)
                .setResultStatus(ResultStatus.RESULT_STATUS_UNSPECIFIED)
                .setTargetResource(TargetResource.newBuilder().setId("gs://lse01-unit").setCreatedTime(ts(created))
                        .setSizeBytes(1234L))), true));

        ArgumentCaptor<LifecycleEvidenceStore.Evidence> captor = ArgumentCaptor.forClass(LifecycleEvidenceStore.Evidence.class);
        verify(store, org.mockito.Mockito.times(2)).record(captor.capture(), eq(LifecycleEvidenceStore.Keep.ALL_WITH_VALUE),
                eq(100));
        assertThat(captor.getAllValues()).extracting(LifecycleEvidenceStore.Evidence::field)
                .containsExactly("createdAt", "sizeBytes");
        assertThat(captor.getAllValues().get(0).valueKey()).isEqualTo(created.toString());
        assertThat(captor.getAllValues().get(0).channel()).isEqualTo("CRAWLER");
        assertThat(captor.getAllValues().get(1).valueKey()).isEqualTo("1234@" + T);
    }

    @Test
    void aSizeThatIsNotTheStoredSizeIsNotEvidence() {
        asset.setSizeBytes(99L);
        asset.setSizeObservedAt(T);
        recorder.onApplied(applied(event("crawl", ChangeType.CREATE, T, b -> b.setOrigin(Origin.ORIGIN_CRAWLER)
                .setResultStatus(ResultStatus.RESULT_STATUS_UNSPECIFIED)
                .setTargetResource(TargetResource.newBuilder().setId("gs://lse01-unit").setSizeBytes(1234L))), true));

        verify(store, never()).record(any(), any(), anyInt());
    }

    @Test
    void aDeleteByTheProvidersLifecycleRuleIsANativeRuleDisposition() {
        asset.setStatus("DELETED");
        asset.setLifecycleEventTime(T);
        recorder.onApplied(applied(event("expired", ChangeType.DELETE, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_MANAGEMENT)
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM))
                .setRequestMetadata(RequestMetadata.newBuilder().setUserAgent("GCS Lifecycle Management"))), true));

        LifecycleEvidenceStore.Evidence e = recorded();
        assertThat(e.field()).isEqualTo("disposition");
        assertThat(e.valueKey()).isEqualTo("delete@" + T);
        assertThat(e.executedBy()).isEqualTo(LifecycleEvidenceRecorder.NATIVE_RULE);
    }

    @Test
    void theExecutorIsTheEngineOnlyForAConfiguredPrincipalAndOtherwiseOther() {
        Principal engine = Principal.newBuilder().setType(PrincipalType.SERVICE_ACCOUNT).setId(ENGINE_PRINCIPAL).build();
        Principal person = Principal.newBuilder().setType(PrincipalType.USER).setId("alice@example.com").build();
        assertThat(recorder.executedBy(by(engine))).isEqualTo(LifecycleEvidenceRecorder.ENGINE);
        assertThat(recorder.executedBy(by(person))).isEqualTo(LifecycleEvidenceRecorder.OTHER);
        assertThat(recorder.executedBy(by(Principal.getDefaultInstance()))).isEqualTo(LifecycleEvidenceRecorder.OTHER);
        assertThat(recorder.executedBy(DlapLogEvent.getDefaultInstance())).isEqualTo(LifecycleEvidenceRecorder.OTHER);
        assertThat(recorder.executedBy(null)).isEqualTo(LifecycleEvidenceRecorder.OTHER);
    }

    @Test
    void aSystemPrincipalWithoutTheLifecycleSignatureIsNotTheNativeRule() {
        // Q-654: another provider service acting as SYSTEM (here S3 replication) is not a lifecycle rule.
        DlapLogEvent replication = DlapLogEvent.newBuilder().setSourceProvider(SourceProvider.AWS)
                .setSourceService("s3.amazonaws.com").setOperation("DeleteObject")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM).setId("s3.amazonaws.com")).build();
        DlapLogEvent lifecycle = replication.toBuilder().setSourceService("s3lifecycle.amazonaws.com").build();

        assertThat(recorder.executedBy(replication)).isEqualTo(LifecycleEvidenceRecorder.OTHER);
        assertThat(recorder.executedBy(lifecycle)).isEqualTo(LifecycleEvidenceRecorder.NATIVE_RULE);
        assertThat(recorder.executedBy(replication.toBuilder().setPrincipal(Principal.newBuilder()
                .setType(PrincipalType.SYSTEM).setId(ENGINE_PRINCIPAL)).build()))
                .as("a configured engine principal wins").isEqualTo(LifecycleEvidenceRecorder.ENGINE);
    }

    @Test
    void aProviderDeleteWithoutTheSignatureHasNoExecutorValue() {
        asset.setStatus("DELETED");
        asset.setLifecycleEventTime(T);
        recorder.onApplied(applied(event("system-no-signature", ChangeType.DELETE, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_MANAGEMENT)
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM))), true));

        assertThat(recorded().executedBy()).isEqualTo(LifecycleEvidenceRecorder.OTHER);
    }

    private static DlapLogEvent by(Principal principal) {
        return DlapLogEvent.newBuilder().setSourceProvider(SourceProvider.GCP).setPrincipal(principal).build();
    }

    @Test
    void anArchiveThatIsNotTheStoredTransitionIsNotEvidence() {
        asset.setStatus("ACTIVE");
        asset.setLifecycleEventTime(T);
        recorder.onApplied(applied(event("archive", ChangeType.ARCHIVE, T, b -> b
                .setEventCategory(EventCategory.EVENT_CATEGORY_MANAGEMENT)), true));

        verify(store, never()).record(any(), any(), anyInt());
    }

    @Test
    void aProviderRetentionSettingTheRowHoldsIsEvidenceKeptOnlyForTheLatestCrawl() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("expirationTime", "2026-09-30T00:00:00Z");
        asset.setTechnicalMetadata(metadata);
        recorder.onApplied(applied(event("crawl-exp", ChangeType.UPDATE, T, b -> b.setOrigin(Origin.ORIGIN_CRAWLER)
                .setResultStatus(ResultStatus.RESULT_STATUS_UNSPECIFIED)
                .setTargetResource(TargetResource.newBuilder().setId("gs://lse01-unit")
                        .putTechnicalMetadata("expirationTime", "2026-09-30T00:00:00Z"))), true));

        ArgumentCaptor<LifecycleEvidenceStore.Evidence> captor = ArgumentCaptor.forClass(LifecycleEvidenceStore.Evidence.class);
        verify(store).record(captor.capture(), eq(LifecycleEvidenceStore.Keep.LATEST_WITH_VALUE), eq(100));
        assertThat(captor.getValue().field()).isEqualTo("retention:expirationTime");
        assertThat(captor.getValue().valueKey()).isEqualTo("2026-09-30T00:00:00Z");
    }

    @Test
    void theStoredFormIsWholeMicrosecondsRoundedHalfUpLikeTheJdbcDriver() {
        assertThat(LifecycleEvidenceRecorder.asStored(null)).isNull();
        assertThat(LifecycleEvidenceRecorder.asStored(Instant.parse("2025-04-15T10:16:32.987344499Z")))
                .isEqualTo(Instant.parse("2025-04-15T10:16:32.987344Z"));
        assertThat(LifecycleEvidenceRecorder.asStored(Instant.parse("2025-04-15T10:16:32.9873445Z")))
                .isEqualTo(Instant.parse("2025-04-15T10:16:32.987345Z"));
        assertThat(LifecycleEvidenceRecorder.asStored(Instant.parse("2025-12-31T23:59:59.999999500Z")))
                .as("the round-up carries into the next second").isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        Instant micros = Instant.parse("2025-04-15T10:16:32.987344Z");
        assertThat(LifecycleEvidenceRecorder.asStored(micros)).isEqualTo(micros);
        assertThat(LifecycleEvidenceRecorder.asStored(LifecycleEvidenceRecorder.asStored(
                Instant.parse("2025-04-15T10:16:32.9873446Z")))).as("idempotent")
                .isEqualTo(Instant.parse("2025-04-15T10:16:32.987345Z"));
        assertThat(LifecycleEvidenceRecorder.timeKey(Instant.parse("2025-04-15T10:16:32.9873441Z")))
                .isEqualTo("2025-04-15T10:16:32.987344Z");
        assertThat(LifecycleEvidenceRecorder.sizeKey(7L, Instant.parse("2025-04-15T10:16:32.987344100Z")))
                .isEqualTo("7@2025-04-15T10:16:32.987344Z");
        assertThat(LifecycleEvidenceRecorder.dispositionKey("delete", Instant.parse("2025-04-15T10:16:32.9873446Z")))
                .isEqualTo("delete@2025-04-15T10:16:32.987345Z");
    }

    @Test
    void anEventWhoseIdIsNotALogEventIdIsNeverCited() {
        asset.setLastAccessedAt(T);
        DlapLogEvent raw = DlapLogEvent.newBuilder().setEventId("legacy-crawler-gs://lse01-unit").setEventTime(ts(T))
                .setSourceProvider(SourceProvider.GCP).setChangeType(ChangeType.ACCESS)
                .setResultStatus(ResultStatus.SUCCESS).setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ)
                .setTargetResource(TargetResource.newBuilder().setId("gs://lse01-unit")).build();
        recorder.onApplied(applied(decoder.decode(CODEC.encode(raw), "test"), true));

        verify(store, never()).record(any(), any(), anyInt());
    }

    @Test
    void anAppliedCoverageObservationIsRecordedWithSortedKinds() {
        String id = LogEventIds.sha256Hex("lse01-obs");
        recorder.onCoverage(new AppliedCoverageObservation(new ReadCoverageObservation(id, "GCP", "p", "gs://b",
                Set.of("GCS_OBJECT", "GCS_BUCKET"), "GCP_CLOUD_AUDIT", ReadCoverageLevel.FULL, List.of("on"), T, "c@1")));

        ArgumentCaptor<LifecycleEvidenceStore.CoverageEvidence> captor =
                ArgumentCaptor.forClass(LifecycleEvidenceStore.CoverageEvidence.class);
        verify(store).recordCoverage(captor.capture());
        assertThat(captor.getValue().appliesTo()).containsExactly("GCS_BUCKET", "GCS_OBJECT");
        assertThat(captor.getValue().appliesTo("gs://b/x", "GCP", "GCS_OBJECT")).isTrue();
        assertThat(captor.getValue().appliesTo("gs://bb", "GCP", "GCS_BUCKET")).as("no prefix match").isFalse();
        assertThat(captor.getValue().appliesTo("gs://b", "AWS", "GCS_BUCKET")).isFalse();

        recorder.onCoverage(new AppliedCoverageObservation(new ReadCoverageObservation("e-not-hex", "GCP", "p",
                "gs://b", Set.of("GCS_BUCKET"), "GCP_CLOUD_AUDIT", ReadCoverageLevel.FULL, List.of(), T, "c@1")));
        verify(store).recordCoverage(any());
    }

    private LifecycleEvidenceStore.Evidence recorded() {
        ArgumentCaptor<LifecycleEvidenceStore.Evidence> captor = ArgumentCaptor.forClass(LifecycleEvidenceStore.Evidence.class);
        verify(store).record(captor.capture(), eq(LifecycleEvidenceStore.Keep.ALL_WITH_VALUE), eq(100));
        assertThat(captor.getValue().assetId()).isEqualTo(asset.getAssetId());
        return captor.getValue();
    }

    private AppliedLogEvent applied(CatalogEvent event, boolean direct) {
        return new AppliedLogEvent(asset, event, event.eventTime().orElseThrow(), event.category(), direct);
    }

    private CatalogEvent event(String name, ChangeType type, Instant time, Consumer<DlapLogEvent.Builder> extra) {
        DlapLogEvent.Builder b = DlapLogEvent.newBuilder().setEventId(id(name)).setEventTime(ts(time))
                .setSourceProvider(SourceProvider.GCP).setChangeType(type).setResultStatus(ResultStatus.SUCCESS)
                .setTargetResource(TargetResource.newBuilder().setId("gs://lse01-unit"));
        extra.accept(b);
        return decoder.decode(CODEC.encode(b.build()), "test");
    }

    private static String id(String name) {
        return LogEventIds.sha256Hex("lse01-" + name);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }
}
