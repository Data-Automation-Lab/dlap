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

package com.dlap.discovery.coverage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * Runs every {@link ReadCoverageCollector} on {@code dlap.discovery.coverage.cron} and publishes
 * the observations (COV-01). A cluster-wide singleton: the run holds the ShedLock JDBC lock
 * {@value #LOCK_NAME} on database time in da-discovery's {@code shedlock} table (Flyway V10), so
 * with several replicas one of them observes per tick.
 *
 * <p>The observation time is the injected clock when a collector starts. It is an operational
 * time (when DLAP read the configuration), the one time a coverage observation has; the catalog
 * uses it only to order observations and to bound how long a {@code FULL} counts as proven.
 *
 * <p>A collector that fails as a whole publishes nothing, so the catalog keeps what it had; the
 * failure is logged and counted. Metrics: {@value #METER_RUNS} (tag {@code outcome}: completed,
 * locked) and {@value #METER_OBSERVATIONS} (tags {@code collector}, {@code level}, {@code outcome}:
 * sent, unacknowledged, failed).
 */
public class ReadCoverageCollectionJob {

    private static final Logger log = LoggerFactory.getLogger(ReadCoverageCollectionJob.class);

    /** ShedLock name of the run. */
    public static final String LOCK_NAME = "discovery-read-coverage";
    /** Runs. Prometheus name {@code dlap_discovery_coverage_runs_total}. */
    public static final String METER_RUNS = "dlap.discovery.coverage.runs";
    /** Observations. Prometheus name {@code dlap_discovery_coverage_observations_total}. */
    public static final String METER_OBSERVATIONS = "dlap.discovery.coverage.observations";

    private final List<ReadCoverageCollector> collectors;
    private final ReadCoveragePublisher publisher;
    private final LockingTaskExecutor lock;
    private final ReadCoverageProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param collectors the enabled collectors
     * @param publisher  sends observations
     * @param lock       the cluster-wide lock
     * @param properties the settings
     * @param meters     the meter registry
     * @param clock      the observation clock
     */
    public ReadCoverageCollectionJob(List<ReadCoverageCollector> collectors, ReadCoveragePublisher publisher,
                                     LockingTaskExecutor lock, ReadCoverageProperties properties,
                                     MeterRegistry meters, Clock clock) {
        this.collectors = List.copyOf(collectors);
        this.publisher = publisher;
        this.lock = lock;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** Scheduled entry point: one run under the cluster-wide lock. */
    @Scheduled(cron = "${dlap.discovery.coverage.cron:0 41 0/6 * * *}")
    public void scheduledRun() {
        LockConfiguration config = new LockConfiguration(clock.instant(), LOCK_NAME,
                properties.getLockAtMostFor(), properties.getLockAtLeastFor());
        LockingTaskExecutor.TaskResult<Integer> result;
        try {
            result = lock.executeWithLock(this::runOnce, config);
        } catch (Throwable t) {
            // runOnce handles collector failures itself; anything here is a lock-store failure.
            log.error("Read-coverage run failed: {}", t.toString());
            meters.counter(METER_RUNS, "outcome", "failed").increment();
            return;
        }
        meters.counter(METER_RUNS, "outcome", result.wasExecuted() ? "completed" : "locked").increment();
    }

    /**
     * Runs every collector once and publishes the observations.
     *
     * @return the number of observations the broker acknowledged
     */
    public int runOnce() {
        if (collectors.isEmpty()) {
            log.info("Read coverage: no collector is enabled (dlap.discovery.coverage.<cloud>.enabled)");
            return 0;
        }
        int acknowledged = 0;
        for (ReadCoverageCollector collector : collectors) {
            Instant observedAt = clock.instant();
            List<ReadCoverageObservation> observations;
            try {
                observations = collector.collect();
            } catch (RuntimeException e) {
                log.error("Read-coverage collector {} failed; nothing published for it: {}", collector.id(),
                        e.toString());
                meters.counter(METER_OBSERVATIONS, "collector", collector.id(), "level", "all",
                        "outcome", "failed").increment();
                continue;
            }
            int ok = publisher.publish(observations, observedAt);
            acknowledged += ok;
            for (ReadCoverageObservation o : observations) {
                String level = o.verdict().known() ? o.verdict().level().name() : CoverageLevel.UNKNOWN;
                meters.counter(METER_OBSERVATIONS, "collector", collector.id(),
                        "level", level.toLowerCase(Locale.ROOT), "outcome", "sent").increment();
            }
            if (ok < observations.size()) {
                meters.counter(METER_OBSERVATIONS, "collector", collector.id(), "level", "all",
                        "outcome", "unacknowledged").increment(observations.size() - ok);
            }
            log.info("Read coverage: collector {} published {} of {} observations", collector.id(), ok,
                    observations.size());
        }
        return acknowledged;
    }
}
