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

package com.dlap.discovery.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.discovery.client.admin.AdminConnectionDetails;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.test.util.FakeConfigService;
import com.dlap.discovery.test.util.FakeJobService;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DISC-01 acceptance: the scheduled discovery trigger runs under ShedLock, as one cluster-wide
 * singleton, against a real PostgreSQL and the shipped {@code V10__shedlock.sql}.
 *
 * <p>Two Spring contexts stand in for two da-discovery replicas that share one database. Each
 * holds the production lock configuration ({@code ScheduledDiscoveryTrigger.ShedLockConfiguration},
 * so this also proves ShedLock 6.10 works on this module's Spring 6.1) and a real
 * {@code ScheduledDiscoveryTrigger} proxied by the ShedLock advisor.
 */
@Testcontainers(disabledWithoutDocker = true)
class ScheduledDiscoveryTriggerLockTest {

    private static final String SCHEMA = "dlap_discovery";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static DataSource dataSource;

    private AnnotationConfigApplicationContext replicaA;
    private AnnotationConfigApplicationContext replicaB;
    private FakeJobService jobsA;
    private FakeJobService jobsB;

    @BeforeAll
    static void createLockTable() throws Exception {
        DriverManagerDataSource admin = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(admin).execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);

        // currentSchema reproduces what Flyway does for the real migration run: the unqualified
        // CREATE TABLE in V10 lands in the service schema.
        String url = POSTGRES.getJdbcUrl();
        dataSource = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V10__shedlock.sql"));
        }
    }

    @BeforeEach
    void startReplicas() {
        new JdbcTemplate(dataSource).update("DELETE FROM shedlock");
        jobsA = new FakeJobService();
        jobsB = new FakeJobService();
        replicaA = replica(jobsA);
        replicaB = replica(jobsB);
    }

    @AfterEach
    void stopReplicas() {
        replicaA.close();
        replicaB.close();
    }

    private static AnnotationConfigApplicationContext replica(FakeJobService jobs) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dlap.discovery.scheduled.enabled", "true",
                // @Value fields are re-injected from the environment after the supplier runs,
                // so the connection list has to come from properties, not from reflection.
                "dlap.discovery.scheduled.connection-ids", "conn-1",
                "dlap.discovery.scheduled.job-name-prefix", "scheduled-scan",
                "spring.jpa.properties.hibernate.default_schema", SCHEMA)));
        ctx.registerBean(DataSource.class, () -> dataSource);
        ctx.register(ScheduledDiscoveryTrigger.ShedLockConfiguration.class);
        ctx.registerBean(ScheduledDiscoveryTrigger.class, () -> new ScheduledDiscoveryTrigger(jobs,
                id -> new AdminConnectionDetails("acct-1", CloudProvider.GCP, id),
                new FakeConfigService(), null, null, null, List.of(),
                Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC)));
        ctx.refresh();
        return ctx;
    }

    private static ScheduledDiscoveryTrigger triggerOf(AnnotationConfigApplicationContext ctx) {
        return ctx.getBean(ScheduledDiscoveryTrigger.class);
    }

    @Test
    void theTriggerBeanIsProxiedByShedLock() {
        assertThat(replicaA.getBean(LockProvider.class)).isNotNull();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(triggerOf(replicaA)))
                .as("ShedLock advises the @SchedulerLock method through a proxy")
                .isTrue();
    }

    @Test
    void whileOneReplicaIsRunningATick_theOtherSkipsIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        jobsA.blockExecution = release;

        CompletableFuture<Void> tickOnA = CompletableFuture.runAsync(() -> triggerOf(replicaA).runScheduledScans());
        assertThat(jobsA.entered.await(10, TimeUnit.SECONDS)).as("replica A is inside the tick").isTrue();

        triggerOf(replicaB).runScheduledScans();

        assertThat(jobsB.createCount.get()).as("replica B must not start a second scan").isZero();
        release.countDown();
        tickOnA.get(10, TimeUnit.SECONDS);
        assertThat(jobsA.createCount.get()).isEqualTo(1);
        assertThat(jobsA.executed).isNotNull();
    }

    @Test
    void afterTheWinnerFinishes_aLateReplicaStillSkipsTheSameTick() {
        triggerOf(replicaA).runScheduledScans();
        assertThat(jobsA.createCount.get()).isEqualTo(1);

        // lockAtLeastFor (PT2M by default) outlives the few seconds the tick took, so a replica
        // whose cron fires slightly late does not run the tick again.
        triggerOf(replicaB).runScheduledScans();
        triggerOf(replicaA).runScheduledScans();

        assertThat(jobsB.createCount.get()).isZero();
        assertThat(jobsA.createCount.get()).isEqualTo(1);
    }

    @Test
    void lockRowIsNamedAndExpiresInTheFuture_thenAnExpiredLockIsTakenOver() {
        triggerOf(replicaA).runScheduledScans();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> row = jdbc.queryForMap("SELECT name, locked_by FROM shedlock");
        assertThat(row.get("name")).isEqualTo(ScheduledDiscoveryTrigger.LOCK_NAME);
        assertThat(row.get("locked_by")).asString().isNotBlank();
        Boolean heldIntoTheFuture = jdbc.queryForObject(
                "SELECT lock_until > timezone('utc', now()) FROM shedlock WHERE name = ?", Boolean.class,
                ScheduledDiscoveryTrigger.LOCK_NAME);
        assertThat(heldIntoTheFuture).isTrue();

        // A lock must not be permanent: once it has expired, the next tick can be taken by anyone.
        jdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'");
        triggerOf(replicaB).runScheduledScans();

        assertThat(jobsB.createCount.get()).isEqualTo(1);
    }

    @Test
    void lockProviderRejectsASchemaThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> ScheduledDiscoveryTrigger.createLockProvider(dataSource, "x; DROP TABLE y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduledDiscoveryTrigger.createLockProvider(dataSource, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
