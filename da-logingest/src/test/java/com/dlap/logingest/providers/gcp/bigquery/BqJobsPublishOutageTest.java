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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.kafka.StubKafka;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.common.errors.NetworkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The BigQuery JOBS stream through the real publisher while the broker is down and after it
 * recovers (SPINE-01): a send that the broker does not acknowledge fails the poll before the stream
 * cursor moves, rolls the event's row back so a retry is not taken for a duplicate, and the next
 * poll publishes every event exactly once. Before SPINE-01 the send was fire and forget, so the
 * cursor moved past events that never reached the spine.
 *
 * <p>The stream, the parser, the ingest service, the store, the publisher and its String-valued
 * template are the production classes; the broker, the database and BigQuery are stand-ins.
 */
class BqJobsPublishOutageTest {

    private static final String BINDING = "acme-gcp-jobs";
    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(BqJobsFormat.FAMILY);
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    private final FakeJobsViewGateway gateway = new FakeJobsViewGateway();
    private final LogEventRepository repo = mock(LogEventRepository.class);
    private final StubKafka kafka = new StubKafka();
    private final Set<String> stored = new HashSet<>();
    private final Set<String> pendingInTransaction = new HashSet<>();
    private final Map<String, CursorPosition> cursors = new HashMap<>();
    private final List<String> transactionLog = new ArrayList<>();
    private final Instant now = Instant.parse("2026-10-20T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final EffectiveStream stream = new EffectiveStream(BINDING, "bq-jobs-view", BqJobsViewTransport.TRANSPORT_ID,
            BqJobsFormat.ENVELOPE, Map.of("location", "US"), MAPPING.defaultBinding(BINDING), StreamFilters.NONE,
            Duration.ofMinutes(30), Duration.ofMinutes(5), null, null, false, false, false);

    /** Commits keep the rows a transaction saved; a rollback removes them, as the database would. */
    private final PlatformTransactionManager transactions = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            pendingInTransaction.clear();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            transactionLog.add("commit");
            pendingInTransaction.clear();
        }

        @Override
        public void rollback(TransactionStatus status) {
            transactionLog.add("rollback");
            stored.removeAll(pendingInTransaction);
            pendingInTransaction.clear();
        }
    };

    private StreamRunner runner() {
        EffectiveBinding binding = EffectiveBinding.of(BINDING, "gcp-jobs", BqJobsFormat.SOURCE_KIND,
                Optional.of(SourceProvider.GCP), ConfigOrigin.FILE, true, true, Map.of("project-id", "proj-x"),
                List.of(stream));
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(binding),
                ValidationReport.CLEAN);
        DefaultParserRegistry registry = AbstractParserContractTest.registryOf(MAPPING,
                List.of(new BqJobsParser(MAPPING)));
        when(repo.existsById(anyString())).thenAnswer(inv -> stored.contains(inv.getArgument(0, String.class)));
        when(repo.save(any(LogEventEntity.class))).thenAnswer(inv -> {
            LogEventEntity e = inv.getArgument(0);
            stored.add(e.getEventId());
            pendingInTransaction.add(e.getEventId());
            return e;
        });
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", clock);
        RegistryIngestService ingest = new RegistryIngestService(registry, new CanonicalEventMapper(clock), sources,
                new LogEventStore(repo, transactions), kafka.publisher(Duration.ofMillis(200)),
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

    private List<String> publishedEventIds() {
        return kafka.received().stream().map(w -> CODEC.decodeStrict(w.text()).getEventId()).toList();
    }

    /**
     * FAIL: the producer reports the refusal before {@code send} returns. FAIL_LATER: after it
     * returned, which is what a real producer does when the broker goes away mid-flight; without the
     * wait for the acknowledgement the refusal would reach nobody.
     */
    @ParameterizedTest
    @EnumSource(value = StubKafka.Mode.class, names = {"FAIL", "FAIL_LATER"})
    void aBrokerThatRefusesTheSendStopsThePollBeforeTheCursorMovesAndLeavesNoRowBehind(StubKafka.Mode mode) {
        kafka.mode(mode).failure(new NetworkException("broker is down"));
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        StreamRunner runner = runner();

        StreamRunner.PollResult result = runner.poll(stream);

        assertThat(result.failed()).as("the partition poll failed").isEqualTo(1);
        assertThat(cursors).as("the cursor stayed where it was").isEmpty();
        assertThat(transactionLog).as("the event's transaction rolled back").containsExactly("rollback");
        assertThat(stored).as("no row survives for a retry to mistake for a duplicate").isEmpty();
        assertThat(kafka.received()).isEmpty();
    }

    @Test
    void aBrokerThatNeverAnswersFailsThePollTheSameWay() {
        kafka.mode(StubKafka.Mode.HANG);
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        StreamRunner runner = runner();

        StreamRunner.PollResult result = runner.poll(stream);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(cursors).isEmpty();
        assertThat(stored).isEmpty();
    }

    @Test
    void afterTheBrokerRecoversTheNextPollPublishesEveryEventOnceAndMovesTheCursor() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        gateway.jobs.add(FakeJobsViewGateway.job("job_2", "2026-10-20T11:30:00Z"));
        StreamRunner runner = runner();

        kafka.mode(StubKafka.Mode.FAIL_LATER).failure(new NetworkException("broker is down"));
        assertThat(runner.poll(stream).failed()).isEqualTo(1);
        assertThat(cursors).isEmpty();

        kafka.mode(StubKafka.Mode.ACK);
        StreamRunner.PollResult recovered = runner.poll(stream);

        assertThat(recovered.failed()).isZero();
        assertThat(publishedEventIds()).as("both jobs reached the topic").hasSize(2).doesNotHaveDuplicates();
        assertThat(cursors.get("").lowWatermark()).as("the cursor moved only after the sends").isNotNull();

        // A third poll re-reads the overlap: stored events are duplicates and are not sent again.
        assertThat(runner.poll(stream).failed()).isZero();
        assertThat(publishedEventIds()).hasSize(2);
        assertThat(kafka.received()).allSatisfy(w -> CODEC.decodeStrict(w.text()));
    }

    @Test
    void aFailureInTheMiddleOfABatchKeepsTheEventsThatWereSentAndRetriesTheRest() {
        gateway.jobs.add(FakeJobsViewGateway.job("job_1", "2026-10-20T11:00:00Z"));
        gateway.jobs.add(FakeJobsViewGateway.job("job_2", "2026-10-20T11:30:00Z"));
        StreamRunner runner = runner();
        // The broker acknowledges the first send, then goes away.
        kafka.failAfter(1);

        StreamRunner.PollResult first = runner.poll(stream);

        assertThat(first.failed()).isEqualTo(1);
        assertThat(cursors).as("the batch did not complete, so the cursor did not move").isEmpty();
        assertThat(publishedEventIds()).hasSize(1);
        assertThat(stored).as("the sent event keeps its row, the unsent one has none").hasSize(1);

        kafka.failAfter(Integer.MAX_VALUE);
        StreamRunner.PollResult second = runner.poll(stream);

        assertThat(second.failed()).isZero();
        assertThat(publishedEventIds()).as("the first event is not sent twice").hasSize(2).doesNotHaveDuplicates();
    }
}
