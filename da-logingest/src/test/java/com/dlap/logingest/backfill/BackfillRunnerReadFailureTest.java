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

package com.dlap.logingest.backfill;

import static com.dlap.logingest.backfill.BackfillTestKit.BINDING;
import static com.dlap.logingest.backfill.BackfillTestKit.STREAM;
import static com.dlap.logingest.backfill.BackfillTestKit.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.backfill.BackfillRunner.RunResult;
import com.dlap.logingest.backfill.BackfillTestKit.CountingIngest;
import com.dlap.logingest.backfill.BackfillTestKit.Event;
import com.dlap.logingest.backfill.BackfillTestKit.FakeLocks;
import com.dlap.logingest.backfill.BackfillTestKit.FakePacing;
import com.dlap.logingest.backfill.BackfillTestKit.InMemoryJobs;
import com.dlap.logingest.backfill.BackfillTestKit.Killed;
import com.dlap.logingest.backfill.BackfillTestKit.RecordingObjects;
import com.dlap.logingest.backfill.BackfillTestKit.ScriptedTransport;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.transport.ReadFailureQuarantine;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.TransportRegistry;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Q-702: a backfill job counts the failed reads of an object like the stream runner does. Before the
 * cap the attempt fails and the job retries after its backoff; at {@code max-read-attempts} the object is
 * quarantined as {@code READ_FAILED}, checkpointed under the job's pseudo stream and skipped, so one
 * unreadable object no longer holds a job in retry until it is {@code FAILED}.
 */
class BackfillRunnerReadFailureTest {

    private static final Instant NOW = T0.plus(Duration.ofDays(30));
    private static final Instant FROM = T0;
    private static final Instant TO = T0.plus(Duration.ofHours(6));
    private static final Instant AT = FROM.plus(Duration.ofHours(2));

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryJobs jobs = new InMemoryJobs();
    private final CountingIngest ingest = new CountingIngest();
    private final FakeLocks locks = new FakeLocks();
    private final RecordingObjects objects = new RecordingObjects();
    private final FakePacing pacing = new FakePacing();
    private final List<LogQuarantineRecord> quarantined = new ArrayList<>();
    private ScriptedTransport transport;
    private EffectiveLogSources sources;
    private BackfillProperties props;

