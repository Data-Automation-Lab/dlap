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

package com.dlap.logingest.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.repository.LogEventPartitionRepository;
import com.dlap.logingest.repository.LogIngestPostgres;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LOG-02bc acceptance (critic item 20): {@code log_events} is partitioned and the sweeper drops
 * whole partitions, keyed by {@code ingest_time} (Testcontainers; requires Docker). A row whose
 * event is two years old but was ingested last week survives; the months wholly before the cutoff
 * are gone as tables, not as deleted rows.
 */
class LogEventRetentionPartitionTest {

    private static final String S = LogIngestPostgres.SCHEMA;

    @Test
    void theSweeperDropsWholeExpiredPartitionsByIngestTime() {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LogEventPartitionRepository repo = new LogEventPartitionRepository(jdbc, S);
        // A clock in 2031 keeps the test independent of the months V3 created at migration time.
        Instant now = Instant.parse("2031-06-15T03:30:00Z");
        for (int m = 1; m <= 6; m++) {
            YearMonth month = YearMonth.of(2031, m);
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds))
                    .execute(s -> repo.ensureMonthlyPartition(month));
        }
        insert(jdbc, "jan", Instant.parse("2029-01-01T00:00:00Z"), Instant.parse("2031-01-20T00:00:00Z"));
        insert(jdbc, "feb", Instant.parse("2031-02-10T00:00:00Z"), Instant.parse("2031-02-10T00:00:00Z"));
        insert(jdbc, "mar", Instant.parse("2031-03-10T00:00:00Z"), Instant.parse("2031-03-10T00:00:00Z"));
        // Two-year-old event, ingested recently (a backfill): kept, retention keys on ingest_time.
        insert(jdbc, "backfill", Instant.parse("2029-06-01T00:00:00Z"), Instant.parse("2031-06-08T00:00:00Z"));
        LogEventRetentionSweeper sweeper = new LogEventRetentionSweeper(repo, Clock.fixed(now, ZoneOffset.UTC));
        ReflectionTestUtils.setField(sweeper, "retentionDays", 90);
        ReflectionTestUtils.setField(sweeper, "retentionEnabled", true);
        ReflectionTestUtils.setField(sweeper, "partitionsAhead", 2);

        LogEventRetentionSweeper.Result r = sweeper.prune();

        // Cutoff 2031-03-17: January and February end before it; March does not.
        assertThat(r.partitionsDropped()).isGreaterThanOrEqualTo(2);
        assertThat(repo.monthlyPartitions()).extracting(p -> p.month())
                .doesNotContain(YearMonth.of(2031, 1), YearMonth.of(2031, 2))
                .contains(YearMonth.of(2031, 3), YearMonth.of(2031, 6), YearMonth.of(2031, 7), YearMonth.of(2031, 8));
        assertThat(jdbc.queryForList("SELECT event_id FROM " + S + ".log_events ORDER BY event_id", String.class))
                .containsExactly("backfill", "mar");
        assertThat(jdbc.queryForObject("SELECT to_regclass('" + S + ".log_events_p2031_01')::text", String.class))
                .as("the January partition is gone as a table").isNull();
    }

    private static void insert(JdbcTemplate jdbc, String id, Instant eventTime, Instant ingestTime) {
        jdbc.update("INSERT INTO " + S + ".log_events (event_id, event_time, ingest_time, source_provider, change_type, "
                        + "operation, schema_version) VALUES (?, ?, ?, 'AWS', 'ACCESS', 'GetObject', 'v1')", id,
                OffsetDateTime.ofInstant(eventTime, ZoneOffset.UTC), OffsetDateTime.ofInstant(ingestTime, ZoneOffset.UTC));
    }
}
