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

package com.dlap.logingest.config.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecLoader;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.logingest.repository.FormatObservationRepository;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.registry.LogEventStore;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.ParseWarning;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * LOG-02bc acceptance: mixed versions across two bindings of one customer. Acme runs two accounts
 * of one source kind: {@code acme-legacy} pinned to the older version {@code 1.0} with
 * {@code on-mismatch: QUARANTINE}, and {@code acme-current} auto-detecting. The same records go
 * through each binding's effective settings, the registry and the ingest path; a third binding
 * pins {@code 1.0} with {@code PARSE_WITH_PIN}. The family is the contract-demo mapping (versions
 * 1.0 and 1.2 of one major), so the test needs no wave-3 parser.
 */
class MixedVersionBindingsTest {

    private static final String BINDINGS = """
            dlap:
              logingest:
                sources:
                  - id: acme
                    kind: DLAP_CONTRACT_DEMO
                    bindings:
                      - id: acme-legacy
                        scope: { account-id: "111111111111" }
                        stream: { transport: TEST_FILES, location: { bucket: legacy-trail } }
                        format: { envelope: generic-json, version: "1.0", on-mismatch: QUARANTINE }
                      - id: acme-current
                        scope: { account-id: "222222222222" }
                        stream: { transport: TEST_FILES, location: { bucket: current-trail } }
                        format: { envelope: generic-json, version: auto }
                      - id: acme-sandbox
                        scope: { account-id: "333333333333" }
                        stream: { transport: TEST_FILES, location: { bucket: sandbox-trail } }
                        format: { envelope: generic-json, version: "1.0", on-mismatch: PARSE_WITH_PIN }
            """;

