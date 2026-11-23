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

package com.dlap.autoarchival.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.autoarchival.approval.ApprovalRefusedException;
import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.reconcile.ArchiveReconcileConfiguration;
import com.dlap.autoarchival.reconcile.ArchiveReconcileProperties;
import com.dlap.autoarchival.reconcile.ArchiveRuleReconciler;
import com.dlap.autoarchival.rule.ArchiveRuleProperties;
import com.dlap.autoarchival.rule.FakeRuleProvider;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.common.audit.AuditActor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * RET-05 on PostgreSQL 15.1 with the module's Flyway schema (V1 to V4) and the real repository:
 * <ul>
 *   <li>Q-609: several workers racing for one {@code PLANNED} row run it once. The policy-event path, the
 *       approval-decided path and {@code POST /rules/{id}/retry} all end in {@code ArchiveExecutor.execute},
 *       so the race is driven there: one provider apply, one {@code REQUESTED} and one outcome record;</li>
 *   <li>Q-607: the reconciliation job counts the rows it should against real rows, on an injected clock, and
 *       leaves every row as it found it; its ShedLock provider lets one replica hold the lock at a time.</li>
 * </ul>
 * Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = ArchiveRulesPostgresTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ArchiveRulesPostgresTest {

    /** JPA and the rule repository only. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ArchiveRuleEntity.class)
    @EnableJpaRepositories(basePackageClasses = ArchiveRuleRepository.class)
    static class Config {
    }

    private static final String APPROVAL = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AuditActor SERVICE = AuditActor.service("da-autoarchival");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
            .withDatabaseName("dlap")
            .withUsername("dlap")
            .withPassword("dlap")
            .withEnv("POSTGRES_MULTIPLE_DATABASES", "da_autoarchival")
            .withCopyFileToContainer(MountableFile.forHostPath(initScript(), 0755),
                    "/docker-entrypoint-initdb.d/00-create-multiple-databases.sh");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/da_autoarchival");
        registry.add("spring.datasource.username", () -> "da_autoarchival_user");
        registry.add("spring.datasource.password", () -> "da_autoarchival_pass");
        registry.add("spring.flyway.schemas", () -> "dlap_autoarchival");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "dlap_autoarchival");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private ArchiveRuleRepository rules;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_autoarchival.dlap_archive_rules");
        jdbc.update("DELETE FROM dlap_autoarchival.shedlock");
    }

    // ---- Q-609 ------------------------------------------------------------------------------------

    /** A provider whose calls are counted without a race of its own. */
    private static class CountingProvider extends FakeRuleProvider {
        final AtomicInteger applies = new AtomicInteger();

        CountingProvider() {
            super("GCP", true);
        }

        @Override
        public synchronized Inventory inventory(RuleSpec spec) {
            return super.inventory(spec);
        }

        @Override
        public synchronized ApplyResult apply(RuleSpec spec) {
            applies.incrementAndGet();
            return super.apply(spec);
        }
    }

    private static IArchivalConfigService coldConfig() {
        IArchivalConfigService c = mock(IArchivalConfigService.class);
        when(c.getArchivalConfig()).thenReturn(ArchivalConfigDTO.builder().defaultArchivalTier("COLD").build());
        return c;
    }

    private ArchiveRuleEntity planned(String ruleId, String approvalId, String uri) {
        return plannedAt(ruleId, approvalId, uri, "finance/");
    }

    /**
     * A planned GCS row on its own prefix. Since V4 (Q-710) a second live row for one GCS scope is refused by the
     * database, so tests that need several live rows give each its own prefix.
     */
    private ArchiveRuleEntity plannedAt(String ruleId, String approvalId, String uri, String prefix) {
        ArchiveRuleEntity row = new ArchiveRuleEntity(ruleId, approvalId, null, "task-" + ruleId, ASSET, uri, "GCP",
                ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", prefix, "COLDLINE", 0, NOW);
        return rules.saveAndFlush(row);
    }

    private ArchiveRuleEntity plannedOwn(String ruleId, String approvalId) {
        return plannedAt(ruleId, approvalId, "gs://pilot/" + ruleId + "/", ruleId + "/");
    }

    @Test
    @Timeout(60)
    void severalWorkersRacingForOnePlannedRowRunItOnce() throws Exception {
        int workers = 6;
        CountingProvider gcp = new CountingProvider();
        ArchiveAudit audit = mock(ArchiveAudit.class);
        // Every worker must have read the PLANNED row and verified the approval before any of them claims it, so
        // the claim is the only thing that can stop a second run.
        CyclicBarrier allVerified = new CyclicBarrier(workers);
        ApprovalVerifier verifier = mock(ApprovalVerifier.class);
        when(verifier.verifyById(APPROVAL, ASSET)).thenAnswer(inv -> {
            allVerified.await(20, TimeUnit.SECONDS);
            return new ArchiveApproval(APPROVAL, "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false,
                    "gs://pilot/finance/", "GCP", Map.of("tier", "COLD"));
        });
        ArchiveExecutor executor = new ArchiveExecutor(
                new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules), rules, verifier, audit, CLOCK,
                List.of(), coldConfig());
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "COLD"), null, APPROVAL, "fire-1"));

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<ArchiveRuleEntity>> calls = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                calls.add(() -> executor.execute(APPROVAL, SERVICE));
            }
            List<ArchiveRuleEntity> results = new ArrayList<>();
            for (Future<ArchiveRuleEntity> f : pool.invokeAll(calls)) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).hasSize(workers);
        } finally {
            pool.shutdownNow();
        }

        assertThat(gcp.applies.get()).as("provider applies").isEqualTo(1);
        assertThat(gcp.rules).hasSize(1);
        verify(audit, times(1)).requested(any(), any(), any());
        verify(audit, times(1)).applied(any(), any(), any());
        ArchiveRuleEntity stored = rules.findByApprovalId(APPROVAL).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(stored.isAlreadyPresent()).isFalse();
    }

    private static ArchiveApproval approved() {
        return new ArchiveApproval(APPROVAL, "APPROVED", "ARCHIVE", 1, List.of("alice-sub"), false,
                "gs://pilot/finance/", "GCP", Map.of("tier", "COLD"));
    }

    /**
     * Worker B read the row while it was PLANNED, then worker A claimed, applied and saved APPLIED, then B's
     * approval check failed with {@code failure}. B must not write its old copy over A's row (Q-609). The
     * Kafka retry that follows a transient failure must find the row decided and run nothing.
     */
    private void aLateLoserMustNotUndoTheWinner(ApprovalRefusedException failure, boolean transientFailure)
            throws Exception {
        CountingProvider gcp = new CountingProvider();
        ArchiveAudit audit = mock(ArchiveAudit.class);
        java.util.concurrent.CountDownLatch bHasReadTheRow = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch aIsDone = new java.util.concurrent.CountDownLatch(1);
        ApprovalVerifier winnerVerifier = mock(ApprovalVerifier.class);
        when(winnerVerifier.verifyById(APPROVAL, ASSET)).thenReturn(approved());
        ApprovalVerifier loserVerifier = mock(ApprovalVerifier.class);
        when(loserVerifier.verifyById(APPROVAL, ASSET)).thenAnswer(inv -> {
            bHasReadTheRow.countDown(); // execute() has already loaded the PLANNED row
            if (!aIsDone.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the winner did not finish");
            }
            throw failure;
        });
        RulePlanner planner = new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules);
        ArchiveExecutor winner = new ArchiveExecutor(planner, rules, winnerVerifier, audit, CLOCK, List.of(),
                coldConfig());
        ArchiveExecutor loser = new ArchiveExecutor(planner, rules, loserVerifier, audit, CLOCK, List.of(),
                coldConfig());
        winner.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "COLD"), null, APPROVAL, "fire-1"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ArchiveRuleEntity> b = pool.submit(() -> loser.execute(APPROVAL, SERVICE));
            assertThat(bHasReadTheRow.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(winner.execute(APPROVAL, SERVICE).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
            aIsDone.countDown();
            if (transientFailure) {
                assertThatThrownBy(() -> b.get(30, TimeUnit.SECONDS)).hasCause(failure);
            } else {
                assertThat(b.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
            }
        } finally {
            pool.shutdownNow();
        }

        ArchiveRuleEntity stored = rules.findByApprovalId(APPROVAL).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(stored.isAlreadyPresent()).isFalse();
        assertThat(stored.getAppliedAt()).isNotNull();
        assertThat(stored.getRuleDocument()).isNotNull();
        assertThat(stored.getLastError()).isNull();

        // The Kafka retry (or a second REST retry) of the same row runs nothing.
        assertThat(winner.execute(APPROVAL, SERVICE).getStatus()).isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
        assertThat(gcp.applies.get()).as("provider applies").isEqualTo(1);
        verify(audit, times(1)).requested(any(), any(), any());
        verify(audit, times(1)).applied(any(), any(), any());
        verify(audit, org.mockito.Mockito.never()).refused(any(), any(), any(), any(), any(), any());
    }

    @Test
    @Timeout(90)
    void aWorkerWhoseApprovalCheckFailsTransientlyAfterAnotherClaimedDoesNotRevertTheRowToPlanned()
            throws Exception {
        aLateLoserMustNotUndoTheWinner(new ApprovalRefusedException(ApprovalRefusedException.Reason.VERIFY_UNAVAILABLE,
                "the policy engine timed out"), true);
    }

    @Test
    @Timeout(90)
    void aWorkerWhoseApprovalIsRefusedAfterAnotherClaimedWritesNoRefusalAndLeavesTheRowApplied() throws Exception {
        aLateLoserMustNotUndoTheWinner(new ApprovalRefusedException(ApprovalRefusedException.Reason.NOT_EXECUTABLE,
                "approval is EXPIRED and not executable"), false);
    }

    @Test
    void endIfPlannedChangesOnlyAPlannedRow() {
        planned("dlap-archive-endp00000", "appr-endp", "gs://pilot/finance/");
        assertThat(rules.endIfPlanned("dlap-archive-endp00000", ArchiveRuleEntity.STATUS_REFUSED, "x", NOW))
                .isEqualTo(1);
        assertThat(rules.findById("dlap-archive-endp00000").orElseThrow().getStatus())
                .isEqualTo(ArchiveRuleEntity.STATUS_REFUSED);

        planned("dlap-archive-endq00000", "appr-endq", "gs://pilot/finance/");
        assertThat(rules.claimPlanned("dlap-archive-endq00000", NOW)).isEqualTo(1);
        assertThat(rules.endIfPlanned("dlap-archive-endq00000", ArchiveRuleEntity.STATUS_REFUSED, "x", NOW))
                .as("a claimed row is not ended by a loser").isZero();
        assertThat(rules.findById("dlap-archive-endq00000").orElseThrow().getStatus())
                .isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
    }

    @Test
    @Timeout(60)
    void theConditionalUpdateLetsExactlyOneCallerClaimARow() throws Exception {
        planned("dlap-archive-claim00000", "appr-claim", "gs://pilot/finance/");
        int callers = 8;
        CyclicBarrier go = new CyclicBarrier(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        int claimed = 0;
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(() -> {
                    go.await(20, TimeUnit.SECONDS);
                    return rules.claimPlanned("dlap-archive-claim00000", NOW);
                });
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                claimed += f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed).isEqualTo(1);
        assertThat(rules.findById("dlap-archive-claim00000").orElseThrow().getStatus())
                .isEqualTo(ArchiveRuleEntity.STATUS_EXECUTING);
        assertThat(rules.claimPlanned("dlap-archive-claim00000", NOW)).as("a claimed row is not claimed again")
                .isZero();
    }

    @Test
    void aRowThatIsNotPlannedCannotBeClaimed() {
        ArchiveRuleEntity applied = planned("dlap-archive-appl00000", "appr-applied", "gs://pilot/finance/");
        applied.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, NOW);
        rules.saveAndFlush(applied);

        assertThat(rules.claimPlanned("dlap-archive-appl00000", NOW)).isZero();
        assertThat(rules.claimPlanned("dlap-archive-nothere000", NOW)).isZero();
        assertThat(rules.findById("dlap-archive-appl00000").orElseThrow().getStatus())
                .isEqualTo(ArchiveRuleEntity.STATUS_APPLIED);
    }

    @Test
    void v3StoresWhetherTheRuleWasAlreadyThereAndAllowsTheClaimState() {
        ArchiveRuleEntity row = planned("dlap-archive-pres00000", "appr-present", "gs://pilot/finance/");
        row.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, true, NOW);
        rules.saveAndFlush(row);

        assertThat(jdbc.queryForObject("SELECT already_present FROM dlap_autoarchival.dlap_archive_rules"
                + " WHERE rule_id = 'dlap-archive-pres00000'", Boolean.class)).isTrue();
        assertThat(rules.findById("dlap-archive-pres00000").orElseThrow().isAlreadyPresent()).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE dlap_autoarchival.dlap_archive_rules SET status = 'BOGUS'"))
                .hasMessageContaining("ck_archive_rules_status");
    }

    // ---- Q-607 ------------------------------------------------------------------------------------

    private void applied(String id, Instant appliedAt, boolean mock) {
        ArchiveRuleEntity row = plannedOwn(id, "appr-" + id);
        row.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", mock, appliedAt);
        rules.saveAndFlush(row);
    }

    @Test
    void theReconciliationCountsOldUnprovenRulesAndStuckClaimsAndChangesNothing() {
        applied("dlap-archive-old100000", NOW.minus(Duration.ofDays(9)), false);
        applied("dlap-archive-old200000", NOW.minus(Duration.ofDays(8)), false);
        applied("dlap-archive-young0000", NOW.minus(Duration.ofDays(6)), false);   // not old enough yet
        applied("dlap-archive-mock00000", NOW.minus(Duration.ofDays(30)), true);   // a mocked apply can never be proven
        ArchiveRuleEntity proven = plannedOwn("dlap-archive-prov00000", "appr-prov");
        proven.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, NOW.minus(Duration.ofDays(20)));
        proven.markExecuted("ev-1", "op", NOW.minus(Duration.ofDays(19)), "PROVIDER_LOG", NOW.minus(Duration.ofDays(19)));
        rules.saveAndFlush(proven);                                                 // proven rows are not listed
        ArchiveRuleEntity stuck = plannedOwn("dlap-archive-stuck0000", "appr-stuck");
        stuck.markClaimed(NOW.minus(Duration.ofHours(2)));
        rules.saveAndFlush(stuck);
        ArchiveRuleEntity fresh = plannedOwn("dlap-archive-fresh0000", "appr-fresh");
        fresh.markClaimed(NOW.minus(Duration.ofMinutes(5)));                        // a worker is probably still on it
        rules.saveAndFlush(fresh);
        List<String> before = snapshot();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ArchiveRuleReconciler job = new ArchiveRuleReconciler(rules,
                new ArchiveReconcileProperties(true, Duration.ofDays(7), Duration.ofMinutes(30), 50), CLOCK, registry);

        ArchiveRuleReconciler.Result result = job.reconcile();

        assertThat(result.unproven()).isEqualTo(2);
        assertThat(result.stuck()).isEqualTo(1);
        assertThat(registry.get(ArchiveRuleReconciler.UNPROVEN_METRIC).gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(ArchiveRuleReconciler.STUCK_METRIC).gauge().value()).isEqualTo(1.0);
        assertThat(snapshot()).as("the job changes no row").isEqualTo(before);
    }

    @Test
    void theAgeIsMeasuredOnTheInjectedClock() {
        applied("dlap-archive-clock0000", NOW.minus(Duration.ofDays(6)), false);
        ArchiveReconcileProperties props = new ArchiveReconcileProperties(true, Duration.ofDays(7),
                Duration.ofMinutes(30), 50);

        long now = new ArchiveRuleReconciler(rules, props, CLOCK, new SimpleMeterRegistry()).reconcile().unproven();
        long later = new ArchiveRuleReconciler(rules, props, Clock.fixed(NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC),
                new SimpleMeterRegistry()).reconcile().unproven();

        assertThat(now).isZero();
        assertThat(later).isEqualTo(1);
    }

    @Test
    void theLockIsHeldByOneReplicaAtATimeOnDatabaseTime() {
        LockProvider replicaA = new ArchiveReconcileConfiguration().autoarchivalLockProvider(dataSource,
                "dlap_autoarchival");
        LockProvider replicaB = new ArchiveReconcileConfiguration().autoarchivalLockProvider(dataSource,
                "dlap_autoarchival");
        LockConfiguration config = new LockConfiguration(Instant.now(), ArchiveRuleReconciler.LOCK_NAME,
                Duration.ofMinutes(10), Duration.ZERO);

        SimpleLock lock = replicaA.lock(config).orElseThrow();
        assertThat(replicaB.lock(config)).as("a second replica cannot take the held lock").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autoarchival.shedlock WHERE name = ?",
                Integer.class, ArchiveRuleReconciler.LOCK_NAME)).isEqualTo(1);
        lock.unlock();
        assertThat(replicaB.lock(config)).as("after the release another replica can").isPresent();
    }

    // ---- Q-672 ------------------------------------------------------------------------------------

    @Test
    void q672_aNinetyDayRuleIsNotUnprovenUntilMaxAppliedPlusNinetyDays() {
        applied("dlap-archive-long00000", NOW.minus(Duration.ofDays(40)), false);
        jdbc.update("UPDATE dlap_autoarchival.dlap_archive_rules SET after_days = 90 WHERE rule_id = ?",
                "dlap-archive-long00000");
        applied("dlap-archive-short0000", NOW.minus(Duration.ofDays(40)), false); // after_days 0
        ArchiveReconcileProperties props = new ArchiveReconcileProperties(true, Duration.ofDays(7),
                Duration.ofMinutes(30), 50);

        long unproven = new ArchiveRuleReconciler(rules, props, CLOCK, new SimpleMeterRegistry()).reconcile().unproven();

        assertThat(unproven).as("only the 0 day rule is overdue; the 90 day rule cannot have fired yet").isEqualTo(1);
    }

    // ---- Q-674 ------------------------------------------------------------------------------------

    private ArchiveRuleEntity appliedRow(String id) {
        ArchiveRuleEntity row = plannedOwn(id, "appr-" + id);
        row.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, NOW.minus(Duration.ofHours(1)));
        return rules.saveAndFlush(row);
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM dlap_autoarchival.dlap_archive_rules WHERE rule_id = ?",
                String.class, id);
    }

    @Test
    void q674_aRestoreThatWonTheRowIsNotOverwrittenByTheProof() {
        appliedRow("dlap-archive-race10000");

        int removed = rules.markRemovedIf("dlap-archive-race10000", "APPLIED", NOW);
        int proven = rules.markExecutedIf("dlap-archive-race10000", "APPLIED", "ev-1", "op", NOW, "PROVIDER_LOG", NOW);

        assertThat(removed).isEqualTo(1);
        assertThat(proven).as("the proof loaded APPLIED, which is no longer true").isZero();
        assertThat(statusOf("dlap-archive-race10000")).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("SELECT evidence_event_id FROM dlap_autoarchival.dlap_archive_rules "
                + "WHERE rule_id = 'dlap-archive-race10000'", String.class)).isNull();
    }

    @Test
    void q674_aProofThatWonTheRowIsNotOverwrittenByTheRestoreOfTheOldStatus() {
        appliedRow("dlap-archive-race20000");

        int proven = rules.markExecutedIf("dlap-archive-race20000", "APPLIED", "ev-2", "op", NOW, "PROVIDER_LOG", NOW);
        int removedFromApplied = rules.markRemovedIf("dlap-archive-race20000", "APPLIED", NOW);

        assertThat(proven).isEqualTo(1);
        assertThat(removedFromApplied).isZero();
        assertThat(statusOf("dlap-archive-race20000")).isEqualTo("EXECUTED");
        assertThat(rules.markRemovedIf("dlap-archive-race20000", "EXECUTED", NOW)).isEqualTo(1);
        assertThat(statusOf("dlap-archive-race20000")).isEqualTo("REMOVED");
    }

    @Test
    @Timeout(60)
    void q674_aProofAndARestoreRacingForOneAppliedRowNeverBothWin() throws Exception {
        for (int i = 0; i < 20; i++) {
            String id = "dlap-archive-race3" + String.format("%04d", i);
            appliedRow(id);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> proof = pool.submit(() -> {
                    start.await();
                    return rules.markExecutedIf(id, "APPLIED", "ev", "op", NOW, "PROVIDER_LOG", NOW);
                });
                Future<Integer> restore = pool.submit(() -> {
                    start.await();
                    return rules.markRemovedIf(id, "APPLIED", NOW);
                });
                assertThat(proof.get() + restore.get()).as("exactly one of them changed the row").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---- Q-669 ------------------------------------------------------------------------------------

    private ArchiveRuleEntity claimedRow(String id, Instant claimedAt) {
        ArchiveRuleEntity row = plannedOwn(id, "appr-" + id);
        row.markClaimed(claimedAt);
        return rules.saveAndFlush(row);
    }

    @Test
    void q669_resetToAppliedTakesTheApplyTimeFromTheClaimAndOnlyWorksOnExecuting() {
        Instant claimed = NOW.minus(Duration.ofHours(3));
        claimedRow("dlap-archive-reset1000", claimed);

        int first = rules.resetExecutingToApplied("dlap-archive-reset1000", true, NOW);
        int second = rules.resetExecutingToApplied("dlap-archive-reset1000", true, NOW);

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the row is no longer EXECUTING").isZero();
        ArchiveRuleEntity row = rules.findById("dlap-archive-reset1000").orElseThrow();
        assertThat(row.getStatus()).isEqualTo("APPLIED");
        assertThat(row.getAppliedAt()).isEqualTo(claimed);
        assertThat(row.isAlreadyPresent()).isTrue();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void q669_resetToFailedKeepsTheReasonAndOnlyWorksOnExecuting() {
        claimedRow("dlap-archive-reset2000", NOW.minus(Duration.ofHours(3)));
        appliedRow("dlap-archive-reset3000");

        assertThat(rules.resetExecutingToFailed("dlap-archive-reset2000", "RESET_RULE_ABSENT: x", NOW)).isEqualTo(1);
        assertThat(rules.resetExecutingToFailed("dlap-archive-reset3000", "RESET_RULE_ABSENT: x", NOW))
                .as("an APPLIED row is never reset").isZero();

        assertThat(statusOf("dlap-archive-reset2000")).isEqualTo("FAILED");
        assertThat(rules.findById("dlap-archive-reset2000").orElseThrow().getLastError())
                .startsWith("RESET_RULE_ABSENT");
        assertThat(statusOf("dlap-archive-reset3000")).isEqualTo("APPLIED");
    }

    // ---- Q-712: the claimer's own outcome writes are conditional on EXECUTING -------------------------

    @Test
    void q712_finishIfExecutingWritesTheOutcomeOnlyWhileTheRowIsExecuting() {
        claimedRow("dlap-archive-fin100000", NOW.minus(Duration.ofMinutes(5)));

        int first = rules.finishIfExecuting("dlap-archive-fin100000", "APPLIED", "{\"doc\":1}", false, true, NOW);
        int second = rules.finishIfExecuting("dlap-archive-fin100000", "APPLIED", "{\"doc\":2}", false, false,
                NOW.plusSeconds(60));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the row is no longer EXECUTING").isZero();
        ArchiveRuleEntity row = rules.findById("dlap-archive-fin100000").orElseThrow();
        assertThat(row.getStatus()).isEqualTo("APPLIED");
        assertThat(row.getRuleDocument()).isEqualTo("{\"doc\":1}");
        assertThat(row.isAlreadyPresent()).isTrue();
        assertThat(row.getAppliedAt()).isEqualTo(NOW);
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void q712_aRowAnAdministratorResetKeepsItsStateWhenTheSlowWorkerFinishes() {
        claimedRow("dlap-archive-fin200000", NOW.minus(Duration.ofHours(3)));
        claimedRow("dlap-archive-fin300000", NOW.minus(Duration.ofHours(3)));
        assertThat(rules.resetExecutingToFailed("dlap-archive-fin200000", "RESET_RULE_ABSENT: x", NOW)).isEqualTo(1);
        assertThat(rules.resetExecutingToApplied("dlap-archive-fin300000", true, NOW)).isEqualTo(1);

        assertThat(rules.finishIfExecuting("dlap-archive-fin200000", "APPLIED", "{}", false, false, NOW)).isZero();
        assertThat(rules.finishIfExecuting("dlap-archive-fin200000", "RENDERED", "{}", false, false, NOW)).isZero();
        assertThat(rules.failIfExecuting("dlap-archive-fin300000", "PROVIDER_ERROR: late", NOW)).isZero();

        ArchiveRuleEntity failed = rules.findById("dlap-archive-fin200000").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getLastError()).startsWith("RESET_RULE_ABSENT");
        assertThat(failed.getRuleDocument()).isNull();
        ArchiveRuleEntity applied = rules.findById("dlap-archive-fin300000").orElseThrow();
        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getLastError()).isNull();
    }

    @Test
    void q712_failIfExecutingFailsOnlyAClaimedRow() {
        claimedRow("dlap-archive-fin400000", NOW.minus(Duration.ofMinutes(5)));
        appliedRow("dlap-archive-fin500000");

        assertThat(rules.failIfExecuting("dlap-archive-fin400000", "UNEXPECTED: boom", NOW)).isEqualTo(1);
        assertThat(rules.failIfExecuting("dlap-archive-fin500000", "UNEXPECTED: boom", NOW)).isZero();

        assertThat(statusOf("dlap-archive-fin400000")).isEqualTo("FAILED");
        assertThat(statusOf("dlap-archive-fin500000")).isEqualTo("APPLIED");
    }

    @Test
    @Timeout(60)
    void q712_aResetAndTheWorkersOutcomeRacingForOneExecutingRowNeverBothWin() throws Exception {
        for (int i = 0; i < 20; i++) {
            String id = "dlap-archive-fin6" + String.format("%04d", i);
            claimedRow(id, NOW.minus(Duration.ofHours(1)));
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> worker = pool.submit(() -> {
                    start.await();
                    return rules.finishIfExecuting(id, "APPLIED", "{}", false, false, NOW);
                });
                Future<Integer> reset = pool.submit(() -> {
                    start.await();
                    return rules.resetExecutingToFailed(id, "RESET_RULE_ABSENT: x", NOW);
                });
                assertThat(worker.get() + reset.get()).as("exactly one of them changed the row").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(60)
    void q712_aSlowWorkerWhoseRowWasResetDuringTheApplyLeavesTheResetInPlace() throws Exception {
        ArchiveAudit audit = mock(ArchiveAudit.class);
        ApprovalVerifier verifier = mock(ApprovalVerifier.class);
        when(verifier.verifyById(APPROVAL, ASSET)).thenReturn(approved());
        // the provider call is slow: while it runs, an administrator resets the claimed row (rule absent -> FAILED)
        CountingProvider gcp = new CountingProvider() {
            @Override
            public synchronized ApplyResult apply(RuleSpec spec) {
                assertThat(ArchiveRulesPostgresTest.this.rules.resetExecutingToFailed(spec.ruleId(), "RESET_RULE_ABSENT: reset by an administrator",
                        NOW)).isEqualTo(1);
                return super.apply(spec);
            }
        };
        ArchiveExecutor executor = new ArchiveExecutor(
                new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules), rules, verifier, audit, CLOCK,
                List.of(), coldConfig());
        executor.prepare(new ArchiveExecutor.Request("task-1", ASSET, "gs://pilot/finance/", "GCP",
                Map.of("tier", "COLD"), null, APPROVAL, "fire-1"));

        ArchiveRuleEntity result = executor.execute(APPROVAL, SERVICE);

        assertThat(gcp.applies.get()).as("the rule was written at the provider").isEqualTo(1);
        ArchiveRuleEntity stored = rules.findByApprovalId(APPROVAL).orElseThrow();
        assertThat(stored.getStatus()).as("the late APPLIED did not overwrite the reset").isEqualTo("FAILED");
        assertThat(stored.getLastError()).startsWith("RESET_RULE_ABSENT");
        assertThat(result.getStatus()).isEqualTo("FAILED");
        verify(audit).applied(any(), any(), org.mockito.ArgumentMatchers.argThat(
                m -> Boolean.FALSE.equals(m.get("rowUpdated"))));
    }

    // ---- Q-710: the partial unique index on live GCS rows ---------------------------------------------

    @Test
    void q710_theDatabaseRefusesASecondLiveRealGcsRowForOneScopeAndFreesItWhenTheHolderLeavesTheLiveSet() {
        planned("dlap-archive-uniq10000", "appr-uniq1", "gs://pilot/finance/");

        assertThatThrownBy(() -> planned("dlap-archive-uniq20000", "appr-uniq2", "gs://pilot/finance/"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ux_archive_rules_live_gcs_scope");

        assertThat(rules.endIfPlanned("dlap-archive-uniq10000", "REFUSED", "x", NOW)).isEqualTo(1);
        planned("dlap-archive-uniq30000", "appr-uniq3", "gs://pilot/finance/"); // the scope is free again
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autoarchival.dlap_archive_rules",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @Timeout(60)
    void q710_twoApprovalsPlannedInTheSameInstantStoreOneRowAndTheLoserIsToldDuplicateScope() throws Exception {
        CyclicBarrier bothPassedTheCheck = new CyclicBarrier(2);
        // The planner reads the live rows first and asks the provider for its inventory after that, so a barrier in
        // the inventory holds both callers between the read and the insert: the window the index closes.
        CountingProvider gcp = new CountingProvider() {
            @Override
            public Inventory inventory(RuleSpec spec) {
                try {
                    bothPassedTheCheck.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return super.inventory(spec);
            }
        };
        RulePlanner planner = new RulePlanner(List.of(gcp), new ArchiveRuleProperties(), rules);
        ArchiveExecutor executor = new ArchiveExecutor(planner, rules, mock(ApprovalVerifier.class),
                mock(ArchiveAudit.class), CLOCK, List.of(), coldConfig());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> outcomes = new ArrayList<>();
            for (String approval : List.of("appr-race-a", "appr-race-b")) {
                outcomes.add(pool.submit(() -> {
                    try {
                        return executor.prepare(new ArchiveExecutor.Request("task-" + approval, ASSET,
                                "gs://pilot/finance/", "GCP", Map.of("tier", "COLD"), null, approval, null));
                    } catch (com.dlap.autoarchival.rule.RuleException e) {
                        return e;
                    }
                }));
            }
            int stored = 0;
            int duplicates = 0;
            for (Future<Object> f : outcomes) {
                Object o = f.get(30, TimeUnit.SECONDS);
                if (o instanceof ArchiveRuleEntity) {
                    stored++;
                } else {
                    duplicates++;
                    assertThat(o).isInstanceOf(com.dlap.autoarchival.rule.DuplicateScopeException.class);
                    assertThat(((com.dlap.autoarchival.rule.DuplicateScopeException) o).reason())
                            .isEqualTo(com.dlap.autoarchival.rule.RuleException.Reason.DUPLICATE_SCOPE);
                }
            }
            assertThat(stored).as("exactly one row was stored").isEqualTo(1);
            assertThat(duplicates).as("the other was told DUPLICATE_SCOPE").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autoarchival.dlap_archive_rules", Integer.class))
                .isEqualTo(1);
    }

    // ---- Q-673 ------------------------------------------------------------------------------------

    @Test
    void q673_theLiveScopeQueryFindsOnlyLiveRealRowsOfTheIdenticalScope() {
        java.util.Set<String> live = java.util.Set.of("PLANNED", "EXECUTING", "APPLIED", "EXECUTED");
        // Created in this order because V4 (Q-710) lets only one live, real row hold the scope at a time.
        ArchiveRuleEntity mock = planned("dlap-archive-scope3000", "appr-scope3", "gs://pilot/finance/");
        mock.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", true, NOW);   // mocked: wrote nothing
        rules.saveAndFlush(mock);
        ArchiveRuleEntity removed = planned("dlap-archive-scope2000", "appr-scope2", "gs://pilot/finance/");
        removed.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, NOW);
        rules.saveAndFlush(removed);
        rules.markRemovedIf(removed.getRuleId(), "APPLIED", NOW);              // REMOVED: not live
        ArchiveRuleEntity holder = planned("dlap-archive-scope1000", "appr-scope1", "gs://pilot/finance/");
        holder.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, NOW); // GCP pilot finance/ COLDLINE 0: live
        rules.saveAndFlush(holder);

        assertThat(rules.findLiveScope("GCP", "pilot", "finance/", "COLDLINE", 0, live, "dlap-archive-other0000"))
                .extracting(ArchiveRuleEntity::getRuleId).containsExactly("dlap-archive-scope1000");
        assertThat(rules.findLiveScope("GCP", "pilot", "finance/", "COLDLINE", 0, live, "dlap-archive-scope1000"))
                .as("a row never collides with itself").isEmpty();
        assertThat(rules.findLiveScope("GCP", "pilot", "finance/", "ARCHIVE", 0, live, "x")).isEmpty();
        assertThat(rules.findLiveScope("GCP", "pilot", "finance/", "COLDLINE", 30, live, "x")).isEmpty();
        assertThat(rules.findLiveScope("GCP", "pilot", "other/", "COLDLINE", 0, live, "x")).isEmpty();
    }

    private List<String> snapshot() {
        return jdbc.queryForList("SELECT rule_id || '|' || status || '|' || already_present || '|' || mock || '|' "
                + "|| coalesce(applied_at::text, '') || '|' || updated_at::text FROM dlap_autoarchival.dlap_archive_rules "
                + "ORDER BY rule_id", String.class);
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
        throw new IllegalStateException("00-create-multiple-databases.sh not found above "
                + System.getProperty("user.dir"));
    }
}
