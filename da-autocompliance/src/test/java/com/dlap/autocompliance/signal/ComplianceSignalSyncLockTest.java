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

package com.dlap.autocompliance.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.autocompliance.signal.ComplianceSignal.ComplianceStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * HA-01 acceptance: the compliance signal sync runs under ShedLock as one cluster-wide singleton,
 * against a real PostgreSQL and the shipped Flyway migrations V1 to V5.
 *
 * <p>Two Spring contexts stand in for two da-autocompliance replicas that share one database. Each
 * holds the production lock configuration ({@code ComplianceSignalSyncService.ShedLockConfiguration},
 * picked up as a member class exactly as component scanning does) and a real
 * {@link ComplianceSignalSyncService} proxied by the ShedLock advisor. The provider and the sink are
 * fakes that count calls, so "a single run" is a measured number and not an assertion about logs.
 */
@Testcontainers(disabledWithoutDocker = true)
class ComplianceSignalSyncLockTest {

    private static final String SCHEMA = "dlap_autocompliance";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"));

    private static DataSource dataSource;

    private final List<AnnotationConfigApplicationContext> open = new ArrayList<>();

    @BeforeAll
    static void migrateWithFlyway() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void emptyLockTable() {
        new JdbcTemplate(dataSource).update("DELETE FROM " + SCHEMA + ".shedlock");
    }

    @AfterEach
    void stopReplicas() {
        closeReplicas();
    }

    private void closeReplicas() {
        open.forEach(AnnotationConfigApplicationContext::close);
        open.clear();
    }

    /** Counts calls across every replica of one test: runs, overlap, start times and applied labels. */
    private static final class Probe {
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger labelsApplied = new AtomicInteger();
        final List<Long> startNanos = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release;
        volatile long workMillis;

        ComplianceSignalProvider provider() {
            return new ComplianceSignalProvider() {
                @Override
                public List<ComplianceSignal> fetchSignals() {
                    startNanos.add(System.nanoTime());
                    fetches.incrementAndGet();
                    int now = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(now, Math::max);
                    entered.countDown();
                    try {
                        CountDownLatch gate = release;
                        if (gate != null) {
                            gate.await(20, TimeUnit.SECONDS);
                        }
                        if (workMillis > 0) {
                            Thread.sleep(workMillis);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return List.of(new ComplianceSignal("asset-1", "GDPR", "art-17", ComplianceStatus.VIOLATION, "x"));
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }
            };
        }

        ComplianceLabelSink sink() {
            return (uri, label, detail) -> labelsApplied.incrementAndGet();
        }
    }

    private AnnotationConfigApplicationContext replica(Probe probe, Map<String, Object> extra) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        Map<String, Object> props = new HashMap<>(Map.of(
                "dlap.compliance.signal-sync.enabled", "true",
                // No automatic first run: the deterministic tests call scheduledSync themselves.
                "dlap.compliance.signal-sync.initial-delay", "PT1H",
                "spring.jpa.properties.hibernate.default_schema", SCHEMA));
        props.putAll(extra);
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.registerBean(DataSource.class, () -> dataSource);
        ctx.registerBean(ComplianceSignalSyncService.class,
                () -> new ComplianceSignalSyncService(probe.provider(), new ComplianceLabelMapper(), probe.sink()));
        ctx.refresh();
        open.add(ctx);
        return ctx;
    }

    private static ComplianceSignalSyncService syncOf(AnnotationConfigApplicationContext ctx) {
        return ctx.getBean(ComplianceSignalSyncService.class);
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static Double heldSeconds() {
        return jdbc().queryForObject(
                "SELECT extract(epoch FROM (lock_until - locked_at)) FROM " + SCHEMA + ".shedlock", Double.class);
    }

    @Test
    void v5CreatesTheShedlockTableAndRerunsCleanly() throws Exception {
        List<String> columns = jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'shedlock' "
                        + "ORDER BY ordinal_position", String.class, SCHEMA);
        assertThat(columns).containsExactly("name", "lock_until", "locked_at", "locked_by");
        Integer applied = jdbc().queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history WHERE version = '5' AND success", Integer.class);
        assertThat(applied).as("V5 recorded as applied").isEqualTo(1);

        // Idempotent per CLAUDE.md section 7: running the script again on a migrated database is a no-op.
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__shedlock.sql"));
        }
    }

    @Test
    void theSyncBeanIsProxiedByShedLock() {
        AnnotationConfigApplicationContext a = replica(new Probe(), Map.of());

        assertThat(a.getBean(LockProvider.class)).isNotNull();
        assertThat(AopUtils.isAopProxy(syncOf(a))).as("ShedLock advises the @SchedulerLock method").isTrue();
    }

