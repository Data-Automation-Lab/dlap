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

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.metrics.ParseTelemetry.DriftKey;
import com.dlap.logingest.metrics.ParseTelemetry.FillField;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The in-memory half of the parser health: outcome windows, fill ratios and their baseline, the
 * heuristic ratio, the read signal and the bounded unknown-path counts. Records go through the real
 * registry of the normalized family, so the telemetry sees genuine outcomes.
 */
class ParseTelemetryTest {

    private final DefaultParserRegistry registry = LogHealthFixtures.registry();
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);

    private ParseTelemetry telemetry(LogIngestMetricsProperties props) {
        return new ParseTelemetry(clock, props);
    }

    private ParseOutcome parse(String text) {
        return LogHealthFixtures.parse(registry, LogHealthFixtures.BINDING, text);
    }

    @Test
    void windowCountsFollowTheOutcomesAndRestartAtDrain() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        t.recordParse(parse(LogHealthFixtures.write("w1")), "v1");
        t.recordParse(parse(LogHealthFixtures.write("w2")), "v1");
        t.recordParse(parse(LogHealthFixtures.broken("b1")), "v1");
        // No event time: a valid record that is no event (DLAP never stamps business time).
        t.recordParse(parse(LogHealthFixtures.write("s1").replaceFirst("\"eventTime\":\"[^\"]*\",", "")), "v1");
        t.recordStore(LogHealthFixtures.BINDING, Disposition.PUBLISHED);
        t.recordStore(LogHealthFixtures.BINDING, Disposition.REPUBLISHED);
        t.recordStore(LogHealthFixtures.BINDING, Disposition.DUPLICATE);
        t.recordStore(LogHealthFixtures.BINDING, Disposition.FILTERED);

        clock.advance(Duration.ofMinutes(5));
        ParseTelemetry.WindowCounts first = t.drain(LogHealthFixtures.BINDING);

        // The normalized family always warns (heuristic classification), so a good record is PARSED_WITH_WARNINGS.
        assertThat(first.parsedWithWarnings()).isEqualTo(2);
        assertThat(first.quarantined()).isEqualTo(1);
        assertThat(first.skipped()).isEqualTo(1);
        assertThat(first.records()).isEqualTo(first.parsed() + first.parsedWithWarnings() + first.skipped()
                + first.quarantined());
        assertThat(first.published()).isEqualTo(2);
        assertThat(first.duplicates()).isEqualTo(1);
        assertThat(first.windowEnd()).isEqualTo(LogHealthFixtures.T0.plusSeconds(300));

        ParseTelemetry.WindowCounts second = t.drain(LogHealthFixtures.BINDING);
        assertThat(second.records()).isZero();
        assertThat(second.windowStart()).isEqualTo(first.windowEnd());
    }

    @Test
    void peekDoesNotStartANewWindow() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        t.recordParse(parse(LogHealthFixtures.write("w1")), "v1");

        assertThat(t.peek(LogHealthFixtures.BINDING).records()).isEqualTo(1);
        assertThat(t.peek(LogHealthFixtures.BINDING).records()).isEqualTo(1);
        assertThat(t.drain(LogHealthFixtures.BINDING).records()).isEqualTo(1);
    }

    @Test
    void fillRatioShowsAFieldThatStoppedArriving() {
        LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();
        ParseTelemetry t = telemetry(props);
        for (int i = 0; i < 100; i++) {
            t.recordParse(parse(LogHealthFixtures.write("a" + i)), "v1");
        }
        assertThat(t.fillReading(LogHealthFixtures.KIND, "v1", FillField.TARGET_REGION).orElseThrow().value())
                .isEqualTo(1.0);
        t.fillReadings();

        // 30 of the next 70 events lose the region: the test strategy's "recipientAccountId from 30% of a batch".
        for (int i = 0; i < 70; i++) {
            String text = i < 30 ? LogHealthFixtures.write("b" + i).replace(",\"targetRegion\":\"us-east-1\"", "")
                    : LogHealthFixtures.write("b" + i);
            t.recordParse(parse(text), "v1");
        }

        double region = t.fillReading(LogHealthFixtures.KIND, "v1", FillField.TARGET_REGION).orElseThrow().value();
        assertThat(region).isCloseTo(0.82, org.assertj.core.data.Offset.offset(0.01));
        assertThat(t.fillReading(LogHealthFixtures.KIND, "v1", FillField.TARGET_PROJECT).orElseThrow().value())
                .isEqualTo(1.0);
        assertThat(t.fillReading(LogHealthFixtures.KIND, "v1", FillField.CALLER_IP).orElseThrow().value())
                .as("a field that was never sent has ratio 0, not NaN").isEqualTo(0.0);
    }

    @Test
    void aDropBeyondTheThresholdIsListedAgainstTheBaseline() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        for (int i = 0; i < 100; i++) {
            t.recordParse(parse(LogHealthFixtures.write("a" + i)), "v1");
        }
        assertThat(t.fillDrops()).as("the baseline is set while the field is full").isEmpty();

        // An hour later only events without the region arrive.
        clock.advance(Duration.ofMinutes(61));
        for (int i = 0; i < 100; i++) {
            t.recordParse(parse(LogHealthFixtures.write("c" + i).replace(",\"targetRegion\":\"us-east-1\"", "")), "v1");
        }

        List<ParseTelemetry.FillReading> drops = t.fillDrops();
        assertThat(drops).extracting(ParseTelemetry.FillReading::field).containsExactly("target.region");
        assertThat(drops.get(0).baseline()).isEqualTo(1.0);
        assertThat(drops.get(0).ratio()).isEqualTo(0.0);
    }

    @Test
    void aFieldWithTooFewSamplesIsNotJudged() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        for (int i = 0; i < 10; i++) {
            t.recordParse(parse(LogHealthFixtures.write("a" + i).replace(",\"targetRegion\":\"us-east-1\"", "")), "v1");
        }

        assertThat(t.fillDrops()).isEmpty();
        assertThat(t.fillReadings()).allSatisfy(r -> assertThat(r.baseline()).isNaN());
    }

    @Test
    void theHeuristicRatioCountsEventsClassifiedByVerb() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        t.recordParse(parse(LogHealthFixtures.write("w1")), "v1");

        assertThat(t.heuristicReading(LogHealthFixtures.KIND).orElseThrow().value())
                .as("the normalized family classifies every event from the change type").isEqualTo(1.0);
        assertThat(t.heuristicReading("OTHER_KIND")).isEmpty();
    }

    @Test
    void theReadSignalTracksReadsAndWritesAndKeepsTheEarliestRead() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        assertThat(t.lastReadSeen(LogHealthFixtures.BINDING)).isEmpty();

        t.recordParse(parse(LogHealthFixtures.write("w1")), "v1");
        t.recordParse(parse(LogHealthFixtures.read("r1")), "v1");

        assertThat(t.lastWriteSeen(LogHealthFixtures.BINDING))
                .as("the normalized family derives MANAGEMENT for a create, never DATA_WRITE").isEmpty();
        assertThat(t.lastReadSeen(LogHealthFixtures.BINDING)).contains(LogHealthFixtures.T0);
        assertThat(t.firstReadEventTime(LogHealthFixtures.BINDING)).contains(LogHealthFixtures.T0.minusSeconds(30));
        assertThat(t.lastRecordAt(LogHealthFixtures.BINDING)).contains(LogHealthFixtures.T0);
    }

    @Test
    void aDataWriteEventMovesTheWriteSignal() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        CanonicalEvent write = CanonicalEvent.builder().nativeKey("evt-w").provider(SourceProvider.AWS)
                .service("s3.amazonaws.com").operation("PutObject").changeType(ChangeType.UPDATE)
                .category(EventCategory.EVENT_CATEGORY_DATA_WRITE).result(ResultStatus.SUCCESS)
                .eventTime(LogHealthFixtures.T0.minusSeconds(5)).build();
        FormatKey format = new FormatKey("AWS_CLOUDTRAIL", "cloudtrail-record", "1.11");
        ParseOutcome outcome = new ParseOutcome(LogHealthFixtures.record("cloudtrail-main", "{}"), "cloudtrail-main",
                ParseResult.Parsed.of(write), Optional.of(format), Optional.of(format), Optional.empty(),
                Optional.of("cloudtrail@1.0.0"), Optional.of(SourceProvider.AWS), Optional.empty());

        t.recordParse(outcome, "1.11");

        assertThat(t.lastWriteSeen("cloudtrail-main")).contains(LogHealthFixtures.T0);
        assertThat(t.lastReadSeen("cloudtrail-main")).isEmpty();
        assertThat(t.firstReadEventTime("cloudtrail-main")).isEmpty();
    }

    @Test
    void seedingKeepsTheLaterLastSeenAndTheEarlierFirstRead() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());
        t.recordParse(parse(LogHealthFixtures.read("r1")), "v1");
        Instant older = LogHealthFixtures.T0.minus(Duration.ofHours(3));

        t.seedReadSignal(LogHealthFixtures.BINDING, older, older, older.minus(Duration.ofDays(10)));

        assertThat(t.lastReadSeen(LogHealthFixtures.BINDING)).contains(LogHealthFixtures.T0);
        assertThat(t.lastWriteSeen(LogHealthFixtures.BINDING)).contains(older);
        assertThat(t.firstReadEventTime(LogHealthFixtures.BINDING)).contains(older.minus(Duration.ofDays(10)));
    }

    @Test
    void unknownPathsAreCountedBoundedAndSanitizedWithoutValues() {
        LogIngestMetricsProperties props = new LogIngestMetricsProperties(50, 32, Duration.ofHours(1), 50,
                Duration.ofMinutes(1), Duration.ofHours(24), 0.2, 2, 2);
        ParseTelemetry t = telemetry(props);
        // The normalized family declares its members, so extra members are unknown paths.
        String extra = ",\"surprise\":\"secret-value-1\",\"another\":{\"deep\":\"secret-value-2\"},\"third\":1";
        t.recordParse(parse(LogHealthFixtures.write("u1").replace("}", extra + "}")), "v1");
        t.recordParse(parse(LogHealthFixtures.write("u2").replace("}", extra + "}")), "v1");

        List<ParseTelemetry.UnknownPath> paths = t.unknownPaths(10);
        assertThat(paths).hasSize(2);
        assertThat(paths).allSatisfy(p -> {
            assertThat(p.count()).isEqualTo(2);
            assertThat(p.path()).doesNotContain("secret");
        });
        assertThat(t.unknownPathOverflow(new DriftKey(LogHealthFixtures.KIND, LogHealthFixtures.ENVELOPE, "v1")))
                .as("the third path did not fit and was counted as overflow").isEqualTo(2);
        assertThat(t.unknownPathsOf(LogHealthFixtures.KIND, 1)).hasSize(1);
        assertThat(t.unknownPathsOf("OTHER_KIND", 5)).isEmpty();
    }

    @Test
    void controlCharactersAndLengthAreBoundedInPaths() {
        assertThat(ParseTelemetry.sanitize("/a\nb\u0000c")).isEqualTo("/a?b?c");
        assertThat(ParseTelemetry.sanitize("/" + "x".repeat(500))).hasSize(ParseTelemetry.MAX_PATH_LENGTH);
    }

    @Test
    void aStoreEventWithoutABindingIsIgnored() {
        ParseTelemetry t = telemetry(LogIngestMetricsProperties.defaults());

        t.recordStore("", Disposition.PUBLISHED);
        t.recordStore(null, Disposition.DUPLICATE);

        assertThat(t.lastRecordAt("")).isEmpty();
    }
}