    @BeforeEach
    void setUp() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.OBJECT, 10);
        transport.add("", new Event(AT, "a-1", "bad"), new Event(AT.plusSeconds(1), "a-2", "bad"),
                new Event(AT.plusSeconds(60), "b-1", "good"), new Event(AT.plusSeconds(61), "b-2", "good"));
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null));
        props = BackfillProperties.defaults();
    }

    private BackfillRunner runner(int cap, QuarantineSink... sinks) {
        Map<String, Object> beans = new HashMap<>();
        for (int i = 0; i < sinks.length; i++) {
            beans.put("sink" + i, sinks[i]);
        }
        ObjectProvider<QuarantineSink> provider = new StaticListableBeanFactory(beans)
                .getBeanProvider(QuarantineSink.class);
        return new BackfillRunner(jobs, sources, new TransportRegistry(List.of(transport)), ingest.service, objects,
                locks, props, clock, pacing::pacer, provider, cap);
    }

    private QuarantineSink collecting() {
        return quarantined::add;
    }

    private UUID newJob() {
        UUID id = UUID.randomUUID();
        jobs.insert(id, BINDING, STREAM, FROM, TO, "ops-admin", NOW);
        return id;
    }

    private BackfillJob job(UUID id) {
        return jobs.find(id).orElseThrow();
    }

    private RunResult runAfterBackoff(BackfillRunner runner, UUID id) {
        clock.advance(Duration.ofMinutes(20));
        return runner.run(id, () -> false);
    }

    @Test
    void theFifthFailedReadQuarantinesTheObjectAndTheBackfillMovesOn() {
        BackfillRunner runner = runner(5, collecting());
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();
        String scope = ScopedObjects.scopeOf(id);

        for (int run = 1; run <= 4; run++) {
            RunResult r = runAfterBackoff(runner, id);
            assertThat(r).as("run %d fails the attempt", run).isEqualTo(RunResult.RETRY);
            assertThat(job(id).status()).isEqualTo(BackfillStatus.RUNNING);
            assertThat(objects.attempts(BINDING, scope, "bad", "g1")).as("attempts after run %d", run).isEqualTo(run);
            assertThat(quarantined).as("earlier failures retry, they do not quarantine").isEmpty();
            assertThat(objects.done).doesNotContain(scope + "|bad#g1");
        }

        RunResult fifth = runAfterBackoff(runner, id);

        assertThat(fifth).isEqualTo(RunResult.COMPLETED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.SUCCEEDED);
        assertThat(ingest.stored).as("the readable object is ingested; the unreadable one never is")
                .containsExactlyInAnyOrder("b-1", "b-2");
        assertThat(quarantined).hasSize(1);
        LogQuarantineRecord q = quarantined.get(0);
        assertThat(q.getReason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        assertThat(q.getReasonDetail()).startsWith(ReadFailureQuarantine.READ_FAILED)
                .contains("IllegalStateException").contains("5 failed reads");
        assertThat(q.getBindingId()).isEqualTo(BINDING);
        assertThat(q.getRawRef().getUri()).isEqualTo("scripted://b/bad");
        assertThat(q.getRawRef().getRecordIndex()).isEqualTo(-1);
        assertThat(q.getQuarantinedTime().getSeconds()).isEqualTo(clock.instant().getEpochSecond());
        assertThat(objects.done).as("checkpointed under the job, not under the stream")
                .contains(scope + "|bad#g1", scope + "|good#g1").doesNotContain(STREAM + "|bad#g1");
        assertThat(objects.streamIdsUsed).containsExactly(scope);
        assertThat(job(id).progress().quarantined()).as("the skipped object is counted").isEqualTo(1);
        assertThat(job(id).progress().records()).isEqualTo(2);
        assertThat(job(id).progress().attempts()).isZero();
    }

    @Test
    void anObjectThatReadsAgainBeforeTheCapIsIngestedAndNeverQuarantined() {
        BackfillRunner runner = runner(5, collecting());
        transport.unreadable.put("bad", 2);
        UUID id = newJob();

        assertThat(runAfterBackoff(runner, id)).isEqualTo(RunResult.RETRY);
        assertThat(runAfterBackoff(runner, id)).isEqualTo(RunResult.RETRY);
        RunResult third = runAfterBackoff(runner, id);

        assertThat(third).isEqualTo(RunResult.COMPLETED);
        assertThat(ingest.stored).containsExactlyInAnyOrder("a-1", "a-2", "b-1", "b-2");
        assertThat(quarantined).isEmpty();
        assertThat(job(id).progress().quarantined()).isZero();
    }

    @Test
    void theCapIsTheConfiguredMaxReadAttempts() {
        BackfillRunner runner = runner(2, collecting());
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();

        assertThat(runAfterBackoff(runner, id)).isEqualTo(RunResult.RETRY);
        assertThat(quarantined).isEmpty();
        RunResult second = runAfterBackoff(runner, id);

        assertThat(second).isEqualTo(RunResult.COMPLETED);
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).getReasonDetail()).contains("2 failed reads");
    }

    @Test
    void theRecordNamesTheExceptionClassNeverItsMessage() {
        BackfillRunner runner = runner(1, collecting());
        transport.unreadable.put("bad", Integer.MAX_VALUE);

        runner.run(newJob(), () -> false);

        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).toString()).doesNotContain("Access Denied");
    }

    @Test
    void aSinkFailureKeepsTheObjectForTheNextAttempt() {
        boolean[] down = {true};
        QuarantineSink flaky = r -> {
            if (down[0]) {
                throw new IllegalStateException("database down");
            }
            quarantined.add(r);
        };
        BackfillRunner runner = runner(1, flaky);
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();
        String scope = ScopedObjects.scopeOf(id);

        assertThat(runAfterBackoff(runner, id)).isEqualTo(RunResult.RETRY);
        assertThat(objects.done).as("not checkpointed when the quarantine row was not written")
                .doesNotContain(scope + "|bad#g1");

        down[0] = false;
        assertThat(runAfterBackoff(runner, id)).isEqualTo(RunResult.COMPLETED);

        assertThat(quarantined).hasSize(1);
        assertThat(objects.done).contains(scope + "|bad#g1");
    }

    @Test
    void withNoSinkRegisteredTheObjectIsLoggedAndCheckpointedAllTheSame() {
        BackfillRunner runner = runner(1);
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();

        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(objects.done).contains(ScopedObjects.scopeOf(id) + "|bad#g1");
        assertThat(job(id).progress().quarantined()).isEqualTo(1);
    }

    @Test
    void aJobThatDiesAfterTheQuarantineDoesNotQuarantineTheObjectAgain() {
        BackfillRunner runner = runner(1, collecting());
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();
        ingest.killAfterStoringCall = 1;

        assertThatThrownBy(() -> runner.run(id, () -> false)).isInstanceOf(Killed.class);
        assertThat(quarantined).hasSize(1);
        ingest.killAfterStoringCall = 0;
        assertThat(runner.run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(quarantined).as("the checkpoint under the job survives the restart").hasSize(1);
        assertThat(ingest.stored).containsExactlyInAnyOrder("b-1", "b-2");
    }

    @Test
    void aStopRequestedWhileReadingTheBadObjectDoesNotCountOrQuarantine() {
        BackfillRunner runner = runner(1, collecting());
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();
        // The first check lets the partition loop start; the second comes with the unreadable object.
        AtomicInteger checks = new AtomicInteger();

        RunResult r = runner.run(id, () -> checks.incrementAndGet() >= 2);

        assertThat(r).isEqualTo(RunResult.STOPPED);
        assertThat(quarantined).isEmpty();
        assertThat(objects.attempts(BINDING, ScopedObjects.scopeOf(id), "bad", "g1")).isZero();
    }

    @Test
    void withTheDefaultSettingsTheCapIsReachedBeforeTheJobGivesUp() {
        // max-attempts 5 and max-read-attempts 5: four failed runs, then the fifth skips the object.
        BackfillRunner runner = runner(ReadFailureQuarantine.DEFAULT_MAX_READ_ATTEMPTS, collecting());
        transport.unreadable.put("bad", Integer.MAX_VALUE);
        UUID id = newJob();
        RunResult last = null;
        for (int run = 1; run <= 5; run++) {
            last = runAfterBackoff(runner, id);
        }

        assertThat(props.maxAttempts()).isEqualTo(ReadFailureQuarantine.DEFAULT_MAX_READ_ATTEMPTS);
        assertThat(last).isEqualTo(RunResult.COMPLETED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.SUCCEEDED);
    }
}
