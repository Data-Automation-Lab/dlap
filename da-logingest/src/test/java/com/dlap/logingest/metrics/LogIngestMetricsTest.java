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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.service.registry.Disposition;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The meters of the parser health, read the way Prometheus reads them: through a
 * {@link PrometheusMeterRegistry} scrape. The Prometheus names are asserted exactly because the
 * alert rules (alerts.yml, group dlap-log-ingest) are written against them.
 */
class LogIngestMetricsTest {

    private final DefaultParserRegistry registry = LogHealthFixtures.registry();
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private LogIngestMetrics metrics(int bindings, LogIngestMetricsProperties props) {
        return new LogIngestMetrics(prometheus, new ParseTelemetry(clock, props), clock, props, bindings);
    }

    private ParseOutcome parse(String text) {
        return LogHealthFixtures.parse(registry, LogHealthFixtures.BINDING, text);
    }

    private List<String> series(String name) {
        return prometheus.scrape().lines().filter(l -> !l.startsWith("#") && (l.startsWith(name + "{")
                || l.startsWith(name + " "))).collect(Collectors.toList());
    }

    @Test
    void recordsAndQuarantinesCarryTheDocumentedLabels() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());

        m.recordOutcome(parse(LogHealthFixtures.write("w1")));
        m.recordOutcome(parse(LogHealthFixtures.write("w2")));
        m.recordOutcome(parse(LogHealthFixtures.broken("b1")));
        m.recordOutcome(parse("this is not json"));

        List<String> records = series("dlap_logingest_records_total");
        assertThat(records).anySatisfy(l -> assertThat(l).contains("source=\"DLAP_NORMALIZED_AWS\"",
                "format=\"ingest-dto\"", "version=\"v1\"", "outcome=\"PARSED_WITH_WARNINGS\"",
                "binding=\"dlap-rest-aws\"").endsWith(" 2.0"));
        assertThat(records).anySatisfy(l -> assertThat(l).contains("outcome=\"QUARANTINED\"", "version=\"v1\""));
        assertThat(records).anySatisfy(l -> assertThat(l).contains("outcome=\"QUARANTINED\"",
                "version=\"unknown\""));

        List<String> quarantine = series("dlap_logingest_quarantine_total");
        assertThat(quarantine).anySatisfy(l -> assertThat(l).contains("reason=\"MISSING_REQUIRED_FIELD\"",
                "source=\"DLAP_NORMALIZED_AWS\"").endsWith(" 1.0"));
        assertThat(quarantine).anySatisfy(l -> assertThat(l).contains("reason=\"INVALID_JSON\""));
    }

    @Test
    void unknownFieldsAreCountedByPathNotByLabel() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());

        m.recordOutcome(parse(LogHealthFixtures.write("u1").replace("}", ",\"surprise\":\"v\",\"another\":1}")));

        assertThat(series("dlap_logingest_unknown_fields_total")).singleElement().satisfies(l ->
                assertThat(l).contains("version=\"v1\"").doesNotContain("surprise").endsWith(" 2.0"));
    }

    @Test
    void theVersionLabelIsBoundedPerSourceAndFormat() {
        LogIngestMetricsProperties props = new LogIngestMetricsProperties(50, 2, Duration.ofHours(1), 50,
                Duration.ofMinutes(1), Duration.ofHours(24), 0.2, 200, 100);
        LogIngestMetrics m = metrics(2, props);

        assertThat(m.versionLabel("K", "e", Optional.of(new FormatKey("K", "e", "1.1")))).isEqualTo("1.1");
        assertThat(m.versionLabel("K", "e", Optional.of(new FormatKey("K", "e", "1.2")))).isEqualTo("1.2");
        assertThat(m.versionLabel("K", "e", Optional.of(new FormatKey("K", "e", "9.9")))).isEqualTo("other");
        assertThat(m.versionLabel("K", "e", Optional.of(new FormatKey("K", "e", "1.1")))).as("known stays known")
                .isEqualTo("1.1");
        assertThat(m.versionLabel("K", "other-envelope", Optional.of(new FormatKey("K", "other-envelope", "9.9"))))
                .as("the cap is per source and format").isEqualTo("9.9");
        assertThat(m.versionLabel("K", "e", Optional.empty())).isEqualTo("unknown");
    }

    @Test
    void theBindingLabelCollapsesWhenThereAreMoreBindingsThanTheMaximum() {
        LogIngestMetricsProperties props = new LogIngestMetricsProperties(2, 32, Duration.ofHours(1), 50,
                Duration.ofMinutes(1), Duration.ofHours(24), 0.2, 200, 100);

        assertThat(metrics(2, props).bindingLabel("b1")).isEqualTo("b1");
        LogIngestMetrics many = metrics(3, props);
        assertThat(many.bindingLabel("b1")).isEqualTo("_all");

        many.recordOutcome(parse(LogHealthFixtures.write("w1")));
        assertThat(series("dlap_logingest_records_total")).singleElement()
                .satisfies(l -> assertThat(l).contains("binding=\"_all\"").doesNotContain("dlap-rest-aws"));
    }

    @Test
    void eventDelayIsAHistogramInSeconds() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());

        m.recordOutcome(parse(LogHealthFixtures.write("w1")));

        assertThat(series("dlap_logingest_event_delay_seconds_count")).singleElement()
                .satisfies(l -> assertThat(l).contains("source=\"DLAP_NORMALIZED_AWS\"").endsWith(" 1"));
        assertThat(series("dlap_logingest_event_delay_seconds_sum")).singleElement()
                .satisfies(l -> assertThat(l).endsWith(" 30.0"));
        assertThat(series("dlap_logingest_event_delay_seconds_bucket")).anySatisfy(l ->
                assertThat(l).contains("le=\"60.0\"").endsWith(" 1"));
        assertThat(series("dlap_logingest_event_delay_seconds_bucket")).anySatisfy(l ->
                assertThat(l).contains("le=\"5.0\"").endsWith(" 0"));
    }

    @Test
    void fillAndHeuristicRatiosAreGauges() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());

        m.recordOutcome(parse(LogHealthFixtures.write("w1")));
        m.recordOutcome(parse(LogHealthFixtures.write("w2").replace(",\"targetRegion\":\"us-east-1\"", "")));

        assertThat(series("dlap_logingest_field_fill_ratio")).anySatisfy(l -> assertThat(l)
                .contains("field=\"target.region\"", "source=\"DLAP_NORMALIZED_AWS\"", "version=\"v1\"")
                .endsWith(" 0.5"));
        assertThat(series("dlap_logingest_field_fill_ratio")).anySatisfy(l -> assertThat(l)
                .contains("field=\"principal.id\"").endsWith(" 1.0"));
        assertThat(series("dlap_logingest_classification_heuristic_ratio")).singleElement()
                .satisfies(l -> assertThat(l).contains("source=\"DLAP_NORMALIZED_AWS\"").endsWith(" 1.0"));
    }

    @Test
    void aFillGaugeWithoutEventsInTheWindowReadsNaNNotZero() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());
        m.recordOutcome(parse(LogHealthFixtures.write("w1")));

        clock.advance(Duration.ofHours(2));

        assertThat(series("dlap_logingest_field_fill_ratio")).allSatisfy(l -> assertThat(l).endsWith(" NaN"));
    }

    @Test
    void publishFailuresAreCountedByDlapTopic() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());

        m.publishFailure("dlap.log.event.quarantine");
        m.publishFailure("dlap.log.event.quarantine");
        m.publishFailure("some.customer.topic");
        m.publishFailure(null);

        List<String> lines = series("dlap_logingest_publish_failures_total");
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("topic=\"dlap.log.event.quarantine\"")
                .endsWith(" 2.0"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("topic=\"other\"").endsWith(" 2.0"));
    }

    @Test
    void snapshotGaugesKeepTheirNamesAndUnitsAndGoNaNWhenTheirLabelsLeave() {
        LogIngestMetrics m = metrics(2, LogIngestMetricsProperties.defaults());
        Tags lagA = Tags.of("source", "GCP_CLOUD_AUDIT", "stream", "logging-api", "binding", "gcp-a");
        Tags lagB = Tags.of("source", "GCP_CLOUD_AUDIT", "stream", "logging-api", "binding", "gcp-b");

        m.setGauge(LogIngestMetrics.CURSOR_LAG, "seconds", lagA, 120);
        m.setGauge(LogIngestMetrics.CURSOR_LAG, "seconds", lagB, 3600);
        m.setGauge(LogIngestMetrics.READ_SIGNAL, null, Tags.of("binding", "gcp-a"), 0);
        m.setGauge(LogIngestMetrics.VERSION_FIRST_SEEN, "seconds",
                Tags.of("source", "K", "format", "e", "version", "1.1"), 1_700_000_000);
        m.setGauge(LogIngestMetrics.VERSION_OBSERVED, null, Tags.of("source", "K", "format", "e", "version", "1.1"), 1);
        m.selfTest(true);

        assertThat(series("dlap_logingest_cursor_lag_seconds")).hasSize(2);
        assertThat(series("dlap_logingest_cursor_lag_seconds")).anySatisfy(l -> assertThat(l)
                .contains("binding=\"gcp-b\"").endsWith(" 3600.0"));
        assertThat(series("dlap_logingest_read_signal_present")).singleElement()
                .satisfies(l -> assertThat(l).contains("binding=\"gcp-a\"").endsWith(" 0.0"));
        assertThat(series("dlap_logingest_version_first_seen_timestamp_seconds")).singleElement()
                .satisfies(l -> assertThat(l).endsWith(" 1.7E9"));
        assertThat(series("dlap_logingest_version_observed")).singleElement();
        assertThat(series("dlap_logingest_parser_selftest_ok")).singleElement()
                .satisfies(l -> assertThat(l).endsWith(" 1.0"));

        m.retainGauges(LogIngestMetrics.CURSOR_LAG, Set.of(lagA));

        assertThat(series("dlap_logingest_cursor_lag_seconds")).anySatisfy(l -> assertThat(l)
                .contains("binding=\"gcp-b\"").endsWith(" NaN"));
        assertThat(series("dlap_logingest_cursor_lag_seconds")).anySatisfy(l -> assertThat(l)
                .contains("binding=\"gcp-a\"").endsWith(" 120.0"));
    }

    @Test
    void recordingNeverThrowsEvenWhenTheRegistryDoes() {
        MeterRegistry broken = mock(MeterRegistry.class);
        LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();
        LogIngestMetrics m = new LogIngestMetrics(broken, new ParseTelemetry(clock, props), clock, props, 1);

        m.recordOutcome(parse(LogHealthFixtures.write("w1")));
        m.recordStore("dlap-rest-aws", Disposition.PUBLISHED);
        m.recordStore("dlap-rest-aws", Disposition.DUPLICATE);

        assertThat(m.telemetry().peek("dlap-rest-aws").published()).isEqualTo(1);
    }
}
