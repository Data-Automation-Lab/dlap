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

package com.dlap.logingest.providers.gcp.logging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sliding-window limit per key (a GCP project): no window of length {@code period} ever holds more
 * than {@code permits} granted requests. Cloud Logging allows 60 {@code entries.list} requests per
 * minute per project and the quota cannot be raised (GCP report section 1), so every stream of one
 * project shares one window. A token bucket would let a burst of 60 follow a minute of steady use and
 * put up to 119 requests in one minute; the window cannot. A stream's
 * {@code rate-limit.requests-per-second} can only lower the rate, as a minimum gap between requests.
 *
 * <p>Time comes from the injected {@link Clock}, waiting from the injected {@link Sleeper}, so tests
 * run without sleeping. The limiter is thread safe.
 */
final class RequestRateLimiter {

    /** Waits. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Sleeps.
         *
         * @param duration how long
         * @throws InterruptedException when interrupted
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    /** The Cloud Logging read quota: 60 requests per minute per project. */
    static final int LOGGING_REQUESTS_PER_MINUTE = 60;

    private final int permits;
    private final Duration period;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    /**
     * Creates a limiter.
     *
     * @param permits requests per period, at least 1
     * @param period  the period
     * @param clock   the clock
     * @param sleeper how to wait
     */
    RequestRateLimiter(int permits, Duration period, Clock clock, Sleeper sleeper) {
        if (permits < 1 || period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("a rate limit needs at least one permit per positive period");
        }
        this.permits = permits;
        this.period = period;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * The Cloud Logging limiter.
     *
     * @param clock the clock
     * @return 60 requests per minute per key
     */
    static RequestRateLimiter forLogging(Clock clock) {
        return new RequestRateLimiter(LOGGING_REQUESTS_PER_MINUTE, Duration.ofMinutes(1), clock, Thread::sleep);
    }

    /**
     * Takes one permit for a key, waiting until one is free.
     *
     * @param key                  the project
     * @param maxRequestsPerSecond a lower stream rate, or {@code null}
     * @return how long the call waited
     * @throws InterruptedException when interrupted while waiting
     */
    Duration acquire(String key, Double maxRequestsPerSecond) throws InterruptedException {
        Deque<Instant> granted = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        Duration minGap = minimumGap(maxRequestsPerSecond);
        Duration waited = Duration.ZERO;
        while (true) {
            Duration wait;
            synchronized (granted) {
                Instant now = clock.instant();
                while (!granted.isEmpty() && !granted.peekFirst().plus(period).isAfter(now)) {
                    granted.pollFirst();
                }
                Instant last = granted.peekLast();
                Duration untilGap = last == null || !now.isBefore(last.plus(minGap)) ? Duration.ZERO
                        : Duration.between(now, last.plus(minGap));
                Duration untilSlot = granted.size() < permits ? Duration.ZERO
                        : Duration.between(now, granted.peekFirst().plus(period));
                wait = untilSlot.compareTo(untilGap) >= 0 ? untilSlot : untilGap;
                if (wait.isZero() || wait.isNegative()) {
                    granted.addLast(now);
                    return waited;
                }
            }
            sleeper.sleep(wait);
            waited = waited.plus(wait);
        }
    }

    private static Duration minimumGap(Double maxRequestsPerSecond) {
        if (maxRequestsPerSecond == null || maxRequestsPerSecond <= 0 || maxRequestsPerSecond.isInfinite()) {
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) Math.ceil(1_000_000_000.0 / maxRequestsPerSecond));
    }
}
