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

package com.dlap.logingest.backfill;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Micrometer meters of backfill jobs (Q-558). Names are the Micrometer names; Prometheus turns
 * dots into underscores, adds {@code _total} to counters and {@code _seconds} to timers:
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Prometheus series</th><th>Type and labels</th></tr>
 *   <tr><td>{@code dlap_logingest_backfill_records_read_total}</td>
 *       <td>counter {@code binding}: records handed to the ingest path. A batch read again after a
 *           crash or a retry counts again, so this is work done, not distinct records.</td></tr>
 *   <tr><td>{@code dlap_logingest_backfill_records_quarantined_total}</td>
 *       <td>counter {@code binding}: records of those that were quarantined</td></tr>
 *   <tr><td>{@code dlap_logingest_backfill_batches_total}</td>
 *       <td>counter {@code binding}: batches that reached their outcomes</td></tr>
 *   <tr><td>{@code dlap_logingest_backfill_objects_failed_total}</td>
 *       <td>counter {@code binding, reason}: objects that failed, {@code read} (the transport could not
 *           read the object) or {@code ingest} (the ingest path threw on it); each failed attempt counts</td></tr>
 *   <tr><td>{@code dlap_logingest_backfill_job_duration_seconds}</td>
 *       <td>timer {@code binding, status}: run time of a job that ended, {@code SUCCEEDED},
 *           {@code FAILED} or {@code CANCELLED}, from its first start to its end</td></tr>
 *   <tr><td>{@code dlap_logingest_backfill_jobs}</td>
 *       <td>gauge {@code status}: jobs in that status now, across bindings (read from the job table,
 *           cached for {@value #GAUGE_TTL_SECONDS} s)</td></tr>
 *   <tr><td>{@code dlap_logingest_backfill_heartbeat_age_seconds}</td>
 *       <td>gauge {@code binding}: seconds since the stored {@code progress.heartbeat} of the oldest
 *           {@code RUNNING} job of that binding (the largest one when several bindings share the label
 *           {@code _all}), 0 while the binding has no {@code RUNNING} job, computed on the injected clock
 *           from the job table and cached for {@value #GAUGE_TTL_SECONDS} s (Q-846). A binding's series
 *           appears after the first read that finds a {@code RUNNING} job of it and then stays. NaN when
 *           the table cannot be read.</td></tr>
 * </table>
 *
 * <p>The {@code binding} label follows the rule of the other log-layer meters: it carries the binding
 * id only while the service has few bindings and reads {@code _all} otherwise, so the series count
 * stays bounded. Recording never throws.
 */
public final class BackfillMetrics {

    /** Records handed to the ingest path. */
    public static final String RECORDS_READ = "dlap.logingest.backfill.records.read";
    /** Records quarantined. */
    public static final String RECORDS_QUARANTINED = "dlap.logingest.backfill.records.quarantined";
    /** Batches committed. */
    public static final String BATCHES = "dlap.logingest.backfill.batches";
    /** Objects that failed. */
    public static final String OBJECTS_FAILED = "dlap.logingest.backfill.objects.failed";
    /** Run time of ended jobs. */
    public static final String JOB_DURATION = "dlap.logingest.backfill.job.duration";
    /** Jobs by status. */
    public static final String JOBS = "dlap.logingest.backfill.jobs";
    /** Age of the oldest heartbeat of the RUNNING jobs, in seconds (the Prometheus name gains {@code _seconds}). */
    public static final String HEARTBEAT_AGE = "dlap.logingest.backfill.heartbeat.age";

    /** The {@code reason} of an object the transport could not read. */
    public static final String REASON_READ = "read";
    /** The {@code reason} of an object the ingest path threw on. */
    public static final String REASON_INGEST = "ingest";

    static final int GAUGE_TTL_SECONDS = 10;

    /** Most RUNNING jobs read for the heartbeat gauge; the table allows one active job per binding. */
    static final int HEARTBEAT_READ_LIMIT = 1000;

    private static final Logger log = LoggerFactory.getLogger(BackfillMetrics.class);

    private final MeterRegistry registry;
    private final Function<String, String> bindingLabel;

    /** Held strongly: a gauge keeps only a weak reference to the object it reads, which would be collected. */
    private StatusCounts statusCounts;

    /** Held strongly for the same reason. */
    private HeartbeatAges heartbeatAges;

    /** The job counts by status and when they were read, shared by the five gauges. */
    private final class StatusCounts {
        private final BackfillJobs jobs;
        private final Clock clock;
        private Map<BackfillStatus, Long> counts = new EnumMap<>(BackfillStatus.class);
        private Instant readAt;

        StatusCounts(BackfillJobs jobs, Clock clock) {
            this.jobs = jobs;
            this.clock = clock;
        }

        synchronized double of(BackfillStatus status) {
            Instant now = clock.instant();
            if (readAt == null || Duration.between(readAt, now).getSeconds() >= GAUGE_TTL_SECONDS) {
                try {
                    Map<BackfillStatus, Long> fresh = new EnumMap<>(BackfillStatus.class);
                    for (BackfillStatus s : BackfillStatus.values()) {
                        fresh.put(s, jobs.count(null, s));
                    }
                    counts = fresh;
                    readAt = now;
                } catch (RuntimeException e) {
                    log.warn("Backfill job counts not read for the gauge: {}", e.toString());
                    return Double.NaN;
                }
            }
            return counts.getOrDefault(status, 0L);
        }
    }

    /**
     * The age of the oldest heartbeat of the {@code RUNNING} jobs by binding label, read from the job
     * table at most every {@value #GAUGE_TTL_SECONDS} s. A gauge is registered the first time a label
     * shows a {@code RUNNING} job, during a read, so it appears on the scrape after that read.
     */
    private final class HeartbeatAges {
        private final BackfillJobs jobs;
        private final Clock clock;
        private final Map<String, Double> ages = new HashMap<>();
        private final Set<String> registered = new HashSet<>();
        private Instant readAt;
        private boolean readFailed;

        HeartbeatAges(BackfillJobs jobs, Clock clock) {
            this.jobs = jobs;
            this.clock = clock;
        }

        /** Reads the table when the cached read is older than the ttl, and registers new labels. */
        synchronized void refresh() {
            Instant now = clock.instant();
            if (readAt != null && Duration.between(readAt, now).getSeconds() < GAUGE_TTL_SECONDS) {
                return;
            }
            try {
                Map<String, Double> fresh = new HashMap<>();
                for (BackfillJob job : jobs.list(null, BackfillStatus.RUNNING, HEARTBEAT_READ_LIMIT, 0)) {
                    fresh.merge(bindingLabel.apply(job.bindingId()), ageSeconds(job, now), Math::max);
                }
                ages.clear();
                ages.putAll(fresh);
                readAt = now;
                readFailed = false;
                for (String label : fresh.keySet()) {
                    if (registered.add(label)) {
                        Gauge.builder(HEARTBEAT_AGE, this, h -> h.of(label)).tags(Tags.of("binding", label))
                                .baseUnit("seconds").strongReference(true)
                                .description("Age of the oldest heartbeat of the RUNNING backfill jobs").register(registry);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Backfill heartbeat ages not read for the gauge: {}", e.toString());
                readFailed = true;
            }
        }

        synchronized double of(String label) {
            refresh();
            return readFailed ? Double.NaN : ages.getOrDefault(label, 0.0);
        }
    }

    /**
     * Seconds from the last stored heartbeat of a running job to now. A job that has not written a
     * heartbeat yet counts from its start, or from its request when it has no start.
     */
    static double ageSeconds(BackfillJob job, Instant now) {
        Instant beat = job.progress().heartbeat();
        if (beat == null) {
            beat = job.startedAt() != null ? job.startedAt() : job.requestedAt();
        }
        if (beat == null || beat.isAfter(now)) {
            return 0.0;
        }
        return Duration.between(beat, now).toMillis() / 1000.0;
    }

    /**
     * Creates the meters.
     *
     * @param registry     the meter registry
     * @param bindingLabel maps a binding id to its label value (the id, or {@code _all})
     */
    public BackfillMetrics(MeterRegistry registry, Function<String, String> bindingLabel) {
        this.registry = registry;
        this.bindingLabel = bindingLabel;
    }

    /**
     * Meters that nobody scrapes, for code that runs a backfill without a Spring context.
     *
     * @return meters on a private registry
     */
    public static BackfillMetrics detached() {
        return new BackfillMetrics(new SimpleMeterRegistry(), b -> b);
    }

    /**
     * Registers the job-status gauges and the heartbeat-age gauges. Call once.
     *
     * @param jobs  the job table
     * @param clock the clock for the cache
     */
    public void bindJobGauges(BackfillJobs jobs, Clock clock) {
        StatusCounts counts = new StatusCounts(jobs, clock);
        this.statusCounts = counts;
        HeartbeatAges ages = new HeartbeatAges(jobs, clock);
        this.heartbeatAges = ages;
        for (BackfillStatus status : BackfillStatus.values()) {
            // The status gauges are always registered, so a scrape of them also finds the bindings that have a
            // RUNNING job and registers their heartbeat gauges (a binding is not known before its job exists).
            Gauge.builder(JOBS, counts, c -> {
                ages.refresh();
                return c.of(status);
            }).tags(Tags.of("status", status.name())).description("Backfill jobs by status").register(registry);
        }
    }

    /**
     * Counts a batch that reached its outcomes.
     *
     * @param bindingId   the binding
     * @param records     records handed to the ingest path (0 for a batch of a finished object)
     * @param quarantined records of those that were quarantined
     */
    public void batch(String bindingId, long records, long quarantined) {
        try {
            Tags tags = Tags.of("binding", bindingLabel.apply(bindingId));
            registry.counter(BATCHES, tags).increment();
            registry.counter(RECORDS_READ, tags).increment(records);
            registry.counter(RECORDS_QUARANTINED, tags).increment(quarantined);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /**
     * Counts an object that failed.
     *
     * @param bindingId the binding
     * @param reason    {@link #REASON_READ} or {@link #REASON_INGEST}
     */
    public void objectFailed(String bindingId, String reason) {
        try {
            registry.counter(OBJECTS_FAILED, Tags.of("binding", bindingLabel.apply(bindingId), "reason", reason))
                    .increment();
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /**
     * Records the run time of a job that ended.
     *
     * @param bindingId the binding
     * @param status    {@code SUCCEEDED}, {@code FAILED} or {@code CANCELLED}
     * @param started   when the job first started (or was requested, when it never started)
     * @param ended     when it ended
     */
    public void jobEnded(String bindingId, BackfillStatus status, Instant started, Instant ended) {
        try {
            Duration d = started == null || ended == null || ended.isBefore(started) ? Duration.ZERO
                    : Duration.between(started, ended);
            Timer.builder(JOB_DURATION).tags("binding", bindingLabel.apply(bindingId), "status", status.name())
                    .description("Run time of backfill jobs that ended").register(registry).record(d);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private static void warn(RuntimeException e) {
        log.warn("Backfill metric not recorded: {}", e.toString());
    }
}
