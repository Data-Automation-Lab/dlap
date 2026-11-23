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

package com.dlap.logingest.source.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.repository.SourceWatermarkRepository;
import com.dlap.logingest.retention.LogEventRetentionSweeper;
import com.dlap.logingest.service.ILogIngestService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Q-550 acceptance for the legacy GCP poll: {@link GcpLogSource#poll()} runs under ShedLock,
 * one lock for the source, so two replicas never read Cloud Logging for the same tick. Two Spring
 * contexts stand in for two da-logingest replicas on one PostgreSQL (Testcontainers; requires
 * Docker) with the shipped Flyway migrations. Each holds the production lock configuration
 * ({@code LogEventRetentionSweeper.ShedLockConfiguration}) and a real source proxied by the ShedLock
 * advisor; the entry fetcher is a fake that counts {@code entries.list} calls, so "polled once"
 * is a measured number.
 */
class GcpLogSourceLockTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private final List<AnnotationConfigApplicationContext> open = new ArrayList<>();

    @AfterEach
    void stopReplicas() {
        open.forEach(AnnotationConfigApplicationContext::close);
        open.clear();
    }

    /** A fake entry fetcher shared by the replicas of one test; blocks on {@code gate} when set. */
    private static final class Probe {
        final AtomicInteger lookups = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch gate;

        GcpLogSource.EntryFetcher fetcher() {
            return (projectId, filter, pageSize, sink) -> {
                lookups.incrementAndGet();
                entered.countDown();
                CountDownLatch g = gate;
                if (g != null) {
                    g.await(20, TimeUnit.SECONDS);
                }
            };
        }
    }

    private AnnotationConfigApplicationContext replica(DataSource ds, Probe probe, String projects) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dlap.logingest.gcp.enabled", "true",
                "dlap.logingest.gcp.projects", projects)));
        ctx.getEnvironment().setActiveProfiles("gcp");
        ctx.registerBean(DataSource.class, () -> ds);
        ctx.register(LogEventRetentionSweeper.ShedLockConfiguration.class);
        SourceWatermarkRepository watermarks = mock(SourceWatermarkRepository.class);
        when(watermarks.findByProviderAndAccountId(any(), any())).thenReturn(Optional.empty());
        ctx.registerBean(GcpLogSource.class, () -> new GcpLogSource(
                mock(ILogIngestService.class), watermarks, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), probe.fetcher()));
        ctx.refresh();
        open.add(ctx);
        return ctx;
    }

    private static GcpLogSource sourceOf(AnnotationConfigApplicationContext ctx) {
        return ctx.getBean(GcpLogSource.class);
    }

    @Test
    void theScheduledPollHoldsAUniquelyNamedLongEnoughLock() throws Exception {
        Method poll = GcpLogSource.class.getMethod("poll");
        SchedulerLock lock = poll.getAnnotation(SchedulerLock.class);

        assertThat(poll.getAnnotation(Scheduled.class)).as("poll is scheduled").isNotNull();
        assertThat(lock).as("poll holds a @SchedulerLock (Q-550)").isNotNull();
        assertThat(lock.name()).isEqualTo(GcpLogSource.LOCK_NAME).isEqualTo("logingest-legacy-gcp-poll");
        assertThat(lock.name().length()).as("the shedlock name column holds 64").isLessThan(64);
        assertThat(Duration.parse(lock.lockAtMostFor())).isGreaterThanOrEqualTo(Duration.ofMinutes(10));
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }

    @Test
    void aSecondReplicaSkipsTheTickWhileTheFirstIsPolling() throws Exception {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        Probe probe = new Probe();
        probe.gate = new CountDownLatch(1);
        GcpLogSource first = sourceOf(replica(ds, probe, "proj-a,proj-b"));
        GcpLogSource second = sourceOf(replica(ds, probe, "proj-a,proj-b"));
        assertThat(AopUtils.isAopProxy(first)).as("the ShedLock advisor wraps the source").isTrue();

        CompletableFuture<Void> running = CompletableFuture.runAsync(first::poll);
        assertThat(probe.entered.await(20, TimeUnit.SECONDS)).isTrue();
        int whileFirstHoldsTheLock = probe.lookups.get();
        second.poll();

        assertThat(probe.lookups.get()).as("the second replica made no Cloud Logging call")
                .isEqualTo(whileFirstHoldsTheLock);
        probe.gate.countDown();
        running.get(20, TimeUnit.SECONDS);
        assertThat(new JdbcTemplate(ds).queryForObject(
                "SELECT count(*) FROM dlap_logingest.shedlock WHERE name = ?", Integer.class,
                GcpLogSource.LOCK_NAME)).as("one lock row for the source").isEqualTo(1);
    }

    @Test
    void aReplicaThatFiresRightAfterTheFirstFinishedStillSkipsTheTick() {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        Probe probe = new Probe();
        GcpLogSource first = sourceOf(replica(ds, probe, "proj-a"));
        GcpLogSource second = sourceOf(replica(ds, probe, "proj-a"));

        first.poll();
        int afterFirst = probe.lookups.get();
        second.poll();

        assertThat(afterFirst).as("the first replica polled").isPositive();
        assertThat(probe.lookups.get()).as("lockAtLeastFor keeps the tick to one replica").isEqualTo(afterFirst);
        Double heldSeconds = new JdbcTemplate(ds).queryForObject(
                "SELECT extract(epoch FROM (lock_until - locked_at)) FROM dlap_logingest.shedlock WHERE name = ?",
                Double.class, GcpLogSource.LOCK_NAME);
        assertThat(heldSeconds).as("the row is held for at least the minimum").isGreaterThanOrEqualTo(14.0);
    }

    @Test
    void anExpiredLockLetsTheNextTickRun() {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        Probe probe = new Probe();
        GcpLogSource source = sourceOf(replica(ds, probe, "proj-a"));

        source.poll();
        int afterFirst = probe.lookups.get();
        new JdbcTemplate(ds).update("UPDATE dlap_logingest.shedlock SET lock_until = now() - interval '1 second'");
        source.poll();

        assertThat(probe.lookups.get()).as("an expired lock lets the next tick run").isGreaterThan(afterFirst);
    }
}
