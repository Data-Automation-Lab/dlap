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

import static com.dlap.logingest.backfill.BackfillTestKit.STREAM;
import static com.dlap.logingest.backfill.BackfillTestKit.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.backfill.BackfillTestKit.InMemoryJobs;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Q-846 acceptance: {@code dlap_logingest_backfill_heartbeat_age_seconds} is the age of the oldest
 * {@code RUNNING} job by binding, computed from the stored {@code progress.heartbeat} on the injected
 * clock. The clock is a fixed, hand-moved clock, so every number asserted is exact.
 */
class BackfillHeartbeatGaugeTest {

    private static final Instant NOW = T0.plus(Duration.ofDays(30));
    private static final Instant FROM = T0;
    private static final Instant TO = T0.plus(Duration.ofHours(6));

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryJobs jobs = new InMemoryJobs();

    private BackfillMetrics bind(java.util.function.Function<String, String> label) {
        BackfillMetrics metrics = new BackfillMetrics(registry, label);
        metrics.bindJobGauges(jobs, clock);
        return metrics;
    }

    private UUID runningJob(String binding, Instant heartbeat) {
        UUID id = UUID.randomUUID();
        jobs.insert(id, binding, STREAM, FROM, TO, "ops-admin", NOW.minus(Duration.ofDays(1)));
        jobs.markRunning(id, NOW.minus(Duration.ofHours(12)));
        if (heartbeat != null) {
            jobs.saveProgress(id, BackfillProgress.EMPTY.withHeartbeat(heartbeat));
        }
        return id;
    }

    /** Reads a status gauge, which is what a scrape does first and what registers the heartbeat gauges. */
    private void scrape() {
        registry.get(BackfillMetrics.JOBS).tag("status", "RUNNING").gauge().value();
    }

    private Gauge heartbeat(String binding) {
        return registry.find(BackfillMetrics.HEARTBEAT_AGE).tag("binding", binding).gauge();
    }

    @Test
    void theGaugeIsTheAgeOfTheStoredHeartbeatPerBindingOnTheInjectedClock() {
        bind(b -> b);
        runningJob("aws-main", NOW.minusSeconds(120));
        runningJob("gcp-main", NOW.minusSeconds(1000));
        scrape();

        assertThat(heartbeat("aws-main")).isNotNull();
        assertThat(heartbeat("aws-main").value()).isEqualTo(120.0);
        assertThat(heartbeat("gcp-main").value()).isEqualTo(1000.0);
        assertThat(heartbeat("aws-main").getId().getBaseUnit()).isEqualTo("seconds");

        // The clock moves, the cached ages do not until the ttl passes; then they follow the clock.
        clock.advance(Duration.ofSeconds(BackfillMetrics.GAUGE_TTL_SECONDS - 1));
        assertThat(heartbeat("gcp-main").value()).as("cached read").isEqualTo(1000.0);
        clock.advance(Duration.ofSeconds(2));
        assertThat(heartbeat("gcp-main").value()).isEqualTo(1000.0 + BackfillMetrics.GAUGE_TTL_SECONDS + 1);
    }

    @Test
    void aNewHeartbeatLowersTheAgeAfterTheTtl() {
        bind(b -> b);
        UUID id = runningJob("aws-main", NOW.minusSeconds(1000));
        scrape();
        assertThat(heartbeat("aws-main").value()).isEqualTo(1000.0);

        clock.advance(Duration.ofSeconds(BackfillMetrics.GAUGE_TTL_SECONDS));
        jobs.saveProgress(id, BackfillProgress.EMPTY.withHeartbeat(clock.instant().minusSeconds(3)));

        assertThat(heartbeat("aws-main").value()).isEqualTo(3.0);
    }

    @Test
    void theOldestRunningJobOfASharedLabelWins() {
        bind(b -> "_all");
        runningJob("b-1", NOW.minusSeconds(60));
        runningJob("b-2", NOW.minusSeconds(2000));
        runningJob("b-3", NOW.minusSeconds(300));
        scrape();

        assertThat(registry.find(BackfillMetrics.HEARTBEAT_AGE).gauges()).hasSize(1);
        assertThat(heartbeat("_all").value()).isEqualTo(2000.0);
    }

    @Test
    void aJobWithoutAHeartbeatCountsFromItsStartAndAPendingJobNotAtAll() {
        bind(b -> b);
        runningJob("aws-main", null);
        UUID pending = UUID.randomUUID();
        jobs.insert(pending, "gcp-main", STREAM, FROM, TO, "ops-admin", NOW.minus(Duration.ofDays(2)));
        scrape();

        assertThat(heartbeat("aws-main").value()).as("started 12 h ago, no heartbeat yet").isEqualTo(12 * 3600.0);
        assertThat(heartbeat("gcp-main")).as("a PENDING job has no heartbeat to age").isNull();
    }

    @Test
    void aBindingWithoutARunningJobReadsZeroAndAFutureHeartbeatNeverGoesNegative() {
        bind(b -> b);
        UUID id = runningJob("aws-main", NOW.minusSeconds(5000));
        scrape();
        assertThat(heartbeat("aws-main").value()).isEqualTo(5000.0);

        jobs.finish(id, BackfillStatus.SUCCEEDED, NOW, "", BackfillProgress.EMPTY);
        clock.advance(Duration.ofSeconds(BackfillMetrics.GAUGE_TTL_SECONDS));
        assertThat(heartbeat("aws-main").value()).as("the job ended, nothing is stuck").isZero();

        runningJob("aws-main", clock.instant().plusSeconds(30));
        clock.advance(Duration.ofSeconds(BackfillMetrics.GAUGE_TTL_SECONDS));
        assertThat(heartbeat("aws-main").value()).as("clock skew between replicas").isZero();
    }

    @Test
    void aTableThatCannotBeReadGivesNaNInsteadOfThrowing() {
        BackfillJobs failing = (BackfillJobs) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {BackfillJobs.class}, (proxy, method, args) -> {
                    if (method.getName().equals("list") && failing(args)) {
                        throw new IllegalStateException("database down");
                    }
                    return method.invoke(jobs, args);
                });
        BackfillMetrics metrics = new BackfillMetrics(registry, b -> b);
        metrics.bindJobGauges(failing, clock);
        runningJob("aws-main", NOW.minusSeconds(100));
        scrape();
        assertThat(heartbeat("aws-main").value()).isEqualTo(100.0);

        broken = true;
        clock.advance(Duration.ofSeconds(BackfillMetrics.GAUGE_TTL_SECONDS));
        assertThat(heartbeat("aws-main").value()).isNaN();

        broken = false;
        assertThat(heartbeat("aws-main").value()).as("the failed read is retried at once").isEqualTo(110.0);
    }

    private volatile boolean broken;

    private boolean failing(Object[] args) {
        return broken && args[1] == BackfillStatus.RUNNING;
    }
}
