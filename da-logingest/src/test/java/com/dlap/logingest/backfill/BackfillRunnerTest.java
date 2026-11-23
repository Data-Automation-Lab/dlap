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
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.TransportRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * LOG-04 acceptance on the runner: bounded, resumable, rate-limited, {@code origin=BACKFILL}, and a
 * kill-and-resume run that leaves no gaps and no duplicates. The transport, the ingest path and the job
 * table are in-memory doubles; the same behaviour against PostgreSQL and the real registry is
 * {@code BackfillPipelineIntegrationTest}.
 */
class BackfillRunnerTest {

    private static final Instant NOW = T0.plus(Duration.ofDays(30));
    private static final Instant FROM = T0;
    private static final Instant TO = T0.plus(Duration.ofHours(6));

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryJobs jobs = new InMemoryJobs();
    private final CountingIngest ingest = new CountingIngest();
    private final FakeLocks locks = new FakeLocks();
    private final RecordingObjects objects = new RecordingObjects();
    private final FakePacing pacing = new FakePacing();
    private ScriptedTransport transport;
    private EffectiveLogSources sources;
    private BackfillProperties props;
    private List<String> expected;

    @BeforeEach
    void setUp() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 3);
        expected = new ArrayList<>();
        // One event every 7 minutes, one exactly on a slice boundary and one exactly at the (excluded) end.
        for (int i = 0; i < 51; i++) {
            addEvent(transport, "", FROM.plus(Duration.ofMinutes(7L * i)), "e-" + String.format("%03d", i));
        }
        addEvent(transport, "", FROM.plus(Duration.ofHours(1)), "e-boundary-1h");
        transport.add("", Event.at(TO, "e-after-the-end"));
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, null));
        props = BackfillProperties.defaults();
    }

    private void addEvent(ScriptedTransport t, String partition, Instant ts, String id) {
        t.add(partition, Event.at(ts, id));
        if (ts.isBefore(TO)) {
            expected.add(id);
        }
    }

    private BackfillRunner runner() {
        return new BackfillRunner(jobs, sources, new TransportRegistry(List.of(transport)), ingest.service, objects,
                locks, props, clock, pacing::pacer);
    }

    private UUID newJob() {
        return newJob(FROM, TO);
    }

    private UUID newJob(Instant from, Instant to) {
        UUID id = UUID.randomUUID();
        jobs.insert(id, BINDING, STREAM, from, to, "ops-admin", NOW);
        return id;
    }

    private BackfillJob job(UUID id) {
        return jobs.find(id).orElseThrow();
    }

    @Test
    void readsTheWindowInBoundedSlicesInBackfillMode() {
        UUID id = newJob();

        RunResult r = runner().run(id, () -> false);

        assertThat(r).isEqualTo(RunResult.COMPLETED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.SUCCEEDED);
        assertThat(job(id).startedAt()).isEqualTo(NOW);
        assertThat(job(id).finishedAt()).isEqualTo(NOW);
        assertThat(job(id).fractionComplete()).isEqualTo(1.0);
        assertThat(ingest.stored).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ingest.published).as("each event published exactly once").hasSize(expected.size())
                .doesNotHaveDuplicates();
        assertThat(ingest.seen).as("origin=BACKFILL: every record was read in BACKFILL mode")
                .allSatisfy(rec -> assertThat(rec.mode()).isEqualTo(IngestMode.BACKFILL))
                .hasSize(expected.size());
        assertThat(ingest.stored).as("an event at the exclusive end is not read").doesNotContain("e-after-the-end");

        // Bounded: every fetch lies inside the job's window, is at most one slice long, and the slices are contiguous.
        assertThat(transport.calls).hasSize(6);
        Instant cursor = FROM;
        for (BackfillTestKit.FetchCall c : transport.calls) {
            assertThat(c.window().from()).isEqualTo(cursor);
            assertThat(Duration.between(c.window().from(), c.window().to())).isLessThanOrEqualTo(Duration.ofHours(1));
            assertThat(c.window().to()).isAfter(c.window().from()).isBeforeOrEqualTo(TO);
            cursor = c.window().to();
        }
        assertThat(cursor).isEqualTo(TO);
        assertThat(locks.lastScope).isEqualTo("backfill-job");
        assertThat(locks.lastKey).isEqualTo(id.toString());
        assertThat(locks.taken).isEqualTo(locks.released).isEqualTo(1);
    }

    @Test
    void aWindowThatIsNotAWholeNumberOfSlicesEndsExactlyAtTo() {
        UUID id = newJob(FROM, FROM.plus(Duration.ofMinutes(90)));

        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(transport.calls).extracting(c -> c.window().to()).containsExactly(FROM.plus(Duration.ofHours(1)),
                FROM.plus(Duration.ofMinutes(90)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 8, 13, 17, 19})
    void aKilledJobResumesWithNoGapsAndNoDuplicates(int killAtCall) {
        UUID id = newJob();
        ingest.killAfterStoringCall = killAtCall;

        assertThatThrownBy(() -> runner().run(id, () -> false)).isInstanceOf(Killed.class);

        assertThat(job(id).status()).as("a killed run leaves the job resumable").isEqualTo(BackfillStatus.RUNNING);
        assertThat(job(id).progress().batches()).as("the batch in flight was not committed")
                .isEqualTo(killAtCall - 1);
        assertThat(locks.released).as("the lock is released when the process dies").isEqualTo(1);

        // Restart: a new runner instance, the same job table and store.
        ingest.killAfterStoringCall = 0;
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(job(id).status()).isEqualTo(BackfillStatus.SUCCEEDED);
        assertThat(ingest.stored).as("no gaps").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ingest.published).as("no duplicates: no event is published twice").hasSize(expected.size())
                .doesNotHaveDuplicates();
        assertThat(ingest.seen.size()).as("the batch in flight when the process died was read again")
                .isGreaterThan(expected.size());
        assertThat(job(id).progress().duplicates()).as("and the store absorbed it").isGreaterThan(0);
    }

    @Test
    void aJobSurvivesRepeatedKills() {
        UUID id = newJob();
        int kills = 0;
        RunResult last = null;
        while (last == null && kills < 40) {
            ingest.killAfterStoringCall = ingest.calls + 2;
            try {
                last = runner().run(id, () -> false);
            } catch (Killed e) {
                kills++;
            }
        }

        assertThat(last).isEqualTo(RunResult.COMPLETED);
        assertThat(kills).isGreaterThan(3);
        assertThat(ingest.stored).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ingest.published).hasSize(expected.size()).doesNotHaveDuplicates();
    }

    @Test
    void aFileTransportSkipsTheObjectsAJobAlreadyFinishedAndNeverUsesTheStreamsOwnCheckpoints() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.OBJECT, 10);
        Instant base = FROM.plus(Duration.ofHours(2));
        transport.add("", new Event(base, "a-1", "o1"), new Event(base.plusSeconds(1), "a-2", "o1"),
                new Event(base.plusSeconds(60), "b-1", "o2"), new Event(base.plusSeconds(61), "b-2", "o2"),
                new Event(base.plusSeconds(120), "c-1", "o3"));
        // The stream itself already read o1: that must not hide it from the backfill.
        objects.done.add(STREAM + "|o1#g1");
        UUID id = newJob();
        ingest.killAfterStoringCall = 2;

        assertThatThrownBy(() -> runner().run(id, () -> false)).isInstanceOf(Killed.class);
        ingest.killAfterStoringCall = 0;
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(ingest.stored).containsExactlyInAnyOrder("a-1", "a-2", "b-1", "b-2", "c-1");
        assertThat(ingest.published).doesNotHaveDuplicates().hasSize(5);
        List<String> readOrder = ingest.seen.stream().map(BackfillTestKit::idOf).toList();
        assertThat(readOrder.stream().filter("a-1"::equals).count()).as("o1 was finished before the kill").isEqualTo(1);
        assertThat(readOrder.stream().filter("b-1"::equals).count()).as("o2 was in flight, so it was read twice")
                .isEqualTo(2);
        String scope = ScopedObjects.scopeOf(id);
        assertThat(objects.done).filteredOn(k -> !k.startsWith(STREAM + "|")).allMatch(k -> k.startsWith(scope + "|"));
        assertThat(objects.done).contains(scope + "|o1#g1", scope + "|o2#g1", scope + "|o3#g1");
        assertThat(objects.streamIdsUsed).as("only the stream's earlier checkpoint existed under its own id")
                .containsExactlyInAnyOrder(scope);
    }

    @Test
    void everyPartitionKeepsItsOwnPositionAndAFinishedPartitionIsNotReadAgain() {
        transport = new ScriptedTransport(TransportDescriptor.CursorKind.TIME, 5, "east", "west");
        for (int i = 0; i < 12; i++) {
            transport.add("east", Event.at(FROM.plus(Duration.ofMinutes(20L * i)), "east-" + i));
            transport.add("west", Event.at(FROM.plus(Duration.ofMinutes(20L * i + 5)), "west-" + i));
        }
        UUID id = newJob(FROM, FROM.plus(Duration.ofHours(4)));
        // East is read first; kill once east is done and west has begun.
        ingest.killAfterStoringCall = 6;

        assertThatThrownBy(() -> runner().run(id, () -> false)).isInstanceOf(Killed.class);
        assertThat(job(id).progress().partition("east")).get().extracting(BackfillProgress.Partition::done)
                .isEqualTo(true);
        int callsBefore = transport.calls.size();
        ingest.killAfterStoringCall = 0;
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(transport.calls.subList(callsBefore, transport.calls.size())).extracting(c -> c.partition())
                .as("east was finished, so the resumed run reads west only").containsOnly("west");
        assertThat(ingest.stored).hasSize(24);
        assertThat(ingest.published).hasSize(24).doesNotHaveDuplicates();
        assertThat(job(id).progress().partitions()).extracting(BackfillProgress.Partition::key)
                .containsExactly("east", "west");
    }

    @Test
    void anAdministratorCancelStopsTheJobAfterTheBatchInFlightAndItIsNeverRevived() {
        UUID id = newJob();
        ingest.beforeBatch = () -> {
            if (ingest.calls == 3) {
                assertThat(jobs.cancel(id, NOW, "cancelled by ops-admin")).isTrue();
            }
        };

        RunResult r = runner().run(id, () -> false);

        assertThat(r).isEqualTo(RunResult.CANCELLED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.CANCELLED);
        assertThat(ingest.calls).as("no batch is read after the cancel").isEqualTo(3);
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.NOT_RUNNABLE);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.CANCELLED);
    }

    @Test
    void aShutdownLeavesTheJobRunningAndTheNextStartFinishesIt() {
        UUID id = newJob();

        RunResult stopped = runner().run(id, () -> ingest.calls >= 4);

        assertThat(stopped).isEqualTo(RunResult.STOPPED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.RUNNING);
        assertThat(job(id).progress().batches()).isEqualTo(4);
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);
        assertThat(ingest.stored).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ingest.published).hasSize(expected.size()).doesNotHaveDuplicates();
    }

    @Test
    void batchesAreSpacedByTheConfiguredRateAndTheStreamsOwnLimitLowersIt() {
        props = new BackfillProperties(true, Duration.ofDays(90), Duration.ofHours(1), Duration.ofDays(1), 2.0, 2,
                Duration.ofSeconds(15), 5, Duration.ofSeconds(30), true, Duration.ofMinutes(5), Duration.ofHours(24),
                Duration.ofHours(6));
        UUID id = newJob();

        runner().run(id, () -> false);

        long batches = job(id).progress().batches();
        assertThat(pacing.rates).containsExactly(2.0);
        assertThat(pacing.sleeps).hasSize((int) batches - 1).allMatch(d -> d.equals(Duration.ofMillis(500)));
        assertThat(pacing.totalSleep()).isEqualTo(Duration.ofMillis(500).multipliedBy(batches - 1));

        // A stream limit below the ceiling wins; one above it does not.
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, 0.5));
        jobs.cancel(id, NOW, "done");
        runner().run(newJob(), () -> false);
        assertThat(pacing.rates).containsExactly(2.0, 0.5);
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(true, false, 50.0));
        runner().run(newJob(), () -> false);
        assertThat(pacing.rates.get(2)).isEqualTo(2.0);
    }

    @Test
    void aFailedAttemptKeepsTheJobRunningWithBackoffAndTheJobFailsAfterTheLastAttempt() {
        UUID id = newJob();
        ingest.failWith = new IllegalStateException("db password=hunter2 for a@example.com");

        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.RETRY);
        BackfillJob after1 = job(id);
        assertThat(after1.status()).isEqualTo(BackfillStatus.RUNNING);
        assertThat(after1.progress().attempts()).isEqualTo(1);
        assertThat(after1.progress().lastError()).isEqualTo("IllegalStateException");
        assertThat(after1.progress().nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(runner().run(id, () -> false)).as("still backing off").isEqualTo(RunResult.WAITING);
        assertThat(ingest.calls).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.RETRY);
        assertThat(job(id).progress().nextAttemptAt()).as("the wait doubles").isEqualTo(clock.instant().plusSeconds(60));

        for (int attempt = 3; attempt < 5; attempt++) {
            clock.advance(Duration.ofMinutes(20));
            assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.RETRY);
        }
        clock.advance(Duration.ofMinutes(20));
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.FAILED);

        BackfillJob failed = job(id);
        assertThat(failed.status()).isEqualTo(BackfillStatus.FAILED);
        assertThat(failed.errorDetail()).isEqualTo("stopped after 5 failed attempts, the last: IllegalStateException");
        assertThat(failed.errorDetail() + failed.progress().lastError()).as("a failure message is never stored")
                .doesNotContain("hunter2").doesNotContain("example.com");
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.NOT_RUNNABLE);
    }

    @Test
    void theBackoffIsCappedAndACommittedBatchResetsTheFailureStreak() {
        props = new BackfillProperties(true, Duration.ofDays(90), Duration.ofHours(1), Duration.ofDays(1), 5, 2,
                Duration.ofSeconds(15), 5, Duration.ofMinutes(10), true, Duration.ofMinutes(5), Duration.ofHours(24),
                Duration.ofHours(6));
        UUID id = newJob();
        ingest.failWith = new IllegalStateException("x");
        runner().run(id, () -> false);
        clock.advance(Duration.ofMinutes(10));
        runner().run(id, () -> false);

        assertThat(job(id).progress().attempts()).isEqualTo(2);
        assertThat(job(id).progress().nextAttemptAt()).as("20 minutes, capped at 15")
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        ingest.failWith = null;
        clock.advance(Duration.ofMinutes(15));
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);
        assertThat(job(id).progress().attempts()).isZero();
        assertThat(job(id).progress().lastError()).isEmpty();
        assertThat(ingest.published).hasSize(expected.size()).doesNotHaveDuplicates();
    }

    @Test
    void aTransportThatStopsShortOfTheSliceEndFailsTheAttemptInsteadOfLeavingAGap() {
        transport.stopShort = true;
        UUID id = newJob();

        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.RETRY);

        BackfillJob j = job(id);
        assertThat(j.status()).isEqualTo(BackfillStatus.RUNNING);
        assertThat(j.progress().lastError()).isEqualTo("IncompleteSliceException");
        BackfillProgress.Partition p = j.progress().partition("").orElseThrow();
        assertThat(p.done()).isFalse();
        assertThat(p.low()).as("the position never jumps past what was delivered").isBefore(FROM.plus(Duration.ofHours(1)));
    }

    @Test
    void aJobWhoseStreamNoLongerAllowsBackfillOrWasRemovedFailsAtOnce() {
        UUID id = newJob();
        sources = BackfillTestKit.sources(true, BackfillTestKit.stream(false, false, null));

        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.FAILED);

        assertThat(job(id).errorDetail()).isEqualTo("the stream no longer allows backfill");
        assertThat(ingest.calls).isZero();
        assertThat(transport.calls).isEmpty();

        UUID other = newJob();
        sources = BackfillTestKit.sources(false, BackfillTestKit.stream(true, false, null));
        assertThat(runner().run(other, () -> false)).isEqualTo(RunResult.FAILED);
        assertThat(job(other).errorDetail()).isEqualTo("the binding or stream no longer runs");
    }

    @Test
    void anInterruptedWaitPausesTheJobWithoutCountingAnAttempt() {
        UUID id = newJob();
        BackfillRunner interrupted = new BackfillRunner(jobs, sources, new TransportRegistry(List.of(transport)),
                ingest.service, objects, locks, props, clock, rate -> new BatchPacer(rate, () -> 0L, d -> {
                    throw new InterruptedException();
                }));

        RunResult r = interrupted.run(id, () -> false);

        assertThat(Thread.interrupted()).as("the flag is set again; cleared here for the next test").isTrue();
        assertThat(r).isEqualTo(RunResult.STOPPED);
        assertThat(job(id).status()).isEqualTo(BackfillStatus.RUNNING);
        assertThat(job(id).progress().attempts()).isZero();
        assertThat(job(id).progress().batches()).isEqualTo(1);
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);
        assertThat(ingest.published).hasSize(expected.size()).doesNotHaveDuplicates();
    }

    @Test
    void aJobWhoseTransportIsNoLongerRegisteredFailsAtOnce() {
        UUID id = newJob();
        BackfillRunner without = new BackfillRunner(jobs, sources, new TransportRegistry(List.of()), ingest.service,
                objects, locks, props, clock, pacing::pacer);

        assertThat(without.run(id, () -> false)).isEqualTo(RunResult.FAILED);

        assertThat(job(id).errorDetail()).isEqualTo("transport SCRIPTED is not registered");
        assertThat(ingest.calls).isZero();
    }

    @Test
    void aLockHeldByAnotherReplicaSkipsTheJobWithoutTouchingIt() {
        UUID id = newJob();
        locks.available = false;

        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.SKIPPED);

        assertThat(job(id).status()).isEqualTo(BackfillStatus.PENDING);
        assertThat(ingest.calls).isZero();
    }

    @Test
    void unknownAndEndedJobsAreNotRunnable() {
        assertThat(runner().run(UUID.randomUUID(), () -> false)).isEqualTo(RunResult.NOT_RUNNABLE);
        UUID id = newJob();
        runner().run(id, () -> false);
        int calls = ingest.calls;
        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.NOT_RUNNABLE);
        assertThat(ingest.calls).isEqualTo(calls);
    }

    @Test
    void quarantinedRecordsAreCountedAndTheJobStillAdvances() {
        transport.add("", Event.at(FROM.plus(Duration.ofMinutes(3)), "bad-1"));
        UUID id = newJob(FROM, FROM.plus(Duration.ofHours(1)));

        assertThat(runner().run(id, () -> false)).isEqualTo(RunResult.COMPLETED);

        assertThat(job(id).progress().quarantined()).isEqualTo(1);
        assertThat(ingest.stored).contains("bad-1");
    }

    @Test
    void theProgressCountersAddUp() {
        UUID id = newJob();

        runner().run(id, () -> false);

        BackfillProgress p = job(id).progress();
        assertThat(p.records()).isEqualTo(expected.size());
        assertThat(p.batches()).isEqualTo(ingest.calls);
        assertThat(p.heartbeat()).isEqualTo(NOW);
        assertThat(ingest.batchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(expected.size());
        assertThat(p.partitions().stream().map(BackfillProgress.Partition::key).collect(Collectors.toList()))
                .containsExactly("");
    }
}
