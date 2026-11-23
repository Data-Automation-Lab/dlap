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

package com.dlap.catalog.lifecycle;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The one form of a Lifecycle State object (docs/specs/lifecycle-state.md rule R6): members in the
 * order of section 2, every member present with {@code null} for unknown, timestamps as
 * {@link Instant#toString()} renders them, integers as JSON numbers.
 *
 * <p>The rendering uses its own {@link ObjectMapper}, never the application's, so no global JSON
 * setting (null inclusion, date format, indentation) can change the bytes: the same object always
 * gives the same text. {@link #compact} is the wire form of the REST API; {@link #pretty} is the
 * two-space form of the OKF mapping (section 8.2).
 */
public final class LifecycleStateJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final List<String> MEMBERS = List.of("specVersion", "asset", "platform", "asOf", "createdAt",
            "lastReadAt", "lastWrittenAt", "readCoverage", "readCoverageReasons", "readCoverageSince", "sizeBytes",
            "sizeObservedAt", "retention", "legalHold", "disposition", "provenance");
    private static final List<String> PLATFORM_MEMBERS = List.of("provider", "kind");
    private static final List<String> RETENTION_MEMBERS = List.of("status", "basis", "days", "fixedDate",
            "expiresAt", "source");
    private static final List<String> DISPOSITION_MEMBERS = List.of("action", "executedAt", "executedBy",
            "evidence");
    private static final List<String> PROVENANCE_MEMBERS = List.of("field", "channel", "bindingIds", "eventIds");

    private LifecycleStateJson() {
    }

    /**
     * The object as a JSON tree, members in specification order.
     *
     * @param state the object
     * @return a new tree
     */
    public static ObjectNode toNode(LifecycleState state) {
        ObjectNode n = NODES.objectNode();
        n.put("specVersion", state.specVersion());
        n.put("asset", state.asset());
        ObjectNode platform = n.putObject("platform");
        platform.put("provider", state.platform().provider());
        platform.put("kind", state.platform().kind());
        n.put("asOf", state.asOf().toString());
        putInstant(n, "createdAt", state.createdAt());
        putInstant(n, "lastReadAt", state.lastReadAt());
        putInstant(n, "lastWrittenAt", state.lastWrittenAt());
        n.put("readCoverage", state.readCoverage());
        ArrayNode reasons = n.putArray("readCoverageReasons");
        state.readCoverageReasons().forEach(reasons::add);
        putInstant(n, "readCoverageSince", state.readCoverageSince());
        if (state.sizeBytes() == null) {
            n.putNull("sizeBytes");
        } else {
            n.put("sizeBytes", state.sizeBytes().longValue());
        }
        putInstant(n, "sizeObservedAt", state.sizeObservedAt());
        LifecycleState.Retention r = state.retention();
        ObjectNode retention = n.putObject("retention");
        retention.put("status", r.status());
        retention.put("basis", r.basis());
        if (r.days() == null) {
            retention.putNull("days");
        } else {
            retention.put("days", r.days().intValue());
        }
        putInstant(retention, "fixedDate", r.fixedDate());
        putInstant(retention, "expiresAt", r.expiresAt());
        retention.put("source", r.source());
        n.put("legalHold", state.legalHold());
        LifecycleState.Disposition d = state.disposition();
        if (d == null) {
            n.putNull("disposition");
        } else {
            ObjectNode disposition = n.putObject("disposition");
            disposition.put("action", d.action());
            disposition.put("executedAt", d.executedAt().toString());
            disposition.put("executedBy", d.executedBy());
            ArrayNode evidence = disposition.putArray("evidence");
            d.evidence().forEach(evidence::add);
        }
        ArrayNode provenance = n.putArray("provenance");
        for (LifecycleState.ProvenanceEntry e : state.provenance()) {
            ObjectNode entry = provenance.addObject();
            entry.put("field", e.field());
            entry.put("channel", e.channel());
            ArrayNode bindings = entry.putArray("bindingIds");
            e.bindingIds().forEach(bindings::add);
            ArrayNode events = entry.putArray("eventIds");
            e.eventIds().forEach(events::add);
        }
        return n;
    }

    /**
     * The object on one line, the wire form of the REST API.
     *
     * @param state the object
     * @return the JSON text
     */
    public static String compact(LifecycleState state) {
        return compact(toNode(state));
    }

    /**
     * A JSON tree on one line, with this class's mapper.
     *
     * @param node the tree
     * @return the JSON text
     */
    public static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot render a JSON tree", e);
        }
    }

    /**
     * The object printed with two-space indentation in member order, as the OKF mapping shows it:
     * {@code "name": value}, one array element per line, {@code []} for an empty array, LF line ends,
     * no final line break.
     *
     * @param state the object
     * @return the JSON text
     */
    public static String pretty(LifecycleState state) {
        StringBuilder out = new StringBuilder();
        print(toNode(state), 0, out);
        return out.toString();
    }

    private static void print(JsonNode node, int indent, StringBuilder out) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                pad(indent + 2, out);
                out.append(compact(NODES.textNode(f.getKey()))).append(": ");
                print(f.getValue(), indent + 2, out);
                out.append(fields.hasNext() ? ",\n" : "\n");
            }
            pad(indent, out);
            out.append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                pad(indent + 2, out);
                print(node.get(i), indent + 2, out);
                out.append(i + 1 < node.size() ? ",\n" : "\n");
            }
            pad(indent, out);
            out.append(']');
        } else {
            out.append(compact(node));
        }
    }

    private static void pad(int count, StringBuilder out) {
        out.append(" ".repeat(count));
    }

    private static void putInstant(ObjectNode node, String name, Instant value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value.toString());
        }
    }

    /**
     * Reads an object strictly: every member of section 2 must be present, no other member may be,
     * {@code specVersion} must be {@link LifecycleState#SPEC_VERSION}, and timestamps must be in the
     * one form of R6. Used by the offline OKF export, which only re-renders objects the engine
     * emitted.
     *
     * @param node the JSON object
     * @return the record
     * @throws IllegalArgumentException when the object is not a Lifecycle State 0.1 object; the
     *                                  message names the member, never its value
     */
    public static LifecycleState parse(JsonNode node) {
        requireMembers(node, MEMBERS, "");
        String version = text(node, "specVersion", "", false);
        if (!LifecycleState.SPEC_VERSION.equals(version)) {
            throw new IllegalArgumentException("specVersion is not " + LifecycleState.SPEC_VERSION);
        }
        JsonNode p = node.get("platform");
        requireMembers(p, PLATFORM_MEMBERS, "platform.");
        JsonNode r = node.get("retention");
        requireMembers(r, RETENTION_MEMBERS, "retention.");
        LifecycleState.Retention retention = new LifecycleState.Retention(text(r, "status", "retention.", false),
                text(r, "basis", "retention.", true), integer(r, "days", "retention."),
                instant(r, "fixedDate", "retention.", true), instant(r, "expiresAt", "retention.", true),
                text(r, "source", "retention.", true));
        JsonNode d = node.get("disposition");
        LifecycleState.Disposition disposition = null;
        if (!d.isNull()) {
            requireMembers(d, DISPOSITION_MEMBERS, "disposition.");
            disposition = new LifecycleState.Disposition(text(d, "action", "disposition.", false),
                    instant(d, "executedAt", "disposition.", false), text(d, "executedBy", "disposition.", false),
                    strings(d, "evidence", "disposition."));
        }
        List<LifecycleState.ProvenanceEntry> provenance = new ArrayList<>();
        JsonNode entries = node.get("provenance");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("provenance is not an array");
        }
        for (JsonNode e : entries) {
            requireMembers(e, PROVENANCE_MEMBERS, "provenance[].");
            provenance.add(new LifecycleState.ProvenanceEntry(text(e, "field", "provenance[].", false),
                    text(e, "channel", "provenance[].", true), strings(e, "bindingIds", "provenance[]."),
                    strings(e, "eventIds", "provenance[].")));
        }
        JsonNode size = node.get("sizeBytes");
        if (!size.isNull() && !size.canConvertToLong()) {
            throw new IllegalArgumentException("sizeBytes is not a 64-bit integer");
        }
        JsonNode hold = node.get("legalHold");
        if (!hold.isBoolean()) {
            throw new IllegalArgumentException("legalHold is not a boolean");
        }
        return new LifecycleState(version, text(node, "asset", "", false),
                new LifecycleState.Platform(text(p, "provider", "platform.", false),
                        text(p, "kind", "platform.", false)),
                instant(node, "asOf", "", false), instant(node, "createdAt", "", true),
                instant(node, "lastReadAt", "", true), instant(node, "lastWrittenAt", "", true),
                text(node, "readCoverage", "", true), strings(node, "readCoverageReasons", ""),
                instant(node, "readCoverageSince", "", true), size.isNull() ? null : size.longValue(),
                instant(node, "sizeObservedAt", "", true), retention, hold.booleanValue(), disposition, provenance);
    }

    private static void requireMembers(JsonNode node, List<String> members, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException((path.isEmpty() ? "the value" : path) + " is not an object");
        }
        Set<String> present = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(present::add);
        for (String m : members) {
            if (!present.contains(m)) {
                throw new IllegalArgumentException(path + m + " is missing");
            }
        }
        present.removeAll(members);
        if (!present.isEmpty()) {
            throw new IllegalArgumentException(path + present.iterator().next() + " is not a member of 0.1");
        }
    }

    private static String text(JsonNode node, String name, String path, boolean nullable) {
        JsonNode v = node.get(name);
        if (v.isNull() && nullable) {
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(path + name + " is not a string");
        }
        return v.textValue();
    }

    private static Integer integer(JsonNode node, String name, String path) {
        JsonNode v = node.get(name);
        if (v.isNull()) {
            return null;
        }
        if (!v.isInt()) {
            throw new IllegalArgumentException(path + name + " is not an integer");
        }
        return v.intValue();
    }

    private static Instant instant(JsonNode node, String name, String path, boolean nullable) {
        String text = text(node, name, path, nullable);
        if (text == null) {
            return null;
        }
        try {
            Instant value = Instant.parse(text);
            if (!value.toString().equals(text)) {
                throw new IllegalArgumentException(path + name + " is not in the one form of rule R6");
            }
            return value;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(path + name + " is not an RFC 3339 instant", e);
        }
    }

    private static List<String> strings(JsonNode node, String name, String path) {
        JsonNode v = node.get(name);
        if (!v.isArray()) {
            throw new IllegalArgumentException(path + name + " is not an array");
        }
        List<String> out = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(path + name + " holds a value that is not a string");
            }
            out.add(item.textValue());
        }
        return out;
    }
}
