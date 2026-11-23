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

package com.dlap.logingest.health;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.health.HealthSnapshot.BindingSnapshot;
import com.dlap.logingest.health.HealthSnapshot.ObservedVersion;
import com.dlap.logingest.health.HealthSnapshot.StreamSnapshot;
import com.dlap.logingest.metrics.LogIngestMetrics;
import com.dlap.logingest.metrics.LogIngestMetricsProperties;
import com.dlap.logingest.metrics.ParseTelemetry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Builds the {@link HealthSnapshot} and exports its gauges. The snapshot joins the effective
 * configuration (which bindings exist, their state, scope, transports and pins) with the database
 * (cursors, format observations); the live signals (last record, read signal) come from
 * {@link ParseTelemetry}.
 *
 * <h3>Gauges set on every refresh</h3>
 * {@code dlap_logingest_cursor_lag_seconds}, {@code dlap_logingest_read_signal_present},
 * {@code dlap_logingest_version_observed} and
 * {@code dlap_logingest_version_first_seen_timestamp_seconds}. A binding or version that left the
 * configuration or the table reads NaN afterwards. With more bindings than
 * {@code binding-label-max} the {@code binding} label reads {@code _all} and the series combine:
 * the largest lag, the lowest read signal.
 *
 * <p>The refresh is per instance (each replica exports its own gauges) and never throws: a database
 * error keeps the previous snapshot and is logged once per outage. The scrape itself never touches
 * the database: gauges read what the last refresh stored.
 */
