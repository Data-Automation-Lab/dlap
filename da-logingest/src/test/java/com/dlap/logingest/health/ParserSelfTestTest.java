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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.metrics.LogHealthFixtures;
import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.metrics.LogIngestMetrics;
import com.dlap.logingest.metrics.LogIngestMetricsProperties;
import com.dlap.logingest.metrics.ParseTelemetry;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedParser;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/**
 * The startup self-test: the bundled golden cases pass on the real registry, a registry that lost a
 * parser fails them with the case ids, and the health component says so.
 */
class ParserSelfTestTest {

    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LogIngestMetricsProperties mprops = LogIngestMetricsProperties.defaults();
    private final LogIngestMetrics metrics = new LogIngestMetrics(meters, new ParseTelemetry(clock, mprops), clock,
            mprops, 1);
    private final CanonicalEventMapper mapper = new CanonicalEventMapper(clock);

    private ParserSelfTest test(DefaultParserRegistry registry, LogHealthProperties props, String location) {
        return new ParserSelfTest(registry, mapper, new ObjectMapper(), props, metrics, clock, location);
    }

    @Test
    void theBundledGoldenCasesPassOnTheRealRegistry() {
        ParserSelfTest t = test(LogHealthFixtures.registry(), LogHealthProperties.defaults(), ParserSelfTest.LOCATION);

        ParserSelfTest.Report r = t.run();

        assertThat(r.failures()).isEmpty();
        assertThat(r.ok()).isTrue();
        assertThat(r.cases()).isGreaterThanOrEqualTo(4);
        assertThat(r.results()).extracting(ParserSelfTest.CaseResult::id).contains(
                "dlap-normalized-aws-put-bucket", "dlap-normalized-aws-get-object",
                "dlap-normalized-aws-missing-result", "dlap-normalized-aws-not-json");
        assertThat(t.passed()).isTrue();
        assertThat(r.ranAt()).isEqualTo(LogHealthFixtures.T0);
    }

    @Test
    void aRegistryWithoutTheParserFailsTheCasesAndNamesThem() {
        // The AWS normalized parser is missing: its envelope is unknown, so every case quarantines or differs.
        DefaultParserRegistry broken = new DefaultParserRegistry(
                DlapNormalizedFormat.providers().stream().filter(p -> p != SourceProvider.AWS)
                        .map(DlapNormalizedParser::new).toList(),
                DlapNormalizedFormat.providers().stream().filter(p -> p != SourceProvider.AWS)
                        .map(p -> (VersionDetector) new DlapNormalizedDetector(p)).toList());
        ParserSelfTest t = test(broken, LogHealthProperties.defaults(), ParserSelfTest.LOCATION);

        ParserSelfTest.Report r = t.run();

        assertThat(r.ok()).isFalse();
        assertThat(t.passed()).isFalse();
        assertThat(r.failures()).extracting(ParserSelfTest.CaseResult::id)
                .contains("dlap-normalized-aws-put-bucket", "dlap-normalized-aws-get-object");
        assertThat(r.failures().get(0).detail()).contains("expected outcome").doesNotContain("AIDAEXAMPLE");
    }

    @Test
    void noGoldenCaseAtAllIsAFailure() {
        ParserSelfTest t = test(LogHealthFixtures.registry(), LogHealthProperties.defaults(),
                "classpath*:dlap/log-formats/nothing-here/*.json");

        ParserSelfTest.Report r = t.run();

        assertThat(r.ok()).isFalse();
        assertThat(r.failures()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("(none)");
            assertThat(f.detail()).contains("no golden case");
        });
    }

    @Test
    void theHealthComponentReportsUpAndDownWithTheFailedCases() {
        ParserSelfTest good = test(LogHealthFixtures.registry(), LogHealthProperties.defaults(),
                ParserSelfTest.LOCATION);
        LogParsersHealthIndicator indicator = new LogParsersHealthIndicator(good);
        assertThat(indicator.health().getStatus()).as("before the first run").isEqualTo(Status.UNKNOWN);

        good.run();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsKey("cases");

        ParserSelfTest empty = test(LogHealthFixtures.registry(), LogHealthProperties.defaults(),
                "classpath*:dlap/log-formats/nothing-here/*.json");
        empty.run();
        org.springframework.boot.health.contributor.Health down = new LogParsersHealthIndicator(empty).health();
        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails()).containsKey("failed");
    }

    @Test
    void theStartupHookExportsTheResultAndOnlyFailsFastWhenAsked() {
        ParserSelfTest good = test(LogHealthFixtures.registry(), LogHealthProperties.defaults(),
                ParserSelfTest.LOCATION);
        good.afterSingletonsInstantiated();
        assertThat(meters.find("dlap.logingest.parser.selftest.ok").gauge().value()).isEqualTo(1.0);

        LogHealthProperties tolerant = LogHealthProperties.defaults();
        ParserSelfTest bad = test(LogHealthFixtures.registry(), tolerant, "classpath*:dlap/log-formats/nope/*.json");
        bad.afterSingletonsInstantiated();
        assertThat(meters.find("dlap.logingest.parser.selftest.ok").gauge().value()).isEqualTo(0.0);

        LogHealthProperties strict = new LogHealthProperties(true, tolerant.statusInterval(), tolerant.degradedLag(),
                tolerant.degradedQuarantineRatio(), tolerant.degradedMinRecords(), tolerant.noReadTransports(),
                tolerant.quarantineWindow(), tolerant.topReasons(), tolerant.samplesPerReason(), true);
        ParserSelfTest strictBad = test(LogHealthFixtures.registry(), strict, "classpath*:dlap/log-formats/nope/*.json");
        assertThatThrownBy(strictBad::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selftest-fail-fast");
    }

    @Test
    void theBundledCasesAreOneFilePerCaseWithTheDocumentedKeys() throws Exception {
        List<org.springframework.core.io.Resource> files = List.of(
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources(ParserSelfTest.LOCATION));
        ObjectMapper json = new ObjectMapper();

        assertThat(files).isNotEmpty();
        for (org.springframework.core.io.Resource f : files) {
            var root = json.readTree(f.getInputStream());
            assertThat(root.path("id").asText()).as(f.getFilename()).isNotBlank();
            assertThat(root.path("binding").path("sourceKind").asText()).as(f.getFilename()).isNotBlank();
            assertThat(root.path("expect").path("outcome").asText()).as(f.getFilename()).isNotBlank();
            assertThat(root.has("record") || root.has("recordText")).as(f.getFilename()).isTrue();
        }
    }
}
