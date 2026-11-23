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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.health.HealthSnapshot.BindingSnapshot;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.metrics.LogIngestMetrics;
import com.dlap.logingest.metrics.LogIngestMetricsProperties;
import com.dlap.logingest.metrics.ParseTelemetry;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.quarantine.QuarantineFixtures;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.proto.log.v1.ReadCoverage;
import com.dlap.proto.log.v1.SourceProvider;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The health snapshot on the real tables: cursors and observations joined with the configuration, the
 * read signal seeded from stored events, and the gauges a scrape reads. Two GCP bindings and one
 * CloudTrail LookupEvents binding on PostgreSQL by Testcontainers (needs Docker), with a clock.
 */
class HealthSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(NOW);
    private final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();
    private final ParseTelemetry telemetry = new ParseTelemetry(clock, props);
    private final LogIngestMetrics metrics = new LogIngestMetrics(prometheus, telemetry, clock, props, 3);

    @BeforeAll
    static void database() {
        jdbc = new JdbcTemplate(LogIngestPostgres.migratedDatabase());
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_logingest.log_events");
        jdbc.update("DELETE FROM dlap_logingest.format_observation");
        jdbc.update("DELETE FROM dlap_logingest.stream_cursor");
        for (String b : List.of("gcp-reads", "gcp-writes", "aws-lookup")) {
            QuarantineFixtures.insertBinding(jdbc, b);
        }
    }

    private static EffectiveBinding binding(String id, String kind, SourceProvider provider, String transport,
                                            String envelope, String pin, Map<String, Object> scope) {
        BindingConfig parse = BindingConfig.auto(id, kind);
        if (!"auto".equals(pin)) {
            parse = parse.pinned(pin, com.dlap.logingest.parse.core.OnMismatch.DEFAULT);
        }
        EffectiveStream stream = new EffectiveStream(id, "main", transport, envelope, Map.of(), parse,
                StreamFilters.NONE, Duration.ZERO, Duration.ofMinutes(1), null, null, false, false, false);
        return EffectiveBinding.of(id, "src-" + id, kind, java.util.Optional.of(provider), ConfigOrigin.FILE, true,
                true, scope, List.of(stream));
    }

    private HealthSnapshotService service(JdbcTemplate template, PipelineMode pipeline) {
        EffectiveLogSources sources = new EffectiveLogSources(pipeline, "FILE", List.of(
                binding("gcp-reads", "GCP_CLOUD_AUDIT", SourceProvider.GCP, "LOGGING_API", "gcp-audit-log", "auto",
                        Map.of("project-id", "dlap-demo")),
                binding("gcp-writes", "GCP_CLOUD_AUDIT", SourceProvider.GCP, "LOGGING_API", "gcp-audit-log", "auditlog",
                        Map.of("project-id", "dlap-other")),
                binding("aws-lookup", "AWS_CLOUDTRAIL", SourceProvider.AWS, "LOOKUP_EVENTS", "cloudtrail-record", "auto",
                        Map.of("account-id", "123456789012"))), ValidationReport.CLEAN);
        return new HealthSnapshotService(sources, new HealthSnapshotRepository(template, LogIngestPostgres.SCHEMA),
                telemetry, metrics, props, LogHealthProperties.defaults(),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));
    }

    private void cursor(String binding, Instant low, Instant high, Instant updated) {
        jdbc.update("INSERT INTO dlap_logingest.stream_cursor (binding_id, stream_id, partition_key, low_watermark, "
                        + "high_watermark, cursor, updated_at) VALUES (?, 'main', '', ?, ?, '{}'::jsonb, ?)", binding,
                at(low), at(high), at(updated));
    }

    private void observation(String binding, String envelope, String version, Instant first, Instant last, long n) {
        jdbc.update("INSERT INTO dlap_logingest.format_observation (binding_id, envelope, schema_version, fingerprint, "
                + "first_seen, last_seen, record_count) VALUES (?, ?, ?, '', ?, ?, ?)", binding, envelope, version,
                at(first), at(last), n);
    }

    private void event(String id, String binding, String category, Instant eventTime, Instant ingestTime) {
        jdbc.update("INSERT INTO dlap_logingest.log_events (event_id, event_time, ingest_time, source_provider, "
                        + "change_type, operation, schema_version, binding_id, event_category) "
                        + "VALUES (?, ?, ?, 'GCP', 'ACCESS', 'op', 'v1', ?, ?)", id, at(eventTime), at(ingestTime),
                binding, category);
    }

    private static OffsetDateTime at(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private String gauge(String name, String... labels) {
        return prometheus.scrape().lines().filter(l -> l.startsWith(name + "{"))
                .filter(l -> java.util.Arrays.stream(labels).allMatch(l::contains)).collect(Collectors.joining("\n"));
    }

    @Test
    void theSnapshotJoinsConfigurationCursorsAndObservations() {
        cursor("gcp-reads", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.minusSeconds(5));
        observation("gcp-reads", "gcp-audit-log", "auditlog", NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(3), 900);
        observation("gcp-writes", "gcp-audit-log", "bq-auditmetadata", NOW.minus(Duration.ofDays(2)), NOW.minusSeconds(9), 5);

        HealthSnapshot snapshot = service(jdbc, PipelineMode.REGISTRY).build();

        assertThat(snapshot.at()).isEqualTo(NOW);
        assertThat(snapshot.bindings()).extracting(BindingSnapshot::bindingId)
                .containsExactly("aws-lookup", "gcp-reads", "gcp-writes");
        BindingSnapshot reads = snapshot.bindings().get(1);
        assertThat(reads.runs()).isTrue();
        assertThat(reads.sourceKind()).isEqualTo("GCP_CLOUD_AUDIT");
        assertThat(reads.provider()).contains(SourceProvider.GCP);
        assertThat(reads.scope()).containsEntry("project-id", "dlap-demo");
        assertThat(reads.streams()).singleElement().satisfies(s -> {
            assertThat(s.transport()).isEqualTo("LOGGING_API");
            assertThat(s.pinnedVersion()).isEqualTo("auto");
            assertThat(s.lowWatermark()).isEqualTo(NOW.minusSeconds(120));
            assertThat(s.highWatermark()).isEqualTo(NOW.minusSeconds(10));
            assertThat(s.updatedAt()).isEqualTo(NOW.minusSeconds(5));
        });
        assertThat(reads.lowWatermark()).contains(NOW.minusSeconds(120));
        assertThat(reads.lastPoll()).contains(NOW.minusSeconds(5));
        assertThat(reads.observed()).singleElement().satisfies(o -> {
            assertThat(o.version()).isEqualTo("auditlog");
            assertThat(o.count()).isEqualTo(900);
        });
        BindingSnapshot writes = snapshot.bindings().get(2);
        assertThat(writes.streams().get(0).pinnedVersion()).isEqualTo("auditlog");
        assertThat(writes.lowWatermark()).as("no cursor yet").isEmpty();
    }

    @Test
    void theLegacyPipelineSnapshotMarksNothingAsRunning() {
        HealthSnapshot snapshot = service(jdbc, PipelineMode.LEGACY).build();

        assertThat(snapshot.bindings()).allSatisfy(b -> assertThat(b.runs()).isFalse());
    }

    @Test
    void refreshSetsTheCursorLagVersionAndReadSignalGauges() {
        cursor("gcp-reads", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.minusSeconds(5));
        cursor("gcp-writes", NOW.minus(Duration.ofHours(1)), NOW.minusSeconds(10), NOW.minusSeconds(5));
        observation("gcp-reads", "gcp-audit-log", "auditlog", NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(3), 900);
        observation("gcp-reads", "gcp-audit-log", "old-version", NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(3)), 4);
        event("r1", "gcp-reads", "EVENT_CATEGORY_DATA_READ", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(3)));
        event("r0", "gcp-reads", "EVENT_CATEGORY_DATA_READ", NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofHours(5)));
        event("w1", "gcp-writes", "EVENT_CATEGORY_DATA_WRITE", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));
        event("m1", "gcp-writes", "EVENT_CATEGORY_MANAGEMENT", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));
        HealthSnapshotService service = service(jdbc, PipelineMode.REGISTRY);

        assertThat(service.refresh()).isPresent();

        assertThat(gauge("dlap_logingest_cursor_lag_seconds", "binding=\"gcp-reads\"", "stream=\"main\"",
                "source=\"GCP_CLOUD_AUDIT\"")).endsWith(" 120.0");
        assertThat(gauge("dlap_logingest_cursor_lag_seconds", "binding=\"gcp-writes\"")).endsWith(" 3600.0");
        assertThat(gauge("dlap_logingest_cursor_lag_seconds", "binding=\"aws-lookup\"")).as("no cursor, no series")
                .isEmpty();
        assertThat(gauge("dlap_logingest_version_observed", "version=\"auditlog\"")).endsWith(" 1.0");
        assertThat(gauge("dlap_logingest_version_observed", "version=\"old-version\""))
                .as("seen three days ago, outside the 24 hour lookback").endsWith(" 0.0");
        assertThat(gauge("dlap_logingest_version_first_seen_timestamp_seconds", "version=\"auditlog\""))
                .contains("format=\"gcp-audit-log\"", "source=\"GCP_CLOUD_AUDIT\"");
        assertThat(gauge("dlap_logingest_read_signal_present", "binding=\"gcp-reads\"")).endsWith(" 1.0");
        assertThat(gauge("dlap_logingest_read_signal_present", "binding=\"gcp-writes\""))
                .as("writes but no read in the lookback: read logging may be off").endsWith(" 0.0");
        assertThat(gauge("dlap_logingest_read_signal_present", "binding=\"aws-lookup\""))
                .as("LookupEvents cannot carry reads, so there is no signal to alert on").endsWith(" NaN");
    }

    @Test
    void readCoverageIsDerivedFromTheSeededSignal() {
        event("r1", "gcp-reads", "EVENT_CATEGORY_DATA_READ", NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofHours(3)));
        event("r2", "gcp-reads", "EVENT_CATEGORY_DATA_READ", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(2)));
        event("w1", "gcp-writes", "EVENT_CATEGORY_DATA_WRITE", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));
        HealthSnapshotService service = service(jdbc, PipelineMode.REGISTRY);
        HealthSnapshot snapshot = service.refresh().orElseThrow();

        ReadCoverageEvaluator.Evaluation reads = service.readCoverage(snapshot.bindings().get(1));
        ReadCoverageEvaluator.Evaluation writes = service.readCoverage(snapshot.bindings().get(2));
        ReadCoverageEvaluator.Evaluation lookup = service.readCoverage(snapshot.bindings().get(0));

        assertThat(reads.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_CAPTURED);
        assertThat(reads.since()).as("the earliest read event time").contains(NOW.minus(Duration.ofDays(9)));
        assertThat(writes.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NONE_OBSERVED);
        assertThat(writes.signal()).isZero();
        assertThat(lookup.coverage()).isEqualTo(ReadCoverage.READ_COVERAGE_NOT_CAPTURED);
    }

    @Test
    void aSeriesWhoseRowDisappearsGoesNaNOnTheNextRefresh() {
        cursor("gcp-reads", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.minusSeconds(5));
        observation("gcp-reads", "gcp-audit-log", "auditlog", NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(3), 9);
        HealthSnapshotService service = service(jdbc, PipelineMode.REGISTRY);
        service.refresh();
        assertThat(gauge("dlap_logingest_cursor_lag_seconds", "binding=\"gcp-reads\"")).endsWith(" 120.0");

        jdbc.update("DELETE FROM dlap_logingest.stream_cursor");
        jdbc.update("DELETE FROM dlap_logingest.format_observation");
        service.refresh();

        assertThat(gauge("dlap_logingest_cursor_lag_seconds", "binding=\"gcp-reads\"")).endsWith(" NaN");
        assertThat(gauge("dlap_logingest_version_observed", "version=\"auditlog\"")).endsWith(" NaN");
    }

    @Test
    void whenTheDatabaseIsDownRefreshKeepsThePreviousSnapshot() {
        cursor("gcp-reads", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.minusSeconds(5));
        HealthSnapshotService good = service(jdbc, PipelineMode.REGISTRY);
        HealthSnapshot before = good.refresh().orElseThrow();

        JdbcTemplate down = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:1/none"));
        HealthSnapshotService broken = service(down, PipelineMode.REGISTRY);

        assertThat(broken.refresh()).isEmpty();
        assertThat(good.current()).isSameAs(before);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(broken::current))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void manyBindingsShareOneAllLabelAndTheWorstValueWins() {
        LogIngestMetrics many = new LogIngestMetrics(prometheus, telemetry, clock, props, 51);
        cursor("gcp-reads", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.minusSeconds(5));
        cursor("gcp-writes", NOW.minus(Duration.ofHours(1)), NOW.minusSeconds(10), NOW.minusSeconds(5));
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(
                binding("gcp-reads", "GCP_CLOUD_AUDIT", SourceProvider.GCP, "LOGGING_API", "gcp-audit-log", "auto",
                        Map.of("project-id", "dlap-demo")),
                binding("gcp-writes", "GCP_CLOUD_AUDIT", SourceProvider.GCP, "LOGGING_API", "gcp-audit-log", "auto",
                        Map.of("project-id", "dlap-other"))), ValidationReport.CLEAN);
        HealthSnapshotService service = new HealthSnapshotService(sources,
                new HealthSnapshotRepository(jdbc, LogIngestPostgres.SCHEMA), telemetry, many, props,
                LogHealthProperties.defaults(),
                new StaticListableBeanFactory(Map.of("clock", clock)).getBeanProvider(java.time.Clock.class));

        service.refresh();

        assertThat(gauge("dlap_logingest_cursor_lag_seconds")).contains("binding=\"_all\"").endsWith(" 3600.0")
                .doesNotContain("gcp-reads");
    }

    @Test
    void everyLogAlertReadsASeriesTheServiceReallyExports() throws Exception {
        cursor("gcp-reads", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.minusSeconds(5));
        observation("gcp-reads", "gcp-audit-log", "auditlog", NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(3), 9);
        event("r1", "gcp-reads", "EVENT_CATEGORY_DATA_READ", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(3)));
        var registry = com.dlap.logingest.metrics.LogHealthFixtures.registry();
        metrics.recordOutcome(com.dlap.logingest.metrics.LogHealthFixtures.parse(registry,
                com.dlap.logingest.metrics.LogHealthFixtures.BINDING,
                com.dlap.logingest.metrics.LogHealthFixtures.read("alert-1")));
        metrics.recordOutcome(com.dlap.logingest.metrics.LogHealthFixtures.parse(registry,
                com.dlap.logingest.metrics.LogHealthFixtures.BINDING,
                com.dlap.logingest.metrics.LogHealthFixtures.broken("alert-2")));
        metrics.publishFailure("dlap.log.event.quarantine");
        metrics.selfTest(true);
        service(jdbc, PipelineMode.REGISTRY).refresh();

        java.util.Set<String> exported = prometheus.scrape().lines().filter(l -> !l.startsWith("#") && !l.isBlank())
                .map(l -> l.split("[{ ]", 2)[0]).collect(Collectors.toSet());
        java.nio.file.Path rules = java.nio.file.Path.of("..", "deployment", "platform", "prometheus", "alerts.yml");
        String yaml = java.nio.file.Files.readString(rules);
        String group = yaml.substring(yaml.indexOf("  - name: dlap-log-ingest"));
        String expressions = group.lines().filter(l -> !l.stripLeading().startsWith("#")).collect(Collectors.joining(System.lineSeparator()));
        java.util.Set<String> used = new java.util.TreeSet<>();
        var m = java.util.regex.Pattern.compile("dlap_logingest_[a-z0-9_]+").matcher(expressions);
        while (m.find()) {
            used.add(m.group());
        }

        assertThat(used).as("the log group of alerts.yml reads these da-logingest series").contains(
                "dlap_logingest_records_total", "dlap_logingest_quarantine_total",
                "dlap_logingest_version_first_seen_timestamp_seconds", "dlap_logingest_field_fill_ratio",
                "dlap_logingest_read_signal_present", "dlap_logingest_cursor_lag_seconds",
                "dlap_logingest_publish_failures_total");
        assertThat(exported).containsAll(used);

        // The two catalog counters of SpineUnmatchedTargetsHigh are declared by the catalog's own meters.
        String catalog = java.nio.file.Files.readString(java.nio.file.Path.of("..", "da-catalog", "src", "main", "java",
                "com", "dlap", "catalog", "kafka", "CatalogIngestMetrics.java"));
        assertThat(expressions).contains("dlap_catalog_unmatched_target_total", "dlap_catalog_asset_match_total");
        assertThat(catalog).contains("dlap.catalog.unmatched.target", "dlap.catalog.asset.match");
    }
}