    @Test
    void whileOneReplicaIsSyncing_theOtherSkipsTheTick() throws Exception {
        Probe probe = new Probe();
        AnnotationConfigApplicationContext a = replica(probe, Map.of());
        AnnotationConfigApplicationContext b = replica(probe, Map.of());
        probe.release = new CountDownLatch(1);

        CompletableFuture<Void> tickOnA = CompletableFuture.runAsync(() -> syncOf(a).scheduledSync());
        assertThat(probe.entered.await(10, TimeUnit.SECONDS)).as("replica A is inside the sync").isTrue();

        syncOf(b).scheduledSync();

        assertThat(probe.fetches.get()).as("replica B must not start a second sync").isEqualTo(1);
        probe.release.countDown();
        tickOnA.get(10, TimeUnit.SECONDS);
        assertThat(probe.fetches.get()).isEqualTo(1);
        assertThat(probe.labelsApplied.get()).as("the labels were applied once").isEqualTo(1);
        assertThat(probe.maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void afterTheWinnerFinishes_aLateReplicaStillSkipsTheSameInterval() {
        Probe probe = new Probe();
        AnnotationConfigApplicationContext a = replica(probe, Map.of());
        AnnotationConfigApplicationContext b = replica(probe, Map.of());

        syncOf(a).scheduledSync();
        syncOf(b).scheduledSync();
        syncOf(a).scheduledSync();

        // lockAtLeastFor defaults to the 15 minute interval, which outlives the milliseconds the sync took.
        assertThat(probe.fetches.get()).isEqualTo(1);
        assertThat(probe.labelsApplied.get()).isEqualTo(1);
    }

    @Test
    void lockRowIsNamedAndHeldForTheDefaultInterval() {
        syncOf(replica(new Probe(), Map.of())).scheduledSync();

        Map<String, Object> row = jdbc().queryForMap("SELECT name, locked_by FROM " + SCHEMA + ".shedlock");
        assertThat(row.get("name")).isEqualTo(ComplianceSignalSyncService.LOCK_NAME).isEqualTo("autocompliance-signal-sync");
        assertThat(row.get("locked_by")).asString().isNotBlank();
        assertThat(heldSeconds()).as("default lock-at-least-for is the 900000 ms interval").isBetween(899.0, 901.0);
    }

    @Test
    void lockAtLeastForFollowsTheConfiguredInterval() {
        syncOf(replica(new Probe(), Map.of("dlap.compliance.signal-sync.interval-ms", "60000"))).scheduledSync();

        assertThat(heldSeconds()).isBetween(59.0, 61.0);
    }

    @Test
    void lockAtLeastForCanBeSetApartFromTheInterval() {
        syncOf(replica(new Probe(), Map.of("dlap.compliance.signal-sync.lock-at-least-for", "PT2M"))).scheduledSync();

        assertThat(heldSeconds()).isBetween(119.0, 121.0);
    }

    @Test
    void anExpiredLockIsTakenOver_soACrashedHolderDoesNotBlockTheJobForever() {
        Probe probe = new Probe();
        AnnotationConfigApplicationContext a = replica(probe, Map.of());
        AnnotationConfigApplicationContext b = replica(probe, Map.of());
        syncOf(a).scheduledSync();

        jdbc().update("UPDATE " + SCHEMA + ".shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'");
        syncOf(b).scheduledSync();

        assertThat(probe.fetches.get()).isEqualTo(2);
    }

    @Test
    void scheduledReplicasSyncOnceAnIntervalAndNeverTogether() throws Exception {
        Probe probe = new Probe();
        probe.workMillis = 300;
        Duration interval = Duration.ofMillis(1500);
        Map<String, Object> timers = Map.of(
                "dlap.compliance.signal-sync.interval-ms", "1500",
                "dlap.compliance.signal-sync.initial-delay", "PT0S");
        long windowStart = System.nanoTime();
        replica(probe, timers);
        replica(probe, timers);

        Thread.sleep(6500);
        closeReplicas();
        Duration window = Duration.ofNanos(System.nanoTime() - windowStart);

        List<Long> starts = new ArrayList<>(probe.startNanos);
        Collections.sort(starts);
        assertThat(starts.size()).as("the timers fired and the cluster synced").isGreaterThanOrEqualTo(3);
        assertThat(probe.maxInFlight.get()).as("two replicas never sync at the same time").isEqualTo(1);
        // lockAtLeastFor counts from the moment the lock is taken, and the work starts a little later
        // (a cold first run needs a few hundred ms), so the gap between two starts can be shorter than
        // the interval by that lag. Without the lock both timers fire within milliseconds of each other
        // and the gap is about zero.
        Duration slack = Duration.ofMillis(500);
        for (int i = 1; i < starts.size(); i++) {
            Duration gap = Duration.ofNanos(starts.get(i) - starts.get(i - 1));
            assertThat(gap).as("run %d starts about one interval after run %d", i, i - 1)
                    .isGreaterThanOrEqualTo(interval.minus(slack));
        }
        // One run per interval for the whole cluster: the lock is taken at most once per interval, so
        // the count cannot exceed the number of intervals in the window plus the first run. Two unlocked
        // replicas would run about twice as often.
        long intervalsInWindow = window.toMillis() / interval.toMillis();
        assertThat((long) starts.size()).isLessThanOrEqualTo(intervalsInWindow + 1);
    }

    @Test
    void theLockBoundsFollowRuleR7() throws Exception {
        Scheduled scheduled = ComplianceSignalSyncService.class.getMethod("scheduledSync").getAnnotation(Scheduled.class);
        SchedulerLock lock = ComplianceSignalSyncService.class.getMethod("scheduledSync").getAnnotation(SchedulerLock.class);

        assertThat(scheduled).isNotNull();
        assertThat(lock).isNotNull();
        assertThat(lock.name()).hasSizeLessThan(64);
        assertThat(lock.lockAtMostFor()).contains("lock-at-most-for:PT30M");
        assertThat(scheduled.fixedDelayString()).contains("interval-ms:900000");
        // Rule R7: at least twice the expected run. A run is one HTTP fetch (5 s connect, 15 s read)
        // and one Kafka publish per signal; 5 minutes is a generous ceiling for that.
        Duration longestExpectedRun = Duration.ofMinutes(5);
        assertThat(Duration.parse("PT30M")).isGreaterThanOrEqualTo(longestExpectedRun.multipliedBy(2));
    }

    @Test
    void lockProviderRejectsASchemaThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> ComplianceSignalSyncService.createLockProvider(dataSource, "x; DROP TABLE y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComplianceSignalSyncService.createLockProvider(dataSource, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
