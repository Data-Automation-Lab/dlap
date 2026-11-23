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
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.registry.BatchReport;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Q-665: an object that cannot be read is retried at the next polls, and once it has failed
 * {@code max-read-attempts} times the runner quarantines it as {@code READ_FAILED}, checkpoints it
 * and goes on with the objects behind it. Earlier failures keep the cursor and retry.
 */
class StreamRunnerReadFailureTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final String BINDING = "aws-111111111111";
    private static final String BAD = "AWSLogs/111111111111/CloudTrail/us-east-1/2026/10/19/bad.json.gz";
    private static final String GOOD = "AWSLogs/111111111111/CloudTrail/us-east-1/2026/10/19/good.json.gz";

    private final RegistryIngestService ingest = mock(RegistryIngestService.class);
    private final StreamRunnerTest.FakeCursors cursors = new StreamRunnerTest.FakeCursors();
    private final StreamRunnerTest.FakeLocks locks = new StreamRunnerTest.FakeLocks();
    private final CountingObjects objects = new CountingObjects();
    private final List<LogQuarantineRecord> quarantined = new ArrayList<>();
    private EffectiveStream stream;
    private EffectiveLogSources sources;
    private UnreadableFirstTransport transport;

    @BeforeEach
    void setUp() {
        transport = new UnreadableFirstTransport();
        BindingConfig parse = new BindingConfig(BINDING, "TEST_AUDIT", Optional.empty(), null, Map.of(), List.of(),
                null, BindingConfig.DEFAULT_MAX_RECORD_BYTES, AssetIdHints.none());
        stream = new EffectiveStream(BINDING, "s3-file", "TEST_FILES", "json-record", Map.of("bucket", "b"), parse,
                StreamFilters.NONE, Duration.ofMinutes(20), Duration.ofMinutes(1), null, null, false, false, false);
        EffectiveBinding binding = EffectiveBinding.of(BINDING, "aws-org", "TEST_AUDIT", Optional.of(SourceProvider.AWS),
                ConfigOrigin.FILE, true, true, Map.of("account-id", "111111111111"), List.of(stream));
        sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(binding), ValidationReport.CLEAN);
        when(ingest.ingestBatch(anyList(), anyBoolean())).thenAnswer(inv -> {
            List<RawRecord> records = inv.getArgument(0);
            List<RecordReport> reports = new ArrayList<>();
            records.forEach(r -> reports.add(new RecordReport("PARSED", List.of(), Optional.empty())));
            return new BatchReport(reports);
        });
    }

    private StreamRunner runner(int cap, QuarantineSink... sinks) {
        Map<String, Object> beans = new HashMap<>();
        for (int i = 0; i < sinks.length; i++) {
            beans.put("sink" + i, sinks[i]);
        }
        ObjectProvider<QuarantineSink> provider = new StaticListableBeanFactory(beans)
                .getBeanProvider(QuarantineSink.class);
        return new StreamRunner(sources, new TransportRegistry(List.of(transport)), ingest, cursors, objects, locks,
                Clock.fixed(NOW, ZoneOffset.UTC), provider, cap);
    }

    private QuarantineSink collecting() {
        return quarantined::add;
    }

    @Test
    void theFifthFailedReadQuarantinesTheObjectAndTheStreamMovesOn() {
        StreamRunner runner = runner(5, collecting());
        transport.failures = Integer.MAX_VALUE;

        for (int poll = 1; poll <= 4; poll++) {
            StreamRunner.PollResult r = runner.poll(stream);
            assertThat(r.failed()).as("poll %d fails", poll).isEqualTo(1);
            assertThat(r.batches()).as("the good object behind it waits").isZero();
            assertThat(objects.attempts(BINDING, "s3-file", BAD, "etag")).isEqualTo(poll);
            assertThat(quarantined).as("earlier failures retry, they do not quarantine").isEmpty();
            assertThat(objects.done).doesNotContain(BAD + "#etag");
            assertThat(cursors.positions).as("the cursor does not move past the bad object").isEmpty();
        }

        StreamRunner.PollResult fifth = runner.poll(stream);

        assertThat(fifth.failed()).isZero();
        assertThat(fifth.quarantined()).isEqualTo(1);
        assertThat(fifth.batches()).as("the object behind the bad one is read in the same poll").isEqualTo(1);
        assertThat(quarantined).hasSize(1);
        LogQuarantineRecord q = quarantined.get(0);
        assertThat(q.getReason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        assertThat(q.getReasonDetail()).startsWith(StreamRunner.READ_FAILED).contains("AccessDeniedException")
                .contains("5 failed reads");
        assertThat(q.getBindingId()).isEqualTo(BINDING);
        assertThat(q.getSourceProvider()).isEqualTo(SourceProvider.AWS);
        assertThat(q.getSourceKind()).isEqualTo("TEST_AUDIT");
        assertThat(q.getEnvelope()).isEqualTo("json-record");
        assertThat(q.getRawRef().getUri()).isEqualTo("s3://b/" + BAD);
        assertThat(q.getRawRef().getCursor()).isEqualTo("etag");
        assertThat(q.getRawRef().getRecordIndex()).isEqualTo(-1);
        assertThat(q.getRawSha256()).hasSize(64);
        assertThat(q.getQuarantineId()).hasSize(64);
        assertThat(q.getQuarantinedTime().getSeconds()).isEqualTo(NOW.getEpochSecond());
        assertThat(objects.done).as("checkpointed").contains(BAD + "#etag", GOOD + "#etag");
        assertThat(cursors.get("").lowWatermark()).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"));

        StreamRunner.PollResult sixth = runner.poll(stream);

        assertThat(sixth.failed()).isZero();
        assertThat(sixth.quarantined()).as("a checkpointed object is skipped, not quarantined again").isZero();
        assertThat(quarantined).hasSize(1);
        assertThat(transport.reads).as("the bad object is read 5 times and never again").isEqualTo(5);
    }

    @Test
    void anObjectThatReadsAgainBeforeTheCapIsIngestedAndNeverQuarantined() {
        StreamRunner runner = runner(5, collecting());
        transport.failures = 2;

        assertThat(runner.poll(stream).failed()).isEqualTo(1);
        assertThat(runner.poll(stream).failed()).isEqualTo(1);
        StreamRunner.PollResult third = runner.poll(stream);

        assertThat(third.failed()).isZero();
        assertThat(third.batches()).isEqualTo(2);
        assertThat(third.quarantined()).isZero();
        assertThat(quarantined).isEmpty();
        assertThat(objects.done).contains(BAD + "#etag", GOOD + "#etag");
    }

    @Test
    void theCapIsConfigurable() {
        StreamRunner runner = runner(2, collecting());
        transport.failures = Integer.MAX_VALUE;

        assertThat(runner.poll(stream).failed()).isEqualTo(1);
        assertThat(quarantined).isEmpty();
        StreamRunner.PollResult second = runner.poll(stream);

        assertThat(second.failed()).isZero();
        assertThat(second.quarantined()).isEqualTo(1);
        assertThat(quarantined.get(0).getReasonDetail()).contains("2 failed reads");
    }

    @Test
    void aCapBelowOneQuarantinesOnTheFirstFailure() {
        StreamRunner runner = runner(0, collecting());
        transport.failures = Integer.MAX_VALUE;

        assertThat(runner.poll(stream).quarantined()).isEqualTo(1);
    }

    @Test
    void theRecordNamesTheExceptionClassNeverItsMessage() {
        StreamRunner runner = runner(1, collecting());
        transport.failures = Integer.MAX_VALUE;
        transport.message = "User: arn:aws:sts::111122223333:assumed-role/secret-role is not authorized, token=abc123";

        runner.poll(stream);

        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).toString()).doesNotContain("secret-role", "abc123", "111122223333");
    }

    @Test
    void aSinkFailureKeepsTheObjectForTheNextPoll() {
        boolean[] down = {true};
        QuarantineSink flaky = r -> {
            if (down[0]) {
                throw new IllegalStateException("database down");
            }
            quarantined.add(r);
        };
        StreamRunner runner = runner(1, flaky);
        transport.failures = Integer.MAX_VALUE;

        StreamRunner.PollResult first = runner.poll(stream);

        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.quarantined()).isZero();
        assertThat(objects.done).as("not checkpointed when the quarantine row was not written")
                .doesNotContain(BAD + "#etag");

        down[0] = false;
        StreamRunner.PollResult second = runner.poll(stream);

        assertThat(second.failed()).isZero();
        assertThat(second.quarantined()).isEqualTo(1);
        assertThat(quarantined).hasSize(1);
        assertThat(objects.done).contains(BAD + "#etag");
    }

    @Test
    void withNoSinkRegisteredTheObjectIsLoggedAndCheckpointedAllTheSame() {
        StreamRunner runner = runner(1);
        transport.failures = Integer.MAX_VALUE;

        StreamRunner.PollResult r = runner.poll(stream);

        assertThat(r.failed()).isZero();
        assertThat(r.quarantined()).isEqualTo(1);
        assertThat(objects.done).contains(BAD + "#etag");
    }

    @Test
    void aFailureThatIsNoObjectReadStillStopsThePartitionAndNeverQuarantines() {
        StreamRunner runner = runner(1, collecting());
        transport.plainFailure = true;

        StreamRunner.PollResult r = runner.poll(stream);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(quarantined).isEmpty();
    }

    @Test
    void anInvalidCredentialsRefFailsThePartitionAtEveryPollAndNeverQuarantinesOrCounts() {
        // Q-707: the S3 client is built when the stream lists its bucket, so the error is thrown from fetch
        // before any object is read. It is a configuration error: no attempt is counted, so the cap is
        // never reached and the partition fails again at every poll until the reference is fixed.
        StreamRunner runner = runner(2, collecting());
        transport.invalidCredentialsRef = true;

        for (int poll = 1; poll <= 6; poll++) {
            StreamRunner.PollResult r = runner.poll(stream);
            assertThat(r.failed()).as("poll %d fails again", poll).isEqualTo(1);
            assertThat(r.quarantined()).isZero();
        }

        assertThat(quarantined).isEmpty();
        assertThat(objects.attempts).as("no object was involved, so nothing was counted").isEmpty();
        assertThat(objects.done).isEmpty();

        transport.invalidCredentialsRef = false;
        StreamRunner.PollResult fixed = runner.poll(stream);

        assertThat(fixed.failed()).as("the next poll after the fix succeeds").isZero();
        assertThat(fixed.batches()).isEqualTo(2);
    }

    /** Checkpoints with an attempt counter, like {@code processed_object}. */
    static final class CountingObjects implements ProcessedObjectStore {
        final Set<String> done = new HashSet<>();
        final Map<String, Integer> attempts = new HashMap<>();

        @Override
        public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
            return done.contains(objectKey + "#" + etag);
        }

        @Override
        public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                             long quarantined) {
            done.add(objectKey + "#" + etag);
            attempts.merge(objectKey + "#" + etag, 1, Integer::sum);
        }

        @Override
        public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
            attempts.merge(objectKey + "#" + etag, 1, Integer::sum);
        }

        @Override
        public int attempts(String bindingId, String streamId, String objectKey, String etag) {
            return attempts.getOrDefault(objectKey + "#" + etag, 0);
        }
    }

    /**
     * Two objects, the unreadable one first: it throws {@link ObjectReadException} for its first
     * {@code failures} reads and is positioned past, like the S3 and GCS transports. An object already
     * checkpointed is skipped, as the real transports do.
     */
    static final class UnreadableFirstTransport implements LogTransport {
        int failures;
        int reads;
        boolean plainFailure;
        boolean invalidCredentialsRef;
        String message = "Access Denied";

        @Override
        public TransportDescriptor descriptor() {
            return new TransportDescriptor("TEST_FILES", Set.of("bucket"), Set.of("prefix"),
                    TransportDescriptor.CursorKind.OBJECT, Duration.ofHours(1), Duration.ZERO);
        }

        @Override
        public RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor,
                                      FetchWindow window) {
            if (invalidCredentialsRef) {
                throw new IllegalStateException("an S3 stream's credentials-ref must be an IAM role ARN");
            }
            List<String> keys = new ArrayList<>(List.of(BAD, GOOD));
            return new RawBatchIterator() {
                private int next;

                @Override
                public boolean hasNext() {
                    while (next < keys.size() && context.alreadyProcessed(keys.get(next), "etag")) {
                        next++;
                    }
                    return next < keys.size();
                }

                @Override
                public RawBatch next() {
                    String key = keys.get(next++);
                    if (plainFailure) {
                        throw new IllegalStateException("not tied to an object");
                    }
                    if (BAD.equals(key)) {
                        reads++;
                        if (reads <= failures) {
                            throw new ObjectReadException(new RawBatch.ObjectRef(key, "etag"), "s3://b/" + key,
                                    new AccessDeniedException(message));
                        }
                    }
                    Instant low = BAD.equals(key) ? Instant.parse("2026-10-19T10:00:00Z")
                            : Instant.parse("2026-10-19T11:00:00Z");
                    RawRecord record = RawRecord.builder().bindingId(BINDING).sourceKind("TEST_AUDIT")
                            .envelope("json-record").ref(RawRef.of("TEST_FILES", "s3://b/" + key, 0)).fetchedAt(NOW)
                            .text("{\"v\":\"1.0\"}").build();
                    return new RawBatch("", List.of(record), Optional.of(new RawBatch.ObjectRef(key, "etag")),
                            new CursorPosition(low, low, Map.of()));
                }
            };
        }
    }

    /** Stands for the SDK's access-denied failure; only its class name may reach the record. */
    static final class AccessDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AccessDeniedException(String message) {
            super(message);
        }
    }
}
