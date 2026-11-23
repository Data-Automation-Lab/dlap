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

package com.dlap.logingest.parse.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.mapping.Framing;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecLoader;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import com.dlap.logingest.parse.core.mapping.RecordFraming;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.json.JSONException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

/**
 * The fixture-driven contract test every log family extends (LOG-02a, ADR-0054). A family test
 * names its fixture family, its source kind and its registry; this base walks the family's cases
 * in the {@code da-log-fixtures} corpus and checks the frozen contract on each.
 *
 * <pre>
 * class CloudTrailContractTest extends AbstractParserContractTest {
 *     protected String family()        { return "cloudtrail"; }
 *     protected String sourceKind()    { return "AWS_CLOUDTRAIL"; }
 *     protected ParserRegistry registry() { return registryOf(mapping().orElseThrow(), parsers); }
 *     protected Optional&lt;MappingSpec&gt; mapping() { return Optional.of(classpathMapping("cloudtrail")); }
 * }
 * </pre>
 *
 * <p>What every family gets, on every case of its family:
 * <ul>
 *   <li>{@link #positiveCases()} and {@link #negativeCases()}: each input is framed into records
 *       ({@link RecordFraming}), parsed through the registry and mapped onto the wire; every record
 *       of a case must end in the same outcome; that outcome must equal both
 *       {@code expected.json} ({@code outcome} plus {@code reason}) and {@code meta.yaml}
 *       {@code expected_outcome}; the events must match {@code expected.json} {@code events}
 *       (JSONAssert {@code STRICT_ORDER}: listed fields only, arrays exact). Negative cases must be
 *       quarantined; positive cases must parse or skip.</li>
 *   <li>{@link #eventsHonourTheWireContract()}: every event passes
 *       {@code DlapLogEventCodec.decodeStrict}, its {@code event_id} is
 *       {@code LogEventIds.eventId(provider, native_event_id)}, ids are unique within a case, and
 *       parsing twice gives identical events.</li>
 *   <li>{@link #unknownFieldsAreReportedNotFatal()}: an injected unknown field changes nothing
 *       but the warnings (and adds {@code PARSE_WARNING_UNKNOWN_FIELDS} when the parser declares
 *       paths).</li>
 *   <li>{@link #parsersAreTotal()}: removing any top-level field, replacing any value with a
 *       random JSON value, and random JSON documents never throw and never end in
 *       {@code PARSER_EXCEPTION}.</li>
 *   <li>{@link #everySupportedCatalogVersionHasAParser()}: each version the family's
 *       {@code catalog.yaml} marks {@code supported: true} is read by a registered parser. Cases of
 *       unsupported versions are reported as skipped tests.</li>
 * </ul>
 * Randomness is seeded ({@link #seed()}), so failures reproduce; no property-testing library is
 * used (decision D7).
 */
public abstract class AbstractParserContractTest {

    /** Clock of every contract run: far from fixture event times, so a stamped business time shows. */
    public static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");

    /** Transport name on fixture raw references. */
    public static final String FIXTURE_TRANSPORT = "FIXTURE";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final String PROBE_FIELD = "dlapContractProbe";

    private ParserRegistry cachedRegistry;
    private FixtureCatalog cachedCorpus;

    // ------------------------------------------------------------------
    // What a family supplies
    // ------------------------------------------------------------------

    /**
     * The fixture family directory, for example {@code cloudtrail}.
     *
     * @return the family
     */
    protected abstract String family();

    /**
     * The source kind of the family's records.
     *
     * @return upper snake case source kind
     */
    protected abstract String sourceKind();

    /**
     * The registry under test, normally built from the family's parser beans with
     * {@link #registryOf(MappingSpec, List)}. Called once per test instance.
     *
     * @return the registry
     */
    protected abstract ParserRegistry registry();

