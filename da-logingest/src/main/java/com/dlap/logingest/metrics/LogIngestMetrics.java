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
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.ShapeFingerprint;
import com.dlap.logingest.service.registry.Disposition;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Micrometer meters of the log layer's parser health (design B3, test strategy L8). Names are
 * the Micrometer names; Prometheus turns dots into underscores, adds {@code _total} to counters and
 * the base unit to gauges and summaries:
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Prometheus series</th><th>Type and labels</th></tr>
 *   <tr><td>{@code dlap_logingest_records_total}</td>
 *       <td>counter {@code source, format, version, outcome, binding}; outcome is PARSED,
 *           PARSED_WITH_WARNINGS, SKIPPED or QUARANTINED</td></tr>
 *   <tr><td>{@code dlap_logingest_quarantine_total}</td>
 *       <td>counter {@code source, format, version, reason, binding}</td></tr>
 *   <tr><td>{@code dlap_logingest_unknown_fields_total}</td>
 *       <td>counter {@code source, format, version}; the paths go to the drift report, never a label</td></tr>
 *   <tr><td>{@code dlap_logingest_version_observed}</td>
 *       <td>gauge {@code source, format, version}: 1 when the version was seen within the lookback, else 0</td></tr>
 *   <tr><td>{@code dlap_logingest_version_first_seen_timestamp_seconds}</td>
 *       <td>gauge {@code source, format, version}: epoch seconds of the first sighting</td></tr>
 *   <tr><td>{@code dlap_logingest_cursor_lag_seconds}</td>
 *       <td>gauge {@code source, stream, binding}: now minus the oldest unacknowledged event time</td></tr>
 *   <tr><td>{@code dlap_logingest_event_delay_seconds}</td>
 *       <td>histogram {@code source}: ingest time minus event time</td></tr>
 *   <tr><td>{@code dlap_logingest_dedup_hits_total}</td><td>counter (owned by the ingest service)</td></tr>
 *   <tr><td>{@code dlap_logingest_publish_failures_total}</td><td>counter {@code topic}</td></tr>
 *   <tr><td>{@code dlap_logingest_field_fill_ratio}</td>
 *       <td>gauge {@code source, version, field}: rolling window ratio of events that carry the field</td></tr>
 *   <tr><td>{@code dlap_logingest_classification_heuristic_ratio}</td>
 *       <td>gauge {@code source}: share of events classified by the verb heuristic</td></tr>
 *   <tr><td>{@code dlap_logingest_read_signal_present}</td>
 *       <td>gauge {@code binding}: 1 when data reads are seen, 0 when writes are seen but no read for the
 *           lookback, NaN when neither is known</td></tr>
 *   <tr><td>{@code dlap_logingest_parser_selftest_ok}</td><td>gauge: 1 when the startup self-test passed</td></tr>
 * </table>
 *
 * <p>Cardinality is bounded: the {@code version} label takes at most
 * {@link LogIngestMetricsProperties#maxVersionsPerFormat()} values per (source, format), the
 * {@code binding} label exists only while there are at most
 * {@link LogIngestMetricsProperties#bindingLabelMax()} bindings, and {@code field} is a closed set.
 * Recording never throws: a failure is logged once and the ingest carries on.
 */
public final class LogIngestMetrics {

    /** Records by outcome. */
    public static final String RECORDS = "dlap.logingest.records";
    /** Quarantined records by reason. */
    public static final String QUARANTINE = "dlap.logingest.quarantine";
    /** Unknown field paths seen. */
    public static final String UNKNOWN_FIELDS = "dlap.logingest.unknown.fields";
    /** Versions seen within the lookback. */
    public static final String VERSION_OBSERVED = "dlap.logingest.version.observed";
    /** When a version was first seen. */
    public static final String VERSION_FIRST_SEEN = "dlap.logingest.version.first.seen.timestamp";
    /** Cursor lag. */
    public static final String CURSOR_LAG = "dlap.logingest.cursor.lag";
    /** Ingest time minus event time. */
    public static final String EVENT_DELAY = "dlap.logingest.event.delay";
    /** Failed Kafka sends. */
    public static final String PUBLISH_FAILURES = "dlap.logingest.publish.failures";
    /** Field fill ratio. */
    public static final String FIELD_FILL = "dlap.logingest.field.fill.ratio";
    /** Heuristic classification ratio. */
    public static final String HEURISTIC_RATIO = "dlap.logingest.classification.heuristic.ratio";
    /** Read signal. */
    public static final String READ_SIGNAL = "dlap.logingest.read.signal.present";
    /** Startup self-test result. */
    public static final String SELFTEST_OK = "dlap.logingest.parser.selftest.ok";

    /** Bucket bounds of the event delay histogram, in seconds: one second to one day. */
    static final double[] DELAY_BUCKETS = {1, 5, 30, 60, 300, 900, 1800, 3600, 21_600, 86_400};

    private static final Logger log = LoggerFactory.getLogger(LogIngestMetrics.class);

    private record GaugeKey(String name, Tags tags) {
    }

    private static final class Holder {
        private volatile double value = Double.NaN;
    }

    private final MeterRegistry registry;
    private final ParseTelemetry telemetry;
    private final Clock clock;
    private final LogIngestMetricsProperties props;
    private final boolean bindingLabelEnabled;
    private final Map<String, Set<String>> versionsByFormat = new ConcurrentHashMap<>();
    private final Map<String, Boolean> fillRegistered = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> delays = new ConcurrentHashMap<>();
    private final Map<GaugeKey, Holder> gauges = new ConcurrentHashMap<>();
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    /**
     * Creates the meters.
     *
     * @param registry     the meter registry
     * @param telemetry    the in-memory state the gauges read
     * @param clock        the time source
     * @param props        the settings
     * @param bindingCount how many bindings the service has; fixed by configuration
     */
    public LogIngestMetrics(MeterRegistry registry, ParseTelemetry telemetry, Clock clock,
                            LogIngestMetricsProperties props, int bindingCount) {
        this.registry = registry;
        this.telemetry = telemetry;
        this.clock = clock;
        this.props = props;
        this.bindingLabelEnabled = bindingCount <= props.bindingLabelMax();
    }

    /**
     * The in-memory state behind the gauges.
     *
     * @return the telemetry
     */
    public ParseTelemetry telemetry() {
        return telemetry;
    }

    /**
     * The value of the {@code binding} label.
     *
     * @param bindingId the binding
     * @return the id while the label is enabled, else {@value LogIngestMetricsProperties#ALL_BINDINGS}
     */
    public String bindingLabel(String bindingId) {
        return bindingLabelEnabled ? bindingId : LogIngestMetricsProperties.ALL_BINDINGS;
    }

    /**
     * The bounded value of the {@code version} label.
     *
     * @param source   the source kind
     * @param format   the envelope
     * @param detected the version detected on the record
     * @return the version, {@value LogIngestMetricsProperties#UNKNOWN_VERSION} when none was detected, or
     *         {@value LogIngestMetricsProperties#OTHER_VERSION} once the (source, format) has
     *         {@link LogIngestMetricsProperties#maxVersionsPerFormat()} versions
     */
    public String versionLabel(String source, String format, Optional<FormatKey> detected) {
        if (detected.isEmpty()) {
            return LogIngestMetricsProperties.UNKNOWN_VERSION;
        }
        return versionLabel(source, format, detected.get().schemaVersion());
    }

    /**
     * The bounded value of the {@code version} label for a version string.
     *
     * @param source  the source kind
     * @param format  the envelope
     * @param version the version
     * @return the version, or {@value LogIngestMetricsProperties#OTHER_VERSION} once the (source, format)
     *         has {@link LogIngestMetricsProperties#maxVersionsPerFormat()} versions
     */
    public String versionLabel(String source, String format, String version) {
        Set<String> known = versionsByFormat.computeIfAbsent(source + "|" + format,
                k -> ConcurrentHashMap.newKeySet());
        if (known.contains(version)) {
            return version;
        }
        synchronized (known) {
            if (known.contains(version)) {
                return version;
            }
            if (known.size() >= props.maxVersionsPerFormat()) {
                return LogIngestMetricsProperties.OTHER_VERSION;
            }
            known.add(version);
            return version;
        }
    }

    /**
     * Records one outcome of the parse path: counters, delay, fill and heuristic ratios, the drift
     * state. Never throws.
     *
     * @param outcome the registry's outcome
     */
    public void recordOutcome(ParseOutcome outcome) {
        try {
            String source = outcome.record().sourceKind();
            String format = outcome.record().envelope();
            String version = versionLabel(source, format, outcome.detected());
            String binding = bindingLabel(outcome.bindingId());
            registry.counter(RECORDS, "source", source, "format", format, "version", version,
                    "outcome", outcome.result().outcome().name(), "binding", binding).increment();
            if (outcome.result() instanceof ParseResult.Quarantined q) {
                registry.counter(QUARANTINE, "source", source, "format", format, "version", version,
                        "reason", q.reasonCode(), "binding", binding).increment();
            }
            outcome.fingerprint().filter(ShapeFingerprint::hasUnknownFields).ifPresent(f ->
                    registry.counter(UNKNOWN_FIELDS, "source", source, "format", format, "version", version)
                            .increment(f.unknownPaths().size()));
            if (outcome.result() instanceof ParseResult.Parsed parsed) {
                registerFillGauges(source, version);
                recordDelays(source, parsed);
            }
            telemetry.recordParse(outcome, version);
        } catch (RuntimeException e) {
            logOnce("record the parse outcome", e);
        }
    }

    /**
     * Records what the replay store did with an event. Never throws.
     *
     * @param bindingId   the event's binding
     * @param disposition what happened
     */
    public void recordStore(String bindingId, Disposition disposition) {
        try {
            telemetry.recordStore(bindingId, disposition);
        } catch (RuntimeException e) {
            logOnce("record the store disposition", e);
        }
    }

    /**
     * Counts a failed Kafka send.
     *
     * @param topic the topic; anything that is not a DLAP topic is counted as {@code other}
     */
    public void publishFailure(String topic) {
        String tag = topic != null && topic.startsWith("dlap.") ? topic : "other";
        registry.counter(PUBLISH_FAILURES, "topic", tag).increment();
    }

    /**
     * Sets a snapshot gauge, registering it on first use.
     *
     * @param name     the meter name
     * @param baseUnit the base unit, or {@code null}
     * @param tags     the labels
     * @param value    the value; NaN reads as "unknown"
     */
    public void setGauge(String name, String baseUnit, Tags tags, double value) {
        Holder h = gauges.computeIfAbsent(new GaugeKey(name, tags), k -> {
            Holder created = new Holder();
            Gauge.builder(name, created, x -> x.value).tags(tags).baseUnit(baseUnit).strongReference(true)
                    .register(registry);
            return created;
        });
        h.value = value;
    }

    /**
     * Sets every gauge of a meter that is not in {@code keep} to NaN, so a binding or version that left
     * the configuration stops reporting an old value.
     *
     * @param name the meter name
     * @param keep the label sets that are current
     */
    public void retainGauges(String name, Set<Tags> keep) {
        gauges.forEach((k, h) -> {
            if (k.name().equals(name) && !keep.contains(k.tags())) {
                h.value = Double.NaN;
            }
        });
    }

    /**
     * Reports the startup self-test.
     *
     * @param ok whether every case passed
     */
    public void selfTest(boolean ok) {
        setGauge(SELFTEST_OK, null, Tags.empty(), ok ? 1 : 0);
    }

    private void registerFillGauges(String source, String version) {
        String key = source + "|" + version;
        if (fillRegistered.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        for (ParseTelemetry.FillField f : ParseTelemetry.FillField.values()) {
            Gauge.builder(FIELD_FILL, telemetry, t -> t.fillReading(source, version, f)
                            .map(RollingRatio.Reading::value).orElse(Double.NaN))
                    .tags("source", source, "version", version, "field", f.label())
                    .strongReference(true).register(registry);
        }
        if (fillRegistered.putIfAbsent("heuristic|" + source, Boolean.TRUE) == null) {
            Gauge.builder(HEURISTIC_RATIO, telemetry, t -> t.heuristicReading(source)
                            .map(RollingRatio.Reading::value).orElse(Double.NaN))
                    .tag("source", source).strongReference(true).register(registry);
        }
    }

    private void recordDelays(String source, ParseResult.Parsed parsed) {
        Instant now = clock.instant();
        DistributionSummary summary = delays.computeIfAbsent(source, s -> DistributionSummary.builder(EVENT_DELAY)
                .baseUnit("seconds").serviceLevelObjectives(DELAY_BUCKETS).tag("source", s).register(registry));
        for (CanonicalEvent e : parsed.events()) {
            summary.record(Math.max(0, Duration.between(e.eventTime(), now).toMillis() / 1000.0));
        }
    }

    private void logOnce(String what, RuntimeException e) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("Could not {} (metrics never stop the ingest; further failures are not logged): {}", what,
                    e.toString());
        }
    }
}
