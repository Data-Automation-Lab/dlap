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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The event-id lock keeps {@code event_id} unique in the partitioned {@code log_events}, whose
 * primary key also holds {@code ingest_time} (Testcontainers; requires Docker): concurrent
 * check-then-insert writers of one id store it once, and the lock refuses to run outside a
 * transaction, where it would end with its own statement.
 */
class LogEventIdLockTest {

    @Test
    void concurrentWritersOfOneIdStoreItOnce() throws Exception {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LogEventIdLockImpl locks = new LogEventIdLockImpl(jdbc);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String id = "a1b2c3d4" + "0".repeat(56);
        int writers = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inserted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            done.add(pool.submit(() -> {
                start.await();
                tx.executeWithoutResult(s -> {
                    locks.lockEventId(id);
                    Integer stored = jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events "
                            + "WHERE event_id = ?", Integer.class, id);
                    if (stored == 0) {
                        // Each writer would insert under its own ingest_time: the key alone would not stop it.
                        Instant ingest = Instant.parse("2026-10-19T00:00:00Z").plusMillis(n);
                        LogIngestMigrationTest.insertEvent(jdbc, id, ingest, ingest);
                        inserted.incrementAndGet();
                    }
                });
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(inserted.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE event_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void theLockNeedsATransaction() {
        LogEventIdLockImpl locks = new LogEventIdLockImpl(new JdbcTemplate(LogIngestPostgres.newDatabase()));

        assertThatThrownBy(() -> locks.lockEventId("ffffffff")).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theKeyIsTheFirst32BitsOfAHexId() {
        assertThat(LogEventIdLockImpl.key("0000000a" + "f".repeat(56))).isEqualTo(10);
        assertThat(LogEventIdLockImpl.key("ffffffff")).isEqualTo(-1);
        assertThat(LogEventIdLockImpl.key("not-hex-id")).isEqualTo("not-hex-id".hashCode());
    }
}
