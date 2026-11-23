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

package com.dlap.fixtures.gen.gcpaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The independent confirmation that {@code gcpaudit} cases claim with
 * {@code confirmed_by: proto-round-trip}: every record of the case is read through Google's
 * published protobuf definitions (googleapis at ba591f1d, vendored in src/main/proto) and nothing is
 * lost or invented.
 *
 * <p>For each record:
 * <ol>
 *   <li>an {@code api-proto} {@code serviceData {typeUrl, base64}} is decoded with the message its
 *       type URL names; re-encoding the decoded message must give the same bytes;</li>
 *   <li>the LogEntry JSON is parsed strictly into {@code google.logging.v2.LogEntry}: an unknown
 *       field, a wrong type or an unknown enum name fails; {@code protoPayload} must resolve to
 *       {@code google.cloud.audit.AuditLog} through the type registry, and an expanded
 *       {@code serviceData} to its own message;</li>
 *   <li>{@code protoPayload.metadata} with the BigQuery v2 type is parsed strictly into
 *       {@code google.cloud.audit.BigQueryAuditMetadata};</li>
 *   <li>printing the parsed message back must reproduce every value of the input (numbers
 *       numerically, int64 text as numbers, times as instants, map and list contents exactly).</li>
 * </ol>
 * The check confirms shape and field names against the vendor's schema; it does not confirm that
 * Google writes a given combination of fields (that needs a capture, LIVE-00).
 */
class GcpAuditProtoRoundTripTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String META_TYPE = "type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata";

    private static List<FixtureCase> confirmed() {
        return FixtureCatalog.fromClasspath().cases(GcpAuditCaseWriter.FAMILY).stream()
                .filter(c -> c.confirmationMethods().stream().anyMatch(m -> m == FixtureCase.ConfirmationMethod.PROTO_ROUND_TRIP))
                .toList();
    }

    /** Every case that claims the round trip passes it. */
    @TestFactory
    Stream<DynamicTest> everyConfirmedCaseRoundTripsThroughTheVendoredProtos() {
        return confirmed().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            List<JsonNode> records = records(c);
            assertThat(records).as("records of %s", c.id()).isNotEmpty();
            for (int i = 0; i < records.size(); i++) {
                roundTrip(records.get(i), c.id() + " record " + i);
            }
        }));
    }

    /** The generated cases are the confirmed ones, and there are some in every json and api-proto cell. */
    @Test
    void theGeneratedCasesAreConfirmedInEveryCell() {
        List<FixtureCase> confirmed = confirmed();
        for (String envelope : List.of("json", "api-proto")) {
            for (String version : List.of("auditlog-2021", "auditlog-2025", "bq-auditmetadata", "bq-auditdata-legacy")) {
                assertThat(confirmed).as("confirmed cases in %s/%s", envelope, version)
                        .anyMatch(c -> c.envelope().equals(envelope) && c.version().equals(version));
            }
        }
    }

    /** The check fails on a field the proto does not define, so it is not vacuous. */
    @Test
    void anUnknownPayloadFieldFailsTheCheck() {
        ObjectNode e = (ObjectNode) GcpAuditCaseBook.cases().get(0).files().get(0).get(0).json();
        ((ObjectNode) e.get("protoPayload")).put("notAnAuditLogField", 1);
        try {
            roundTrip(e, "probe");
        } catch (AssertionError expected) {
            assertThat(expected).hasMessageContaining("probe");
            return;
        }
        fail("an unknown AuditLog field passed the strict parse");
    }

    /** The check fails on a BigQuery metadata field the proto does not define. */
    @Test
    void anUnknownBigQueryMetadataFieldFailsTheCheck() {
        GcpAuditCase v2 = GcpAuditCaseBook.cases().stream().filter(c -> c.version().equals("bq-auditmetadata"))
                .findFirst().orElseThrow();
        ObjectNode e = v2.files().get(0).get(0).json();
        ((ObjectNode) e.path("protoPayload").path("metadata")).putObject("jobInsertion").put("notAField", true);
        try {
            roundTrip(e, "probe");
        } catch (AssertionError expected) {
            return;
        }
        fail("an unknown BigQueryAuditMetadata field passed the strict parse");
    }

    static void roundTrip(JsonNode record, String where) {
        GoogleApisDescriptors d = GoogleApisDescriptors.get();
        ObjectNode entry = record.deepCopy();
        JsonNode pp = entry.path("protoPayload");
        if (pp instanceof ObjectNode p && p.path("serviceData").has("typeUrl")) {
            p.set("serviceData", decodeServiceData((ObjectNode) p.get("serviceData"), where));
        }
        if (pp instanceof ObjectNode p && !p.has("@type")) {
            // The google-cloudevents shape: an audit payload without @type is checked as the AuditLog it is.
            ObjectNode typed = JSON.createObjectNode();
            typed.put("@type", "type.googleapis.com/google.cloud.audit.AuditLog");
            typed.setAll(p);
            entry.set("protoPayload", typed);
            pp = typed;
        }
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d.message("google.logging.v2.LogEntry"));
        try {
            JsonFormat.parser().usingTypeRegistry(d.registry()).merge(JSON.writeValueAsString(entry), b);
        } catch (IOException e) {
            throw new AssertionError(where + ": not a google.logging.v2.LogEntry: " + e.getMessage(), e);
        }
        DynamicMessage message = b.build();
        String printed;
        try {
            printed = JsonFormat.printer().usingTypeRegistry(d.registry()).alwaysPrintFieldsWithNoPresence().print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(where + ": cannot print the parsed LogEntry: " + e.getMessage(), e);
        }
        JsonNode back;
        try {
            back = JSON.readTree(printed);
        } catch (IOException e) {
            throw new AssertionError(where + ": " + e.getMessage(), e);
        }
        if (!(pp.isObject() || pp.isMissingNode())) {
            throw new AssertionError(where + ": protoPayload is not an object");
        }
        if (pp.isObject() && pp.has("@type") && !back.path("protoPayload").path("@type").asText().endsWith("google.cloud.audit.AuditLog")) {
            throw new AssertionError(where + ": protoPayload did not resolve to google.cloud.audit.AuditLog");
        }
        subset(entry, back, where, "");
        JsonNode metadata = entry.path("protoPayload").path("metadata");
        if (META_TYPE.equals(metadata.path("@type").asText())) {
            ObjectNode m = metadata.deepCopy();
            m.remove("@type");
            DynamicMessage.Builder mb = DynamicMessage.newBuilder(d.message("google.cloud.audit.BigQueryAuditMetadata"));
            try {
                JsonFormat.parser().usingTypeRegistry(d.registry()).merge(JSON.writeValueAsString(m), mb);
                JsonNode mback = JSON.readTree(JsonFormat.printer().usingTypeRegistry(d.registry())
                        .alwaysPrintFieldsWithNoPresence().print(mb.build()));
                subset(m, mback, where, "/protoPayload/metadata");
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(where + ": metadata is not a google.cloud.audit.BigQueryAuditMetadata: "
                        + e.getMessage(), e);
            } catch (IOException e) {
                throw new AssertionError(where + ": " + e.getMessage(), e);
            }
        }
    }

    private static ObjectNode decodeServiceData(ObjectNode sd, String where) {
        GoogleApisDescriptors d = GoogleApisDescriptors.get();
        String typeUrl = sd.path("typeUrl").asText();
        String fullName = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);
        Descriptors.Descriptor descriptor;
        try {
            descriptor = d.message(fullName);
        } catch (IllegalArgumentException e) {
            throw new AssertionError(where + ": serviceData type " + typeUrl + " is not a vendored message", e);
        }
        byte[] bytes = Base64.getDecoder().decode(sd.path("base64").asText());
        try {
            DynamicMessage m = DynamicMessage.parseFrom(descriptor, bytes);
            assertThat(m.getUnknownFields().asMap()).as(where + ": serviceData bytes carry unknown fields").isEmpty();
            assertThat(m.toByteArray()).as(where + ": serviceData re-encodes to the same bytes").isEqualTo(bytes);
            ObjectNode out = JSON.createObjectNode();
            out.put("@type", typeUrl);
            out.setAll((ObjectNode) JSON.readTree(JsonFormat.printer().usingTypeRegistry(d.registry()).print(m)));
            return out;
        } catch (IOException e) {
            throw new AssertionError(where + ": serviceData bytes are not a " + fullName + ": " + e.getMessage(), e);
        }
    }

    /** Every value of {@code in} appears, equal, in {@code out}. */
    private static void subset(JsonNode in, JsonNode out, String where, String at) {
        if (in.isObject()) {
            if (!out.isObject()) {
                throw new AssertionError(where + ": " + at + " lost its object shape");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = in.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!out.has(f.getKey())) {
                    throw new AssertionError(where + ": " + at + "/" + f.getKey() + " is lost in the round trip");
                }
                subset(f.getValue(), out.get(f.getKey()), where, at + "/" + f.getKey());
            }
            return;
        }
        if (in.isArray()) {
            if (!out.isArray() || out.size() != in.size()) {
                throw new AssertionError(where + ": " + at + " changed length in the round trip");
            }
            for (int i = 0; i < in.size(); i++) {
                subset(in.get(i), out.get(i), where, at + "/" + i);
            }
            return;
        }
        if (!sameValue(in, out)) {
            throw new AssertionError(where + ": " + at + " is " + in + " in the input but " + out + " after the round trip");
        }
    }

    private static boolean sameValue(JsonNode a, JsonNode b) {
        if (a.equals(b)) {
            return true;
        }
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        if (a.isTextual() && b.isTextual()) {
            try {
                return Instant.parse(a.textValue()).equals(Instant.parse(b.textValue()));
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return false;
    }

    private static BigDecimal number(JsonNode n) {
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isTextual() && n.textValue().matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(n.textValue());
        }
        return null;
    }

    /** The records of a case: array elements, JSON lines, or one object, per input file. */
    static List<JsonNode> records(FixtureCase c) {
        List<JsonNode> out = new ArrayList<>();
        for (Path p : c.inputFiles()) {
            String text;
            try {
                text = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(c.id() + ": " + e.getMessage(), e);
            }
            try {
                JsonNode whole = JSON.readTree(text);
                if (whole.isArray()) {
                    whole.forEach(out::add);
                } else {
                    out.add(whole);
                }
                continue;
            } catch (IOException e) {
                // JSON lines below.
            }
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    try {
                        out.add(JSON.readTree(line));
                    } catch (IOException e) {
                        throw new AssertionError(c.id() + ": a line is not JSON: " + e.getMessage(), e);
                    }
                }
            }
        }
        return out;
    }
}
