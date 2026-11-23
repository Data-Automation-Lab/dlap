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

import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.ShapeFingerprint;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * The in-memory half of the parser-health signals: what the parse path saw lately, kept in fixed
 * memory. Counters and gauges of {@link LogIngestMetrics} read from here, and so do the binding
 * status publisher and the drift report. It holds no business data: counts, ratios, JSON paths of
 * unknown fields (never values) and processing times.
 *
 * <h3>What is per instance</h3>
 * Everything in this class is the view of one service instance. A restart empties the window
 * counts, the fill ratios and the binding counters, apart from the read signal, which the health
 * snapshot seeds from {@code log_events} (see {@link #seedReadSignal}). The unknown paths and the
 * fill baselines are the exception (Q-364): {@link DriftStatePersister} hands out what is new
 * ({@link #drainUnknownDeltas}, {@link #drainBaselines}) and restores what was stored
 * ({@link #restoreUnknownPath}, {@link #restoreBaseline}), so a restart keeps the weekly drift
 * report whole. A second replica ingesting other streams has its own live view, which is why the
 * alerts aggregate across replicas.
 *
 * <h3>Bounds</h3>
 * Every map is bounded: fill ratios by (source, version, field) with a closed field set, unknown
 * paths by {@link LogIngestMetricsProperties#maxDriftKeys()} and
 * {@link LogIngestMetricsProperties#maxPathsPerKey()}, bindings by configuration. Thread safe.
 */
public final class ParseTelemetry {

    /** Longest unknown path kept, in characters; longer paths are cut. */
    static final int MAX_PATH_LENGTH = 200;

    /**
     * The key optional fields whose fill ratio is watched (test strategy L8: "declared required and
     * key optional fields"). A required field is present on every parsed event by construction, so
     * only fields a provider may omit are tracked. The closed set bounds the {@code field} label.
     */
    public enum FillField {
        /** The acting identity. */
        PRINCIPAL_ID("principal.id", e -> !e.principal().id().isEmpty()),
        /** The account, project or tenant of the acting identity. */
        PRINCIPAL_ACCOUNT_ID("principal.account-id", e -> !e.principal().accountId().isEmpty()),
        /** The provider service that emitted the record. */
        SERVICE("service", e -> !e.service().isEmpty()),
        /** The provider-native id of the target. */
        TARGET_ID("target.id", e -> !e.target().id().isEmpty()),
        /** The canonical asset id of the target. */
        TARGET_CANONICAL_URI("target.canonical-uri", e -> !e.target().canonicalUri().isEmpty()),
        /** The account or project the target lives in. */
        TARGET_PROJECT("target.project", e -> !e.target().project().isEmpty()),
        /** The region of the target. */
        TARGET_REGION("target.region", e -> !e.target().region().isEmpty()),
        /** The caller's address. */
        CALLER_IP("request.caller-ip", e -> !e.request().callerIp().isEmpty()),
        /** The caller's user agent. */
        USER_AGENT("request.user-agent", e -> !e.request().userAgent().isEmpty()),
        /** The provider request id. */
        REQUEST_ID("request.request-id", e -> !e.request().requestId().isEmpty());

        private final String label;
        private final Predicate<CanonicalEvent> present;

        FillField(String label, Predicate<CanonicalEvent> present) {
            this.label = label;
            this.present = present;
        }

        /**
         * The value of the {@code field} label.
         *
         * @return the canonical field key
         */
        public String label() {
            return label;
        }

        boolean presentOn(CanonicalEvent e) {
            return present.test(e);
        }
    }

    /**
     * The group of the drift report.
     *
     * @param source  the source kind
     * @param format  the envelope
     * @param version the version label
     */
    public record DriftKey(String source, String format, String version) {
    }

    /**
     * One watched field of one (source, version).
     *
     * @param source   the source kind
     * @param version  the version label
     * @param field    the field label
     * @param ratio    the fill ratio over the window, NaN when nothing was seen
     * @param samples  events in the window
     * @param baseline the highest ratio seen with enough samples (restored across restarts), NaN before
     */
    public record FillReading(String source, String version, String field, double ratio, long samples,
                              double baseline) {
    }

    /**
     * An unknown JSON path and how often it was seen.
     *
     * @param key   the group
     * @param path  the path, never a value
     * @param count records that carried it (restored across restarts, so not only since this start)
     */
    public record UnknownPath(DriftKey key, String path, long count) {
    }

    /**
     * Outcome counts of one binding for a window.
     *
     * @param windowStart         when the window opened
     * @param windowEnd           when it closed
     * @param parsed              records parsed without warnings
     * @param parsedWithWarnings  records parsed with warnings
     * @param skipped             valid records that are no event
     * @param quarantined         records quarantined
     * @param published           events stored and published
     * @param duplicates          events whose id was already stored
     */
    public record WindowCounts(Instant windowStart, Instant windowEnd, long parsed, long parsedWithWarnings,
                               long skipped, long quarantined, long published, long duplicates) {

        /**
         * Records that reached an outcome.
         *
         * @return the sum of the four outcomes
         */
        public long records() {
            return parsed + parsedWithWarnings + skipped + quarantined;
        }
    }

    /**
     * Unknown-path sightings since the last drain.
     *
     * @param key   the group
     * @param path  the path, never a value
     * @param delta records that carried it since the previous drain
     */
    public record UnknownPathDelta(DriftKey key, String path, long delta) {
    }

    /**
     * A fill baseline to persist.
     *
     * @param source   the source kind
     * @param version  the version label
     * @param field    the field label
     * @param baseline the highest ratio seen with enough samples
     */
    public record FillBaseline(String source, String version, String field, double baseline) {
    }

    private record FillKey(String source, String version, FillField field) {
    }

    private static final class FillCell {
        private final RollingRatio ratio;
        private volatile double baseline;
        private volatile boolean raised;

        FillCell(RollingRatio ratio, double baseline) {
            this.ratio = ratio;
            this.baseline = baseline;
        }
    }

    /** The count of one unknown path: the total, and the part not yet handed to the persister. */
    private static final class PathCount {
        private final LongAdder total = new LongAdder();
        private final LongAdder pending = new LongAdder();

        void increment() {
            total.increment();
            pending.increment();
        }
    }

    private static final class BindingState {
        private final AtomicLong parsed = new AtomicLong();
        private final AtomicLong parsedWithWarnings = new AtomicLong();
        private final AtomicLong skipped = new AtomicLong();
        private final AtomicLong quarantined = new AtomicLong();
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong duplicates = new AtomicLong();
        private final AtomicReference<Instant> windowStart;
        private volatile Instant lastRecordAt;
        private volatile Instant lastReadSeen;
        private volatile Instant lastWriteSeen;
        private volatile Instant firstReadEventTime;

        BindingState(Instant now) {
            this.windowStart = new AtomicReference<>(now);
        }
    }

    private final Clock clock;
    private final LogIngestMetricsProperties props;
    private final Map<FillKey, FillCell> fill = new ConcurrentHashMap<>();
    private final Map<String, RollingRatio> heuristic = new ConcurrentHashMap<>();
    private final Map<String, BindingState> bindings = new ConcurrentHashMap<>();
    private final Map<DriftKey, Map<String, PathCount>> unknownPaths = new ConcurrentHashMap<>();
    private final Map<FillKey, Double> restoredBaselines = new ConcurrentHashMap<>();
    private final Map<DriftKey, LongAdder> unknownOverflow = new ConcurrentHashMap<>();

    /**
     * Creates the telemetry.
     *
     * @param clock the time source for windows and last-seen times (processing time only)
     * @param props the settings
     */
    public ParseTelemetry(Clock clock, LogIngestMetricsProperties props) {
        this.clock = clock;
        this.props = props;
    }

    /**
     * Records one parsed record.
     *
     * @param outcome      the registry's outcome
     * @param versionLabel the bounded version label of the record
     */
    public void recordParse(ParseOutcome outcome, String versionLabel) {
        Instant now = clock.instant();
        String source = outcome.record().sourceKind();
        String format = outcome.record().envelope();
        BindingState b = binding(outcome.bindingId(), now);
        b.lastRecordAt = now;
        switch (outcome.result().outcome()) {
            case PARSED -> b.parsed.incrementAndGet();
            case PARSED_WITH_WARNINGS -> b.parsedWithWarnings.incrementAndGet();
            case SKIPPED -> b.skipped.incrementAndGet();
            case QUARANTINED -> b.quarantined.incrementAndGet();
        }
        if (outcome.result() instanceof ParseResult.Parsed parsed) {
            for (CanonicalEvent e : parsed.events()) {
                recordEvent(b, source, versionLabel, parsed, e, now);
            }
        }
        outcome.fingerprint().filter(ShapeFingerprint::hasUnknownFields)
                .ifPresent(f -> recordUnknown(new DriftKey(source, format, versionLabel), f));
    }

    /**
     * Records what the replay store did with an event.
     *
     * @param bindingId   the binding of the event
     * @param disposition what happened
     */
    public void recordStore(String bindingId, Disposition disposition) {
        if (bindingId == null || bindingId.isEmpty()) {
            return;
        }
        BindingState b = binding(bindingId, clock.instant());
        switch (disposition) {
            case PUBLISHED, REPUBLISHED -> b.published.incrementAndGet();
            case DUPLICATE -> b.duplicates.incrementAndGet();
            case FILTERED -> {
                // a filtered event is not stored and is already counted as a skipped record
            }
        }
    }

    /**
     * Returns the binding's counts since the last call and starts a new window.
     *
     * @param bindingId the binding
     * @return the counts of the closed window
     */
    public WindowCounts drain(String bindingId) {
        Instant now = clock.instant();
        BindingState b = binding(bindingId, now);
        Instant start = b.windowStart.getAndSet(now);
        return new WindowCounts(start, now, b.parsed.getAndSet(0), b.parsedWithWarnings.getAndSet(0),
                b.skipped.getAndSet(0), b.quarantined.getAndSet(0), b.published.getAndSet(0),
                b.duplicates.getAndSet(0));
    }

    /**
     * Returns the binding's counts since the last {@link #drain} without starting a new window.
     *
     * @param bindingId the binding
     * @return the counts of the open window
     */
    public WindowCounts peek(String bindingId) {
        Instant now = clock.instant();
        BindingState b = binding(bindingId, now);
        return new WindowCounts(b.windowStart.get(), now, b.parsed.get(), b.parsedWithWarnings.get(),
                b.skipped.get(), b.quarantined.get(), b.published.get(), b.duplicates.get());
    }

    /**
     * When the binding last delivered a record to the parse path.
     *
     * @param bindingId the binding
     * @return the processing time, or empty when this instance has seen none
     */
    public Optional<Instant> lastRecordAt(String bindingId) {
        BindingState b = bindings.get(bindingId);
        return b == null ? Optional.empty() : Optional.ofNullable(b.lastRecordAt);
    }

    /**
     * When the binding last delivered a {@code DATA_READ} event.
     *
     * @param bindingId the binding
     * @return the processing time, or empty when none is known
     */
    public Optional<Instant> lastReadSeen(String bindingId) {
        BindingState b = bindings.get(bindingId);
        return b == null ? Optional.empty() : Optional.ofNullable(b.lastReadSeen);
    }

    /**
     * When the binding last delivered a {@code DATA_WRITE} event.
     *
     * @param bindingId the binding
     * @return the processing time, or empty when none is known
     */
    public Optional<Instant> lastWriteSeen(String bindingId) {
        BindingState b = bindings.get(bindingId);
        return b == null ? Optional.empty() : Optional.ofNullable(b.lastWriteSeen);
    }

    /**
     * The earliest {@code event_time} of a {@code DATA_READ} event known for the binding.
     *
     * @param bindingId the binding
     * @return the event time, or empty when none is known
     */
    public Optional<Instant> firstReadEventTime(String bindingId) {
        BindingState b = bindings.get(bindingId);
        return b == null ? Optional.empty() : Optional.ofNullable(b.firstReadEventTime);
    }

    /**
     * Seeds the read signal from stored events, so a restart does not forget a read seen an hour ago.
     * Keeps the later of the stored and the in-memory last-seen times and the earlier first read.
     *
     * @param bindingId the binding
     * @param lastRead  the latest ingest time of a read event, or {@code null}
     * @param lastWrite the latest ingest time of a write event, or {@code null}
     * @param firstRead the earliest event time of a read event, or {@code null}
     */
    public void seedReadSignal(String bindingId, Instant lastRead, Instant lastWrite, Instant firstRead) {
        BindingState b = binding(bindingId, clock.instant());
        b.lastReadSeen = later(b.lastReadSeen, lastRead);
        b.lastWriteSeen = later(b.lastWriteSeen, lastWrite);
        b.firstReadEventTime = earlier(b.firstReadEventTime, firstRead);
    }

    /**
     * The fill ratio of one watched field, live.
     *
     * @param source  the source kind
     * @param version the version label
     * @param field   the field
     * @return the reading, or empty when the pair was never seen
     */
    public Optional<RollingRatio.Reading> fillReading(String source, String version, FillField field) {
        FillCell c = fill.get(new FillKey(source, version, field));
        return c == null ? Optional.empty() : Optional.of(c.ratio.read());
    }

    /**
     * The share of parsed events of a source that were classified by the verb heuristic.
     *
     * @param source the source kind
     * @return the reading, or empty when the source delivered no parsed event
     */
    public Optional<RollingRatio.Reading> heuristicReading(String source) {
        RollingRatio r = heuristic.get(source);
        return r == null ? Optional.empty() : Optional.of(r.read());
    }

    /**
     * Updates the baselines and lists every watched field.
     *
     * @return one reading per (source, version, field) seen
     */
    public List<FillReading> fillReadings() {
        List<FillReading> out = new ArrayList<>();
        for (Map.Entry<FillKey, FillCell> e : fill.entrySet()) {
            FillCell c = e.getValue();
            RollingRatio.Reading r = c.ratio.read();
            double v = r.value();
            if (r.total() >= props.fillMinSamples() && (Double.isNaN(c.baseline) || v > c.baseline)) {
                c.baseline = v;
                c.raised = true;
            }
            out.add(new FillReading(e.getKey().source(), e.getKey().version(), e.getKey().field().label(), v,
                    r.total(), c.baseline));
        }
        out.sort(Comparator.comparing(FillReading::source).thenComparing(FillReading::version)
                .thenComparing(FillReading::field));
        return out;
    }

    /**
     * The fields whose fill ratio fell by more than the drift threshold below their baseline.
     *
     * @return the dropped fields, the largest drop first
     */
    public List<FillReading> fillDrops() {
        List<FillReading> drops = new ArrayList<>();
        for (FillReading r : fillReadings()) {
            if (r.samples() >= props.fillMinSamples() && !Double.isNaN(r.baseline())
                    && r.baseline() - r.ratio() > props.driftDropThreshold()) {
                drops.add(r);
            }
        }
        drops.sort(Comparator.comparingDouble((FillReading r) -> r.ratio() - r.baseline()));
        return drops;
    }

    /**
     * The unknown JSON paths seen, most frequent first.
     *
     * @param limit the most paths to return
     * @return the paths
     */
    public List<UnknownPath> unknownPaths(int limit) {
        List<UnknownPath> out = new ArrayList<>();
        unknownPaths.forEach((key, paths) -> paths.forEach((path, n) ->
                out.add(new UnknownPath(key, path, n.total.sum()))));
        out.sort(Comparator.comparingLong(UnknownPath::count).reversed().thenComparing(UnknownPath::path));
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /**
     * The unknown paths of one binding's group, for the health page.
     *
     * @param source the source kind
     * @param limit  the most paths to return
     * @return the paths of every format and version of the source
     */
    public List<UnknownPath> unknownPathsOf(String source, int limit) {
        List<UnknownPath> all = unknownPaths(Integer.MAX_VALUE);
        List<UnknownPath> out = new ArrayList<>();
        for (UnknownPath p : all) {
            if (p.key().source().equals(source)) {
                out.add(p);
                if (out.size() == limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Counts the paths that did not fit in a full group.
     *
     * @param key the group
     * @return paths dropped because the group was full
     */
    public long unknownPathOverflow(DriftKey key) {
        LongAdder a = unknownOverflow.get(key);
        return a == null ? 0 : a.sum();
    }

    /**
     * The unknown-path sightings since the previous call, for {@link DriftStatePersister}. Each
     * sighting is handed out once: the pending part is reset as it is read.
     *
     * @return the new sightings, one entry per path that was seen again
     */
    public List<UnknownPathDelta> drainUnknownDeltas() {
        List<UnknownPathDelta> out = new ArrayList<>();
        unknownPaths.forEach((key, paths) -> paths.forEach((path, n) -> {
            long delta = n.pending.sumThenReset();
            if (delta > 0) {
                out.add(new UnknownPathDelta(key, path, delta));
            }
        }));
        return out;
    }

    /**
     * Puts back the sightings that {@link #drainUnknownDeltas} handed out and the store did not
     * take, so the next flush retries them.
     *
     * @param deltas the sightings that were not stored
     */
    public void requeueUnknownDeltas(List<UnknownPathDelta> deltas) {
        for (UnknownPathDelta d : deltas) {
            Map<String, PathCount> paths = unknownPaths.get(d.key());
            PathCount n = paths == null ? null : paths.get(d.path());
            if (n != null) {
                n.pending.add(d.delta());
            }
        }
    }

    /**
     * Restores an unknown path counted by an earlier run. Adds to the total only (the count is
     * already stored), within the same bounds as live sightings.
     *
     * @param key   the group
     * @param path  the path, as stored
     * @param count the stored count
     */
    public void restoreUnknownPath(DriftKey key, String path, long count) {
        Map<String, PathCount> paths = unknownPaths.get(key);
        if (paths == null) {
            if (unknownPaths.size() >= props.maxDriftKeys()) {
                return;
            }
            paths = unknownPaths.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        }
        PathCount n = paths.get(path);
        if (n == null) {
            if (paths.size() >= props.maxPathsPerKey()) {
                return;
            }
            n = paths.computeIfAbsent(path, k -> new PathCount());
        }
        n.total.add(count);
    }

    /**
     * The baselines worth writing: every one raised since the last call, and every one whose field
     * is still healthy now (enough samples and a ratio within the drop threshold of the baseline),
     * so the store can age out the baseline of a field that stays low or disappears.
     *
     * @return the baselines to store
     */
    public List<FillBaseline> drainBaselines() {
        List<FillBaseline> out = new ArrayList<>();
        for (FillReading r : fillReadings()) {
            FillKey key = fillKey(r);
            FillCell c = key == null ? null : fill.get(key);
            if (c == null || Double.isNaN(c.baseline)) {
                continue;
            }
            boolean healthy = r.samples() >= props.fillMinSamples() && !Double.isNaN(r.ratio())
                    && c.baseline - r.ratio() <= props.driftDropThreshold();
            if (c.raised || healthy) {
                c.raised = false;
                out.add(new FillBaseline(r.source(), r.version(), r.field(), c.baseline));
            }
        }
        return out;
    }

    /**
     * Restores a baseline stored by an earlier run; the higher of the stored and the live baseline
     * wins. A field label this build does not know is ignored.
     *
     * @param source   the source kind
     * @param version  the version label
     * @param field    the field label
     * @param baseline the stored baseline
     */
    public void restoreBaseline(String source, String version, String field, double baseline) {
        if (Double.isNaN(baseline)) {
            return;
        }
        for (FillField f : FillField.values()) {
            if (f.label().equals(field)) {
                FillKey key = new FillKey(source, version, f);
                restoredBaselines.merge(key, baseline, Math::max);
                FillCell live = fill.get(key);
                if (live != null && (Double.isNaN(live.baseline) || baseline > live.baseline)) {
                    live.baseline = baseline;
                }
                return;
            }
        }
    }

    private static FillKey fillKey(FillReading r) {
        for (FillField f : FillField.values()) {
            if (f.label().equals(r.field())) {
                return new FillKey(r.source(), r.version(), f);
            }
        }
        return null;
    }

    private void recordEvent(BindingState b, String source, String version, ParseResult.Parsed parsed,
                             CanonicalEvent e, Instant now) {
        for (FillField f : FillField.values()) {
            FillKey key = new FillKey(source, version, f);
            fill.computeIfAbsent(key, k -> new FillCell(new RollingRatio(clock, props.fillWindow()),
                    restoredBaselines.getOrDefault(k, Double.NaN))).ratio.add(f.presentOn(e));
        }
        heuristic.computeIfAbsent(source, k -> new RollingRatio(clock, props.fillWindow()))
                .add(parsed.warningsOf(e).contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION));
        if (e.category() == EventCategory.EVENT_CATEGORY_DATA_READ) {
            b.lastReadSeen = later(b.lastReadSeen, now);
            b.firstReadEventTime = earlier(b.firstReadEventTime, e.eventTime());
        } else if (e.category() == EventCategory.EVENT_CATEGORY_DATA_WRITE) {
            b.lastWriteSeen = later(b.lastWriteSeen, now);
        }
    }

    private void recordUnknown(DriftKey key, ShapeFingerprint fingerprint) {
        Map<String, PathCount> paths = unknownPaths.get(key);
        if (paths == null) {
            if (unknownPaths.size() >= props.maxDriftKeys()) {
                return;
            }
            paths = unknownPaths.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        }
        for (String raw : fingerprint.unknownPaths()) {
            String path = sanitize(raw);
            PathCount n = paths.get(path);
            if (n == null) {
                if (paths.size() >= props.maxPathsPerKey()) {
                    unknownOverflow.computeIfAbsent(key, k -> new LongAdder()).increment();
                    continue;
                }
                n = paths.computeIfAbsent(path, k -> new PathCount());
            }
            n.increment();
        }
    }

    private BindingState binding(String bindingId, Instant now) {
        return bindings.computeIfAbsent(bindingId, k -> new BindingState(now));
    }

    /** A path is JSON keys of a provider record; control characters are replaced and the length bounded. */
    static String sanitize(String path) {
        StringBuilder sb = new StringBuilder(Math.min(path.length(), MAX_PATH_LENGTH));
        for (int i = 0; i < path.length() && sb.length() < MAX_PATH_LENGTH; i++) {
            char c = path.charAt(i);
            sb.append(Character.isISOControl(c) ? '?' : c);
        }
        return sb.toString();
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }
}
