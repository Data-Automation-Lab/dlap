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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.transport.CursorPosition;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The settings, the pacer, the progress JSON and the job view. */
class BackfillValueTypesTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    // ---- BatchPacer ----

    @Test
    void theFirstGrantIsImmediateAndLaterOnesWaitOneInterval() {
        BackfillTestKit.FakePacing pacing = new BackfillTestKit.FakePacing();
        BatchPacer p = pacing.pacer(4.0);

        p.acquire();
        p.acquire();
        p.acquire();

        assertThat(p.interval()).isEqualTo(Duration.ofMillis(250));
        assertThat(pacing.sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(250));
    }

    @Test
    void timeSpentWorkingCountsTowardsTheInterval() {
        BackfillTestKit.FakePacing pacing = new BackfillTestKit.FakePacing();
        BatchPacer p = pacing.pacer(1.0);

        p.acquire();
        pacing.nanos += Duration.ofMillis(700).toNanos();
        p.acquire();
        pacing.nanos += Duration.ofSeconds(5).toNanos();
        p.acquire();

        assertThat(pacing.sleeps).as("300 ms to fill the first interval, none after a slow batch")
                .containsExactly(Duration.ofMillis(300));
    }

    @Test
    void anInterruptedWaitStopsTheJobWithoutLosingTheInterruptFlag() {
        BatchPacer p = new BatchPacer(1.0, () -> 0L, d -> {
            throw new InterruptedException();
        });
        p.acquire();

        assertThatThrownBy(p::acquire).isInstanceOf(BatchPacer.InterruptedWaitException.class);
        assertThat(Thread.interrupted()).as("the flag is set again, and cleared here for the next test").isTrue();
    }

    @Test
    void aRateMustBePositiveAndTheStreamLimitOnlyLowersIt() {
        assertThatThrownBy(() -> new BatchPacer(0, () -> 0L, d -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchPacer(-1, () -> 0L, d -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchPacer(Double.NaN, () -> 0L, d -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BatchPacer.effectiveRate(5, null)).isEqualTo(5);
        assertThat(BatchPacer.effectiveRate(5, 2.0)).isEqualTo(2.0);
        assertThat(BatchPacer.effectiveRate(5, 20.0)).isEqualTo(5);
        assertThat(BatchPacer.effectiveRate(5, 0.0)).as("a zero limit means none").isEqualTo(5);
    }

    // ---- BackfillProperties ----

    @Test
    void theDefaultsAreBoundedAndConservative() {
        BackfillProperties d = BackfillProperties.defaults();

        assertThat(d.maxWindow()).isEqualTo(Duration.ofDays(90));
        assertThat(d.sliceTime()).isEqualTo(Duration.ofHours(1));
        assertThat(d.sliceObject()).isEqualTo(Duration.ofDays(1));
        assertThat(d.maxBatchesPerSecond()).isEqualTo(5.0);
        assertThat(d.maxConcurrentJobs()).isEqualTo(2);
        assertThat(d.replayFromSource()).isTrue();
    }

    @Test
    void invalidSettingsAreRefusedAtStartup() {
        BackfillProperties d = BackfillProperties.defaults();
        assertThatThrownBy(() -> new BackfillProperties(true, Duration.ZERO, d.sliceTime(), d.sliceObject(), 5, 2,
                d.pollInterval(), 5, d.retryBackoff(), true, d.replayTimePad(), d.replayFallbackLookback(),
                d.replayMaxWindow())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackfillProperties(true, d.maxWindow(), d.sliceTime(), d.sliceObject(), 0, 2,
                d.pollInterval(), 5, d.retryBackoff(), true, d.replayTimePad(), d.replayFallbackLookback(),
                d.replayMaxWindow())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackfillProperties(true, d.maxWindow(), d.sliceTime(), d.sliceObject(), 5, 0,
                d.pollInterval(), 5, d.retryBackoff(), true, d.replayTimePad(), d.replayFallbackLookback(),
                d.replayMaxWindow())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackfillProperties(true, d.maxWindow(), d.sliceTime(), d.sliceObject(), 5, 2,
                d.pollInterval(), 0, d.retryBackoff(), true, d.replayTimePad(), d.replayFallbackLookback(),
                d.replayMaxWindow())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackfillProperties(true, d.maxWindow(), d.sliceTime(), d.sliceObject(), 5, 2,
                d.pollInterval(), 5, Duration.ofSeconds(-1), true, d.replayTimePad(), d.replayFallbackLookback(),
                d.replayMaxWindow())).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- BackfillProgress ----

    @Test
    void progressSurvivesItsJsonFormIncludingPartitionsAndTransportState() {
        BackfillProgress p = BackfillProgress.EMPTY
                .withPartition(new BackfillProgress.Partition("east", T, T.plusSeconds(5), Map.of("pageToken", "abc"),
                        false))
                .withPartition(new BackfillProgress.Partition("", null, null, Map.of(), true))
                .afterBatch(10, 2, 3, T.plusSeconds(9))
                .afterFailure("IllegalStateException", T.plusSeconds(60), T.plusSeconds(10));

        BackfillProgress back = BackfillProgress.fromMap(p.toMap());

        assertThat(back).isEqualTo(p);
        assertThat(back.partition("east").orElseThrow().cursor())
                .isEqualTo(new CursorPosition(T, T.plusSeconds(5), Map.of("pageToken", "abc")));
        assertThat(back.waiting(T.plusSeconds(59))).isTrue();
        assertThat(back.waiting(T.plusSeconds(60))).isFalse();
    }

    @Test
    void aStoredRowFromAnOlderOrDamagedBuildStillLoads() {
        assertThat(BackfillProgress.fromMap(null)).isEqualTo(BackfillProgress.EMPTY);
        assertThat(BackfillProgress.fromMap(Map.of())).isEqualTo(BackfillProgress.EMPTY);
        Map<String, Object> odd = new HashMap<>();
        odd.put("partitions", List.of("not a map", Map.of("key", "a", "low", "not a time", "done", "yes")));
        odd.put("batches", "seven");
        odd.put("records", 12);

        BackfillProgress p = BackfillProgress.fromMap(odd);

        assertThat(p.batches()).isZero();
        assertThat(p.records()).isEqualTo(12);
        assertThat(p.partitions()).hasSize(1);
        assertThat(p.partitions().get(0).low()).isNull();
        assertThat(p.partitions().get(0).done()).isFalse();
    }

    @Test
    void aBatchResetsTheFailureStreakAndAFailureCountsOne() {
        BackfillProgress failed = BackfillProgress.EMPTY.afterFailure("X", T, T).afterFailure("X", T, T);
        assertThat(failed.attempts()).isEqualTo(2);

        BackfillProgress ok = failed.afterBatch(5, 0, 0, T);

        assertThat(ok.attempts()).isZero();
        assertThat(ok.lastError()).isEmpty();
        assertThat(ok.nextAttemptAt()).isNull();
        assertThat(ok.records()).isEqualTo(5);
        assertThat(ok.batches()).isEqualTo(1);
    }

    // ---- BackfillJob ----

    private static BackfillJob job(BackfillStatus status, BackfillProgress progress) {
        return new BackfillJob(UUID.randomUUID(), "b", "s", T, T.plus(Duration.ofHours(10)), status, progress, "a", T,
                null, null, null);
    }

    @Test
    void theFractionIsTheMeanTravelOfThePartitions() {
        BackfillProgress p = BackfillProgress.EMPTY
                .withPartition(new BackfillProgress.Partition("a", T.plus(Duration.ofHours(5)), null, Map.of(), false))
                .withPartition(new BackfillProgress.Partition("b", T, null, Map.of(), false));

        assertThat(job(BackfillStatus.RUNNING, p).fractionComplete()).isEqualTo(0.25);
        assertThat(job(BackfillStatus.RUNNING, BackfillProgress.EMPTY).fractionComplete()).isZero();
        assertThat(job(BackfillStatus.SUCCEEDED, BackfillProgress.EMPTY).fractionComplete()).isEqualTo(1.0);
        BackfillProgress done = BackfillProgress.EMPTY.withPartition(new BackfillProgress.Partition("a",
                T.plus(Duration.ofHours(10)), null, Map.of(), true));
        assertThat(job(BackfillStatus.RUNNING, done).fractionComplete()).isEqualTo(1.0);
    }

    @Test
    void onlyPendingAndRunningOccupyABinding() {
        assertThat(BackfillStatus.PENDING.active()).isTrue();
        assertThat(BackfillStatus.RUNNING.active()).isTrue();
        assertThat(BackfillStatus.SUCCEEDED.active()).isFalse();
        assertThat(BackfillStatus.FAILED.active()).isFalse();
        assertThat(BackfillStatus.CANCELLED.active()).isFalse();
    }
}
