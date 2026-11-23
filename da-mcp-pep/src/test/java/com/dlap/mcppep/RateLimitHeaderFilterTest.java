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

package com.dlap.mcppep;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Verifies the fixed-window counting, remaining floor, reset boundary, and
 * per-client key isolation of {@link RateLimitHeaderFilter}, using a mutable
 * clock to drive window roll-over deterministically (no sleeps).
 */
class RateLimitHeaderFilterTest {

    /** A clock whose "now" is advanced by the test. */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private RateLimitHeaderFilter filter(long limit, Clock clock) {
        return new RateLimitHeaderFilter(limit, "X-Client-Id", clock);
    }

    @Test
    void remainingDecrementsWithinWindow() {
        MutableClock clock = new MutableClock(0L);
        RateLimitHeaderFilter f = filter(5L, clock);

        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(4L);
        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(3L);
        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(2L);
    }

    @Test
    void remainingFlooredAtZeroWhenExceeded() {
        MutableClock clock = new MutableClock(0L);
        RateLimitHeaderFilter f = filter(2L, clock);

        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(1L);
        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(0L);
        // Over the ceiling: still floored at 0, never negative (advisory only).
        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(0L);
    }

    @Test
    void windowRollsOverAndResets() {
        MutableClock clock = new MutableClock(0L);
        RateLimitHeaderFilter f = filter(3L, clock);

        f.recordAndSnapshot("c1"); // remaining 2
        f.recordAndSnapshot("c1"); // remaining 1
        RateLimitHeaderFilter.Snapshot beforeRoll = f.recordAndSnapshot("c1");
        assertThat(beforeRoll.remaining()).isEqualTo(0L);
        assertThat(beforeRoll.resetEpochSecond())
                .isEqualTo(RateLimitHeaderFilter.WINDOW_SECONDS);

        // Advance past the 60s window boundary: counter resets.
        clock.advance(RateLimitHeaderFilter.WINDOW_SECONDS * 1000L);
        RateLimitHeaderFilter.Snapshot afterRoll = f.recordAndSnapshot("c1");
        assertThat(afterRoll.remaining()).isEqualTo(2L);
        assertThat(afterRoll.resetEpochSecond())
                .isEqualTo(RateLimitHeaderFilter.WINDOW_SECONDS * 2L);
    }

    @Test
    void distinctClientsCountedIndependently() {
        MutableClock clock = new MutableClock(0L);
        RateLimitHeaderFilter f = filter(5L, clock);

        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(4L);
        assertThat(f.recordAndSnapshot("c1").remaining()).isEqualTo(3L);
        // A different client starts fresh.
        assertThat(f.recordAndSnapshot("c2").remaining()).isEqualTo(4L);
    }

    @Test
    void limitAndResetReflectedInSnapshot() {
        MutableClock clock = new MutableClock(0L);
        RateLimitHeaderFilter f = filter(600L, clock);

        RateLimitHeaderFilter.Snapshot snap = f.recordAndSnapshot("c1");
        assertThat(snap.limit()).isEqualTo(600L);
        assertThat(snap.resetEpochSecond()).isEqualTo(RateLimitHeaderFilter.WINDOW_SECONDS);
    }
}
