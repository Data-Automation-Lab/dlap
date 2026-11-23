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

package com.dlap.logingest.metrics;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * A hit ratio over a sliding time window, kept in 60 buckets so the memory is fixed however many
 * events arrive. Time comes from the injected {@link Clock} (plan rule R6), so a test moves it by
 * hand. Thread safe.
 */
public final class RollingRatio {

    /** Number of buckets the window is divided into. */
    static final int BUCKETS = 60;

    /**
     * A reading of the window.
     *
     * @param hits  events that counted as a hit
     * @param total events seen
     */
    public record Reading(long hits, long total) {

        /**
         * The ratio.
         *
         * @return hits divided by total, or NaN when nothing was seen in the window
         */
        public double value() {
            return total == 0 ? Double.NaN : (double) hits / total;
        }
    }

    private final Clock clock;
    private final long bucketSeconds;
    private final long[] hits = new long[BUCKETS];
    private final long[] total = new long[BUCKETS];
    private final long[] bucketId = new long[BUCKETS];

    /**
     * Creates a ratio.
     *
     * @param clock  the time source
     * @param window the window; at least one minute, divided into 60 buckets
     * @throws IllegalArgumentException when the window is under one minute
     */
    public RollingRatio(Clock clock, Duration window) {
        if (window.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("a rolling window is at least one minute");
        }
        this.clock = clock;
        this.bucketSeconds = Math.max(1, window.toSeconds() / BUCKETS);
        Arrays.fill(bucketId, -1L);
    }

    /**
     * Counts one event.
     *
     * @param hit whether it counts toward the numerator of the ratio
     */
    public synchronized void add(boolean hit) {
        long id = clock.instant().getEpochSecond() / bucketSeconds;
        int slot = (int) Math.floorMod(id, (long) BUCKETS);
        if (bucketId[slot] != id) {
            bucketId[slot] = id;
            hits[slot] = 0;
            total[slot] = 0;
        }
        total[slot]++;
        if (hit) {
            hits[slot]++;
        }
    }

    /**
     * Reads the window that ends now.
     *
     * @return hits and total of the last 60 buckets
     */
    public synchronized Reading read() {
        long now = clock.instant().getEpochSecond() / bucketSeconds;
        long h = 0;
        long t = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (bucketId[i] > now - BUCKETS && bucketId[i] <= now) {
                h += hits[i];
                t += total[i];
            }
        }
        return new Reading(h, t);
    }
}
