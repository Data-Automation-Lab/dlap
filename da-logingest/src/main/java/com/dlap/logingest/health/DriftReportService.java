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

import com.dlap.logingest.health.LogHealthViews.DriftPathView;
import com.dlap.logingest.health.LogHealthViews.DriftReport;
import com.dlap.logingest.health.LogHealthViews.FillDropView;
import com.dlap.logingest.health.LogHealthViews.NewVersionView;
import com.dlap.logingest.metrics.ParseTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The drift report (design B3): what changed in the logs lately, as the list a person reads each
 * week to decide which fixtures and parser changes are due.
 *
 * <ul>
 *   <li><b>New versions</b> come from {@code format_observation}, so they survive restarts: every
 *       (binding, envelope, version, fingerprint) whose first sighting is inside the window.</li>
 *   <li><b>Unknown paths</b> are the JSON paths the parsers do not declare, most frequent first.
 *       They are counted in memory, bounded, and flushed to {@code unknown_path_observation}
 *       (Q-364), so a restart restores them. Other replicas' sightings join this instance's view
 *       at its next restart.</li>
 *   <li><b>Fill drops</b> are watched fields whose rolling-window fill ratio fell by more than
 *       {@code drift-drop-threshold} below the highest ratio seen. The baseline is kept in
 *       {@code fill_baseline} and restored on start, and ages out after the drift-state retention
 *       (30 days) without a healthy reading. The 7-day baseline alert ({@code LogFieldFillDrop})
 *       is the durable version of the same check.</li>
 * </ul>
 * Paths and field names appear; values never do.
 */
@Service
public class DriftReportService {

    /** The default window of the report. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private static final String NOTE = "newVersions persist (format_observation); unknownPaths and fill baselines are "
            + "counted by this instance and stored (unknown_path_observation, fill_baseline), so a restart "
            + "restores them; fillDrops compare the live ratio of this instance to the stored baseline";

    private final HealthSnapshotService snapshots;
    private final ParseTelemetry telemetry;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param snapshots the health snapshot
     * @param telemetry the in-memory signals
     * @param clock     the application clock when unique, else the UTC system clock
     */
    public DriftReportService(HealthSnapshotService snapshots, ParseTelemetry telemetry, ObjectProvider<Clock> clock) {
        this.snapshots = snapshots;
        this.telemetry = telemetry;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /**
     * Builds the report.
     *
     * @param since the start of the window for new versions, or {@code null} for the last 7 days
     * @param limit the most unknown paths and new versions to list
     * @return the report
     */
    public DriftReport report(Instant since, int limit) {
        Instant now = clock.instant();
        Instant from = since == null ? now.minus(DEFAULT_WINDOW) : since;
        List<NewVersionView> versions = BindingHealthService.allVersions(snapshots.current()).stream()
                .filter(e -> e.getValue().firstSeen() != null && !e.getValue().firstSeen().isBefore(from))
                .map(e -> new NewVersionView(e.getKey().bindingId(), e.getKey().sourceKind(),
                        e.getValue().envelope(), e.getValue().version(), e.getValue().fingerprint(),
                        e.getValue().firstSeen(), e.getValue().count()))
                .sorted(Comparator.comparing(NewVersionView::firstSeen).reversed())
                .limit(limit).toList();
        List<DriftPathView> paths = telemetry.unknownPaths(limit).stream()
                .map(p -> new DriftPathView(p.key().source(), p.key().format(), p.key().version(), p.path(),
                        p.count())).toList();
        List<FillDropView> drops = telemetry.fillDrops().stream()
                .map(r -> new FillDropView(r.source(), r.version(), r.field(), r.ratio(), r.baseline(),
                        r.baseline() - r.ratio(), r.samples())).limit(limit).toList();
        return new DriftReport(now, from, NOTE, versions, paths, drops);
    }
}
