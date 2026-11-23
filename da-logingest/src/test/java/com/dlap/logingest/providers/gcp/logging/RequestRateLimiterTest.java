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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Cloud Logging request limiter: 60 requests per minute per project, measured on a test clock. */
class RequestRateLimiterTest {

    /** A clock the sleeper moves. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RequestRateLimiter limiter = new RequestRateLimiter(RequestRateLimiter.LOGGING_REQUESTS_PER_MINUTE,
            Duration.ofMinutes(1), clock, d -> {
                sleeps.add(d);
                clock.advance(d);
            });

    @Test
    void sixtyRequestsOfOneProjectPassAtOnceAndTheSixtyFirstWaitsUntilTheFirstLeavesTheMinute()
            throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire("proj-0d1a0001", null)).isZero();
        }
        Duration waited = limiter.acquire("proj-0d1a0001", null);
        assertThat(waited).isEqualTo(Duration.ofMinutes(1));
        assertThat(sleeps).containsExactly(Duration.ofMinutes(1));
    }

    @Test
    void noMinuteEverHoldsMoreThanSixtyRequests() throws InterruptedException {
        List<Instant> granted = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            if (i % 7 == 0) {
                clock.advance(Duration.ofMillis(1700));
            }
            limiter.acquire("proj-0d1a0001", null);
            granted.add(clock.instant());
        }
        int worst = 0;
        for (int i = 0; i < granted.size(); i++) {
            Instant start = granted.get(i);
            int inWindow = (int) granted.stream().filter(t -> !t.isBefore(start) && t.isBefore(start.plusSeconds(60))).count();
            worst = Math.max(worst, inWindow);
        }
        assertThat(worst).as("the most requests in any 60 second window").isEqualTo(60);
    }

    @Test
    void projectsHaveSeparateWindows() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            limiter.acquire("proj-0d1a0001", null);
        }
        assertThat(limiter.acquire("proj-0d1a0002", null)).isZero();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aStreamRateBelowTheQuotaSpacesRequests() throws InterruptedException {
        limiter.acquire("proj-0d1a0001", 0.5);
        Duration waited = limiter.acquire("proj-0d1a0001", 0.5);
        assertThat(waited).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theForLoggingLimiterUsesTheDocumentedQuota() {
        assertThat(RequestRateLimiter.LOGGING_REQUESTS_PER_MINUTE).isEqualTo(60);
        assertThat(RequestRateLimiter.forLogging(Clock.systemUTC())).isNotNull();
        assertThatThrownBy(() -> new RequestRateLimiter(0, Duration.ofMinutes(1), clock, d -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
