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

import com.dlap.logingest.repository.LogEventPartitionRepository;
import com.dlap.logingest.repository.LogEventPartitionRepository.MonthlyPartition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the local replay store ({@code dlap_logingest.log_events}) to the configured retention
 * window, by month partitions of {@code ingest_time} (ADR-0060, hardening critic item 20).
 *
 * <p>Each run:
 * <ol>
 *   <li>creates the partitions of the current month and of {@code retention-partitions-ahead}
 *       months after it, moving any row already waiting for them out of the default partition;</li>
 *   <li>drops every monthly partition whose whole range lies before the cutoff
 *       ({@code now - retention-days}): a catalog change per month, no row scan;</li>
 *   <li>deletes rows of the default partition ingested before the cutoff.</li>
 * </ol>
 * Retention keys on {@code ingest_time}, not {@code event_time}, so a backfill of old events is
 * kept for the full window from the day it lands (log-event spec section 14). Granularity is one
 * month: a row lives between {@code retention-days} and {@code retention-days} plus one month.
 *
 * <h3>Guards</h3>
 * <ul>
 *   <li>A non-positive {@code retention-days} disables pruning instead of computing a cutoff at or
 *       after now; partitions are still created.</li>
 *   <li>{@code retention-enabled=false} turns pruning off; partitions are still created.</li>
 *   <li>The scheduled run is a cluster-wide singleton (ShedLock JDBC, database time, plan rule R7):
 *       with several replicas one of them sweeps per cron tick.</li>
 *   <li>A failure is logged and swallowed: housekeeping must never take the ingest path down; a
 *       month without a partition lands in the default partition and is moved later.</li>
 * </ul>
 *
 * <p>Pruning only affects the local replay copy; the published events are a Kafka concern.
 */
@Component
public class LogEventRetentionSweeper {

    /** Name of the ShedLock row that serializes the sweep across replicas. */
    public static final String LOCK_NAME = "logingest-log-event-retention";

    /**
     * Longest a sweep may hold the lock: at least twice an expected run (plan rule R7). A run is a
     * handful of catalog changes plus one delete on the default partition.
     */
    public static final String LOCK_AT_MOST_FOR = "PT2H";

    private static final Logger log = LoggerFactory.getLogger(LogEventRetentionSweeper.class);

    private final LogEventPartitionRepository partitions;
    private final Clock clock;

    @Value("${dlap.logingest.retention-days:90}")
    private int retentionDays;

    @Value("${dlap.logingest.retention-enabled:true}")
    private boolean retentionEnabled;

    @Value("${dlap.logingest.retention-partitions-ahead:2}")
    private int partitionsAhead;

    /**
     * What one run did.
     *
     * @param partitionsCreated  monthly partitions created
     * @param partitionsDropped  monthly partitions dropped
     * @param defaultRowsDeleted rows deleted from the default partition
     * @param cutoff             the retention cutoff, or {@code null} when pruning did not run
     */
    public record Result(int partitionsCreated, int partitionsDropped, int defaultRowsDeleted, Instant cutoff) {
    }

    /**
     * Creates the sweeper.
     *
     * @param partitions the partition maintenance repository
     * @param clock      the application clock when unique, else the UTC system clock
     */
    @Autowired
    public LogEventRetentionSweeper(LogEventPartitionRepository partitions, ObjectProvider<Clock> clock) {
        this(partitions, clock.getIfUnique(Clock::systemUTC));
    }

    /**
     * Creates the sweeper with a fixed clock.
     *
     * @param partitions the partition maintenance repository
     * @param clock      the clock
     */
    LogEventRetentionSweeper(LogEventPartitionRepository partitions, Clock clock) {
        this.partitions = partitions;
        this.clock = clock;
    }

    /**
     * Scheduled entry point, a cluster-wide singleton.
     */
    @Scheduled(cron = "${dlap.logingest.retention-cron:0 30 3 * * ?}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = "PT1M")
    public void scheduledPrune() {
        try {
            prune();
        } catch (RuntimeException e) {
            // Housekeeping must never break ingest; the next fire retries.
            log.error("Log-event retention sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Creates this month's and the next months' partitions when the service starts, so the first
     * rows do not wait in the default partition for the nightly run.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensurePartitionsAtStartup() {
        try {
            int created = ensureUpcomingPartitions();
            if (created > 0) {
                log.info("Created {} log_events partition(s) at startup", created);
            }
        } catch (RuntimeException e) {
            log.error("Could not create log_events partitions at startup; rows land in the default partition "
                    + "until the next sweep: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs one sweep.
     *
     * @return what the sweep did
     */
    public Result prune() {
        int created = ensureUpcomingPartitions();
        if (!retentionEnabled) {
            log.debug("Log-event retention pruning is disabled (dlap.logingest.retention-enabled=false)");
            return new Result(created, 0, 0, null);
        }
        if (retentionDays <= 0) {
            log.warn("dlap.logingest.retention-days={} is not positive; skipping the retention sweep rather than "
                    + "deleting the whole replay store", retentionDays);
            return new Result(created, 0, 0, null);
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int dropped = 0;
        for (MonthlyPartition p : partitions.monthlyPartitions()) {
            if (!p.upperBound().isAfter(cutoff)) {
                partitions.drop(p);
                dropped++;
                log.info("Log-event retention: dropped partition {} (ingested before {})", p.name(), p.upperBound());
            }
        }
        int defaultRows = partitions.deleteDefaultRowsBefore(cutoff);
        if (dropped > 0 || defaultRows > 0) {
            log.info("Log-event retention sweep: dropped {} partition(s) and {} default-partition row(s) ingested "
                    + "before {} ({} days)", dropped, defaultRows, cutoff, retentionDays);
        } else {
            log.debug("Log-event retention sweep: nothing ingested before {} ({} days)", cutoff, retentionDays);
        }
        return new Result(created, dropped, defaultRows, cutoff);
    }

    /**
     * Creates the partitions of the current month and the configured months after it.
     *
     * @return how many were created
     */
    int ensureUpcomingPartitions() {
        YearMonth current = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        int ahead = Math.max(0, partitionsAhead);
        int created = 0;
        for (int i = 0; i <= ahead; i++) {
            if (partitions.ensureMonthlyPartition(current.plusMonths(i))) {
                created++;
            }
        }
        return created;
    }

    /** Enables {@link SchedulerLock} and supplies the JDBC lock provider (lock table: Flyway V3). */
    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = LOCK_AT_MOST_FOR)
    public static class ShedLockConfiguration {

        private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

        /**
         * The lock provider: database time, so replica clock skew cannot shorten or extend a lock;
         * the table is addressed by schema because the provider issues plain JDBC.
         *
         * @param dataSource the service datasource
         * @param schema     the service schema
         * @return the provider
         */
        @Bean
        LockProvider logIngestLockProvider(DataSource dataSource,
                @Value("${spring.jpa.properties.hibernate.default_schema:dlap_logingest}") String schema) {
            if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
                throw new IllegalArgumentException("Invalid schema name for the ShedLock table: " + schema);
            }
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .withTableName(schema + ".shedlock")
                    .usingDbTime()
                    .build());
        }
    }
}
