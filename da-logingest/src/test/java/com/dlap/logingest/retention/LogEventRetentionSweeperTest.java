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

package com.dlap.logingest.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.logingest.repository.LogEventPartitionRepository;
import com.dlap.logingest.repository.LogEventPartitionRepository.MonthlyPartition;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The sweeper prunes by whole month partitions of {@code ingest_time} (ADR-0060): a month is
 * dropped only when its whole range is older than the cutoff, default-partition stragglers are
 * deleted by the same cutoff, upcoming months are created every run, it is scheduled as a ShedLock
 * singleton, and a misconfiguration cannot turn it into "delete everything".
 */
class LogEventRetentionSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:30:00Z");

    private final LogEventPartitionRepository partitions = mock(LogEventPartitionRepository.class);

    private LogEventRetentionSweeper sweeper(int retentionDays, boolean enabled) {
        LogEventRetentionSweeper s = new LogEventRetentionSweeper(partitions, Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(s, "retentionDays", retentionDays);
        ReflectionTestUtils.setField(s, "retentionEnabled", enabled);
        ReflectionTestUtils.setField(s, "partitionsAhead", 2);
        return s;
    }

    private static MonthlyPartition month(int year, int month) {
        YearMonth m = YearMonth.of(year, month);
        return new MonthlyPartition(LogEventPartitionRepository.partitionName(m), m);
    }

    @Test
    void dropsOnlyMonthsWhollyOlderThanTheCutoff() {
        // Cutoff 90 days before 2026-10-19 is 2026-07-21: June ends before it, July does not.
        when(partitions.monthlyPartitions()).thenReturn(List.of(month(2026, 5), month(2026, 6), month(2026, 7),
                month(2026, 10)));
        when(partitions.deleteDefaultRowsBefore(any())).thenReturn(3);

        LogEventRetentionSweeper.Result r = sweeper(90, true).prune();

        assertThat(r.cutoff()).isEqualTo(NOW.minus(Duration.ofDays(90)));
        assertThat(r.partitionsDropped()).isEqualTo(2);
        assertThat(r.defaultRowsDeleted()).isEqualTo(3);
        verify(partitions).drop(month(2026, 5));
        verify(partitions).drop(month(2026, 6));
        verify(partitions, never()).drop(month(2026, 7));
        verify(partitions, never()).drop(month(2026, 10));
        verify(partitions).deleteDefaultRowsBefore(NOW.minus(Duration.ofDays(90)));
    }

    @Test
    void createsThisMonthAndTheNextTwoEveryRun() {
        when(partitions.ensureMonthlyPartition(any())).thenReturn(true);

        LogEventRetentionSweeper.Result r = sweeper(90, true).prune();

        assertThat(r.partitionsCreated()).isEqualTo(3);
        verify(partitions).ensureMonthlyPartition(YearMonth.of(2026, 10));
        verify(partitions).ensureMonthlyPartition(YearMonth.of(2026, 11));
        verify(partitions).ensureMonthlyPartition(YearMonth.of(2026, 12));
    }

    @Test
    void scheduledEntryPointInvokesThePrune() {
        sweeper(30, true).scheduledPrune();

        verify(partitions).monthlyPartitions();
        verify(partitions).deleteDefaultRowsBefore(any());
    }

    @Test
    void schedulerFailureIsSwallowedSoIngestKeepsRunning() {
        when(partitions.monthlyPartitions()).thenThrow(new IllegalStateException("db down"));

        // Must not propagate out of the scheduled method.
        sweeper(30, true).scheduledPrune();

        verify(partitions).monthlyPartitions();
    }

    @Test
    void startupPartitionFailureIsSwallowed() {
        when(partitions.ensureMonthlyPartition(any())).thenThrow(new IllegalStateException("db down"));

        sweeper(30, true).ensurePartitionsAtStartup();

        verify(partitions).ensureMonthlyPartition(YearMonth.of(2026, 10));
    }

    @Test
    void nonPositiveRetentionDisablesPruningInsteadOfDeletingEverything() {
        assertThat(sweeper(0, true).prune().partitionsDropped()).isZero();
        assertThat(sweeper(-1, true).prune().cutoff()).isNull();

        verify(partitions, never()).drop(any());
        verify(partitions, never()).deleteDefaultRowsBefore(any());
    }

    @Test
    void disabledFlagSkipsPruningButStillCreatesPartitions() {
        when(partitions.ensureMonthlyPartition(any())).thenReturn(true);

        LogEventRetentionSweeper.Result r = sweeper(90, false).prune();

        assertThat(r.partitionsCreated()).isEqualTo(3);
        verify(partitions, never()).monthlyPartitions();
        verify(partitions, never()).deleteDefaultRowsBefore(any());
    }

    @Test
    void theSweepIsScheduledAsAShedLockSingleton() throws Exception {
        Method m = LogEventRetentionSweeper.class.getMethod("scheduledPrune");

        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled on scheduledPrune").isNotNull();
        assertThat(scheduled.cron()).contains("dlap.logingest.retention-cron");
        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("@SchedulerLock on scheduledPrune").isNotNull();
        assertThat(lock.name()).isEqualTo(LogEventRetentionSweeper.LOCK_NAME);
        assertThat(Duration.parse(lock.lockAtMostFor())).isGreaterThanOrEqualTo(Duration.ofHours(1));
    }
}
