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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link GcpAuditCase} into the files of its case directories: {@code input.*},
 * {@code expected.json} and {@code meta.yaml}, for each envelope it is written to.
 *
 * <p>The {@code api-proto} form follows log-event spec section 6: the LogEntry as the transport
 * prints it, with any {@code serviceData} as {@code {"typeUrl", "base64"}} of the message bytes,
 * encoded here from the vendored descriptors. Inputs of {@code json} cases are a JSON array (a
 * Cloud Storage sink shard), a single object when the case has one entry, or JSON lines when the
 * case name ends in {@code -ndjson}; {@code api-proto} inputs are JSON lines, one transport record
 * per line. Output is deterministic: two-space indentation and LF line ends.
 */
final class GcpAuditCaseWriter {

    /** Family directory under the corpus root. */
    static final String FAMILY = "gcpaudit";

    /** The value of {@code meta.yaml} {@code generator}. */
    static final String GENERATOR = GcpAuditCaseBook.class.getName();

    /** The evidence of {@code confirmed_by: proto-round-trip}. */
    static final String ROUND_TRIP_TEST = "com.dlap.fixtures.gen.gcpaudit.GcpAuditProtoRoundTripTest";

    /** The googleapis commit the vendored protos come from. */
    static final String GOOGLEAPIS_COMMIT = "ba591f1d7fdedc610639425448756cd8a64adc0f";

    /** Date of that commit. */
    static final String GOOGLEAPIS_DATE = "2026-10-19";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter PRETTY = JSON.writer(new DefaultPrettyPrinter(
            Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private GcpAuditCaseWriter() {
    }

    /**
     * The files of one case, by path relative to the corpus root.
     *
     * @param c the case
     * @return file path to content, in a stable order
     */
    static Map<String, String> render(GcpAuditCase c) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String envelope : c.envelopes()) {
            String dir = FAMILY + "/" + (c.negative() ? "negative/" : "") + envelope + "/" + c.version() + "/"
                    + c.name() + "/";
            boolean api = "api-proto".equals(envelope);
            for (int i = 0; i < c.files().size(); i++) {
                List<ObjectNode> records = new ArrayList<>();
                for (Entry e : c.files().get(i)) {
                    records.add(api ? apiProto(e.json()) : e.json());
                }
                String base = i == 0 ? "input" : "input.late";
                if (api || c.name().endsWith("-ndjson")) {
                    StringBuilder sb = new StringBuilder();
                    for (ObjectNode r : records) {
                        sb.append(compact(r)).append('\n');
                    }
                    out.put(dir + base + ".jsonl", sb.toString());
                } else if (records.size() == 1 && c.files().size() == 1) {
                    out.put(dir + base + ".json", pretty(records.get(0)));
                } else {
                    ArrayNode a = JSON.createArrayNode();
                    records.forEach(a::add);
                    out.put(dir + base + ".json", pretty(a));
                }
            }
            out.put(dir + "expected.json", expected(c, envelope));
            out.put(dir + "meta.yaml", meta(c, envelope));
        }
        return out;
    }

    private static String expected(GcpAuditCase c, String envelope) {
        ObjectNode root = JSON.createObjectNode();
        String[] outcome = c.outcome().split(":", 2);
        root.put("outcome", outcome[0]);
        if (outcome.length > 1) {
            root.put("reason", outcome[1]);
        }
        ArrayNode events = root.putArray("events");
        c.expected().forEach(e -> events.add(e.render(envelope)));
        return pretty(root);
    }

    private static String meta(GcpAuditCase c, String envelope) {
        Set<String> variants = new LinkedHashSet<>(c.variants());
        if ("json".equals(envelope)) {
            variants.addAll(c.jsonOnly());
            if (anyTyped(c)) {
                variants.add("envelope.rest-typed-expansion");
            }
        } else {
            variants.add("envelope.any-auditlog-unpack");
            if (anyServiceData(c) && "bq-auditdata-legacy".equals(c.version())) {
                variants.add("envelope.servicedata-typeurl-base64");
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("origin: synthetic-from-proto\n");
        sb.append("source_url: https://raw.githubusercontent.com/googleapis/googleapis/").append(GOOGLEAPIS_COMMIT)
                .append('/').append(protoOf(c.version())).append('\n');
        sb.append("doc_date: ").append(GOOGLEAPIS_DATE).append('\n');
        sb.append("license: synthetic\n");
        list(sb, "covers", c.covers());
        list(sb, "variant_ids", new ArrayList<>(variants));
        sb.append("expected_outcome: ").append(c.outcome()).append('\n');
        sb.append("anonymised: false\n");
        sb.append("generator: ").append(GENERATOR).append('\n');
        sb.append("confirmed_by:\n");
        sb.append("  - method: proto-round-trip\n");
        sb.append("    evidence: ").append(ROUND_TRIP_TEST).append('\n');
        String notes = "Generated by " + GcpAuditCaseBook.class.getSimpleName() + " from the field lists of the vendored "
                + "googleapis protos; every value is invented. " + c.notes();
        sb.append("notes: ").append(quote(notes.strip())).append('\n');
        return sb.toString();
    }

    private static String protoOf(String version) {
        return switch (version) {
            case "bq-auditmetadata" -> "google/cloud/audit/bigquery_audit_metadata.proto";
            case "bq-auditdata-legacy" -> "google/cloud/bigquery/logging/v1/audit_data.proto";
            default -> "google/cloud/audit/audit_log.proto";
        };
    }

    private static boolean anyTyped(GcpAuditCase c) {
        return c.files().stream().flatMap(List::stream).anyMatch(e -> e.pp() != null && e.pp().has("@type"));
    }

    private static boolean anyServiceData(GcpAuditCase c) {
        return c.files().stream().flatMap(List::stream).anyMatch(e -> e.pp() != null && e.pp().has("serviceData"));
    }

    private static void list(StringBuilder sb, String key, List<String> values) {
        if (values.isEmpty()) {
            sb.append(key).append(": []\n");
            return;
        }
        sb.append(key).append(":\n");
        values.forEach(v -> sb.append("  - ").append(v).append('\n'));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The api-proto form of an entry: {@code serviceData} as {@code {typeUrl, base64}}. */
    static ObjectNode apiProto(ObjectNode entry) {
        ObjectNode copy = entry.deepCopy();
        JsonNode pp = copy.path("protoPayload");
        if (pp instanceof ObjectNode p && p.path("serviceData").isObject() && p.path("serviceData").has("@type")) {
            p.set("serviceData", encode((ObjectNode) p.get("serviceData")));
        }
        return copy;
    }

    /**
     * Encodes an expanded {@code serviceData} object as {@code {typeUrl, base64}}.
     *
     * @param expanded the object with {@code @type}
     * @return the encoded form
     */
    static ObjectNode encode(ObjectNode expanded) {
        String typeUrl = expanded.path("@type").asText();
        String fullName = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);
        Descriptors.Descriptor d = GoogleApisDescriptors.get().message(fullName);
        ObjectNode body = expanded.deepCopy();
        body.remove("@type");
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
        try {
            JsonFormat.parser().usingTypeRegistry(GoogleApisDescriptors.get().registry()).merge(compact(body), b);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("serviceData is not a valid " + fullName + ": " + e.getMessage(), e);
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("typeUrl", typeUrl);
        out.put("base64", Base64.getEncoder().encodeToString(b.build().toByteArray()));
        return out;
    }

    static String pretty(JsonNode n) {
        try {
            return PRETTY.writeValueAsString(n) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String compact(JsonNode n) {
        try {
            return JSON.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