    /**
     * The family's mapping, which supplies framing and the default binding.
     *
     * @return the mapping, or empty for a family without one
     */
    protected Optional<MappingSpec> mapping() {
        return Optional.empty();
    }

    /**
     * The corpus. Defaults to the {@code da-log-fixtures} corpus on the test classpath.
     *
     * @return the corpus
     */
    protected FixtureCatalog corpus() {
        return FixtureCatalog.fromClasspath();
    }

    /**
     * The binding a case is parsed through. Defaults to the mapping's default binding, or an
     * {@code auto} binding, with id {@code fixture-<family>}. Override for pin-mismatch cases.
     *
     * @param c the case
     * @return the binding
     */
    protected BindingConfig binding(FixtureCase c) {
        String id = "fixture-" + family();
        return mapping().map(m -> m.defaultBinding(id)).orElseGet(() -> BindingConfig.auto(id, sourceKind()));
    }

    /**
     * How an envelope's inputs are framed. Defaults to the mapping's framing, else
     * {@code record} and {@code json-lines}.
     *
     * @param envelope the envelope id
     * @return the framings
     */
    protected Set<Framing> framing(String envelope) {
        return mapping().flatMap(m -> m.envelope(envelope)).map(MappingSpec.Envelope::framing)
                .orElse(Set.of(Framing.RECORD, Framing.JSON_LINES));
    }

    /**
     * The record array of {@code json-object-array} framing.
     *
     * @param envelope the envelope id
     * @return the pointer, or {@code null}
     */
    protected FieldPath recordsPointer(String envelope) {
        return mapping().flatMap(m -> m.envelope(envelope)).map(MappingSpec.Envelope::recordsPointer).orElse(null);
    }

