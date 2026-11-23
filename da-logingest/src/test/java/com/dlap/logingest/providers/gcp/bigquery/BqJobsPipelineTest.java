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

package com.dlap.logingest.providers.gcp.bigquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.parse.bqjobs.BqJobsFormat;
import com.dlap.logingest.parse.bqjobs.BqJobsParser;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.repository.FormatObservationRepository;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.service.registry.LogEventStore;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.StreamRunner;
import com.dlap.logingest.transport.TransportRegistry;
import com.dlap.logingest.transport.cursor.StreamCursorStore;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.dlap.proto.log.v1.SourceProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The BigQuery JOBS stream end to end without BigQuery or a database: gateway to transport to stream
 * runner to the registry ingest path. It proves what the plan asks of the stream: one event per job
 * and table with a stable id, a re-read overlap that republishes nothing, a new job picked up on the
 * next poll, and a cursor that moves only as far as the data allows.
 */
class BqJobsPipelineTest {

    private static final String BINDING = "acme-gcp-jobs";
    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(BqJobsFormat.FAMILY);

    private final FakeJobsViewGateway gateway = new FakeJobsViewGateway();
    private final LogEventRepository repo = mock(LogEventRepository.class);
    private final LogEventPublisher publisher = mock(LogEventPublisher.class);
    private final Set<String> stored = new HashSet<>();
    private final Map<String, CursorPosition> cursors = new HashMap<>();
    private Instant now = Instant.parse("2026-10-20T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private final EffectiveStream stream = new EffectiveStream(BINDING, "bq-jobs-view", BqJobsViewTransport.TRANSPORT_ID,
            BqJobsFormat.ENVELOPE, Map.of("location", "US"), MAPPING.defaultBinding(BINDING), StreamFilters.NONE,
            Duration.ofMinutes(30), Duration.ofMinutes(5), null, null, false, false, false);

    private StreamRunner runner() {
        EffectiveBinding binding = EffectiveBinding.of(BINDING, "gcp-jobs", BqJobsFormat.SOURCE_KIND,
                Optional.of(SourceProvider.GCP), ConfigOrigin.FILE, true, true, Map.of("project-id", "proj-x"), List.of(stream));
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(binding), ValidationReport.CLEAN);
        DefaultParserRegistry registry = AbstractParserContractTest.registryOf(MAPPING, List.of(new BqJobsParser(MAPPING)));

        when(repo.existsById(anyString())).thenAnswer(inv -> stored.contains(inv.getArgument(0, String.class)));
        when(repo.save(any(LogEventEntity.class))).thenAnswer(inv -> {
            LogEventEntity e = inv.getArgument(0);
            stored.add(e.getEventId());
            return e;
        });
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", clock);
        RegistryIngestService ingest = new RegistryIngestService(registry, new CanonicalEventMapper(clock), sources,
                new LogEventStore(repo, new NoTransactions()), publisher,
                none.getBeanProvider(com.dlap.logingest.service.QuarantineSink.class),
                mock(FormatObservationRepository.class), new SimpleMeterRegistry(), clocks.getBeanProvider(Clock.class));
        BqJobsViewTransport transport = new BqJobsViewTransport(gateway, id -> Optional.of("proj-x"));
        StreamCursorStore store = new StreamCursorStore() {
            @Override
            public Optional<CursorPosition> load(String bindingId, String streamId, String partitionKey) {
                return Optional.ofNullable(cursors.get(partitionKey));
            }

            @Override
            public void commit(String bindingId, String streamId, String partitionKey, CursorPosition position) {
                cursors.put(partitionKey, position);
            }
        };
        return new StreamRunner(sources, new TransportRegistry(List.of(transport)), ingest, store,
                mock(ProcessedObjectStore.class), (b, s) -> Optional.of(() -> { }), clock);
    }

    @Test
    void aJobIsPublishedOncePerTableAndAReReadOfTheOverlapRepublishesNothing() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        ObjectNodes.addTable(gateway.jobs.get(0), "sales", "customers");
        gateway.jobs.add(FakeJobsViewGateway.job("job_2", "2026-10-20T11:30:00Z"));
        StreamRunner runner = runner();

        StreamRunner.PollResult first = runner.poll(stream);

        assertThat(first.ran()).isTrue();
        assertThat(first.failed()).isZero();
        assertThat(first.records()).isEqualTo(2);
        verify(publisher, times(3)).publish(anyString(), anyMap());
        CursorPosition afterFirst = cursors.get("");
        assertThat(afterFirst.lowWatermark()).as("the cursor stops at the settled end of the window")
                .isEqualTo(Instant.parse("2026-10-20T11:55:00Z"));
        assertThat(afterFirst.state()).containsKey(BqJobsViewTransport.STATE_COLUMN_HASH);

        // Ten minutes later: the same two jobs sit inside the 30-minute overlap, one new job has ended.
        now = Instant.parse("2026-10-20T12:10:00Z");
        gateway.jobs.add(FakeJobsViewGateway.job("job_3", "2026-10-20T12:02:00Z"));
        StreamRunner.PollResult second = runner.poll(stream);

        assertThat(second.records()).as("the overlap re-reads job_2 at least and the new job").isGreaterThanOrEqualTo(2);
        verify(publisher, times(4)).publish(anyString(), anyMap());
        assertThat(cursors.get("").lowWatermark()).isEqualTo(Instant.parse("2026-10-20T12:05:00Z"));
        assertThat(stored).hasSize(4);
    }

    @Test
    void aFirstPollWithoutBackfillReadsOnlyTheLastTwentyFourHours() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_old", "2026-10-18T11:00:00Z"));
        gateway.jobs.add(FakeJobsViewGateway.job("job_new", "2026-10-20T11:00:00Z"));
        StreamRunner runner = runner();

        runner.poll(stream);

        assertThat(stored).hasSize(1);
        assertThat(gateway.rowQueries.get(0).timestamps().get("from")).isEqualTo(Instant.parse("2026-10-19T11:55:00Z"));
    }

    @Test
    void aDriftedViewQuarantinesOneRecordAndLeavesTheCursorAlone() {
        gateway.columns.remove("referenced_tables");
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        StreamRunner runner = runner();

        StreamRunner.PollResult r = runner.poll(stream);

        assertThat(r.quarantined()).isEqualTo(1);
        assertThat(stored).isEmpty();
        verify(publisher, times(0)).publish(anyString(), anyMap());
        assertThat(cursors.get("").lowWatermark()).isNull();
    }

    /** Transactions that just run the callback. */
    private static final class NoTransactions implements PlatformTransactionManager {
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

    /** Small JSON helpers for the fake rows. */
    private static final class ObjectNodes {
        static void addTable(com.fasterxml.jackson.databind.node.ObjectNode job, String dataset, String table) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) job.get("referenced_tables")).addObject()
                    .put("project_id", "proj-x").put("dataset_id", dataset).put("table_id", table);
        }
    }
}
