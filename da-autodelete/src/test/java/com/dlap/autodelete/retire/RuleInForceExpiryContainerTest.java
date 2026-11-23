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

package com.dlap.autodelete.retire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Q-677 (RET-06) on PostgreSQL 15.1 with the module's Flyway schema (V8): the rule-end job removes a rule in force
 * past its maximum age and ends the record RULE_ENDED, leaves a younger rule alone, never races a restore that holds
 * the row, and runs under its own ShedLock on database time. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = RuleInForceExpiryContainerTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class RuleInForceExpiryContainerTest {

    /** JPA and the repositories only. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.autodelete.model", "com.dlap.autodelete.domain"})
    @EnableJpaRepositories(basePackageClasses = SoftDeleteRecordRepository.class)
    static class Config {
    }

    private static final Instant NOW = Instant.parse("2026-12-15T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
            .withDatabaseName("dlap")
            .withUsername("dlap")
            .withPassword("dlap")
            .withEnv("POSTGRES_MULTIPLE_DATABASES", "da_autodelete")
            .withCopyFileToContainer(MountableFile.forHostPath(initScript(), 0755),
                    "/docker-entrypoint-initdb.d/00-create-multiple-databases.sh");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RuleInForceExpiryContainerTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "da_autodelete_user");
        registry.add("spring.datasource.password", () -> "da_autodelete_pass");
    }

    @Autowired
    private SoftDeleteRecordRepository records;

    @Autowired
    private RetireDispositionRepository dispositions;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final NativeRuleApplier applier = mock(NativeRuleApplier.class);
    private final RetireAuditLog audit = mock(RetireAuditLog.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RuleInForceExpiry expiry;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dlap_autodelete.retire_dispositions");
        jdbc.update("DELETE FROM dlap_autodelete.soft_delete_records");
        DispositionPlanner planner = mock(DispositionPlanner.class);
        when(planner.applierFor("GCS_LIFECYCLE")).thenReturn(Optional.of(applier));
        when(applier.remove(any())).thenReturn(true);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        expiry = new RuleInForceExpiry(records, new RuleRemover(dispositions, planner, audit, clock), audit,
                RetireProperties.defaults(), clock, txManager, registry);
    }

    private UUID ruleInForce(Duration appliedAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dlap_autodelete.soft_delete_records (id, asset_id, queued_at, scheduled_hard_delete_at,"
                + " status, cloud_provider, asset_uri) VALUES (?, ?, ?, ?, 'RULE_IN_FORCE', 'GCP', 'gs://bucket-a')",
                id, UUID.randomUUID(), Timestamp.from(NOW.minus(Duration.ofDays(90))),
                Timestamp.from(NOW.minus(Duration.ofDays(60))));
        jdbc.update("INSERT INTO dlap_autodelete.retire_dispositions (id, soft_delete_record_id, asset_id, target_uri,"
                + " cloud_provider, mode, platform, rule_id, rule_document, status, dry_run, approval_id, planned_at,"
                + " applied_at, proof_event_id, proof_event_time, updated_at) VALUES (?, ?, ?, 'gs://bucket-a', 'GCP',"
                + " 'NATIVE_RULE', 'GCS_LIFECYCLE', 'dlap-retire-x', ?, 'EXECUTED', false, ?, ?, ?, 'evt', ?, ?)",
                UUID.randomUUID(), id, UUID.randomUUID(), "{\"bucket\":\"bucket-a\",\"lifecycle\":{\"rule\":[]}}",
                UUID.randomUUID(), Timestamp.from(NOW.minus(appliedAgo)), Timestamp.from(NOW.minus(appliedAgo)),
                Timestamp.from(NOW.minus(appliedAgo).plusSeconds(3600)), Timestamp.from(NOW));
        return id;
    }

    /**
     * An applied rule that was never proven (RET-08, Q-695): the record AWAITING_PROOF with the sweeper's proof check
     * still ahead in next_check_at, the disposition in the given state.
     */
    private UUID unproven(Duration appliedAgo, String dispositionStatus, String platform, boolean dryRun) {
        UUID id = UUID.randomUUID();
        Instant applied = NOW.minus(appliedAgo);
        jdbc.update("INSERT INTO dlap_autodelete.soft_delete_records (id, asset_id, queued_at, scheduled_hard_delete_at,"
                + " status, cloud_provider, asset_uri, next_check_at) VALUES (?, ?, ?, ?, 'AWAITING_PROOF', 'GCP',"
                + " 'gs://bucket-a', ?)",
                id, UUID.randomUUID(), Timestamp.from(NOW.minus(Duration.ofDays(90))),
                Timestamp.from(NOW.minus(Duration.ofDays(60))), Timestamp.from(NOW.plus(Duration.ofDays(1))));
        jdbc.update("INSERT INTO dlap_autodelete.retire_dispositions (id, soft_delete_record_id, asset_id, target_uri,"
                + " cloud_provider, mode, platform, rule_id, rule_document, status, dry_run, approval_id, planned_at,"
                + " applied_at, proof_due_at, updated_at) VALUES (?, ?, ?, 'gs://bucket-a', 'GCP',"
                + " 'NATIVE_RULE', ?, 'dlap-retire-x', ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, UUID.randomUUID(), platform, "{\"bucket\":\"bucket-a\",\"lifecycle\":{\"rule\":[]}}",
                dispositionStatus, dryRun, UUID.randomUUID(), Timestamp.from(applied), dryRun ? null : Timestamp.from(applied),
                Timestamp.from(NOW.plus(Duration.ofDays(1))), Timestamp.from(NOW));
        return id;
    }

    private Timestamp column(String column, UUID id) {
        return jdbc.queryForObject("SELECT " + column + " FROM dlap_autodelete.soft_delete_records WHERE id = ?",
                Timestamp.class, id);
    }

    /**
     * Q-695 on PostgreSQL with Flyway V9: an applied GCS rule never proven past the maximum age ends
     * RULE_ENDED_UNPROVEN (the CHECK accepts it), whether its disposition is AWAITING_PROOF or PROOF_OVERDUE, even
     * though the sweeper's next_check_at is still ahead; a younger one, a dry run and a table expiration are not
     * claimed.
     */
    @Test
    void anUnprovenAppliedContainerRulePastItsMaximumAgeEndsRuleEndedUnproven() {
        UUID awaiting = unproven(Duration.ofDays(31), "AWAITING_PROOF", "GCS_LIFECYCLE", false);
        UUID overdue = unproven(Duration.ofDays(32), "PROOF_OVERDUE", "GCS_LIFECYCLE", false);
        UUID young = unproven(Duration.ofDays(29), "AWAITING_PROOF", "GCS_LIFECYCLE", false);
        UUID dryRun = unproven(Duration.ofDays(31), "DRY_RUN", "GCS_LIFECYCLE", true);
        UUID table = unproven(Duration.ofDays(31), "AWAITING_PROOF", "BIGQUERY_TABLE_EXPIRATION", false);

        assertThat(expiry.endExpired()).isEqualTo(2);

        assertThat(status(awaiting)).isEqualTo("RULE_ENDED_UNPROVEN");
        assertThat(status(overdue)).isEqualTo("RULE_ENDED_UNPROVEN");
        assertThat(column("next_check_at", awaiting)).isNull();
        assertThat(status(young)).isEqualTo("AWAITING_PROOF");
        assertThat(status(dryRun)).isEqualTo("AWAITING_PROOF");
        assertThat(status(table)).isEqualTo("AWAITING_PROOF");
        verify(applier, times(2)).remove(any());
        assertThat(registry.get(RuleInForceExpiry.ENDED_UNPROVEN_METRIC).counter().count()).isEqualTo(2.0);
        assertThat(expiry.endExpired()).as("a second run finds nothing").isZero();
    }

    /**
     * Q-695: a refused removal of an unproven rule is retried on the job's own rule_end_retry_at (V9); the sweeper's
     * next_check_at stays where it was, and the row is not claimed again before its retry time.
     */
    @Test
    void aRefusedUnprovenRemovalWaitsOnItsOwnRetryTimeAndLeavesTheSweepersCheck() {
        UUID id = unproven(Duration.ofDays(31), "AWAITING_PROOF", "GCS_LIFECYCLE", false);
        Timestamp sweeperCheck = column("next_check_at", id);
        when(applier.remove(any())).thenThrow(new com.dlap.autodelete.nativerule.NativeRuleException("HTTP 403", null));

        assertThat(expiry.endExpired()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("AWAITING_PROOF");
        assertThat(column("rule_end_retry_at", id).toInstant()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(column("next_check_at", id)).isEqualTo(sweeperCheck);
        assertThat(expiry.endExpired()).as("not before its retry time").isZero();
        verify(applier, times(1)).remove(any());
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM dlap_autodelete.soft_delete_records WHERE id = ?", String.class,
                id);
    }

    @Test
    void aRuleInForcePastItsMaximumAgeEndsRuleEndedAndAYoungerOneStays() {
        UUID old = ruleInForce(Duration.ofDays(31));
        UUID young = ruleInForce(Duration.ofDays(29));
        assertThat(registry.get(RuleInForceExpiry.IN_FORCE_METRIC).gauge().value()).isEqualTo(2.0);

        assertThat(expiry.endExpired()).isEqualTo(1);

        assertThat(status(old)).isEqualTo("RULE_ENDED");
        assertThat(status(young)).isEqualTo("RULE_IN_FORCE");
        assertThat(jdbc.queryForObject("SELECT rule_removed_at FROM dlap_autodelete.retire_dispositions"
                + " WHERE soft_delete_record_id = ?", Timestamp.class, old).toInstant()).isEqualTo(NOW);
        verify(applier, times(1)).remove(any());
        assertThat(registry.get(RuleInForceExpiry.IN_FORCE_METRIC).gauge().value()).isEqualTo(1.0);
        assertThat(expiry.endExpired()).as("a second run finds nothing").isZero();
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRowARestoreHoldsIsSkippedNotRaced() throws Exception {
        UUID held = ruleInForce(Duration.ofDays(31));
        try (Connection other = DriverManager.getConnection(jdbcUrl(), "da_autodelete_user", "da_autodelete_pass")) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT id FROM dlap_autodelete.soft_delete_records WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery();
            }
            long started = System.nanoTime();
            assertThat(expiry.endExpired()).isZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(10_000);
            verify(applier, never()).remove(any());
            other.rollback();
        }
        assertThat(expiry.endExpired()).isEqualTo(1);
        assertThat(status(held)).isEqualTo("RULE_ENDED");
    }

    @Test
    void theRuleEndLockIsHeldByOneReplicaAtATimeOnDatabaseTime() {
        LockProvider replicaA = new RetireConfiguration().autodeleteLockProvider(dataSource, "dlap_autodelete");
        LockProvider replicaB = new RetireConfiguration().autodeleteLockProvider(dataSource, "dlap_autodelete");
        LockConfiguration config = new LockConfiguration(Instant.now(), RuleInForceExpiry.LOCK_NAME,
                Duration.ofMinutes(10), Duration.ZERO);

        SimpleLock lock = replicaA.lock(config).orElseThrow();
        assertThat(replicaB.lock(config)).as("a second replica while the first holds it").isEmpty();
        lock.unlock();
        assertThat(replicaB.lock(config)).as("after release").isPresent();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_autodelete";
    }

    private static Path initScript() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts/00-create-multiple-databases.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("00-create-multiple-databases.sh not found above " + System.getProperty("user.dir"));
    }
}
