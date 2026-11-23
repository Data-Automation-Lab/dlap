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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.backfill.BackfillTestKit.InMemoryJobs;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

/** The scheduler runs runnable jobs on a bounded pool and stops them cleanly. */
class BackfillSchedulerTest {

    private final InMemoryJobs jobs = new InMemoryJobs();
    private final BackfillRunner runner = mock(BackfillRunner.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    @SuppressWarnings("unchecked")
    private final ScheduledFuture<Object> future = mock(ScheduledFuture.class);
    private BackfillScheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    private BackfillScheduler start(BackfillProperties props) {
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        scheduler = new BackfillScheduler(jobs, runner, props, taskScheduler);
        scheduler.start();
        return scheduler;
    }

    private UUID job(String binding) {
        UUID id = UUID.randomUUID();
        jobs.insert(id, binding, STREAM, T0, T0.plusSeconds(3600), "a", T0.plusSeconds(jobs.rows.size()));
        return id;
    }

    private static BackfillProperties props(boolean enabled, int concurrent) {
        BackfillProperties d = BackfillProperties.defaults();
        return new BackfillProperties(enabled, d.maxWindow(), d.sliceTime(), d.sliceObject(), 5, concurrent,
                Duration.ofSeconds(7), 5, d.retryBackoff(), true, d.replayTimePad(), d.replayFallbackLookback(),
                d.replayMaxWindow());
    }

    @Test
    void itTicksAtThePollIntervalAndRunsEachRunnableJobOnce() throws Exception {
        UUID a = job(BINDING);
        Map<UUID, CountDownLatch> started = new ConcurrentHashMap<>();
        started.put(a, new CountDownLatch(1));
        when(runner.run(any(), any())).thenAnswer(inv -> {
            started.get(inv.<UUID>getArgument(0)).countDown();
            return BackfillRunner.RunResult.COMPLETED;
        });

        start(props(true, 2));
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(7)));
        int submitted = scheduler.tick();

        assertThat(submitted).isEqualTo(1);
        assertThat(started.get(a).await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aReplicaRunsNoMoreThanTheConfiguredNumberOfJobsAtOnce() throws Exception {
        job("b1");
        job("b2");
        job("b3");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoRunning = new CountDownLatch(2);
        when(runner.run(any(), any())).thenAnswer(inv -> {
            twoRunning.countDown();
            release.await(10, TimeUnit.SECONDS);
            return BackfillRunner.RunResult.COMPLETED;
        });
        start(props(true, 2));

        assertThat(scheduler.tick()).isEqualTo(2);
        assertThat(twoRunning.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.running()).isEqualTo(2);
        assertThat(scheduler.tick()).as("a full pool takes no more, and nothing runs twice").isZero();

        release.countDown();
        for (int i = 0; i < 100 && scheduler.running() > 0; i++) {
            Thread.sleep(20);
        }
        assertThat(scheduler.running()).isZero();
    }

    @Test
    void aRunnerThatThrowsFreesItsSlotAndTheTickKeepsGoing() throws Exception {
        UUID a = job(BINDING);
        CountDownLatch ran = new CountDownLatch(1);
        when(runner.run(any(), any())).thenAnswer(inv -> {
            ran.countDown();
            throw new IllegalStateException("boom");
        });
        start(props(true, 1));

        scheduler.tick();

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100 && scheduler.running() > 0; i++) {
            Thread.sleep(20);
        }
        assertThat(scheduler.running()).isZero();
        assertThat(jobs.rows).containsKey(a);
    }

    @Test
    void shuttingDownRaisesTheStopFlagTheRunnerPolls() throws Exception {
        job(BINDING);
        ArgumentCaptor<BooleanSupplier> stop = ArgumentCaptor.forClass(BooleanSupplier.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(runner.run(any(), stop.capture())).thenAnswer(inv -> {
            started.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return BackfillRunner.RunResult.STOPPED;
        });
        start(props(true, 1));
        scheduler.tick();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stop.getValue().getAsBoolean()).isFalse();

        scheduler.destroy();

        assertThat(stop.getValue().getAsBoolean()).isTrue();
        verify(future).cancel(false);
        assertThat(scheduler.tick()).as("a stopped scheduler submits nothing").isZero();
        finish.countDown();
    }

    @Test
    void whenDisabledNothingIsScheduledAndJobsStayPending() {
        UUID a = job(BINDING);
        scheduler = new BackfillScheduler(jobs, runner, props(false, 2), taskScheduler);

        scheduler.start();

        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        assertThat(scheduler.tick()).isZero();
        assertThat(jobs.rows.get(a).status()).isEqualTo(BackfillStatus.PENDING);
    }
}
