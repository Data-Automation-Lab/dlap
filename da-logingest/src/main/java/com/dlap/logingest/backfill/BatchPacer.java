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
import java.util.function.LongSupplier;

/**
 * Spaces the batches of one backfill job: at most {@code perSecond} calls to {@link #acquire()} per
 * second, so a history read never hammers a provider API or a bucket (design B6 "rate-limited").
 * The first call returns at once; each later call waits until one interval has passed since the
 * previous grant. One pacer belongs to one job run and one thread.
 *
 * <p>Time comes from a monotonic nanosecond source and the wait from a {@link Sleeper}, so a test
 * drives both without sleeping.
 */
public final class BatchPacer {

    /** Waits for a duration. */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Waits.
         *
         * @param duration how long
         * @throws InterruptedException when the thread is interrupted
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    /** The wait was interrupted, which means the service is shutting down; the job resumes later. */
    public static final class InterruptedWaitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InterruptedWaitException(InterruptedException cause) {
            super("interrupted while pacing a backfill", cause);
        }
    }

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private boolean started;
    private long nextGrant;

    /**
     * Creates a pacer.
     *
     * @param perSecond the most grants per second; positive
     * @param nanoTime  a monotonic clock in nanoseconds
     * @param sleeper   how to wait
     * @throws IllegalArgumentException when the rate is not a positive finite number
     */
    public BatchPacer(double perSecond, LongSupplier nanoTime, Sleeper sleeper) {
        if (!(perSecond > 0) || Double.isInfinite(perSecond)) {
            throw new IllegalArgumentException("a rate is a positive number of batches per second");
        }
        this.intervalNanos = Math.max(1L, (long) (1_000_000_000d / perSecond));
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    /**
     * A pacer on the system clock that really sleeps.
     *
     * @param perSecond the most grants per second
     * @return the pacer
     */
    public static BatchPacer system(double perSecond) {
        return new BatchPacer(perSecond, System::nanoTime, d -> Thread.sleep(d.toMillis(), d.toNanosPart() % 1_000_000));
    }

    /**
     * The gap between two grants.
     *
     * @return the interval
     */
    public Duration interval() {
        return Duration.ofNanos(intervalNanos);
    }

    /**
     * Waits until the next batch may be read.
     *
     * @throws InterruptedWaitException when the wait is interrupted; the thread's interrupt flag is set again
     */
    public void acquire() {
        long now = nanoTime.getAsLong();
        if (!started) {
            started = true;
            nextGrant = now + intervalNanos;
            return;
        }
        long wait = nextGrant - now;
        if (wait > 0) {
            try {
                sleeper.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedWaitException(e);
            }
            nextGrant += intervalNanos;
        } else {
            nextGrant = now + intervalNanos;
        }
    }

    /**
     * The rate a job runs at: the configured ceiling, lowered by the stream's own request limit.
     *
     * @param ceiling       the configured most batches per second
     * @param streamLimit   the stream's {@code rate-limit}, or {@code null}
     * @return the lower of the two positive values
     */
    public static double effectiveRate(double ceiling, Double streamLimit) {
        return streamLimit != null && streamLimit > 0 ? Math.min(ceiling, streamLimit) : ceiling;
    }
}