    /**
     * Turns one wire event into what {@code expected.json} lists. Defaults to its proto3 JSON, so
     * expected events name {@code DlapLogEvent} fields (lowerCamelCase, enum names).
     *
     * @param event the event
     * @return the JSON compared with the expected event
     */
    protected JsonNode projectEvent(DlapLogEvent event) {
        try {
            return JSON.readTree(CODEC.encode(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Seed of the random inputs.
     *
     * @return the seed
     */
    protected long seed() {
        return 20_261_019L;
    }

    /**
     * Number of random documents and random value replacements per record.
     *
     * @return the count
     */
    protected int randomInputs() {
        return 60;
    }

    // ------------------------------------------------------------------
    // Helpers for family tests
    // ------------------------------------------------------------------

    /**
     * A registry of a mapping's declarative detectors and the given parsers.
     *
     * @param spec    the family mapping
     * @param parsers the family's parsers
     * @return the registry
     */
    public static DefaultParserRegistry registryOf(MappingSpec spec, List<? extends LogRecordParser> parsers) {
        return new DefaultParserRegistry(parsers, new MappingSpecs(List.of(spec)).detectors());
    }

    /**
     * Loads a shipped family mapping, {@code dlap/log-formats/<family>/mapping.yaml}.
     *
     * @param family the family
     * @return the mapping
     */
    public static MappingSpec classpathMapping(String family) {
        return mappingResource(MappingSpecLoader.ROOT + family + "/" + MappingSpecLoader.MAPPING_FILE, family);
    }

    /**
     * Loads a mapping from any classpath resource.
     *
     * @param resource the resource path
     * @param family   the family it must declare
     * @return the mapping
     */
    public static MappingSpec mappingResource(String resource, String family) {
        try (InputStream in = AbstractParserContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("no mapping at " + resource);
            }
            return MappingSpecLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource, family);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The mapper every contract run uses, on {@link #FIXED_NOW}.
     *
     * @return the mapper
     */
    protected static CanonicalEventMapper mapper() {
        return new CanonicalEventMapper(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------
    // Contract tests
    // ------------------------------------------------------------------

    /** The family exists in the corpus and has cases. */
    @Test
    void corpusHasCasesForTheFamily() {
        assertThat(corpusOnce().familyNames()).as("fixture families").contains(family());
        assertThat(cases(false)).as("positive cases of %s", family()).isNotEmpty();
    }

    /** Every version {@code catalog.yaml} marks supported is read by a registered parser. */
    @Test
    void everySupportedCatalogVersionHasAParser() {
        FixtureCatalog.Family fam = corpusOnce().family(family()).orElseThrow(() ->
                new AssertionError("family " + family() + " has no readable catalog.yaml"));
        for (FixtureCatalog.Envelope env : fam.envelopes()) {
            EnvelopeKey key = new EnvelopeKey(sourceKind(), env.id());
            for (FixtureCatalog.Version v : env.versions()) {
                if (v.supported()) {
                    assertThat(registryOnce().envelopes()).as("detector for %s", key).contains(key);
                    assertThat(registryOnce().rangeFor(key, v.id()))
                            .as("a parser for supported version %s of %s", v.id(), key).isPresent();
                }
            }
        }
    }

    /**
     * Positive cases: parsed or skipped, matching {@code expected.json} and {@code meta.yaml}.
     *
     * @return one test per case
     */
    @TestFactory
    Stream<DynamicTest> positiveCases() {
        return cases(false).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> checkCase(c)));
    }

    /**
     * Negative cases: quarantined, matching {@code expected.json} and {@code meta.yaml}.
     *
     * @return one test per case
     */
    @TestFactory
    Stream<DynamicTest> negativeCases() {
        return cases(true).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> checkCase(c)));
    }

    /**
     * Wire contract, ids and determinism on every case.
     *
     * @return one test per case
     */
    @TestFactory
    Stream<DynamicTest> eventsHonourTheWireContract() {
        return allCases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            assumeSupported(c);
            List<DlapLogEvent> first = parseCase(c).events();
            List<DlapLogEvent> second = parseCase(c).events();
            assertThat(second).as("parsing %s twice", c.id()).isEqualTo(first);
            Set<String> ids = new HashSet<>();
            for (DlapLogEvent e : first) {
                DlapLogEvent strict = CODEC.decodeStrict(CODEC.encode(e));
                assertThat(strict).as("decodeStrict(encode(event)) of %s", c.id()).isEqualTo(e);
                assertThat(e.getNativeEventId()).as("native_event_id").isNotBlank();
                assertThat(e.getEventId()).as("event_id formula")
                        .isEqualTo(LogEventIds.eventId(e.getSourceProvider(), e.getNativeEventId()));
                assertThat(ids.add(e.getEventId())).as("event_id %s unique in %s", e.getEventId(), c.id()).isTrue();
                assertThat(e.getSourceFormat()).startsWith(sourceKind() + "/");
                assertThat(e.getBindingId()).isEqualTo(binding(c).bindingId());
                assertThat(e.getIngestTime().getSeconds()).as("ingest_time from the injected clock")
                        .isEqualTo(FIXED_NOW.getEpochSecond());
                assertThat(e.getEventTime().getSeconds()).as("event_time is a provider time, never DLAP's")
                        .isNotEqualTo(FIXED_NOW.getEpochSecond());
            }
        }));
    }

