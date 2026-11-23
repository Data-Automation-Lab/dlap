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

package com.dlap.logingest.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.fixtures.FixtureCase;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

/**
 * The parser contract for a family whose catalog cells are not claimed yet (PARSE-02).
 *
 * <p>The frozen contract base skips every case of a version the family's {@code catalog.yaml}
 * does not mark {@code supported: true}, and a cell may be marked supported only at a coverage score
 * of 0.99 (fixture coverage gate). PARSE-02 ships parsers for the CloudTrail and S3 server access
 * log families before their coverage reaches that score, so this class runs the same checks on
 * every case of the family whatever the flag says: outcome against {@code expected.json} and
 * {@code meta.yaml}, events (JSONAssert {@code STRICT_ORDER}, listed fields only), negative cases
 * quarantined, the wire contract and determinism, and totality under removed and replaced fields.
 * The base's own tests still run (random documents, the corpus has cases, supported versions have a
 * parser).
 */
public abstract class UnclaimedCellsContractTest extends AbstractParserContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    private ParserRegistry registry;

    private ParserRegistry registryOnce() {
        if (registry == null) {
            registry = registry();
        }
        return registry;
    }

    private List<FixtureCase> allCases() {
        return corpus().cases(family());
    }

    /** One parsed case: the outcomes of its records and their wire events. */
    private record Run(List<ParseOutcome> outcomes, List<DlapLogEvent> events, String code) {
    }

    private Run run(FixtureCase c) {
        List<ParseOutcome> outcomes = new ArrayList<>();
        List<DlapLogEvent> events = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        BindingConfig binding = binding(c);
        for (RawRecord r : records(c)) {
            ParseOutcome o = registryOnce().parse(r, binding);
            outcomes.add(o);
            codes.add(o.outcomeCode());
            events.addAll(mapper().events(o));
        }
        if (outcomes.isEmpty()) {
            fail("case %s has no records", c.id());
        }
        if (codes.size() != 1) {
            fail("records of case %s end in different outcomes %s; split the case", c.id(), codes);
        }
        return new Run(outcomes, events, codes.iterator().next());
    }

    /**
     * Every case of the family, claimed or not: outcome, events and quarantine records.
     *
     * @return one test per case
     */
    @TestFactory
    Stream<DynamicTest> everyCaseMatchesItsExpectedResult() {
        return allCases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            Run run = run(c);
            JsonNode expected = c.expectedTree();
            JsonNode reason = expected.get("reason");
            String expectedCode = expected.path("outcome").asText()
                    + (reason == null || reason.isNull() ? "" : ":" + reason.asText());
            assertThat(c.meta().expectedOutcome()).as("meta.yaml expected_outcome of %s", c.id()).isEqualTo(expectedCode);
            assertThat(run.code()).as("outcome of %s", c.id()).isEqualTo(expectedCode);
            if (c.negative()) {
                assertThat(run.code()).as("a negative case is quarantined").startsWith("QUARANTINED:");
            } else {
                assertThat(run.code()).as("a positive case parses or skips").doesNotStartWith("QUARANTINED");
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
        }));
    }

    /**
     * Every event of every case passes the wire contract, has the formula id, is unique within its
     * case and parses the same twice.
     *
     * @return one test per case
     */
    @TestFactory
    Stream<DynamicTest> everyCaseHonoursTheWireContract() {
        return allCases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            Run first = run(c);
            assertThat(run(c).events()).as("parsing %s twice", c.id()).isEqualTo(first.events());
            // Ids are unique within a case, as the frozen base requires once a cell is claimed. The three
            // derived seed cases that reused one eventID now carry distinct ids (PARSE-03, Q-642).
            Set<String> ids = new HashSet<>();
            for (DlapLogEvent e : first.events()) {
                assertThat(ids.add(e.getEventId())).as("event_id unique within %s", c.id()).isTrue();
            }
            for (DlapLogEvent e : first.events()) {
                assertThat(CODEC.decodeStrict(CODEC.encode(e))).isEqualTo(e);
                assertThat(e.getEventId()).isEqualTo(LogEventIds.eventId(e.getSourceProvider(), e.getNativeEventId()));
                assertThat(e.getSourceFormat()).startsWith(sourceKind() + "/");
                assertThat(e.getIngestTime().getSeconds()).isEqualTo(FIXED_NOW.getEpochSecond());
                assertThat(e.getEventTime().getSeconds()).isNotEqualTo(FIXED_NOW.getEpochSecond());
            }
        }));
    }

    /**
     * Removing any top-level field or replacing any value never throws and never ends in
     * {@code PARSER_EXCEPTION}.
     *
     * @return one test per positive case
     */
    @TestFactory
    Stream<DynamicTest> everyCaseIsTotalUnderDamage() {
        return allCases().stream().filter(c -> !c.negative()).map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            Random random = new Random(seed() ^ c.id().hashCode());
            BindingConfig binding = binding(c);
            for (RawRecord r : records(c)) {
                if (!r.decode().ok() || !r.body().isObject()) {
                    continue;
                }
                ObjectNode body = (ObjectNode) r.body();
                for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
                    ObjectNode copy = body.deepCopy();
                    copy.remove(it.next());
                    assertTotal(r, copy, binding);
                }
                for (int i = 0; i < randomInputs(); i++) {
                    ObjectNode copy = body.deepCopy();
                    replaceRandomLeaf(copy, random);
                    assertTotal(r, copy, binding);
                }
            }
        }));
    }

    private void assertTotal(RawRecord r, JsonNode body, BindingConfig binding) {
        RawRecord damaged = RawRecord.builder().bindingId(r.bindingId()).sourceKind(r.sourceKind())
                .envelope(r.envelope()).ref(r.ref()).mode(r.mode()).fetchedAt(r.fetchedAt()).json(body).build();
        ParseOutcome o = registryOnce().parse(damaged, binding);
        if (o.result() instanceof ParseResult.Quarantined q) {
            assertThat(q.reason()).as("damaged record of %s", r.ref()).isNotEqualTo(
                    QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
            assertThat(mapper().quarantine(o)).isPresent();
        } else {
            mapper().events(o).forEach(e -> CODEC.decodeStrict(CODEC.encode(e)));
        }
    }

    private static void replaceRandomLeaf(ObjectNode root, Random random) {
        List<Map.Entry<ObjectNode, String>> slots = new ArrayList<>();
        collect(root, slots);
        if (slots.isEmpty()) {
            return;
        }
        Map.Entry<ObjectNode, String> slot = slots.get(random.nextInt(slots.size()));
        JsonNodeFactory f = JsonNodeFactory.instance;
        JsonNode value = switch (random.nextInt(6)) {
            case 0 -> f.nullNode();
            case 1 -> f.numberNode(random.nextLong());
            case 2 -> f.booleanNode(random.nextBoolean());
            case 3 -> f.textNode(Long.toString(random.nextLong(), 36));
            case 4 -> f.arrayNode().add(random.nextInt());
            default -> f.objectNode().put("x", random.nextInt());
        };
        slot.getKey().set(slot.getValue(), value);
    }

    private static void collect(JsonNode node, List<Map.Entry<ObjectNode, String>> out) {
        if (node instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                out.add(Map.entry(o, e.getKey()));
                collect(e.getValue(), out);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }
}
