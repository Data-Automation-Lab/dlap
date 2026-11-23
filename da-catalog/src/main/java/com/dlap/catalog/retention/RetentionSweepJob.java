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

package com.dlap.catalog.retention;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled retention sweep, a cluster-wide singleton (plan rule R7): with several catalog
 * replicas one of them runs each tick, under a ShedLock JDBC lock on database time
 * ({@link RetentionLockConfiguration}). The lock is held for at most
 * {@code dlap.catalog.retention.lock-at-most-for} (default {@code PT2H}, at least twice the
 * expected run) and for at least a minute, so a second replica whose clock fires a moment later
 * does not start a second pass.
 *
 * <p>Off with {@code dlap.catalog.retention.enabled=false}. The pass itself is
 * {@link RetentionSweep#run()}.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionSweepJob {

    /** Name of the ShedLock row that serializes the sweep across replicas. */
    public static final String LOCK_NAME = "catalog-retention-sweep";

    private static final Logger log = LoggerFactory.getLogger(RetentionSweepJob.class);

    private final RetentionSweep sweep;

    /**
     * Creates the job.
     *
     * @param sweep the sweep
     */
    public RetentionSweepJob(RetentionSweep sweep) {
        this.sweep = sweep;
    }

    /** Scheduled entry point; a failure is logged and the next tick tries again. */
    @Scheduled(cron = "${dlap.catalog.retention.cron:0 15 * * * *}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "${dlap.catalog.retention.lock-at-most-for:PT2H}",
            lockAtLeastFor = "PT1M")
    public void scheduledSweep() {
        try {
            sweep.run();
        } catch (RuntimeException e) {
            log.error("Retention sweep failed; the next scheduled run starts again: {}", e.toString(), e);
        }
    }
}
