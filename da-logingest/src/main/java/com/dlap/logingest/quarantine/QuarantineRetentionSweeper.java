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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes quarantine rows older than the retention (30 days by default, counted from
 * {@code quarantined_at}). The raw record is not kept, only a masked excerpt, so the retention
 * bounds how long even that stays.
 *
 * <p>The scheduled run is a cluster-wide singleton: ShedLock JDBC on database time (plan rule R7;
 * the lock provider and the lock table come with {@code LogEventRetentionSweeper} and Flyway V3).
 * Deletion runs in batches of {@code retention-batch} rows so a large backlog never holds one long
 * lock. A failure is logged and swallowed: housekeeping must not take ingest down, and the next fire
 * retries. Replayed and pending rows are treated alike; a pending row that nobody replayed in
 * 30 days is still on the topic and in the source log.
 */
@Component
public class QuarantineRetentionSweeper {

    /** Name of the ShedLock row that serializes the sweep across replicas. */
    public static final String LOCK_NAME = "logingest-quarantine-retention";

    /** Longest a sweep may hold the lock: at least twice an expected run (plan rule R7). */
    public static final String LOCK_AT_MOST_FOR = "PT2H";

    private static final Logger log = LoggerFactory.getLogger(QuarantineRetentionSweeper.class);

    private final QuarantineRecordRepository repository;
    private final QuarantineProperties props;
    private final Clock clock;

    /**
     * Creates the sweeper.
     *
     * @param repository the quarantine table
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public QuarantineRetentionSweeper(QuarantineRecordRepository repository, QuarantineProperties props,
                                      ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /** Scheduled entry point, a cluster-wide singleton. */
    @Scheduled(cron = "${dlap.logingest.quarantine.retention-cron:0 45 3 * * ?}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = "PT1M")
    public void scheduledPrune() {
        try {
            prune();
        } catch (DataAccessException e) {
            log.error("Quarantine retention sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs one sweep.
     *
     * @return rows deleted; 0 when the sweep is off
     */
    public int prune() {
        if (!props.retentionEnabled() || props.retentionDays() <= 0) {
            log.debug("Quarantine retention is off (retention-enabled={}, retention-days={})",
                    props.retentionEnabled(), props.retentionDays());
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(props.retentionDays()));
        int total = 0;
        int deleted;
        do {
            deleted = repository.deleteBefore(cutoff, props.retentionBatch());
            total += deleted;
        } while (deleted >= props.retentionBatch());
        if (total > 0) {
            log.info("Quarantine retention: deleted {} row(s) quarantined before {} ({} days)", total, cutoff,
                    props.retentionDays());
        }
        return total;
    }
}
