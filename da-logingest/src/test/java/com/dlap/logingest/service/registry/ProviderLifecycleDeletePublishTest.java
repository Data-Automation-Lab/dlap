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

package com.dlap.logingest.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.kafka.StubKafka;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.parse.cloudtrail.CloudTrailConfiguration;
import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.core.VersionedDeletes;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecLoader;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.logingest.parse.core.mapping.RecordFraming;
import com.dlap.logingest.parse.gcpaudit.GcpAuditConfiguration;
import com.dlap.logingest.parse.gcpaudit.GcpAuditFormat;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogConfiguration;
import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.dlap.logingest.repository.FormatObservationRepository;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * PARSE-02 (Q-612): the three provider lifecycle deletes travel the existing publish path. Each
 * fixture case is framed and handed to {@link RegistryIngestService} as a streamed record of a
 * binding, with the parsers and detectors the services' configurations register; the published
 * event reaches {@code dlap.log.event.normalized} through the production {@code LogEventPublisher}
 * as a {@code change_type DELETE} with the canonical target URI, a {@code SYSTEM} principal and
 * origin {@code ORIGIN_CLOUD_LOG}, keyed by that URI. The router's DELETE row (da-discovery,
 * {@code ProviderLifecycleDeleteRoutingTest}) then puts it on {@code dlap.discovery.asset.deleted}.
 */
class ProviderLifecycleDeletePublishTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final LogEventRepository repo = mock(LogEventRepository.class);
    private final StubKafka kafka = new StubKafka();
    private final MappingSpecs specs = new MappingSpecs(
            MappingSpecLoader.loadClasspath(new PathMatchingResourcePatternResolver()));

    @BeforeEach
    void emptyStore() {
        when(repo.existsById(anyString())).thenReturn(false);
        when(repo.save(any(LogEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The registry as the service builds it: every family's beans and every mapping's detectors. */
    private DefaultParserRegistry registry() {
        GcpAuditConfiguration gcp = new GcpAuditConfiguration();
        List<LogRecordParser> parsers = List.of(
                new CloudTrailConfiguration().cloudTrailS3FileParser(specs),
                new S3AccessLogConfiguration().s3AccessLogParser(specs),
                gcp.gcpAuditApiProtoParser(specs), gcp.gcpAuditJsonParser(specs), gcp.gcpAuditBqSinkRowParser(specs));
        List<VersionDetector> detectors = new ArrayList<>(specs.detectors());
        detectors.add(gcp.gcpAuditBqSinkRowDetector());
        return new DefaultParserRegistry(parsers, detectors);
    }

    private static EffectiveBinding binding(String id, String kind, String envelope, SourceProvider provider,
                                            AssetProvider assets) {
        BindingConfig parse = new BindingConfig(id, kind, Optional.empty(), OnMismatch.DEFAULT, Map.of(), List.of(),
                PersistRaw.of(PersistRaw.Policy.ALL), BindingConfig.DEFAULT_MAX_RECORD_BYTES, AssetIdHints.of(assets));
        EffectiveStream stream = new EffectiveStream(id, "lifecycle", "TEST", envelope, Map.of(), parse,
                StreamFilters.NONE, Duration.ZERO, Duration.ofMinutes(5), null, null, false, false, false);
        return EffectiveBinding.of(id, id + "-source", kind, Optional.of(provider), ConfigOrigin.FILE, true, true,
                Map.of(), List.of(stream));
    }

    private RegistryIngestService service() {
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(
                binding("aws-trail", CloudTrailFormat.SOURCE_KIND, CloudTrailFormat.S3_FILE, SourceProvider.AWS,
                        AssetProvider.AWS),
                binding("aws-sal", S3AccessLogFormat.SOURCE_KIND, S3AccessLogFormat.CWL_JSON, SourceProvider.AWS,
                        AssetProvider.AWS),
                binding("gcp-audit", GcpAuditFormat.SOURCE_KIND, GcpAuditFormat.JSON, SourceProvider.GCP,
                        AssetProvider.GCP)), ValidationReport.CLEAN);
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", CLOCK);
        return new RegistryIngestService(registry(), new CanonicalEventMapper(CLOCK), sources,
                new LogEventStore(repo, new RegistryIngestServiceTest.NoTransactions()),
                kafka.publisher(Duration.ofSeconds(2)),
                new StaticListableBeanFactory().getBeanProvider(com.dlap.logingest.service.QuarantineSink.class),
                mock(FormatObservationRepository.class), new SimpleMeterRegistry(), clocks.getBeanProvider(Clock.class));
    }

    private List<RawRecord> streamed(String caseId, String bindingId, String kind) {
        FixtureCase c = FixtureCatalog.fromClasspath().cases().stream().filter(x -> x.id().equals(caseId)).findFirst()
                .orElseThrow(() -> new AssertionError("no fixture case " + caseId));
        MappingSpec.Envelope envelope = specs.forSourceKind(kind).flatMap(m -> m.envelope(c.envelope())).orElseThrow();
        List<RawRecord> out = new ArrayList<>();
        for (Path file : c.inputFiles()) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (RecordFraming.Piece p : RecordFraming.split(text, envelope.framing(), envelope.recordsPointer())) {
                RawRecord.Builder b = RawRecord.builder().bindingId(bindingId).sourceKind(kind).envelope(c.envelope())
                        .ref(RawRef.of("TEST", "fixture:" + caseId, p.index())).mode(IngestMode.STREAM).fetchedAt(NOW);
                out.add(p.text() != null ? b.text(p.text()).build() : b.json(p.json()).build());
            }
        }
        return out;
    }

    private List<DlapLogEvent> publishedDeletes(String caseId, String bindingId, String kind) {
        RegistryIngestService service = service();
        for (RawRecord r : streamed(caseId, bindingId, kind)) {
            assertThat(service.ingest(r).outcomeCode()).as("record of %s", caseId).isEqualTo("PARSED");
        }
        List<DlapLogEvent> deletes = new ArrayList<>();
        for (StubKafka.Wire wire : kafka.received()) {
            assertThat(wire.topic()).isEqualTo(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED);
            DlapLogEvent e = CODEC.decodeStrict(wire.text());
            if (e.getChangeType() == ChangeType.DELETE) {
                assertThat(wire.key()).as("partition key").isEqualTo(LogEventSemantics.partitionKey(e))
                        .isEqualTo(e.getTargetResource().getCanonicalUri());
                deletes.add(e);
            }
        }
        return deletes;
    }

    private static void assertProviderDelete(DlapLogEvent e, SourceProvider provider, String uri) {
        assertThat(e.getSourceProvider()).isEqualTo(provider);
        assertThat(e.getResultStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(e.getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo(uri);
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(e.hasEventTime()).isTrue();
        assertThat(e.getIngestTime().getSeconds()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void anS3LifecycleCloudTrailExpirationIsPublishedAsADeleteOfTheObject() {
        List<DlapLogEvent> deletes = publishedDeletes(
                "cloudtrail/s3-file/1.11/s3-lifecycle-expiration-and-transition", "aws-trail", CloudTrailFormat.SOURCE_KIND);

        assertThat(deletes).hasSize(1);
        assertProviderDelete(deletes.get(0), SourceProvider.AWS, "s3://dlap-aws-e2e/raw/2026/09/orders.parquet");
        assertThat(deletes.get(0).getSourceService()).isEqualTo(CloudTrailFormat.S3_LIFECYCLE_SERVICE);
        assertThat(deletes.get(0).getBindingId()).isEqualTo("aws-trail");
        assertThat(kafka.received()).as("the transition is published too, as an archive").hasSize(2);
    }

    @Test
    void anS3ServerAccessLogExpirationIsPublishedAsADeleteAndItsDeleteMarkerIsNot() {
        List<DlapLogEvent> deletes = publishedDeletes(
                "s3accesslog/cwl-json/V_1_0/lifecycle-expiration", "aws-sal", S3AccessLogFormat.SOURCE_KIND);

        // PARSE-03 (D36, Q-651): a delete marker keeps every version, so it is published as an UPDATE.
        assertThat(deletes).extracting(DlapLogEvent::getOperation).containsExactly("S3.EXPIRE.OBJECT");
        assertProviderDelete(deletes.get(0), SourceProvider.AWS, "s3://dlap-aws-e2e/raw/2026/09/orders.parquet");
        assertThat(kafka.received()).as("the delete marker is published too, as an update").hasSize(2);
    }

    @Test
    void versionQualifiedDeletesArePublishedAsUpdatesNeverAsDeletes() {
        List<DlapLogEvent> accessLog = publishedDeletes(
                "s3accesslog/cwl-json/V_1_0/versioned-deletes", "aws-sal", S3AccessLogFormat.SOURCE_KIND);
        assertThat(accessLog).as("noncurrent expiration, delete marker, version delete").isEmpty();
        assertThat(kafka.received()).hasSize(3).allSatisfy(w -> {
            DlapLogEvent e = CODEC.decodeStrict(w.text());
            assertThat(e.getChangeType()).isEqualTo(ChangeType.UPDATE);
            assertThat(e.getTargetResource().getTechnicalMetadataMap()).containsKey(VersionedDeletes.ACTION_KEY);
        });
    }

    @Test
    void onlyTheCurrentObjectDeleteOfTheCloudTrailVersionCaseIsPublishedAsADelete() {
        List<DlapLogEvent> deletes = publishedDeletes(
                "cloudtrail/s3-file/1.11/s3-versioned-deletes", "aws-trail", CloudTrailFormat.SOURCE_KIND);

        assertThat(deletes).hasSize(1);
        assertThat(deletes.get(0).getTargetResource().getCanonicalUri()).isEqualTo("s3://dlap-aws-e2e/curated/2026/old.parquet");
        assertThat(deletes.get(0).getTargetResource().getTechnicalMetadataMap()).isEmpty();
        assertThat(kafka.received()).hasSize(4);
    }

    @Test
    void unlistedS3LifecycleEventsAreNeverPublishedAsDeletes() {
        List<DlapLogEvent> deletes = publishedDeletes(
                "cloudtrail/s3-file/1.11/s3-lifecycle-unlisted-events", "aws-trail", CloudTrailFormat.SOURCE_KIND);

        assertThat(deletes).isEmpty();
        assertThat(kafka.received()).hasSize(2);
        assertThat(kafka.received()).noneSatisfy(w -> assertThat(CODEC.decodeStrict(w.text())
                .getTargetResource().getCanonicalUri()).isEqualTo("s3://dlap-aws-e2e"));
    }

    @Test
    void aBigQueryInternalTableExpiredSystemEventIsPublishedAsADeleteOfTheTable() {
        List<DlapLogEvent> deletes = publishedDeletes(
                "gcpaudit/json/bq-auditmetadata/table-expired", "gcp-audit", GcpAuditFormat.SOURCE_KIND);

        assertThat(deletes).hasSize(1);
        assertProviderDelete(deletes.get(0), SourceProvider.GCP, "bq://proj-0d1a0001/sales/tmp_orders_20260801");
        assertThat(deletes.get(0).getOperation()).isEqualTo("InternalTableExpired");
        assertThat(deletes.get(0).getSourceService()).isEqualTo("bigquery.googleapis.com");
    }
}
