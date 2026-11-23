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

package com.dlap.policyengine.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * POL-01 Flyway V6 on a real PostgreSQL: the schedule cursor table and the ShedLock table the
 * sweep's singleton lock uses (plan rule R7, database time).
 */
@Testcontainers
class PolicyScheduleStoreTest {

    private static final String SCHEMA = "dlap_policyengine";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PolicyScheduleStore store;
    private UUID policyId;

    @BeforeEach
    void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        store = new PolicyScheduleStore(dataSource, SCHEMA);
        policyId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".policies (id, name, status, policy_kind, created_at) "
                + "VALUES (?, ?, 'ENABLED', 'DSL', now())",
                policyId, "p-" + policyId);
    }

    @Test
    void v6IsTheLatestMigration() {
        Integer v6 = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history "
                + "WHERE version = '6' AND description = 'shedlock' AND success", Integer.class);
        assertThat(v6).isEqualTo(1);
    }

    @Test
    void theBaselineIsWrittenOnceAndReadBackInUtc() {
        Instant baseline = Instant.parse("2026-10-20T03:00:00Z");
        store.insertBaseline(policyId, baseline, baseline);
        store.insertBaseline(policyId, Instant.parse("2030-01-01T00:00:00Z"), baseline);

        Optional<PolicyScheduleStore.State> state = store.find(policyId);
        assertThat(state).contains(new PolicyScheduleStore.State(policyId, baseline, baseline));
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRecordedFireMovesTheCursorAndKeepsTheCounts() {
        Instant baseline = Instant.parse("2026-10-19T03:00:00Z");
        Instant slot = Instant.parse("2026-10-20T02:00:00Z");
        store.insertBaseline(policyId, baseline, baseline);

        store.recordFire(policyId, slot, slot.plusSeconds(60), slot.plusSeconds(90),
                new PolicyScheduleStore.FireCounts(5, 1, 2, 1, 0));

        assertThat(store.find(policyId).orElseThrow().lastSlot()).isEqualTo(slot);
        assertThat(jdbc.queryForList("SELECT assets_evaluated, matched, unknown, published, shadowed FROM "
                + SCHEMA + ".policy_schedule_state WHERE policy_id = ?", policyId).get(0))
                .containsEntry("assets_evaluated", 5L).containsEntry("matched", 1L).containsEntry("unknown", 2L)
                .containsEntry("published", 1L).containsEntry("shadowed", 0L);
    }

    /**
     * Since AUD-02a (Flyway V9) a policy is only ever soft deleted: it is disabled, so the sweep never
     * reads it again, and its row stays, so its cursor stays too. A hard delete is refused.
     */
    @Test
    void aPolicyIsNeverHardDeletedSoItsCursorStays() {
        store.insertBaseline(policyId, Instant.parse("2026-10-20T03:00:00Z"), Instant.parse("2026-10-20T03:00:00Z"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".policies WHERE id = ?", policyId))
                .hasMessageContaining("policies are soft deleted");
        assertThat(store.find(policyId)).isPresent();
    }

    @Test
    void theSweepLockIsExclusiveOnTheDatabaseClock() {
        LockProvider provider = new PolicyScheduleConfiguration().policyEngineLockProvider(dataSource, SCHEMA);
        LockConfiguration config = new LockConfiguration(Instant.now(), ScheduledPolicySweeper.LOCK_NAME,
                Duration.ofMinutes(10), Duration.ZERO);

        Optional<SimpleLock> first = provider.lock(config);
        Optional<SimpleLock> second = provider.lock(config);

        assertThat(first).as("the first replica takes the lock").isPresent();
        assertThat(second).as("a second replica is refused while it is held").isEmpty();
        first.get().unlock();
        Optional<SimpleLock> third = provider.lock(config);
        assertThat(third).as("the lock is free again after release").isPresent();
        third.get().unlock();
    }
}
