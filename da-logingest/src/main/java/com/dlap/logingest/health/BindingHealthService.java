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

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.health.HealthSnapshot.BindingSnapshot;
import com.dlap.logingest.health.HealthSnapshot.ObservedVersion;
import com.dlap.logingest.health.HealthSnapshot.StreamSnapshot;
import com.dlap.logingest.health.LogHealthViews.BindingHealth;
import com.dlap.logingest.health.LogHealthViews.FillView;
import com.dlap.logingest.health.LogHealthViews.HealthPage;
import com.dlap.logingest.health.LogHealthViews.ReasonView;
import com.dlap.logingest.health.LogHealthViews.SampleView;
import com.dlap.logingest.health.LogHealthViews.SelfTestView;
import com.dlap.logingest.health.LogHealthViews.StreamView;
import com.dlap.logingest.health.LogHealthViews.UnknownPathView;
import com.dlap.logingest.health.LogHealthViews.VersionView;
import com.dlap.logingest.metrics.ParseTelemetry;
import com.dlap.logingest.metrics.RollingRatio;
import com.dlap.logingest.quarantine.QuarantineRecordRepository;
import com.dlap.proto.log.v1.BindingScope;
import com.dlap.proto.log.v1.ReadCoverage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Builds the parser health view: one row per binding with its status, declared versus observed
 * versions, top quarantine reasons with redacted samples, unknown field paths, fill ratios, last
 * record time, cursor lag and read coverage (test strategy L8, "Parser health page").
 *
 * <p>The row is assembled from the {@link HealthSnapshot} (configuration, cursors, observations),
 * the quarantine table and the live {@link ParseTelemetry}; nothing in it is a record value.
 */
@Service
public class BindingHealthService {

    private final HealthSnapshotService snapshots;
    private final EffectiveLogSources sources;
    private final QuarantineRecordRepository quarantine;
    private final ParseTelemetry telemetry;
    private final ParserSelfTest selfTest;
    private final LogHealthProperties props;
    private final BindingStatusAssembler assembler;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param snapshots  the health snapshot
     * @param sources    the effective configuration
     * @param quarantine the quarantine table
     * @param telemetry  the in-memory signals
     * @param selfTest   the startup self-test
     * @param props      the health settings
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public BindingHealthService(HealthSnapshotService snapshots, EffectiveLogSources sources,
                                QuarantineRecordRepository quarantine, ParseTelemetry telemetry,
                                ParserSelfTest selfTest, LogHealthProperties props, ObjectProvider<Clock> clock) {
        this.snapshots = snapshots;
        this.sources = sources;
        this.quarantine = quarantine;
        this.telemetry = telemetry;
        this.selfTest = selfTest;
        this.props = props;
        this.assembler = new BindingStatusAssembler(props);
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /**
     * The whole page.
     *
     * @return every binding
     */
    public HealthPage page() {
        HealthSnapshot snapshot = snapshots.current();
        Instant now = clock.instant();
        List<BindingHealth> rows = snapshot.bindings().stream().map(b -> row(b, now)).toList();
        ParserSelfTest.Report report = selfTest.report();
        Map<String, String> failed = new LinkedHashMap<>();
        if (report != null) {
            report.failures().forEach(f -> failed.put(f.id(), f.detail()));
        }
        return new HealthPage(now, sources.pipeline().name().toLowerCase(Locale.ROOT),
                new SelfTestView(selfTest.passed(), report == null ? 0 : report.cases(), failed), rows);
    }

    /**
     * One binding.
     *
     * @param bindingId the binding id
     * @return its row, or empty when the id is not configured
     */
    public Optional<BindingHealth> binding(String bindingId) {
        HealthSnapshot snapshot = snapshots.current();
        Instant now = clock.instant();
        return snapshot.bindings().stream().filter(b -> b.bindingId().equals(bindingId)).findFirst()
                .map(b -> row(b, now));
    }

    private BindingHealth row(BindingSnapshot b, Instant now) {
        BindingStatusAssembler.State state = assembler.state(b, telemetry.peek(b.bindingId()), selfTest.passed(), now);
        ReadCoverageEvaluator.Evaluation coverage = snapshots.readCoverage(b);
        BindingScope scope = BindingStatusAssembler.scope(b);
        Instant since = now.minus(props.quarantineWindow());

        List<ReasonView> reasons = quarantine.topReasons(b.bindingId(), since, props.topReasons()).stream()
                .map(r -> new ReasonView(r.reason(), r.count(), quarantine.samples(b.bindingId(), r.reason(), since,
                        props.samplesPerReason()).stream().map(BindingHealthService::sample).toList()))
                .toList();

        List<FillView> fills = telemetry.fillReadings().stream().filter(f -> f.source().equals(b.sourceKind()))
                .map(f -> new FillView(f.version(), f.field(), nanToNull(f.ratio()), f.samples(),
                        nanToNull(f.baseline()))).toList();
        List<UnknownPathView> paths = telemetry.unknownPathsOf(b.sourceKind(), 20).stream()
                .map(p -> new UnknownPathView(p.key().format(), p.key().version(), p.path(), p.count())).toList();
        Double heuristic = telemetry.heuristicReading(b.sourceKind()).map(RollingRatio.Reading::value)
                .filter(v -> !Double.isNaN(v)).orElse(null);
        Long lag = b.lowWatermark().map(t -> Math.max(0, Duration.between(t, now).toSeconds())).orElse(null);

        return new BindingHealth(b.bindingId(), b.sourceId(), b.sourceKind(), scope.getScopeType(),
                scope.getScopeId(), state.state().name().substring("BINDING_STATE_".length()), state.detail(),
                b.configHash(), b.streams().stream().map(BindingHealthService::stream).toList(), versions(b), reasons,
                paths, fills, heuristic, telemetry.lastRecordAt(b.bindingId()).orElse(null), lag,
                coverage.coverage().name().substring("READ_COVERAGE_".length()), coverage.since().orElse(null),
                coverage.coverage() == ReadCoverage.READ_COVERAGE_NOT_CAPTURED || Double.isNaN(coverage.signal()) ? null
                        : coverage.signal() == 1.0);
    }

    private static StreamView stream(StreamSnapshot s) {
        return new StreamView(s.streamId(), s.transport(), s.envelope(), s.pinnedVersion(), s.lowWatermark(),
                s.highWatermark(), s.updatedAt());
    }

    private static List<VersionView> versions(BindingSnapshot b) {
        return b.observed().stream().map(o -> {
            String declared = b.streams().stream().filter(s -> s.envelope().equals(o.envelope()))
                    .map(StreamSnapshot::pinnedVersion).findFirst().orElse("auto");
            return new VersionView(o.envelope(), o.version(), o.fingerprint(), declared,
                    "auto".equals(declared) || declared.equals(o.version()), o.firstSeen(), o.lastSeen(), o.count());
        }).toList();
    }

    private static SampleView sample(com.dlap.logingest.quarantine.QuarantineRow r) {
        return new SampleView(r.quarantineId(), r.quarantinedAt(), r.detectedVersion(), r.reasonDetail(),
                r.rawExcerpt(), r.rawTruncated());
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }

    /**
     * The observed versions of all bindings, for the drift report.
     *
     * @param snapshot the snapshot
     * @return every (binding, version) pair
     */
    static List<Map.Entry<BindingSnapshot, ObservedVersion>> allVersions(HealthSnapshot snapshot) {
        return snapshot.bindings().stream().flatMap(b -> b.observed().stream()
                .map(o -> Map.entry(b, o))).toList();
    }
}