    private MappingSpec spec;
    private DefaultParserRegistry registry;
    private LogSourceConfigValidator.Context ctx;
    private RegistryIngestService ingest;
    private final List<DlapLogEvent> published = new ArrayList<>();
    private final List<LogQuarantineRecord> quarantined = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream(
                "/com/dlap/logingest/parse/core/contract/contract-demo-mapping.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        spec = MappingSpecLoader.parse(yaml, "contract-demo-mapping.yaml", "contract-demo");
        List<LogRecordParser> parsers = new ArrayList<>(MappingDrivenParser.forEnvelope(spec, "generic-json",
                "contract-demo", "1.0.0"));
        MappingSpecs specs = new MappingSpecs(List.of(spec));
        registry = new DefaultParserRegistry(parsers, specs.detectors());
        ctx = new LogSourceConfigValidator.Context(registry,
                Map.of(spec.sourceKind(), spec.sourceProvider()), specs,
                id -> "TEST_FILES".equals(id)
                        ? java.util.Optional.of(new TransportCatalog.Requirements(java.util.Set.of("bucket"), java.util.Set.of()))
                        : java.util.Optional.empty(),
                PipelineMode.REGISTRY, List.of(), n -> null, registry.describe());
        LogSourceConfigValidator.Result r = new LogSourceConfigValidator(ctx).validate(TestFamilies.bind(BINDINGS));
        assertThat(r.report().problems()).as("the mixed-version file is valid").isEmpty();
        EffectiveLogSources sources = new EffectiveLogSources(PipelineMode.REGISTRY, r.configMode(), r.bindings(),
                r.report());

        LogEventRepository repo = mock(LogEventRepository.class);
        when(repo.existsById(anyString())).thenReturn(false);
        LogEventPublisher publisher = mock(LogEventPublisher.class);
        LogEventStore store = new LogEventStore(repo, new NoTransactions());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("capture", (QuarantineSink) quarantined::add);
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        StaticListableBeanFactory clocks = new StaticListableBeanFactory();
        clocks.addBean("clock", clock);
        ingest = new RegistryIngestService(registry, new CanonicalEventMapper(clock), sources, store, publisher,
                beans.getBeanProvider(QuarantineSink.class), mock(FormatObservationRepository.class),
                new SimpleMeterRegistry(), clocks.getBeanProvider(Clock.class));
        org.mockito.Mockito.doAnswer(inv -> {
            published.add(DlapLogEventCodec.defaults().decodeStrict(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inv.getArgument(1))));
            return null;
        }).when(publisher).publish(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    private static RawRecord record(String binding, String version, String id) {
        String json = "{\"v\":\"" + version + "\",\"id\":\"" + id + "\",\"at\":\"2026-10-01T10:00:00Z\","
                + "\"svc\":\"store\",\"op\":\"GetObject\",\"who\":{\"type\":\"IAMUser\",\"name\":\"u\"},"
                + "\"object\":\"s3://acme-bucket/reports/" + id + ".csv\"}";
        return RawRecord.builder().bindingId(binding).sourceKind("DLAP_CONTRACT_DEMO").envelope("generic-json")
                .ref(RawRef.of("TEST_FILES", "s3://trail/" + binding, 0)).fetchedAt(Instant.parse("2026-10-19T12:00:00Z"))
                .text(json).build();
    }

    @Test
    void thePinnedBindingQuarantinesTheNewerVersionAndTheAutoBindingParsesIt() {
        RecordReport legacyOld = ingest.ingest(record("acme-legacy", "1.0", "l-10"));
        RecordReport legacyNew = ingest.ingest(record("acme-legacy", "1.2", "l-12"));
        RecordReport currentOld = ingest.ingest(record("acme-current", "1.0", "c-10"));
        RecordReport currentNew = ingest.ingest(record("acme-current", "1.2", "c-12"));

        assertThat(legacyOld.outcomeCode()).isEqualTo("PARSED");
        assertThat(legacyNew.outcomeCode()).isEqualTo("QUARANTINED:VERSION_PIN_MISMATCH");
        assertThat(currentOld.outcomeCode()).isEqualTo("PARSED");
        assertThat(currentNew.outcomeCode()).isEqualTo("PARSED");
        assertThat(quarantined).singleElement().satisfies(q -> {
            assertThat(q.getBindingId()).isEqualTo("acme-legacy");
            assertThat(q.getDetectedVersion()).isEqualTo("1.2");
            assertThat(q.getPinnedVersion()).isEqualTo("1.0");
        });
        assertThat(published).extracting(DlapLogEvent::getBindingId, DlapLogEvent::getSourceSchemaVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("acme-legacy", "1.0"),
                        org.assertj.core.groups.Tuple.tuple("acme-current", "1.0"),
                        org.assertj.core.groups.Tuple.tuple("acme-current", "1.2"));
        assertThat(published).allSatisfy(e -> assertThat(e.getParseWarningsList()).isEmpty());
    }

    @Test
    void parseWithPinReadsTheNewerRecordAsThePinnedVersionWithAWarning() {
        RecordReport sandboxNew = ingest.ingest(record("acme-sandbox", "1.2", "s-12"));

        assertThat(sandboxNew.outcomeCode()).isEqualTo("PARSED_WITH_WARNINGS");
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.getBindingId()).isEqualTo("acme-sandbox");
            assertThat(e.getSourceSchemaVersion()).as("the detected version stays on the event").isEqualTo("1.2");
            assertThat(e.getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_VERSION_PIN_MISMATCH);
        });
    }

    @Test
    void aNewerMinorParsesOnTheAutoBindingAndAnUnknownMajorIsQuarantinedOnBoth() {
        RecordReport newerMinor = ingest.ingest(record("acme-current", "1.7", "c-17"));
        RecordReport legacyMajor = ingest.ingest(record("acme-legacy", "2.0", "l-20"));
        RecordReport currentMajor = ingest.ingest(record("acme-current", "2.0", "c-20"));

        assertThat(newerMinor.outcomeCode()).isEqualTo("PARSED_WITH_WARNINGS");
        assertThat(published).singleElement().satisfies(e ->
                assertThat(e.getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_NEWER_MINOR));
        assertThat(legacyMajor.outcomeCode()).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
        assertThat(currentMajor.outcomeCode()).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
    }

    @Test
    void theValidatorRejectsAPinNoParserReadsForThisFamily() {
        LogSourceConfigValidator.Result r = new LogSourceConfigValidator(ctx)
                .validate(TestFamilies.bind(BINDINGS.replace("version: \"1.0\", on-mismatch: QUARANTINE",
                        "version: \"2.0\", on-mismatch: QUARANTINE")));

        assertThat(r.report().errors()).singleElement().satisfies(p -> {
            assertThat(p.rule()).isEqualTo(ConfigProblem.Rule.PIN_NOT_READ);
            assertThat(p.bindingId()).isEqualTo("acme-legacy");
        });
    }

    /** Runs the store's callbacks without a database. */
    static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
