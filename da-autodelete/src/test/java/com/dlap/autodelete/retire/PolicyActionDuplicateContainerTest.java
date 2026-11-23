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

import static com.dlap.autodelete.retire.Approvals.ALICE;
import static com.dlap.autodelete.retire.Approvals.BOB;
import static com.dlap.autodelete.retire.Approvals.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.kafka.PolicyActionEventListener;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.service.impl.DeletionServiceImpl;
import com.dlap.common.audit.AuditOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
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

/**
 * IDEM-01 (Q-856): {@link PolicyActionEventListener} admits a repeated {@code fireId} to the soft-delete window
 * once. Delivery on {@code dlap.policies.actions} is at least once and the policy engine republishes a fire with
 * the same {@code fireId} when its source event is redelivered. The listener, the service, the admission and the
 * repository are the module's own on PostgreSQL 15.1 with the module's Flyway schema; the approval client, the
 * catalog client and the audit log are doubles. The clock moves between deliveries, so a second admission would
 * show in the timestamps. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = PolicyActionDuplicateContainerTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class PolicyActionDuplicateContainerTest {

    private static final Instant FIRST = Instant.parse("2026-12-03T01:00:05Z");
    private static final UUID ASSET = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID APPROVAL = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000002");
    private static final String FIRE = "9a9a9a9a-0000-4000-8000-000000000f1e";
    private static final String POLICY_DB = "5e5e5e5e-0000-4000-8000-000000000042";

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(FIRST);

        void set(Instant instant) {
            now.set(instant);
        }

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
            return now.get();
        }
    }

    /** JPA, the repository, the admission, the service and the listener; the remote clients are doubles. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.autodelete.model", "com.dlap.autodelete.domain"})
    @EnableJpaRepositories(basePackageClasses = SoftDeleteRecordRepository.class)
    @Import({SoftDeleteAdmission.class, DeletionServiceImpl.class, PolicyActionEventListener.class})
    static class Config {

        @Bean
        MovableClock clock() {
            return new MovableClock();
        }

        @Bean
        RetireAuditLog audit() {
            return mock(RetireAuditLog.class);
        }

        @Bean
        ApprovalClient approvals() {
            return mock(ApprovalClient.class);
        }

        @Bean
        CatalogFactsClient catalog() {
            return mock(CatalogFactsClient.class);
        }

        @Bean
        RetireProperties retireProperties() {
            return RetireProperties.defaults();
        }

        @Bean
        RetireSweeper sweeper() {
            return mock(RetireSweeper.class);
        }

        @Bean
        RuleRemover ruleRemover() {
            return mock(RuleRemover.class);
        }

        @Bean
        ApprovalWithdrawer withdrawer() {
            return mock(ApprovalWithdrawer.class);
        }
    }

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
        registry.add("spring.datasource.url", PolicyActionDuplicateContainerTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "da_autodelete_user");
        registry.add("spring.datasource.password", () -> "da_autodelete_pass");
    }

    @Autowired
    private PolicyActionEventListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovableClock clock;

    @Autowired
    private RetireAuditLog audit;

    @Autowired
    private ApprovalClient approvals;

    @Autowired
    private CatalogFactsClient catalog;

    private final Acknowledgment ack = mock(Acknowledgment.class);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_autodelete.retire_dispositions");
        jdbc.update("DELETE FROM dlap_autodelete.soft_delete_records");
        clock.set(FIRST);
        org.mockito.Mockito.reset(audit, approvals, catalog, ack);
        when(approvals.read(APPROVAL)).thenReturn(Optional.of(view(APPROVAL, ASSET, "APPROVED", "SOFT_DELETE", 2,
                "policy:" + POLICY_DB, FIRE, ALICE, BOB)));
        when(catalog.read(ASSET)).thenReturn(Optional.of(new AssetFacts(ASSET, "gs://dlap-gcp-e2e-raw", "GCP",
                "GCS_BUCKET", false, "FULL", List.of(), 2048L)));
    }

    private static Map<String, Object> deleteAction(boolean withApproval) {
        Map<String, Object> e = new HashMap<>();
        e.put("actionType", "delete");
        e.put("assetId", ASSET.toString());
        e.put("assetUri", "GS://Dlap-GCP-E2E-Raw");
        e.put("provider", "GCP");
        e.put("serviceType", "GCS_BUCKET");
        e.put("fireId", FIRE);
        e.put("policyId", "e2e-gcp-delete");
        e.put("policyDbId", POLICY_DB);
        e.put("requireApproval", false);
        e.put("actionParameters", Map.of());
        if (withApproval) {
            e.put("approval", Map.of("approvalId", APPROVAL.toString(), "status", "APPROVED",
                    "actionClass", "SOFT_DELETE", "requiredApprovals", 2, "approvers", List.of(ALICE, BOB),
                    "breakGlass", false));
        }
        return e;
    }

    private void deliver(Map<String, Object> event) {
        listener.onPolicyAction(event, "dlap.policies.actions", 0L, ack);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM dlap_autodelete.soft_delete_records");
    }

    @Test
    void aRepeatedFireIdAdmitsOneSoftDeleteAndAuditsItOnce() {
        deliver(deleteAction(true));
        List<Map<String, Object>> afterFirst = rows();
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0)).containsEntry("status", "SOFT_DELETED")
                .containsEntry("policy_fire_id", UUID.fromString(FIRE));

        // The copies arrive later; each finds the fire's row and joins it.
        clock.set(FIRST.plusSeconds(3 * 3600));
        deliver(deleteAction(true));
        clock.set(FIRST.plusSeconds(2 * 86400));
        deliver(deleteAction(true));

        assertThat(rows()).as("two more copies change no column of the row").isEqualTo(afterFirst);
        verify(audit, times(1)).record(eq(RetireAuditLog.SOFT_DELETED), any(), any(), any(),
                eq(AuditOutcome.SUCCEEDED), any(), any());
        verify(approvals, times(1)).read(APPROVAL);
        verify(ack, times(3)).acknowledge();
    }

    /**
     * A refusal is not a decision on the fire: the policy engine releases a held action under the fire id it
     * was held with, so the refused first copy must not shadow the approved one.
     */
    @Test
    void aRefusedCopyDoesNotBlockTheSameFireWhenItIsLaterReleasedWithItsApproval() {
        deliver(deleteAction(false));
        assertThat(rows()).isEmpty();
        verify(audit, times(1)).refused(any(), any(), any(), eq(FIRE), eq("soft_delete"),
                eq(RefusalReason.APPROVAL_MISSING), any(), any());

        deliver(deleteAction(true));
        deliver(deleteAction(true));

        assertThat(rows()).hasSize(1);
        verify(audit, times(1)).record(eq(RetireAuditLog.SOFT_DELETED), any(), any(), any(),
                eq(AuditOutcome.SUCCEEDED), any(), any());
    }

    /**
     * Two copies in flight at once (two partitions, or a rebalance) both pass the lookup. The database lets one
     * insert win; the other fails its insert and is redelivered, finds the row and joins it. One row, one audit
     * record, and no copy is acknowledged as admitted twice.
     */
    @Test
    void twoCopiesInFlightAtOnceAdmitOneRowAndTheLoserJoinsItOnRedelivery() throws Exception {
        CyclicBarrier bothLooked = new CyclicBarrier(2);
        when(catalog.read(ASSET)).thenAnswer(inv -> {
            bothLooked.await(20, TimeUnit.SECONDS);
            return Optional.of(new AssetFacts(ASSET, "gs://dlap-gcp-e2e-raw", "GCP", "GCS_BUCKET", false, "FULL",
                    List.of(), 2048L));
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    try {
                        deliver(deleteAction(true));
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> f : results) {
                Throwable t = f.get(60, TimeUnit.SECONDS);
                if (t != null) {
                    failures.add(t);
                }
            }
            assertThat(failures.size()).as("at most the loser fails, and it fails as a retryable error")
                    .isLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows()).hasSize(1);

        // the redelivery of the loser (or of a late copy) joins the row
        org.mockito.Mockito.clearInvocations(approvals);
        deliver(deleteAction(true));

        assertThat(rows()).hasSize(1);
        verify(approvals, never()).read(APPROVAL);
        verify(audit, times(1)).record(eq(RetireAuditLog.SOFT_DELETED), any(), any(), any(),
                eq(AuditOutcome.SUCCEEDED), any(), any());
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
        throw new IllegalStateException(
                "00-create-multiple-databases.sh not found above " + System.getProperty("user.dir"));
    }
}
