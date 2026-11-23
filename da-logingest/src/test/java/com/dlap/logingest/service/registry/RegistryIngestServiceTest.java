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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedParser;
import com.dlap.logingest.repository.FormatObservationRepository;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.impl.LogIngestServiceImpl;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.SourceProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The registry ingest path end to end without a database: a REST DTO is stored and published once,
 * a duplicate is not republished, replay republishes unless told to replay once, a quarantined
 * record reaches the sinks and its pass-through is stored, filters drop events, persist-raw decides
 * the stored raw record, and a record of a binding that does not run is refused.
 */
class RegistryIngestServiceTest {

    private final LogEventRepository repo = mock(LogEventRepository.class);
    private final LogEventPublisher publisher = mock(LogEventPublisher.class);
    private final FormatObservationRepository observations = mock(FormatObservationRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Set<String> stored = new HashSet<>();
    private final List<LogQuarantineRecord> quarantined = new ArrayList<>();
    private final DefaultParserRegistry registry = new DefaultParserRegistry(
            DlapNormalizedFormat.providers().stream().map(DlapNormalizedParser::new).toList(),
            DlapNormalizedFormat.providers().stream().map(p -> (VersionDetector) new DlapNormalizedDetector(p)).toList());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    private RegistryIngestService service(List<EffectiveBinding> extra) {
        List<EffectiveBinding> all = new ArrayList<>(BuiltinBindings.all());
        all.addAll(extra);
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", all, ValidationReport.CLEAN);
        StaticListableBeanFactory sinks = new StaticListableBeanFactory();
        sinks.addBean("capture", (QuarantineSink) quarantined::add);
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", clock);
        return new RegistryIngestService(registry, new CanonicalEventMapper(clock), sources,
                new LogEventStore(repo, new NoTransactions()), publisher, sinks.getBeanProvider(QuarantineSink.class),
                observations, meters, clocks.getBeanProvider(Clock.class));
    }

    @BeforeEach
    void storeInMemory() {
        when(repo.existsById(anyString())).thenAnswer(inv -> stored.contains(inv.getArgument(0, String.class)));
        when(repo.save(any(LogEventEntity.class))).thenAnswer(inv -> {
            LogEventEntity e = inv.getArgument(0);
            stored.add(e.getEventId());
            return e;
        });
    }

    private static IngestRequestDTO dto() {
        return IngestRequestDTO.builder()
                .sourceProvider("AWS").sourceService("s3.amazonaws.com").operation("GetObject").changeType("ACCESS")
                .resultStatus("SUCCESS").eventTime(Instant.parse("2026-07-05T10:00:00Z"))
                .targetResourceId("s3://bucket/key").requestId("req-1").rawEvent(Map.of("eventName", "GetObject"))
                .build();
    }

    @Test
    void aRestRecordIsStoredAndPublishedOnceAndADuplicateIsNotRepublished() {
        RegistryIngestService s = service(List.of());

        RecordReport first = s.ingestDto(dto(), BuiltinBindings.Channel.REST, RegistryIngestService.REST_SOURCE_URI, -1,
                false);
        RecordReport again = s.ingestDto(dto(), BuiltinBindings.Channel.REST, RegistryIngestService.REST_SOURCE_URI, -1,
                false);

        assertThat(first.primaryDisposition()).contains(Disposition.PUBLISHED);
        assertThat(again.primaryDisposition()).contains(Disposition.DUPLICATE);
        assertThat(again.primaryEventId()).isEqualTo(first.primaryEventId());
        verify(publisher, times(1)).publish(eq("s3://bucket/key"), anyMap());
        verify(repo, times(1)).save(any(LogEventEntity.class));
        verify(repo, times(2)).lockEventId(first.primaryEventId().orElseThrow());
        assertThat(meters.counter(LogIngestServiceImpl.METRIC_DEDUP_HITS).count()).isEqualTo(1.0);
        verify(observations, times(2)).record(eq("dlap-rest-aws"), eq("ingest-dto"), eq("v1"), anyString(), eq(1L),
                any());
    }

    @Test
    void theRestChannelKeepsTheRawRecordAndStampsTheRegistryColumns() {
        service(List.of()).ingestDto(dto(), BuiltinBindings.Channel.REST, RegistryIngestService.REST_SOURCE_URI, -1,
                false);

        ArgumentCaptor<LogEventEntity> row = ArgumentCaptor.forClass(LogEventEntity.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getRawEvent()).containsEntry("eventName", "GetObject");
        assertThat(row.getValue().getBindingId()).isEqualTo("dlap-rest-aws");
        assertThat(row.getValue().getSourceFormat()).isEqualTo("DLAP_NORMALIZED_AWS/ingest-dto");
        assertThat(row.getValue().getEventCategory()).isEqualTo("EVENT_CATEGORY_DATA_READ");
        assertThat(row.getValue().getOrigin()).isEqualTo("ORIGIN_CLOUD_LOG");
        assertThat(row.getValue().getChangeType()).isEqualTo("ACCESS");
        assertThat(row.getValue().getTargetCanonicalUri()).isEqualTo("s3://bucket/key");
    }

    @Test
    void replayRepublishesAStoredIdUnlessToldToReplayOnce() {
        RegistryIngestService s = service(List.of());
        s.ingestDto(dto(), BuiltinBindings.Channel.FIXTURE_REPLAY, "fixture:aws.json", 0, true);

        RecordReport once = s.ingestDto(dto(), BuiltinBindings.Channel.FIXTURE_REPLAY, "fixture:aws.json", 0, true);
        RecordReport onPurpose = s.ingestDto(dto(), BuiltinBindings.Channel.FIXTURE_REPLAY, "fixture:aws.json", 0, false);

        assertThat(once.primaryDisposition()).contains(Disposition.DUPLICATE);
        assertThat(onPurpose.primaryDisposition()).contains(Disposition.REPUBLISHED);
        verify(publisher, times(2)).publish(anyString(), anyMap());
    }

    @Test
    void aQuarantinedRecordReachesTheSinksAndItsPassThroughIsStored() {
        IngestRequestDTO noResult = dto();
        noResult.setResultStatus(null);

        RecordReport r = service(List.of()).ingestDto(noResult, BuiltinBindings.Channel.REST,
                RegistryIngestService.REST_SOURCE_URI, -1, false);

        assertThat(r.quarantined()).isTrue();
        assertThat(r.quarantineId()).isPresent();
        assertThat(quarantined).singleElement().satisfies(q -> {
            assertThat(q.getQuarantineId()).isEqualTo(r.quarantineId().get());
            assertThat(q.getBindingId()).isEqualTo("dlap-rest-aws");
        });
        assertThat(r.primaryDisposition()).contains(Disposition.PUBLISHED);
        ArgumentCaptor<LogEventEntity> row = ArgumentCaptor.forClass(LogEventEntity.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getParseStatus()).isEqualTo("PARSE_STATUS_PARTIAL");
        assertThat(row.getValue().getChangeType()).isEqualTo("CHANGE_TYPE_UNSPECIFIED");
    }

    @Test
    void filtersDropEventsAndPersistRawFollowsTheBinding() {
        EffectiveBinding builtin = BuiltinBindings.binding(BuiltinBindings.Channel.REST, SourceProvider.GCP);
        EffectiveStream s = builtin.streams().get(0);
        // A REST binding of GCP that only keeps storage events and keeps no raw record of data events.
        EffectiveStream filtered = new EffectiveStream(s.bindingId(), s.streamId(), s.transport(), s.envelope(),
                s.location(), new com.dlap.logingest.parse.core.BindingConfig(s.bindingId(), builtin.sourceKind(),
                java.util.Optional.empty(), null, Map.of(), List.of(), null,
                com.dlap.logingest.parse.core.BindingConfig.DEFAULT_MAX_RECORD_BYTES, s.parse().assetIdHints()),
                new StreamFilters(List.of("storage.googleapis.com"), List.of(), List.of(), List.of()), s.overlap(),
                s.pollInterval(), null, null, false, false, true);
        List<EffectiveBinding> all = new ArrayList<>(BuiltinBindings.all());
        all.removeIf(b -> b.id().equals(builtin.id()));
        all.add(EffectiveBinding.of(builtin.id(), builtin.sourceId(), builtin.sourceKind(), builtin.provider(),
                builtin.origin(), true, true, Map.of(), List.of(filtered)));
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", all, ValidationReport.CLEAN);
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", clock);
        RegistryIngestService service = new RegistryIngestService(registry, new CanonicalEventMapper(clock), sources,
                new LogEventStore(repo, new NoTransactions()), publisher, none.getBeanProvider(QuarantineSink.class),
                observations, meters, clocks.getBeanProvider(Clock.class));

        IngestRequestDTO bq = dto();
        bq.setSourceProvider("GCP");
        bq.setSourceService("bigquery.googleapis.com");
        IngestRequestDTO gcs = dto();
        gcs.setSourceProvider("GCP");
        gcs.setSourceService("storage.googleapis.com");
        gcs.setRequestId("req-2");
        RecordReport dropped = service.ingestDto(bq, BuiltinBindings.Channel.REST, "rest", -1, false);
        RecordReport kept = service.ingestDto(gcs, BuiltinBindings.Channel.REST, "rest", -1, false);

        assertThat(dropped.outcomeCode()).isEqualTo(RecordReport.SKIPPED_FILTERED);
        assertThat(dropped.primaryDisposition()).contains(Disposition.FILTERED);
        assertThat(kept.primaryDisposition()).contains(Disposition.PUBLISHED);
        verify(publisher, times(1)).publish(anyString(), anyMap());
        ArgumentCaptor<LogEventEntity> row = ArgumentCaptor.forClass(LogEventEntity.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getRawEvent()).as("persist-raw default: data events keep no raw record").isNull();
    }

    @Test
    void anUnknownProviderAndABindingThatDoesNotRunAreRefused() {
        IngestRequestDTO ibm = dto();
        ibm.setSourceProvider("IBM");
        RegistryIngestService s = service(List.of());

        assertThatThrownBy(() -> s.ingestDto(ibm, BuiltinBindings.Channel.REST, "rest", -1, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sourceProvider");
        EffectiveLogSources legacy = new EffectiveLogSources(PipelineMode.LEGACY, "FILE", BuiltinBindings.all(),
                ValidationReport.CLEAN);
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", clock);
        RegistryIngestService onLegacy = new RegistryIngestService(registry, new CanonicalEventMapper(clock), legacy,
                new LogEventStore(repo, new NoTransactions()), publisher, none.getBeanProvider(QuarantineSink.class),
                observations, meters, clocks.getBeanProvider(Clock.class));
        assertThatThrownBy(() -> onLegacy.ingestDto(dto(), BuiltinBindings.Channel.REST, "rest", -1, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not run");
        verify(publisher, never()).publish(anyString(), anyMap());
    }

    @Test
    void anObservationFailureDoesNotUndoTheIngest() {
        when(observations.record(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        RecordReport r = service(List.of()).ingestDto(dto(), BuiltinBindings.Channel.REST, "rest", -1, false);

        assertThat(r.primaryDisposition()).contains(Disposition.PUBLISHED);
    }

    /** Runs the store's callbacks without a database. */
    static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
