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

import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Finds runnable backfill jobs and runs them on a small bounded pool, at most
 * {@code max-concurrent-jobs} per replica.
 *
 * <p>Every replica ticks every {@code poll-interval}. A tick offers each {@code PENDING} or
 * {@code RUNNING} job to {@link BackfillRunner}, which takes the job's advisory lock first, so
 * replicas never run one job twice and need no ShedLock here (the same arrangement as
 * {@code StreamPollScheduler}; plan rule R7 concerns singleton jobs, and this is a per-job lock). A
 * job whose runner died is still {@code RUNNING} and its lock is gone with the session, so the next
 * tick on any replica resumes it: that is the kill-and-resume path.
 *
 * <p>On shutdown the stop flag is raised: running jobs finish the batch in flight, leave their
 * progress stored and stay {@code RUNNING} for the next start.
 */
@Component
@ConditionalOnRegistryPipeline
public class BackfillScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BackfillScheduler.class);

    private final BackfillJobs jobs;
    private final BackfillRunner runner;
    private final BackfillProperties props;
    private final TaskScheduler scheduler;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private ScheduledFuture<?> ticking;
    private ExecutorService pool;

    /**
     * Creates the scheduler.
     *
     * @param jobs      the job table
     * @param runner    runs one job
     * @param props     the settings
     * @param scheduler the application task scheduler
     */
    public BackfillScheduler(BackfillJobs jobs, BackfillRunner runner, BackfillProperties props,
                             TaskScheduler scheduler) {
        this.jobs = jobs;
        this.runner = runner;
        this.props = props;
        this.scheduler = scheduler;
    }

    /** Starts the ticks once the application is ready, unless {@code enabled} is false. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!props.enabled()) {
            log.info("Backfill scheduler is off (dlap.logingest.backfill.enabled=false); jobs stay PENDING");
            return;
        }
        if (ticking != null) {
            return;
        }
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(props.maxConcurrentJobs(), r -> {
            Thread t = new Thread(r, "backfill-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ticking = scheduler.scheduleWithFixedDelay(this::tickQuietly, props.pollInterval());
        log.info("Backfill scheduler ticks every {} with up to {} job(s) at once", props.pollInterval(),
                props.maxConcurrentJobs());
    }

    /**
     * Offers the runnable jobs to the pool. Public so a test or an operator hook can force a tick.
     *
     * @return how many jobs this tick submitted
     */
    public int tick() {
        if (stopping || pool == null) {
            return 0;
        }
        int submitted = 0;
        for (BackfillJob job : jobs.runnable(props.maxConcurrentJobs() * 4)) {
            if (inFlight.size() >= props.maxConcurrentJobs()) {
                break;
            }
            if (inFlight.add(job.id())) {
                submitted++;
                pool.execute(() -> runOne(job.id()));
            }
        }
        return submitted;
    }

    /**
     * Jobs this replica is running now.
     *
     * @return the count
     */
    public int running() {
        return inFlight.size();
    }

    @Override
    public synchronized void destroy() {
        stopping = true;
        if (ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            // A failing tick must not cancel the schedule; the next tick retries.
            log.error("Backfill scheduler tick failed: {}", e.getMessage(), e);
        }
    }

    private void runOne(UUID id) {
        try {
            runner.run(id, () -> stopping || Thread.currentThread().isInterrupted());
        } catch (RuntimeException e) {
            log.error("Backfill job {} stopped on an unexpected error: {}", id, e.getMessage(), e);
        } finally {
            inFlight.remove(id);
        }
    }
}
