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

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.metrics.LogIngestMetrics;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The startup self-test of the parser registry (test strategy L8): a bundled mini golden set runs
 * through the real registry and mapper when the service starts, and the result is the
 * {@code logParsers} component of {@code /actuator/health}. A build whose parser, mapping or event
 * mapper is broken reports DOWN before it has touched any data.
 *
 * <h3>Golden cases</h3>
 * Every file {@value #LOCATION} on the class path is one case. A parser family ships its own cases
 * next to its mapping, so the self-test grows with the families; the DLAP-normalized family ships
 * three. A case file:
 * <pre>{@code
 * {
 *   "id": "dlap-normalized-aws-put-bucket",
 *   "binding": { "id": "dlap-selftest", "sourceKind": "DLAP_NORMALIZED_AWS", "envelope": "ingest-dto" },
 *   "record": { ... }            // the record as JSON, or "recordText": "..." for text that is not JSON
 *   "expect": { "outcome": "PARSED_WITH_WARNINGS", "events": 1, "operation": "PutBucket",
 *               "changeType": "CREATE", "category": "EVENT_CATEGORY_MANAGEMENT" }
 * }
 * }</pre>
 * {@code outcome} is required (the spelling of fixture {@code expected_outcome}); the other keys are
 * checked when present. {@code events} counts the mapper's events, so the pass-through event of a
 * quarantine counts. Every event must also pass {@code DlapLogEventCodec#decodeStrict}.
 *
 * <p>The test runs on the {@code DefaultParserRegistry} itself, not on the metered wrapper, so it
 * leaves the counters alone. No cases at all is a failure: a service that finds no golden set was
 * built wrongly. With {@code dlap.logingest.health.selftest-fail-fast=true} a failure stops the start.
 */
@Component
public class ParserSelfTest implements SmartInitializingSingleton {

    /** Where golden cases are looked up. */
    public static final String LOCATION = "classpath*:dlap/log-formats/golden/*.json";

    private static final Logger log = LoggerFactory.getLogger(ParserSelfTest.class);

    /**
     * The result of one case.
     *
     * @param id     the case id
     * @param passed whether every expectation held
     * @param detail what differed, empty when it passed; names fields, never values
     */
    public record CaseResult(String id, boolean passed, String detail) {
    }

    /**
     * The result of a run.
     *
     * @param ok      true when at least one case ran and all passed
     * @param cases   cases run
     * @param results one entry per case, failures included
     * @param ranAt   when the run finished
     */
    public record Report(boolean ok, int cases, List<CaseResult> results, Instant ranAt) {

        /**
         * The failed cases.
         *
         * @return the failures
         */
        public List<CaseResult> failures() {
            return results.stream().filter(r -> !r.passed()).toList();
        }
    }

    private final DefaultParserRegistry registry;
    private final CanonicalEventMapper mapper;
    private final ObjectMapper json;
    private final LogHealthProperties props;
    private final LogIngestMetrics metrics;
    private final Clock clock;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final String location;
    private volatile Report report;

    /**
     * Creates the self-test.
     *
     * @param registry the registry that serves the service
     * @param mapper   the event mapper
     * @param json     the JSON mapper
     * @param props    the health settings
     * @param metrics  where the result is exported
     * @param clock    the application clock when unique, else the UTC system clock
     */
    @Autowired
    public ParserSelfTest(DefaultParserRegistry registry, CanonicalEventMapper mapper, ObjectMapper json,
                          LogHealthProperties props, LogIngestMetrics metrics, ObjectProvider<Clock> clock) {
        this(registry, mapper, json, props, metrics, clock.getIfUnique(Clock::systemUTC), LOCATION);
    }

    /**
     * Creates the self-test with another golden set location, for tests.
     *
     * @param registry the registry that serves the service
     * @param mapper   the event mapper
     * @param json     the JSON mapper
     * @param props    the health settings
     * @param metrics  where the result is exported
     * @param clock    the clock
     * @param location a resource pattern for the golden cases
     */
    ParserSelfTest(DefaultParserRegistry registry, CanonicalEventMapper mapper, ObjectMapper json,
                   LogHealthProperties props, LogIngestMetrics metrics, Clock clock, String location) {
        this.location = location;
        this.registry = registry;
        this.mapper = mapper;
        this.json = json;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Report r = run();
        metrics.selfTest(r.ok());
        if (r.ok()) {
            log.info("Parser self-test passed: {} golden case(s)", r.cases());
            return;
        }
        r.failures().forEach(f -> log.error("Parser self-test case {} failed: {}", f.id(), f.detail()));
        if (props.selftestFailFast()) {
            throw new IllegalStateException("the parser self-test failed (" + r.failures().size() + " of "
                    + r.cases() + " cases); dlap.logingest.health.selftest-fail-fast is on");
        }
    }

    /**
     * Whether the last run passed.
     *
     * @return true when the test has not run yet or its last run passed
     */
    public boolean passed() {
        Report r = report;
        return r == null || r.ok();
    }

    /**
     * The last report.
     *
     * @return the report, or {@code null} before the first run
     */
    public Report report() {
        return report;
    }

    /**
     * Runs every golden case and stores the report.
     *
     * @return the report
     */
    public Report run() {
        List<CaseResult> results = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(location);
            Arrays.sort(files, Comparator.comparing(f -> String.valueOf(f.getFilename())));
            for (Resource file : files) {
                results.add(runCase(file));
            }
        } catch (IOException e) {
            results.add(new CaseResult("(lookup)", false, "the golden set cannot be listed: " + e.getClass().getSimpleName()));
        }
        if (results.isEmpty()) {
            results.add(new CaseResult("(none)", false, "no golden case found at " + location));
        }
        boolean ok = results.stream().allMatch(CaseResult::passed);
        Report r = new Report(ok, results.size(), List.copyOf(results), clock.instant());
        report = r;
        return r;
    }

    private CaseResult runCase(Resource file) {
        String id = String.valueOf(file.getFilename());
        try (InputStream in = file.getInputStream()) {
            JsonNode root = json.readTree(in);
            id = root.path("id").asText(id);
            JsonNode b = root.path("binding");
            RawRecord.Builder rec = RawRecord.builder()
                    .bindingId(b.path("id").asText())
                    .sourceKind(b.path("sourceKind").asText())
                    .envelope(b.path("envelope").asText())
                    .ref(RawRef.of("SELFTEST", "selftest:" + id, -1))
                    .mode(IngestMode.STREAM)
                    .fetchedAt(clock.instant());
            if (root.has("record")) {
                rec.json(root.get("record"));
            } else {
                rec.text(root.path("recordText").asText());
            }
            ParseOutcome outcome = registry.parse(rec.build(),
                    BindingConfig.auto(b.path("id").asText(), b.path("sourceKind").asText()));
            List<String> problems = check(root.path("expect"), outcome);
            return new CaseResult(id, problems.isEmpty(), String.join("; ", problems));
        } catch (IOException | RuntimeException e) {
            return new CaseResult(id, false, "the case could not run: " + e.getClass().getSimpleName());
        }
    }

    private List<String> check(JsonNode expect, ParseOutcome outcome) {
        List<String> problems = new ArrayList<>();
        String wanted = expect.path("outcome").asText("");
        if (wanted.isEmpty()) {
            problems.add("the case names no expected outcome");
        } else if (!wanted.equals(outcome.outcomeCode())) {
            problems.add("expected outcome " + wanted + " but was " + outcome.outcomeCode());
        }
        List<DlapLogEvent> events = mapper.events(outcome);
        if (expect.has("events") && expect.get("events").asInt(-1) != events.size()) {
            problems.add("expected " + expect.get("events").asInt() + " event(s) but got " + events.size());
        }
        if (!events.isEmpty()) {
            DlapLogEvent first = events.get(0);
            compare(problems, expect, "operation", first.getOperation());
            compare(problems, expect, "changeType", first.getChangeType().name());
            compare(problems, expect, "category", first.getEventCategory().name());
            for (DlapLogEvent e : events) {
                try {
                    codec.decodeStrict(codec.encode(e));
                } catch (RuntimeException ex) {
                    problems.add("an event does not pass the wire contract (" + ex.getClass().getSimpleName() + ")");
                    break;
                }
            }
        }
        return problems;
    }

    private static void compare(List<String> problems, JsonNode expect, String key, String actual) {
        if (expect.has(key) && !expect.get(key).asText().equals(actual)) {
            problems.add(key + " differs from the golden value");
        }
    }
}
