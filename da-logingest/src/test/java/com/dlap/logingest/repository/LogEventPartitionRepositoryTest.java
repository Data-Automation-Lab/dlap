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

package com.dlap.logingest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.repository.LogEventPartitionRepository.MonthlyPartition;
import java.time.Instant;
import java.time.YearMonth;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Partition maintenance of {@code log_events} on a real PostgreSQL (Testcontainers; requires
 * Docker): months are created once, rows waiting in the default partition move into the month's
 * partition when it is created, whole partitions drop, and only conventionally named partitions
 * can be dropped.
 */
class LogEventPartitionRepositoryTest {

    private JdbcTemplate jdbc;
    private LogEventPartitionRepository repo;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        jdbc = new JdbcTemplate(ds);
        repo = new LogEventPartitionRepository(jdbc, LogIngestPostgres.SCHEMA);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private boolean ensure(YearMonth month) {
        return Boolean.TRUE.equals(tx.execute(s -> repo.ensureMonthlyPartition(month)));
    }

    @Test
    void createsAMonthOnceAndListsItInOrder() {
        YearMonth far = YearMonth.of(2031, 7);

        assertThat(ensure(far)).isTrue();
        assertThat(ensure(far)).isFalse();

        assertThat(repo.isPartitioned()).isTrue();
        assertThat(repo.monthlyPartitions()).extracting(MonthlyPartition::month).contains(far).isSorted();
    }

    @Test
    void rowsWaitingInTheDefaultPartitionMoveIntoTheNewMonth() {
        YearMonth far = YearMonth.of(2032, 3);
        Instant inMonth = Instant.parse("2032-03-15T12:00:00Z");
        LogIngestMigrationTest.insertEvent(jdbc, "waiting", inMonth, inMonth);
        assertThat(repo.defaultRowCount()).isEqualTo(1);

        assertThat(ensure(far)).isTrue();

        assertThat(repo.defaultRowCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT tableoid::regclass::text FROM dlap_logingest.log_events "
                + "WHERE event_id = 'waiting'", String.class)).endsWith("log_events_p2032_03");
        // The attached partition enforces the key and the event-id lookup like any other.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE event_id = 'waiting'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void dropRemovesAWholeMonthAndDefaultRowsAreDeletedByCutoff() {
        YearMonth month = YearMonth.of(2030, 1);
        tx.execute(s -> repo.ensureMonthlyPartition(month));
        Instant inMonth = Instant.parse("2030-01-10T00:00:00Z");
        LogIngestMigrationTest.insertEvent(jdbc, "in-jan", inMonth, inMonth);
        Instant orphan = Instant.parse("2001-01-01T00:00:00Z");
        LogIngestMigrationTest.insertEvent(jdbc, "orphan", orphan, orphan);

        repo.drop(new MonthlyPartition(LogEventPartitionRepository.partitionName(month), month));

        assertThat(repo.monthlyPartitions()).extracting(MonthlyPartition::month).doesNotContain(month);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE event_id = 'in-jan'",
                Integer.class)).isZero();
        assertThat(repo.deleteDefaultRowsBefore(Instant.parse("2002-01-01T00:00:00Z"))).isEqualTo(1);
    }

    @Test
    void onlyMonthlyPartitionsCanBeDropped() {
        assertThatThrownBy(() -> repo.drop(new MonthlyPartition("log_events_default", YearMonth.of(2030, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.drop(new MonthlyPartition("log_events_p2030_02", YearMonth.of(2030, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(LogEventPartitionRepository.monthOf("log_events_p2030_13")).isEmpty();
        assertThat(LogEventPartitionRepository.monthOf("log_events_p2030_12")).contains(YearMonth.of(2030, 12));
    }
}