    /**
     * An unknown field changes nothing but the warnings.
     *
     * @return one test per positive case
     */
    @TestFactory
    Stream<DynamicTest> unknownFieldsAreReportedNotFatal() {
        return cases(false).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            assumeSupported(c);
            for (RawRecord r : records(c)) {
                if (!r.decode().ok() || !r.body().isObject()) {
                    continue;
                }
                ParseOutcome base = registryOnce().parse(r, binding(c));
                ObjectNode probed = r.body().deepCopy();
                probed.putObject(PROBE_FIELD).put("nested", 1);
                ParseOutcome withProbe = registryOnce().parse(withBody(r, probed), binding(c));
                assertThat(withProbe.result().getClass()).as("result type with an unknown field")
                        .isEqualTo(base.result().getClass());
                if (base.result() instanceof ParseResult.Quarantined bq) {
                    assertThat(((ParseResult.Quarantined) withProbe.result()).reason()).isEqualTo(bq.reason());
                    continue;
                }
                if (base.result() instanceof ParseResult.Skipped bs) {
                    assertThat(((ParseResult.Skipped) withProbe.result()).reason()).isEqualTo(bs.reason());
                    continue;
                }
                List<DlapLogEvent> a = mapper().events(base);
                List<DlapLogEvent> b = mapper().events(withProbe);
                assertThat(b).hasSameSizeAs(a);
                boolean declares = withProbe.fingerprint().isPresent();
                for (int i = 0; i < a.size(); i++) {
                    assertThat(withoutWarningsAndRaw(b.get(i))).as("event %s with an unknown field", i)
                            .isEqualTo(withoutWarningsAndRaw(a.get(i)));
                    if (declares) {
                        assertThat(b.get(i).getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_UNKNOWN_FIELDS);
                    }
                }
                if (declares) {
                    assertThat(withProbe.fingerprint().get().unknownPaths()).contains("/" + PROBE_FIELD);
                }
            }
        }));
    }

    /**
     * Parsers are total: damaged and random inputs never throw and never end in
     * {@code PARSER_EXCEPTION}.
     *
     * @return one test per positive case, plus one for random documents
     */
    @TestFactory
    Stream<DynamicTest> parsersAreTotal() {
        Stream<DynamicTest> damaged = cases(false).stream().map(c -> DynamicTest.dynamicTest("damaged " + c.id(), () -> {
            assumeSupported(c);
            Random random = new Random(seed() ^ c.id().hashCode());
            for (RawRecord r : records(c)) {
                if (!r.decode().ok() || !r.body().isObject()) {
                    continue;
                }
                ObjectNode body = (ObjectNode) r.body();
                for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
                    ObjectNode copy = body.deepCopy();
                    copy.remove(it.next());
                    assertTotal(withBody(r, copy), binding(c));
                }
                List<ObjectNode> leaves = new ArrayList<>();
                for (int i = 0; i < randomInputs(); i++) {
                    ObjectNode copy = body.deepCopy();
                    replaceRandomValue(copy, random);
                    leaves.add(copy);
                }
                for (ObjectNode copy : leaves) {
                    assertTotal(withBody(r, copy), binding(c));
                }
            }
        }));
        Stream<DynamicTest> random = Stream.of(DynamicTest.dynamicTest("random documents", () -> {
            Random rnd = new Random(seed());
            BindingConfig b = mapping().map(m -> m.defaultBinding("fixture-" + family()))
                    .orElseGet(() -> BindingConfig.auto("fixture-" + family(), sourceKind()));
            for (EnvelopeKey key : registryOnce().envelopes()) {
                if (!key.sourceKind().equals(sourceKind())) {
                    continue;
                }
                for (int i = 0; i < randomInputs(); i++) {
                    JsonNode doc = randomValue(rnd, 0);
                    RawRecord r = RawRecord.builder().bindingId(b.bindingId()).sourceKind(sourceKind())
                            .envelope(key.envelope()).ref(RawRef.of(FIXTURE_TRANSPORT, "random:" + key, i))
                            .mode(IngestMode.REPLAY).fetchedAt(FIXED_NOW).json(doc).build();
                    assertTotal(r, b);
                }
            }
        }));
        return Stream.concat(damaged, random);
    }

    // ------------------------------------------------------------------
    // Case handling
    // ------------------------------------------------------------------

    /** The parsed form of one case. */
    record CaseRun(List<ParseOutcome> outcomes, List<DlapLogEvent> events, String outcomeCode) {
    }

    private void checkCase(FixtureCase c) throws JSONException {
        assumeSupported(c);
        CaseRun run = parseCase(c);
        JsonNode expected = c.expectedTree();
        String expectedCode = expectedCode(expected, c);
        assertThat(c.meta().expectedOutcome()).as("meta.yaml expected_outcome of %s agrees with expected.json", c.id())
                .isEqualTo(expectedCode);
        assertThat(run.outcomeCode()).as("outcome of %s", c.id()).isEqualTo(expectedCode);
        if (c.negative()) {
            assertThat(run.outcomeCode()).as("a negative case is quarantined").startsWith("QUARANTINED:");
        } else {
            assertThat(run.outcomeCode()).as("a positive case parses or skips").doesNotStartWith("QUARANTINED");
        }
        ArrayNode actual = JSON.createArrayNode();
        run.events().forEach(e -> actual.add(projectEvent(e)));
        JsonNode expectedEvents = expected.get("events");
        if (expectedEvents == null || !expectedEvents.isArray()) {
            fail("expected.json of %s needs an events array", c.id());
        }
        JSONAssert.assertEquals("events of " + c.id(), expectedEvents.toString(), actual.toString(),
                JSONCompareMode.STRICT_ORDER);
        for (ParseOutcome o : run.outcomes()) {
            if (o.result() instanceof ParseResult.Quarantined) {
                assertThat(mapper().quarantine(o)).as("quarantine record of %s", c.id()).isPresent();
            }
        }
    }

    /**
     * Parses every record of a case.
     *
     * @param c the case
     * @return the outcomes, wire events and the case outcome code
     */
    protected CaseRun parseCase(FixtureCase c) {
        List<ParseOutcome> outcomes = new ArrayList<>();
        List<DlapLogEvent> events = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        CanonicalEventMapper mapper = mapper();
        BindingConfig binding = binding(c);
        for (RawRecord r : records(c)) {
            ParseOutcome o = registryOnce().parse(r, binding);
            outcomes.add(o);
            codes.add(o.outcomeCode());
            events.addAll(mapper.events(o));
        }
        if (outcomes.isEmpty()) {
            fail("case %s has no records", c.id());
        }
        if (codes.size() != 1) {
            fail("records of case %s end in different outcomes %s; split the case", c.id(), codes);
        }
        return new CaseRun(outcomes, events, codes.iterator().next());
    }

    /**
     * Frames every input file of a case into raw records.
     *
     * @param c the case
     * @return the records, in file then record order
     */
    protected List<RawRecord> records(FixtureCase c) {
        List<RawRecord> out = new ArrayList<>();
        BindingConfig binding = binding(c);
        for (Path file : c.inputFiles()) {
            String text = readInput(file);
            List<RecordFraming.Piece> pieces = RecordFraming.split(text, framing(c.envelope()),
                    recordsPointer(c.envelope()));
            for (RecordFraming.Piece p : pieces) {
                RawRecord.Builder b = RawRecord.builder()
                        .bindingId(binding.bindingId())
                        .sourceKind(sourceKind())
                        .envelope(c.envelope())
                        .ref(RawRef.of(FIXTURE_TRANSPORT, "fixture:" + c.id() + "/" + file.getFileName(), p.index()))
                        .mode(IngestMode.REPLAY)
                        .fetchedAt(FIXED_NOW);
                out.add(p.text() != null ? b.text(p.text()).build() : b.json(p.json()).build());
            }
        }
        return out;
    }

    private List<FixtureCase> cases(boolean negative) {
        return allCases().stream().filter(c -> c.negative() == negative).toList();
    }

    private List<FixtureCase> allCases() {
        return corpusOnce().cases(family());
    }

    private void assumeSupported(FixtureCase c) {
        Optional<FixtureCatalog.Version> v = corpusOnce().family(family()).flatMap(f -> f.version(c.envelope(), c.version()));
        org.junit.jupiter.api.Assumptions.assumeTrue(v.map(FixtureCatalog.Version::supported).orElse(false),
                () -> c.id() + ": version " + c.version() + " of " + c.envelope() + " is not marked supported in catalog.yaml");
    }

    private static String expectedCode(JsonNode expected, FixtureCase c) {
        JsonNode outcome = expected.get("outcome");
        if (outcome == null || !outcome.isTextual()) {
            fail("expected.json of %s needs a textual outcome", c.id());
        }
        JsonNode reason = expected.get("reason");
        return reason == null || reason.isNull() ? outcome.asText() : outcome.asText() + ":" + reason.asText();
    }

    private static String readInput(Path file) {
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

    private static RawRecord withBody(RawRecord r, JsonNode body) {
        return RawRecord.builder().bindingId(r.bindingId()).sourceKind(r.sourceKind()).envelope(r.envelope())
                .ref(r.ref()).mode(r.mode()).fetchedAt(r.fetchedAt()).observedTime(r.observedTime().orElse(null))
                .json(body).build();
    }

    private void assertTotal(RawRecord r, BindingConfig b) {
        ParseOutcome o;
        try {
            o = registryOnce().parse(r, b);
        } catch (RuntimeException e) {
            throw new AssertionError("the registry threw on " + r, e);
        }
        if (o.result() instanceof ParseResult.Quarantined q) {
            assertThat(q.reason()).as("parser defect on %s: %s", r, q.detail())
                    .isNotEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        }
        CanonicalEventMapper m = mapper();
        for (DlapLogEvent e : m.events(o)) {
            CODEC.decodeStrict(CODEC.encode(e));
        }
        m.quarantine(o);
    }

    private static DlapLogEvent withoutWarningsAndRaw(DlapLogEvent e) {
        return e.toBuilder().clearParseWarnings().clearParseStatus().clearRawEvent().build();
    }

    private static void replaceRandomValue(ObjectNode root, Random random) {
        List<JsonNode> containers = new ArrayList<>();
        collectContainers(root, containers);
        JsonNode target = containers.get(random.nextInt(containers.size()));
        if (target instanceof ObjectNode o && o.size() > 0) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            o.set(names.get(random.nextInt(names.size())), randomValue(random, 2));
        } else if (target instanceof ArrayNode a && a.size() > 0) {
            a.set(random.nextInt(a.size()), randomValue(random, 2));
        }
    }

    private static void collectContainers(JsonNode node, List<JsonNode> out) {
        if (node.isContainerNode()) {
            out.add(node);
            node.elements().forEachRemaining(n -> collectContainers(n, out));
        }
    }

    private static JsonNode randomValue(Random random, int depth) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        int pick = random.nextInt(depth >= 3 ? 7 : 9);
        return switch (pick) {
            case 0 -> f.nullNode();
            case 1 -> f.booleanNode(random.nextBoolean());
            case 2 -> f.numberNode(random.nextInt());
            case 3 -> f.numberNode(random.nextDouble() * 1e6);
            case 4 -> f.textNode("");
            case 5 -> f.textNode(randomText(random));
            case 6 -> f.textNode(random.nextBoolean() ? "2026-01-01T00:00:00Z" : "1.0");
            case 7 -> {
                ArrayNode a = f.arrayNode();
                int n = random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    a.add(randomValue(random, depth + 1));
                }
                yield a;
            }
            default -> {
                ObjectNode o = f.objectNode();
                int n = random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    o.set(randomText(random), randomValue(random, depth + 1));
                }
                yield o;
            }
        };
    }

    private static String randomText(Random random) {
        String alphabet = "abcXYZ019 _-./:@~|*#é中";
        StringBuilder sb = new StringBuilder();
        int n = 1 + random.nextInt(12);
        for (int i = 0; i < n; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private ParserRegistry registryOnce() {
        if (cachedRegistry == null) {
            cachedRegistry = registry();
        }
        return cachedRegistry;
    }

    private FixtureCatalog corpusOnce() {
        if (cachedCorpus == null) {
            cachedCorpus = corpus();
        }
        return cachedCorpus;
    }
}