@Service
public class HealthSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(HealthSnapshotService.class);

    private final EffectiveLogSources sources;
    private final HealthSnapshotRepository repository;
    private final ParseTelemetry telemetry;
    private final LogIngestMetrics metrics;
    private final LogIngestMetricsProperties metricProps;
    private final LogHealthProperties healthProps;
    private final Clock clock;
    private final AtomicReference<HealthSnapshot> latest = new AtomicReference<>();
    private final AtomicBoolean seeded = new AtomicBoolean();
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * Creates the service.
     *
     * @param sources     the effective configuration
     * @param repository  the read queries
     * @param telemetry   the in-memory signals
     * @param metrics     the meters
     * @param metricProps the metric settings
     * @param healthProps the health settings
     * @param clock       the application clock when unique, else the UTC system clock
     */
    public HealthSnapshotService(EffectiveLogSources sources, HealthSnapshotRepository repository,
                                 ParseTelemetry telemetry, LogIngestMetrics metrics,
                                 LogIngestMetricsProperties metricProps, LogHealthProperties healthProps,
                                 ObjectProvider<Clock> clock) {
        this.sources = sources;
        this.repository = repository;
        this.telemetry = telemetry;
        this.metrics = metrics;
        this.metricProps = metricProps;
        this.healthProps = healthProps;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /** Scheduled refresh; the interval is {@code dlap.logingest.metrics.snapshot-interval}. */
    @Scheduled(initialDelayString = "${dlap.logingest.metrics.snapshot-initial-delay:PT15S}",
            fixedDelayString = "${dlap.logingest.metrics.snapshot-interval:PT1M}")
    public void scheduledRefresh() {
        refresh();
    }

    /**
     * Rebuilds the snapshot and sets the gauges. Never throws.
     *
     * @return the new snapshot, or empty when the database could not be read (the previous one stays)
     */
    public Optional<HealthSnapshot> refresh() {
        try {
            HealthSnapshot snapshot = build();
            latest.set(snapshot);
            applyGauges(snapshot);
            if (failing.getAndSet(false)) {
                log.info("The log health snapshot recovered");
            }
            return Optional.of(snapshot);
        } catch (DataAccessException e) {
            if (!failing.getAndSet(true)) {
                log.warn("Could not rebuild the log health snapshot; keeping the previous one: {}", e.getMessage());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("Unexpected failure rebuilding the log health snapshot: {}", e.toString(), e);
            return Optional.empty();
        }
    }

    /**
     * The latest snapshot, built on first use.
     *
     * @return the snapshot
     * @throws DataAccessException when none exists yet and the database cannot be read
     */
    public HealthSnapshot current() {
        HealthSnapshot s = latest.get();
        if (s != null) {
            return s;
        }
        HealthSnapshot built = build();
        latest.compareAndSet(null, built);
        return latest.get();
    }

    /**
     * The read-coverage verdict of a binding, from the live telemetry.
     *
     * @param b the binding
     * @return the verdict
     */
    public ReadCoverageEvaluator.Evaluation readCoverage(BindingSnapshot b) {
        boolean canCarry = b.streams().isEmpty() || b.streams().stream()
                .anyMatch(s -> !healthProps.noReadTransports().contains(s.transport()));
        return ReadCoverageEvaluator.evaluate(canCarry, telemetry.lastReadSeen(b.bindingId()),
                telemetry.lastWriteSeen(b.bindingId()), telemetry.lastRecordAt(b.bindingId()),
                telemetry.firstReadEventTime(b.bindingId()), clock.instant(), metricProps.readSignalWindow());
    }

    /**
     * Builds a snapshot from the configuration and the database.
     *
     * @return the snapshot
     * @throws DataAccessException when the database cannot be read
     */
    HealthSnapshot build() {
        Instant now = clock.instant();
        Map<String, List<HealthSnapshotRepository.Cursor>> cursors = new HashMap<>();
        repository.cursors().forEach(c -> cursors.computeIfAbsent(c.bindingId(), k -> new ArrayList<>()).add(c));
        Map<String, List<ObservedVersion>> observations = repository.observations();
        seedReadSignal(now);

        List<BindingSnapshot> out = new ArrayList<>();
        for (EffectiveBinding b : sources.bindings()) {
            List<StreamSnapshot> streams = new ArrayList<>();
            for (EffectiveStream s : b.streams()) {
                Optional<HealthSnapshotRepository.Cursor> cursor = cursors.getOrDefault(b.id(), List.of()).stream()
                        .filter(c -> c.streamId().equals(s.streamId())).findFirst();
                String pin = s.parse() == null ? "auto" : s.parse().pinnedVersion().orElse("auto");
                streams.add(new StreamSnapshot(s.streamId(), s.transport(), s.envelope() == null ? "" : s.envelope(),
                        pin, cursor.map(HealthSnapshotRepository.Cursor::low).orElse(null),
                        cursor.map(HealthSnapshotRepository.Cursor::high).orElse(null),
                        cursor.map(HealthSnapshotRepository.Cursor::updatedAt).orElse(null)));
            }
            out.add(new BindingSnapshot(b.id(), b.sourceId(), b.sourceKind(), b.provider(), b.scope(),
                    b.state(sources.pipeline()), b.runs(sources.pipeline()), b.configHash(), streams,
                    observations.getOrDefault(b.id(), List.of())));
        }
        return new HealthSnapshot(now, out);
    }

    private void seedReadSignal(Instant now) {
        if (sources.pipeline() != com.dlap.logingest.parse.core.PipelineMode.REGISTRY
                || !seeded.compareAndSet(false, true)) {
            return;
        }
        try {
            for (HealthSnapshotRepository.ReadSeed seed : repository.readSeeds(now.minus(metricProps.readSignalWindow()))) {
                Instant firstRead = seed.lastRead() == null ? null
                        : repository.firstReadEventTime(seed.bindingId()).orElse(null);
                telemetry.seedReadSignal(seed.bindingId(), seed.lastRead(), seed.lastWrite(), firstRead);
            }
        } catch (DataAccessException e) {
            seeded.set(false);
            throw e;
        }
    }

    private void applyGauges(HealthSnapshot snapshot) {
        Instant now = snapshot.at();
        Map<Tags, Double> lag = new HashMap<>();
        Map<Tags, Double> signal = new HashMap<>();
        Map<Tags, Double> observed = new HashMap<>();
        Map<Tags, Double> firstSeen = new HashMap<>();
        Duration lookback = metricProps.readSignalWindow();

        for (BindingSnapshot b : snapshot.bindings()) {
            String bindingLabel = metrics.bindingLabel(b.bindingId());
            if (b.runs()) {
                for (StreamSnapshot s : b.streams()) {
                    if (s.lowWatermark() != null) {
                        double seconds = Math.max(0, Duration.between(s.lowWatermark(), now).toMillis() / 1000.0);
                        lag.merge(Tags.of("source", b.sourceKind(), "stream", s.streamId(), "binding", bindingLabel),
                                seconds, Math::max);
                    }
                }
                // NaN (unknown) never hides a known value when bindings share the "_all" label.
                signal.merge(Tags.of("binding", bindingLabel), readCoverage(b).signal(),
                        (a, c) -> Double.isNaN(a) ? c : Double.isNaN(c) ? a : Math.min(a, c));
            }
            for (ObservedVersion o : b.observed()) {
                String version = metrics.versionLabel(b.sourceKind(), o.envelope(), o.version());
                Tags tags = Tags.of("source", b.sourceKind(), "format", o.envelope(), "version", version);
                boolean recent = o.lastSeen() != null && !o.lastSeen().isBefore(now.minus(lookback));
                observed.merge(tags, recent ? 1.0 : 0.0, Math::max);
                if (o.firstSeen() != null) {
                    firstSeen.merge(tags, (double) o.firstSeen().getEpochSecond(), Math::min);
                }
            }
        }
        publish(LogIngestMetrics.CURSOR_LAG, "seconds", lag);
        publish(LogIngestMetrics.READ_SIGNAL, null, signal);
        publish(LogIngestMetrics.VERSION_OBSERVED, null, observed);
        publish(LogIngestMetrics.VERSION_FIRST_SEEN, "seconds", firstSeen);
    }

    private void publish(String name, String unit, Map<Tags, Double> values) {
        values.forEach((tags, v) -> metrics.setGauge(name, unit, tags, v));
        Set<Tags> keep = new HashSet<>(values.keySet());
        metrics.retainGauges(name, keep);
    }
}
