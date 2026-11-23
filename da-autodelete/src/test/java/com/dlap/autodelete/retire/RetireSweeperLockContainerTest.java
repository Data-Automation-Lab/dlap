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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
import org.springframework.scheduling.annotation.Scheduled;
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
 * Q-548 / RET-02 on PostgreSQL 15.1 with the module's Flyway schema: two replicas never act on
 * the same row. Two sweepers run at the same moment against the same due rows, a row another
 * transaction holds is skipped instead of waited for, and the ShedLock provider the service wires
 * (database time, Flyway V5 table) lets one replica hold the sweep lock at a time. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = RetireSweeperLockContainerTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class RetireSweeperLockContainerTest {

    /** JPA and the repositories only. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.autodelete.model", "com.dlap.autodelete.domain"})
    @EnableJpaRepositories(basePackageClasses = SoftDeleteRecordRepository.class)
    static class Config {
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
        registry.add("spring.datasource.url", RetireSweeperLockContainerTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "da_autodelete_user");
        registry.add("spring.datasource.password", () -> "da_autodelete_pass");
    }

    @Autowired
    private SoftDeleteRecordRepository records;

    @Autowired
    private DeletionTaskRepository tasks;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_autodelete.retire_dispositions");
        jdbc.update("DELETE FROM dlap_autodelete.soft_delete_records");
    }

    private List<UUID> dueRows(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO dlap_autodelete.soft_delete_records (id, asset_id, queued_at,"
                    + " scheduled_hard_delete_at, status, cloud_provider, asset_uri) VALUES (?, ?, ?, ?, 'SOFT_DELETED',"
                    + " 'GCP', ?)", id, UUID.randomUUID(), Timestamp.from(Instant.now().minus(Duration.ofDays(31))),
                    Timestamp.from(Instant.now().minus(Duration.ofMinutes(i + 1))), "gs://bucket-" + i);
            ids.add(id);
        }
        return ids;
    }

    /** An executor that records each row it is handed and moves it on, slowly enough to overlap. */
    private HardDeleteExecutor recordingExecutor(Set<UUID> handled, List<UUID> duplicates) {
        HardDeleteExecutor executor = mock(HardDeleteExecutor.class);
        doAnswer(inv -> {
            SoftDeleteRecordEntity row = inv.getArgument(0);
            if (!handled.add(row.getId())) {
                duplicates.add(row.getId());
            }
            Thread.sleep(15);
            row.setStatus(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
            records.saveAndFlush(row);
            return null;
        }).when(executor).handle(any());
        return executor;
    }

    private RetireSweeper sweeper(HardDeleteExecutor executor) {
        return new RetireSweeper(records, tasks, executor, mock(TaskApprovalProcessor.class),
                RetireProperties.defaults(), clock, txManager);
    }

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void twoSweepersRunningAtOnceHandleEveryDueRowExactlyOnce() throws Exception {
        List<UUID> ids = dueRows(40);
        Set<UUID> handled = ConcurrentHashMap.newKeySet();
        List<UUID> duplicates = Collections.synchronizedList(new ArrayList<>());
        RetireSweeper a = sweeper(recordingExecutor(handled, duplicates));
        RetireSweeper b = sweeper(recordingExecutor(handled, duplicates));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> fa = pool.submit(() -> {
                start.await();
                return a.sweep();
            });
            Future<Integer> fb = pool.submit(() -> {
                start.await();
                return b.sweep();
            });
            start.countDown();
            int total = fa.get(60, TimeUnit.SECONDS) + fb.get(60, TimeUnit.SECONDS);

            assertThat(duplicates).as("rows handled twice").isEmpty();
            assertThat(handled).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(total).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRowAnotherTransactionHoldsIsSkippedNotWaitedFor() throws Exception {
        List<UUID> ids = dueRows(3);
        UUID held = ids.get(0);
        Set<UUID> handled = ConcurrentHashMap.newKeySet();
        try (Connection other = DriverManager.getConnection(jdbcUrl(), "da_autodelete_user", "da_autodelete_pass")) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT id FROM dlap_autodelete.soft_delete_records WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery();
            }
            long started = System.nanoTime();
            int done = sweeper(recordingExecutor(handled, new ArrayList<>())).sweep();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(done).isEqualTo(2);
            assertThat(handled).containsExactlyInAnyOrder(ids.get(1), ids.get(2)).doesNotContain(held);
            assertThat(tookMs).as("the sweep did not wait for the held row").isLessThan(10_000);
            other.rollback();
        }
        Set<UUID> later = new HashSet<>();
        assertThat(sweeper(recordingExecutor(later, new ArrayList<>())).sweep()).isEqualTo(1);
        assertThat(later).containsExactly(held);
    }

    @Test
    void theSweepLockIsHeldByOneReplicaAtATimeOnDatabaseTime() {
        LockProvider replicaA = new RetireConfiguration().autodeleteLockProvider(dataSource, "dlap_autodelete");
        LockProvider replicaB = new RetireConfiguration().autodeleteLockProvider(dataSource, "dlap_autodelete");
        LockConfiguration config = new LockConfiguration(Instant.now(), RetireSweeper.LOCK_NAME,
                Duration.ofMinutes(10), Duration.ZERO);

        SimpleLock lock = replicaA.lock(config).orElseThrow();
        assertThat(replicaB.lock(config)).as("a second replica while the first holds it").isEmpty();
        lock.unlock();
        assertThat(replicaB.lock(config)).as("after release").isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_autodelete.shedlock WHERE name = ?", Integer.class,
                RetireSweeper.LOCK_NAME)).isEqualTo(1);
    }

    @Test
    void theScheduledSweepCarriesItsShedLock() throws NoSuchMethodException {
        Method m = RetireSweeper.class.getMethod("scheduledSweep");
        assertThat(m.getAnnotation(Scheduled.class)).isNotNull();
        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo(RetireSweeper.LOCK_NAME);
        assertThat(lock.lockAtMostFor()).isNotBlank();
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
