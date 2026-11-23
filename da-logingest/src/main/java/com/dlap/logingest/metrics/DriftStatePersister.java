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

package com.dlap.logingest.metrics;

import com.dlap.logingest.metrics.ParseTelemetry.FillBaseline;
import com.dlap.logingest.metrics.ParseTelemetry.UnknownPath;
import com.dlap.logingest.metrics.ParseTelemetry.UnknownPathDelta;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the unknown JSON paths and the fill baselines of {@link ParseTelemetry} across restarts
 * (Q-364), so the weekly drift report is not emptied by a deploy.
 *
 * <ul>
 *   <li>On start ({@code ApplicationReadyEvent}) it loads what was stored within the retention
 *       window back into the telemetry.</li>
 *   <li>Every flush interval, and once more on shutdown, it adds what the telemetry saw since the
 *       last flush, in one transaction: path counts are summed, baselines are raised, never
 *       lowered. Replicas that flush the same key converge on the sum and the maximum.</li>
 *   <li>Rows not seen for the retention window are pruned, so a field that went away does not keep
 *       a baseline for ever.</li>
 * </ul>
 *
 * <p>The flush is per instance and takes no cluster lock: it only adds this instance's own
 * increments. A store failure is logged and swallowed (telemetry must not break ingest) and the
 * path counts are put back for the next flush. Every failed flush also counts on
 * {@code dlap_logingest_drift_state_flush_failures_total}, which the {@code DriftStateFlushFailing}
 * alert watches (Q-826): a persister that cannot write loses the drift report at the next deploy.
 */
@Component
public class DriftStatePersister {

    /** Most rows read back per table at start. */
    static final int RESTORE_LIMIT = 10_000;

    /** Counter of flushes that failed (the Prometheus series adds {@code _total}); registered at 0 at start. */
    public static final String FLUSH_FAILURES = "dlap.logingest.drift.state.flush.failures";

    private static final Logger log = LoggerFactory.getLogger(DriftStatePersister.class);

    private final ParseTelemetry telemetry;
    private final DriftStateRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration retention;
    private final Counter flushFailures;

    /**
     * Creates the persister.
     *
     * @param telemetry   the in-memory signals
     * @param repository  the V5 tables
     * @param transactions the transaction manager of the service datasource
     * @param clock       the application clock when unique, else the UTC system clock
     * @param meters      the meter registry when the service has one, else the failure counter is private
     * @param retention   how long an unseen path or baseline is kept
     */
    @Autowired
    public DriftStatePersister(ParseTelemetry telemetry, DriftStateRepository repository,
                               PlatformTransactionManager transactions, ObjectProvider<Clock> clock,
                               ObjectProvider<MeterRegistry> meters,
                               @Value("${dlap.logingest.drift-state.retention:P30D}") Duration retention) {
        this(telemetry, repository, transactions, clock.getIfUnique(Clock::systemUTC),
                meters.getIfAvailable(SimpleMeterRegistry::new), retention);
    }

    /**
     * Creates the persister with a fixed clock and a private meter registry.
     *
     * @param telemetry    the in-memory signals
     * @param repository   the V5 tables
     * @param transactions the transaction manager of the service datasource
     * @param clock        the clock
     * @param retention    how long an unseen path or baseline is kept
     */
    public DriftStatePersister(ParseTelemetry telemetry, DriftStateRepository repository,
                               PlatformTransactionManager transactions, Clock clock, Duration retention) {
        this(telemetry, repository, transactions, clock, new SimpleMeterRegistry(), retention);
    }

    /**
     * Creates the persister with a fixed clock.
     *
     * @param telemetry    the in-memory signals
     * @param repository   the V5 tables
     * @param transactions the transaction manager of the service datasource
     * @param clock        the clock
     * @param meters       where the flush failure counter is registered
     * @param retention    how long an unseen path or baseline is kept
     */
    public DriftStatePersister(ParseTelemetry telemetry, DriftStateRepository repository,
                               PlatformTransactionManager transactions, Clock clock, MeterRegistry meters,
                               Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("dlap.logingest.drift-state.retention must be positive");
        }
        this.telemetry = telemetry;
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.retention = retention;
        this.flushFailures = Counter.builder(FLUSH_FAILURES)
                .description("Drift state flushes that failed; the sightings wait for the next interval")
                .register(meters);
    }

    /**
     * Loads the stored paths and baselines into the telemetry. A failure is logged: the report
     * then starts empty, as it did before Q-364, and the next flush still adds to the store.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStart() {
        try {
            restore();
        } catch (RuntimeException e) {
            log.warn("Drift state not restored, the drift report starts empty: {}", e.toString());
        }
    }

    /**
     * Loads the stored paths and baselines within the retention window into the telemetry.
     *
     * @return the number of stored rows applied (paths plus baselines)
     */
    public synchronized int restore() {
        Instant since = clock.instant().minus(retention);
        List<UnknownPath> paths = repository.unknownPathsSince(since, RESTORE_LIMIT);
        for (UnknownPath p : paths) {
            telemetry.restoreUnknownPath(p.key(), p.path(), p.count());
        }
        List<FillBaseline> baselines = repository.baselinesSince(since, RESTORE_LIMIT);
        for (FillBaseline b : baselines) {
            telemetry.restoreBaseline(b.source(), b.version(), b.field(), b.baseline());
        }
        return paths.size() + baselines.size();
    }

    /** Scheduled flush; per instance, no cluster lock (see the class description). */
    @Scheduled(initialDelayString = "${dlap.logingest.drift-state.flush-interval:PT5M}",
            fixedDelayString = "${dlap.logingest.drift-state.flush-interval:PT5M}")
    public void scheduledFlush() {
        flushQuietly();
    }

    /** Writes what is pending when the service stops, so a deploy loses at most the last seconds. */
    @PreDestroy
    public void flushOnShutdown() {
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Drift state flush failed, retrying at the next interval: {}", e.toString());
        }
    }

    /**
     * Adds the telemetry's new unknown-path sightings and its healthy or raised baselines to the
     * store, and prunes what is older than the retention window. On a store failure the sightings
     * go back to the telemetry and the exception is rethrown.
     *
     * @return the number of rows written (paths plus baselines)
     */
    public synchronized int flush() {
        List<UnknownPathDelta> deltas = telemetry.drainUnknownDeltas();
        List<FillBaseline> baselines = telemetry.drainBaselines();
        Instant now = clock.instant();
        try {
            Integer written = transaction.execute(status -> {
                int n = repository.addUnknownPaths(deltas, now) + repository.raiseBaselines(baselines, now);
                repository.pruneBefore(now.minus(retention));
                return n;
            });
            return written == null ? 0 : written;
        } catch (RuntimeException e) {
            telemetry.requeueUnknownDeltas(deltas);
            flushFailures.increment();
            throw e;
        }
    }
}
