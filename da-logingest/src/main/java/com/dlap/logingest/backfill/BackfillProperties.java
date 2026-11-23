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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the backfill jobs and of the source re-read behind quarantine replay, under
 * {@code dlap.logingest.backfill}. The defaults live here, not in {@code application.properties}.
 *
 * @param enabled                the switch of the job scheduler; false leaves jobs PENDING
 * @param maxWindow              the longest {@code [from, to)} one job may read
 * @param sliceTime              the span of one fetch for a time-cursored transport
 * @param sliceObject            the span of one fetch for an object-cursored transport
 * @param maxBatchesPerSecond    the most batches (API pages or objects) one job reads per second; the
 *                               stream's own {@code rate-limit} lowers it further
 * @param maxConcurrentJobs      the most jobs one replica runs at once
 * @param pollInterval           how often the scheduler looks for a runnable job
 * @param maxAttempts            consecutive failed attempts after which a job is FAILED
 * @param retryBackoff           the wait after the first failed attempt; it doubles per attempt, up to
 *                               {@value #MAX_BACKOFF_MINUTES} minutes
 * @param replayFromSource       whether quarantine replay may re-read truncated or masked records from
 *                               their source (decision D26, Q-362)
 * @param replayTimePad          the span before and after a record's event time that a source re-read
 *                               covers
 * @param replayFallbackLookback how far before {@code quarantined_at} a re-read searches for a record
 *                               whose event time is unknown
 * @param replayMaxWindow        the longest merged window one source re-read fetch covers
 */
@ConfigurationProperties(prefix = "dlap.logingest.backfill")
public record BackfillProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("P90D") Duration maxWindow,
        @DefaultValue("PT1H") Duration sliceTime,
        @DefaultValue("P1D") Duration sliceObject,
        @DefaultValue("5") double maxBatchesPerSecond,
        @DefaultValue("2") int maxConcurrentJobs,
        @DefaultValue("PT15S") Duration pollInterval,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT30S") Duration retryBackoff,
        @DefaultValue("true") boolean replayFromSource,
        @DefaultValue("PT5M") Duration replayTimePad,
        @DefaultValue("PT24H") Duration replayFallbackLookback,
        @DefaultValue("PT6H") Duration replayMaxWindow) {

    /** Ceiling of the retry wait. */
    public static final long MAX_BACKOFF_MINUTES = 15;

    /**
     * Validates the settings.
     *
     * @param enabled                see the record description
     * @param maxWindow              see the record description
     * @param sliceTime              see the record description
     * @param sliceObject            see the record description
     * @param maxBatchesPerSecond    see the record description
     * @param maxConcurrentJobs      see the record description
     * @param pollInterval           see the record description
     * @param maxAttempts            see the record description
     * @param retryBackoff           see the record description
     * @param replayFromSource       see the record description
     * @param replayTimePad          see the record description
     * @param replayFallbackLookback see the record description
     * @param replayMaxWindow        see the record description
     */
    public BackfillProperties {
        if (notPositive(maxWindow) || notPositive(sliceTime) || notPositive(sliceObject) || notPositive(pollInterval)
                || notPositive(replayMaxWindow) || notPositive(replayFallbackLookback)) {
            throw new IllegalArgumentException("dlap.logingest.backfill: max-window, slice-time, slice-object, "
                    + "poll-interval, replay-max-window and replay-fallback-lookback are positive durations");
        }
        if (retryBackoff == null || retryBackoff.isNegative() || replayTimePad == null || replayTimePad.isNegative()) {
            throw new IllegalArgumentException(
                    "dlap.logingest.backfill: retry-backoff and replay-time-pad are not negative");
        }
        if (!(maxBatchesPerSecond > 0) || Double.isInfinite(maxBatchesPerSecond) || maxConcurrentJobs < 1
                || maxAttempts < 1) {
            throw new IllegalArgumentException("dlap.logingest.backfill: max-batches-per-second is a positive "
                    + "number, max-concurrent-jobs and max-attempts are at least 1");
        }
    }

    private static boolean notPositive(Duration d) {
        return d == null || d.isZero() || d.isNegative();
    }

    /**
     * The defaults, for code that builds the settings without Spring.
     *
     * @return the default settings
     */
    public static BackfillProperties defaults() {
        return new BackfillProperties(true, Duration.ofDays(90), Duration.ofHours(1), Duration.ofDays(1), 5, 2,
                Duration.ofSeconds(15), 5, Duration.ofSeconds(30), true, Duration.ofMinutes(5), Duration.ofHours(24),
                Duration.ofHours(6));
    }
}
