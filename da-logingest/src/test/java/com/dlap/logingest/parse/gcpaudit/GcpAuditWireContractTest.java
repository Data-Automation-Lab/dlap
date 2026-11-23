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

package com.dlap.logingest.parse.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.kafka.StubKafka;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.mapping.Framing;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.RecordFraming;
import com.dlap.proto.log.v1.DlapLogEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The wire contract (SPINE-01, log-event.md section 8) over the whole {@code gcpaudit} fixture
 * corpus: every event the registry pipeline would send for any case, including the quarantined
 * records' pass-through events and the three cells the catalog does not claim yet, reaches the
 * broker as a JSON object that {@link DlapLogEventCodec#decodeStrict} accepts, and decodes to the
 * event the parser produced. The sends use the production publisher and a real
 * {@code StringSerializer}.
 *
 * <p>The records are framed as {@code AbstractParserContractTest} frames them, with the same
 * bindings as {@link GcpAuditContractTest}; this class does not extend it, so the parser contract
 * checks (several minutes) do not run twice.
 */
class GcpAuditWireContractTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final MappingSpec mapping = GcpAuditTestSupport.mapping();

    private BindingConfig binding(FixtureCase c) {
        BindingConfig b = mapping.defaultBinding("fixture-" + GcpAuditFormat.FAMILY);
        return c.name().startsWith(GcpAuditContractTest.PINNED_V1)
                ? b.pinned(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY, OnMismatch.QUARANTINE) : b;
    }

    private List<RawRecord> records(FixtureCase c) {
        List<RawRecord> out = new ArrayList<>();
        BindingConfig binding = binding(c);
        // As the parser contract base frames them: the envelope's framing, else record and json-lines.
        Optional<MappingSpec.Envelope> envelope = mapping.envelope(c.envelope());
        Set<Framing> framing = envelope.map(MappingSpec.Envelope::framing)
                .orElse(Set.of(Framing.RECORD, Framing.JSON_LINES));
        FieldPath pointer = envelope.map(MappingSpec.Envelope::recordsPointer).orElse(null);
        for (Path file : c.inputFiles()) {
            for (RecordFraming.Piece p : RecordFraming.split(read(file), framing, pointer)) {
                RawRecord.Builder b = RawRecord.builder()
                        .bindingId(binding.bindingId())
                        .sourceKind(GcpAuditFormat.SOURCE_KIND)
                        .envelope(c.envelope())
                        .ref(RawRef.of("FIXTURE", "fixture:" + c.id() + "/" + file.getFileName(), p.index()))
                        .mode(IngestMode.REPLAY)
                        .fetchedAt(FIXED_NOW);
                out.add(p.text() != null ? b.text(p.text()).build() : b.json(p.json()).build());
            }
        }
        return out;
    }

    private static String read(Path file) {
        try {
            byte[] raw = Files.readAllBytes(file);
            if (file.getFileName().toString().endsWith(".gz")) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    raw = in.readAllBytes();
                }
            }
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    @Test
    void everyEventOfTheCorpusReachesTheBrokerAsOneStrictlyDecodableObject() {
        StubKafka kafka = new StubKafka();
        var publisher = kafka.publisher(Duration.ofSeconds(2));
        FixtureCatalog corpus = GcpAuditTestSupport.allCellsCorpus();
        ParserRegistry registry = GcpAuditTestSupport.registry();
        CanonicalEventMapper mapper = new CanonicalEventMapper(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        List<DlapLogEvent> expected = new ArrayList<>();
        int cases = 0;
        for (FixtureCase c : corpus.cases(GcpAuditFormat.FAMILY)) {
            cases++;
            BindingConfig binding = binding(c);
            for (RawRecord record : records(c)) {
                ParseOutcome outcome = registry.parse(record, binding);
                for (DlapLogEvent event : mapper.events(outcome)) {
                    expected.add(event);
                    // What RegistryIngestService does for each event it stores.
                    publisher.publish(LogEventSemantics.partitionKey(event), CODEC.toMap(event));
                }
            }
        }

        LoggerFactory.getLogger(GcpAuditWireContractTest.class)
                .info("wire contract: {} corpus cases produced {} events, all strictly decodable", cases, expected.size());
        assertThat(cases).as("gcpaudit cases in the corpus").isGreaterThan(20);
        assertThat(expected).as("events the corpus produces").isNotEmpty();
        assertThat(kafka.received()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            StubKafka.Wire wire = kafka.received().get(i);
            assertThat((char) wire.value()[0]).as("event %s is a JSON object", expected.get(i).getEventId())
                    .isEqualTo('{');
            DlapLogEvent onTheWire = CODEC.decodeStrict(wire.text());
            assertThat(onTheWire.getEventId()).isEqualTo(expected.get(i).getEventId());
            assertThat(onTheWire.getTargetResource().getCanonicalUri())
                    .isEqualTo(expected.get(i).getTargetResource().getCanonicalUri());
            assertThat(onTheWire.getChangeType()).isEqualTo(expected.get(i).getChangeType());
            assertThat(onTheWire.getEventCategory()).isEqualTo(expected.get(i).getEventCategory());
            assertThat(onTheWire.getNativeEventId()).isEqualTo(expected.get(i).getNativeEventId());
        }
    }
}
