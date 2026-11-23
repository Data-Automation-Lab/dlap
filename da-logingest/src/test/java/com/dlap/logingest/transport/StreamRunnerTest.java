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

package com.dlap.logingest.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.service.registry.BatchReport;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.cursor.StreamCursorStore;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The stream runner's commit discipline (design B5): the cursor moves only after a batch reached
 * its outcomes; a failure stops the partition without moving the cursor and marks the object
 * failed; objects already read are skipped; the window starts at the low watermark minus the
 * overlap; a held lock skips the poll.
 */
class StreamRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final String BINDING = "aws-111111111111";

    private final RegistryIngestService ingest = mock(RegistryIngestService.class);
    private final FakeCursors cursors = new FakeCursors();
    private final FakeObjects objects = new FakeObjects();
    private final FakeLocks locks = new FakeLocks();
    private FakeTransport transport;
    private EffectiveStream stream;
    private StreamRunner runner;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        BindingConfig parse = new BindingConfig(BINDING, "TEST_AUDIT", Optional.empty(), null, Map.of(), List.of(),
                null, BindingConfig.DEFAULT_MAX_RECORD_BYTES, AssetIdHints.none());
        stream = new EffectiveStream(BINDING, "s3-file", "TEST_FILES", "json-record", Map.of("bucket", "b"), parse,
                StreamFilters.NONE, Duration.ofMinutes(20), Duration.ofMinutes(1), null, null, false, false, false);
        EffectiveBinding binding = EffectiveBinding.of(BINDING, "aws-org", "TEST_AUDIT", Optional.of(SourceProvider.AWS),
                ConfigOrigin.FILE, true, true, Map.of("account-id", "111111111111"), List.of(stream));
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(binding),
                ValidationReport.CLEAN);
        runner = new StreamRunner(sources, new TransportRegistry(List.of(transport)), ingest, cursors, objects, locks,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(ingest.ingestBatch(anyList(), anyBoolean())).thenAnswer(inv -> {
            List<RawRecord> records = inv.getArgument(0);
            List<RecordReport> reports = new ArrayList<>();
            records.forEach(r -> reports.add(new RecordReport("PARSED", List.of(), Optional.empty())));
            return new BatchReport(reports);
        });
    }

    @Test
    void commitsTheCursorAfterEachBatchAndChecksPointsObjects() {
        transport.batches.add(batch("k1", "e1", 2, Instant.parse("2026-10-19T10:00:00Z")));
        transport.batches.add(batch("k2", "e1", 1, Instant.parse("2026-10-19T11:00:00Z")));

        StreamRunner.PollResult r = runner.poll(stream);

        assertThat(r.ran()).isTrue();
        assertThat(r.batches()).isEqualTo(2);
        assertThat(r.records()).isEqualTo(3);
        assertThat(cursors.get("").lowWatermark()).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"));
        assertThat(objects.done).containsExactlyInAnyOrder("k1#e1", "k2#e1");
        assertThat(transport.committed).isEqualTo(2);
        assertThat(locks.released).isTrue();
    }

    @Test
    void theWindowStartsAtTheLowWatermarkMinusTheOverlap() {
        cursors.positions.put("", new CursorPosition(Instant.parse("2026-10-19T11:00:00Z"), null, Map.of()));

        runner.poll(stream);

        assertThat(transport.windows.get(0).from()).isEqualTo(Instant.parse("2026-10-19T10:40:00Z"));
        assertThat(transport.windows.get(0).to()).isEqualTo(NOW);
    }

    @Test
    void theFirstPollReadsTheInitialLookback() {
        runner.poll(stream);

        assertThat(transport.windows.get(0).from()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    void aFailureStopsThePartitionWithoutMovingTheCursor() {
        transport.batches.add(batch("k1", "e1", 1, Instant.parse("2026-10-19T10:00:00Z")));
        transport.batches.add(batch("k2", "e1", 1, Instant.parse("2026-10-19T11:00:00Z")));
        transport.batches.add(batch("k3", "e1", 1, Instant.parse("2026-10-19T11:30:00Z")));
        when(ingest.ingestBatch(anyList(), anyBoolean()))
                .thenReturn(new BatchReport(List.of(new RecordReport("PARSED", List.of(), Optional.empty()))))
                .thenThrow(new IllegalStateException("database down"));

        StreamRunner.PollResult r = runner.poll(stream);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.batches()).isEqualTo(1);
        assertThat(cursors.get("").lowWatermark()).as("cursor stays after the last good batch")
                .isEqualTo(Instant.parse("2026-10-19T10:00:00Z"));
        assertThat(objects.failed).containsExactly("k2#e1");
        assertThat(objects.done).containsExactly("k1#e1");
        verify(ingest, times(2)).ingestBatch(anyList(), anyBoolean());
        assertThat(locks.released).isTrue();
    }

    @Test
    void anObjectAlreadyReadIsSkippedButTheCursorMoves() {
        objects.done.add("k1#e1");
        transport.batches.add(batch("k1", "e1", 3, Instant.parse("2026-10-19T10:00:00Z")));

        runner.poll(stream);

        verify(ingest, never()).ingestBatch(anyList(), anyBoolean());
        assertThat(cursors.get("").lowWatermark()).isEqualTo(Instant.parse("2026-10-19T10:00:00Z"));
    }

    @Test
    void aLockHeldElsewhereSkipsThePoll() {
        locks.available = false;
        transport.batches.add(batch("k1", "e1", 1, Instant.parse("2026-10-19T10:00:00Z")));

        assertThat(runner.poll(stream).ran()).isFalse();
        assertThat(transport.windows).isEmpty();
    }

    private RawBatch batch(String key, String etag, int records, Instant low) {
        List<RawRecord> rs = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            rs.add(RawRecord.builder().bindingId(BINDING).sourceKind("TEST_AUDIT").envelope("json-record")
                    .ref(RawRef.of("TEST_FILES", "s3://b/" + key, i)).fetchedAt(NOW).text("{\"v\":\"1.0\"}").build());
        }
        return new RawBatch("", rs, Optional.of(new RawBatch.ObjectRef(key, etag)), new CursorPosition(low, low, Map.of()));
    }

    /** A transport that hands out prepared batches and records what it was asked. */
    static final class FakeTransport implements LogTransport {
        final List<RawBatch> batches = new ArrayList<>();
        final List<FetchWindow> windows = new ArrayList<>();
        int committed;

        @Override
        public TransportDescriptor descriptor() {
            return new TransportDescriptor("TEST_FILES", Set.of("bucket"), Set.of("prefix"),
                    TransportDescriptor.CursorKind.OBJECT, Duration.ofHours(1), Duration.ZERO);
        }

        @Override
        public RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor,
                                      FetchWindow window) {
            windows.add(window);
            return RawBatchIterator.of(batches);
        }

        @Override
        public void committed(StreamContext context, RawBatch batch) {
            committed++;
        }
    }

    /** In-memory cursors. */
    static final class FakeCursors implements StreamCursorStore {
        final Map<String, CursorPosition> positions = new HashMap<>();

        CursorPosition get(String partition) {
            return positions.get(partition);
        }

        @Override
        public Optional<CursorPosition> load(String bindingId, String streamId, String partitionKey) {
            return Optional.ofNullable(positions.get(partitionKey));
        }

        @Override
        public void commit(String bindingId, String streamId, String partitionKey, CursorPosition position) {
            positions.put(partitionKey, position);
        }
    }

    /** In-memory checkpoints. */
    static final class FakeObjects implements ProcessedObjectStore {
        final Set<String> done = new HashSet<>();
        final List<String> failed = new ArrayList<>();

        @Override
        public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
            return done.contains(objectKey + "#" + etag);
        }

        @Override
        public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                             long quarantined) {
            done.add(objectKey + "#" + etag);
        }

        @Override
        public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
            failed.add(objectKey + "#" + etag);
        }

        @Override
        public int attempts(String bindingId, String streamId, String objectKey, String etag) {
            return (int) failed.stream().filter((objectKey + "#" + etag)::equals).count();
        }
    }

    /** A lock that is free or held elsewhere. */
    static final class FakeLocks implements AdvisoryLocks {
        boolean available = true;
        boolean released;

        @Override
        public Optional<Lease> tryLockStream(String bindingId, String streamId) {
            return available ? Optional.of(() -> released = true) : Optional.empty();
        }
    }
}
