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

package com.dlap.logingest.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** The 30-day retention of the quarantine table, tested with a clock and the real table. */
class QuarantineRetentionSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:45:00Z");

    private static JdbcTemplate jdbc;
    private static QuarantineRecordRepository repository;

    private final MutableClock clock = new MutableClock(NOW);

    @BeforeAll
    static void database() {
        jdbc = new JdbcTemplate(LogIngestPostgres.migratedDatabase());
        repository = new QuarantineRecordRepository(jdbc, new ObjectMapper(), LogIngestPostgres.SCHEMA);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_logingest.quarantine_record");
        QuarantineFixtures.insertBinding(jdbc, "b1");
    }

    private QuarantineRetentionSweeper sweeper(QuarantineProperties props) {
        return new QuarantineRetentionSweeper(repository, props,
                new StaticListableBeanFactory(java.util.Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));
    }

    private void store(String text, long index, Duration age) {
        repository.upsert(QuarantineFixtures.exact("b1", text, QuarantineReason.QUARANTINE_REASON_INVALID_JSON, index,
                NOW.minus(age)), NOW.minus(age));
    }

    @Test
    void rowsOlderThanThirtyDaysGoAndYoungerOnesStay() {
        store("{\"a\":1}", 1, Duration.ofDays(10));
        store("{\"a\":2}", 2, Duration.ofDays(30).minusMinutes(1));
        store("{\"a\":3}", 3, Duration.ofDays(30).plusMinutes(1));
        store("{\"a\":4}", 4, Duration.ofDays(90));

        int deleted = sweeper(QuarantineProperties.defaults()).prune();

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).isEqualTo(2);
    }

    @Test
    void aBacklogLargerThanOneBatchIsClearedInSeveralStatements() {
        for (int i = 0; i < 7; i++) {
            store("{\"old\":" + i + "}", i, Duration.ofDays(45).plusSeconds(i));
        }
        store("{\"new\":1}", 99, Duration.ofDays(1));
        QuarantineProperties smallBatches = new QuarantineProperties(true, true, 2048, 30, true, 3, 500);

        int deleted = sweeper(smallBatches).prune();

        assertThat(deleted).isEqualTo(7);
        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).isEqualTo(1);
    }

    @Test
    void aBacklogThatIsAnExactMultipleOfTheBatchTerminates() {
        for (int i = 0; i < 6; i++) {
            store("{\"old\":" + i + "}", i, Duration.ofDays(45).plusSeconds(i));
        }

        int deleted = sweeper(new QuarantineProperties(true, true, 2048, 30, true, 3, 500)).prune();

        assertThat(deleted).isEqualTo(6);
    }

    @Test
    void theSweepCanBeSwitchedOffAndANonPositiveRetentionNeverDeletesEverything() {
        store("{\"a\":1}", 1, Duration.ofDays(365));

        assertThat(sweeper(new QuarantineProperties(true, true, 2048, 30, false, 5000, 500)).prune()).isZero();
        assertThat(sweeper(new QuarantineProperties(true, true, 2048, 0, true, 5000, 500)).prune()).isZero();
        assertThat(sweeper(new QuarantineProperties(true, true, 2048, -5, true, 5000, 500)).prune()).isZero();
        assertThat(repository.count(QuarantineRecordRepository.Filter.ALL)).isEqualTo(1);
    }

    @Test
    void theScheduledEntryPointSwallowsADatabaseFailure() {
        QuarantineRecordRepository broken = new QuarantineRecordRepository(new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://localhost:1/none")),
                new ObjectMapper(), LogIngestPostgres.SCHEMA);
        QuarantineRetentionSweeper s = new QuarantineRetentionSweeper(broken, QuarantineProperties.defaults(),
                new StaticListableBeanFactory(java.util.Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));

        s.scheduledPrune();
    }
}
