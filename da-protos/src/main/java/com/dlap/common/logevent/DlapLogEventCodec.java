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

package com.dlap.common.logevent;

import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.PrincipalType;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one reader and writer for {@code dlap.log.v1.DlapLogEvent} on Kafka
 * (ADR-0045, docs/specs/log-event.md).
 *
 * <p><b>Writes one encoding.</b> {@link #encode} prints proto3 JSON: a JSON
 * object with lowerCamelCase names, every non-message field present (default
 * values included, so map-based readers keep finding {@code changeType}),
 * sorted map keys and no insignificant whitespace. Producers send it with a
 * {@code StringSerializer}; sending the String through Spring's
 * {@code JsonSerializer} wraps it in quotes again.
 *
 * <p><b>Reads both encodings.</b> {@link #decode} accepts that object, the
 * legacy double encoding (a JSON string holding the object), an already
 * deserialized Jackson tree or Map, and the three producer shapes in use
 * before ADR-0045: da-logingest's normalized map, the crawler bridge and the
 * log-analyzer publisher. Reading is forward compatible: fields and enum
 * values this build does not know are dropped and reported as warnings, never
 * fatal. Legacy keys are mapped as the spec's "Legacy shapes" table states.
 *
 * <p>{@link #decodeStrict} is the producer contract check: it accepts only the
 * single encoding with fields this build defines.
 *
 * <p>Instances are immutable and thread-safe; {@link #defaults()} is shared.
 */
public final class DlapLogEventCodec {

    /** The only schema version of this message. */
    public static final String SCHEMA_VERSION = "v1";

    /** String wraps the reader unwraps before giving up (the legacy double encoding is one). */
    static final int MAX_STRING_WRAPS = 2;

    private static final DlapLogEventCodec DEFAULT = new DlapLogEventCodec();

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
            .includingDefaultValueFields()
            .omittingInsignificantWhitespace()
            .sortingMapKeys();
    private static final JsonFormat.Parser STRICT_PARSER = JsonFormat.parser();
    private static final JsonFormat.Parser TOLERANT_PARSER = JsonFormat.parser().ignoringUnknownFields();

    private static final String STRUCT = Struct.getDescriptor().getFullName();
    private static final String TIMESTAMP = Timestamp.getDescriptor().getFullName();
    /** U+FEFF, the byte order mark some writers put before UTF-8 text. */
    private static final char BOM = (char) 0xFEFF;

    /** Legacy {@code source} markers and what they mean in the v1 contract. */
    private static final Map<String, LegacyProducer> LEGACY_PRODUCERS = Map.of(
            "crawler", new LegacyProducer(Origin.ORIGIN_CRAWLER, SourceFormats.DLAP_CRAWLER),
            "loganalyzer", new LegacyProducer(Origin.ORIGIN_CLOUD_LOG, SourceFormats.DLAP_LOGANALYZER));

    private static final List<String> LEGACY_TARGET_ID_KEYS = List.of("targetResourceId", "assetUri", "asset_uri");
    private static final List<String> LEGACY_PROVIDER_KEYS = List.of("cloudProvider", "cloud_provider");
    private static final List<String> LEGACY_TECH_KEYS = List.of("technicalMetadata", "technical_metadata");

    /**
     * Provider identity-type spellings the legacy pipeline passes through as
     * {@code principal.type} (REST ingest and the replay fixtures send CloudTrail
     * {@code userIdentity.type} and Azure principal types verbatim), keyed by
     * {@link #aliasKey}. Spec section 12.
     */
    private static final Map<String, Map<String, String>> LEGACY_ENUM_ALIASES = Map.of(
            PrincipalType.getDescriptor().getFullName(), Map.of(
                    "ROOT", PrincipalType.USER.name(),
                    "IAMUSER", PrincipalType.USER.name(),
                    "FEDERATEDUSER", PrincipalType.USER.name(),
                    "IDENTITYCENTERUSER", PrincipalType.USER.name(),
                    "ASSUMEDROLE", PrincipalType.ROLE.name(),
                    "AWSSERVICE", PrincipalType.SYSTEM.name(),
                    "SERVICEPRINCIPAL", PrincipalType.SERVICE_ACCOUNT.name(),
                    "MANAGEDIDENTITY", PrincipalType.SERVICE_ACCOUNT.name(),
                    "SERVICEACCOUNT", PrincipalType.SERVICE_ACCOUNT.name()));

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<Descriptor, Map<String, FieldDescriptor>> fieldIndex = new ConcurrentHashMap<>();

    /** Creates a codec. Prefer {@link #defaults()}; instances hold no per-call state. */
    public DlapLogEventCodec() {
        // Stateless apart from caches.
    }

    /**
     * Returns the shared codec.
     *
     * @return the shared instance
     */
    public static DlapLogEventCodec defaults() {
        return DEFAULT;
    }

    // =====================================================================
    // Writing
    // =====================================================================

    /**
     * Prints the event in the single wire encoding. An empty
     * {@code schema_version} is written as {@value #SCHEMA_VERSION}.
     *
     * @param event the event
     * @return proto3 JSON object text
     */
    public String encode(DlapLogEvent event) {
        DlapLogEvent e = event.getSchemaVersion().isEmpty()
                ? event.toBuilder().setSchemaVersion(SCHEMA_VERSION).build()
                : event;
        return print(e);
    }

    /**
     * Prints the event as UTF-8 bytes, for a {@code ByteArraySerializer}.
     *
     * @param event the event
     * @return UTF-8 bytes of {@link #encode}
     */
    public byte[] encodeToBytes(DlapLogEvent event) {
        return encode(event).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the event as a mutable map of the wire JSON, for consumers that
     * still evaluate events as maps (lineage extraction, metadata harvest).
     *
     * @param event the event
     * @return lowerCamelCase map; numbers inside {@code rawEvent} are doubles
     */
    public Map<String, Object> toMap(DlapLogEvent event) {
        try {
            return mapper.readValue(encode(event), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Printed log event is not valid JSON", e);
        }
    }

    /**
     * Prints any log-layer message ({@code LogQuarantineRecord},
     * {@code LogBindingStatus}) in the same encoding as {@link #encode}.
     *
     * @param message the message
     * @return proto3 JSON object text
     */
    public String encodeMessage(MessageOrBuilder message) {
        return print(message);
    }

    // =====================================================================
    // Reading
    // =====================================================================

    /**
     * Reads an event from any supported encoding or legacy shape.
     *
     * @param payload {@code byte[]}, a {@link CharSequence}, a Jackson
     *                {@link JsonNode}, a {@link Map} or a {@link DlapLogEvent}
     * @return the event
     * @throws LogEventDecodeException when the payload is empty, not JSON, not
     *                                 an object, or holds a value its field cannot take
     */
    public DlapLogEvent decode(Object payload) {
        return decodeDetailed(payload).event();
    }

    /**
     * Reads an event and reports how it was encoded and what was rewritten.
     *
     * @param payload see {@link #decode}
     * @return the event, its encoding and the reader's warnings
     * @throws LogEventDecodeException see {@link #decode}
     */
    public DecodedLogEvent decodeDetailed(Object payload) {
        if (payload instanceof DlapLogEvent event) {
            return new DecodedLogEvent(event, PayloadEncoding.MESSAGE, List.of());
        }
        Root root = readRoot(payload);
        ObjectNode work = root.node().deepCopy();
        List<String> warnings = new ArrayList<>();
        mapLegacyKeys(work, warnings);
        normalizeMessage(work, DlapLogEvent.getDescriptor(), "", warnings);

        DlapLogEvent.Builder builder = DlapLogEvent.newBuilder();
        merge(STRICT_PARSER, work, builder);
        if (builder.getSchemaVersion().isEmpty()) {
            builder.setSchemaVersion(SCHEMA_VERSION);
        }
        return new DecodedLogEvent(builder.build(), root.encoding(), warnings);
    }

    /**
     * Producer contract check: accepts only the single encoding, only fields
     * this build defines and only canonical enum names. Meant for contract
     * tests and CI; the exception message quotes the parser and may include
     * payload content.
     *
     * @param json payload text
     * @return the event
     * @throws LogEventDecodeException when the payload is double-encoded, has an
     *                                 unknown field, or does not parse
     */
    public DlapLogEvent decodeStrict(String json) {
        Root root = readRoot(json);
        if (root.encoding() != PayloadEncoding.JSON_OBJECT) {
            throw new LogEventDecodeException(LogEventDecodeException.Reason.NOT_AN_OBJECT,
                    "payload is string-wrapped (double-encoded); producers must send the JSON object", null);
        }
        DlapLogEvent.Builder builder = DlapLogEvent.newBuilder();
        try {
            STRICT_PARSER.merge(stripBom(json), builder);
        } catch (InvalidProtocolBufferException e) {
            String msg = String.valueOf(e.getMessage());
            LogEventDecodeException.Reason reason = msg.contains("Cannot find field")
                    ? LogEventDecodeException.Reason.UNKNOWN_FIELD
                    : LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH;
            throw new LogEventDecodeException(reason, msg, e);
        }
        return builder.build();
    }

    /**
     * Reads any log-layer message from either encoding, ignoring fields this
     * build does not know (forward compatibility).
     *
     * @param payload         see {@link #decode}
     * @param defaultInstance the message type, for example
     *                        {@code LogQuarantineRecord.getDefaultInstance()}
     * @param <M>             the message type
     * @return the message
     * @throws LogEventDecodeException when the payload is not a JSON object of that type
     */
    @SuppressWarnings("unchecked")
    public <M extends Message> M decodeMessage(Object payload, M defaultInstance) {
        if (defaultInstance.getClass().isInstance(payload)) {
            return (M) payload;
        }
        Root root = readRoot(payload);
        Message.Builder builder = defaultInstance.newBuilderForType();
        merge(TOLERANT_PARSER, root.node(), builder);
        return (M) builder.build();
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private record Root(ObjectNode node, PayloadEncoding encoding) { }

    private record LegacyProducer(Origin origin, String sourceFormat) { }

    private Root readRoot(Object payload) {
        if (payload == null) {
            throw empty();
        }
        JsonNode node;
        PayloadEncoding encoding;
        if (payload instanceof byte[] bytes) {
            node = parseText(new String(bytes, StandardCharsets.UTF_8));
            encoding = PayloadEncoding.JSON_OBJECT;
        } else if (payload instanceof CharSequence text) {
            node = parseText(text.toString());
            encoding = PayloadEncoding.JSON_OBJECT;
        } else if (payload instanceof JsonNode tree) {
            node = tree;
            encoding = PayloadEncoding.TREE;
        } else if (payload instanceof Map<?, ?> map) {
            node = mapper.valueToTree(map);
            encoding = PayloadEncoding.TREE;
        } else {
            throw new LogEventDecodeException(LogEventDecodeException.Reason.UNSUPPORTED_PAYLOAD_TYPE,
                    "cannot decode a " + payload.getClass().getName(), null);
        }
        int wraps = 0;
        while (node.isTextual()) {
            if (++wraps > MAX_STRING_WRAPS) {
                throw new LogEventDecodeException(LogEventDecodeException.Reason.NOT_AN_OBJECT,
                        "payload is string-wrapped more than " + MAX_STRING_WRAPS + " times", null);
            }
            node = parseText(node.textValue());
            encoding = PayloadEncoding.STRING_WRAPPED_JSON;
        }
        if (node.isNull() || node.isMissingNode()) {
            throw empty();
        }
        if (!node.isObject()) {
            throw new LogEventDecodeException(LogEventDecodeException.Reason.NOT_AN_OBJECT,
                    "payload is a JSON " + node.getNodeType().name().toLowerCase(Locale.ROOT), null);
        }
        return new Root((ObjectNode) node, encoding);
    }

    private JsonNode parseText(String text) {
        String t = stripBom(text);
        if (t.isBlank()) {
            throw empty();
        }
        try {
            JsonNode node = mapper.readTree(t);
            if (node == null) {
                throw empty();
            }
            return node;
        } catch (JsonProcessingException e) {
            // Jackson's message can quote payload content; report the position only.
            JsonLocation where = e.getLocation();
            String at = where == null ? "" : " at line " + where.getLineNr() + ", column " + where.getColumnNr();
            throw new LogEventDecodeException(LogEventDecodeException.Reason.INVALID_JSON,
                    "payload is not valid JSON" + at, null);
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    private static LogEventDecodeException empty() {
        return new LogEventDecodeException(LogEventDecodeException.Reason.EMPTY_PAYLOAD, "payload is empty", null);
    }

    private static LogEventDecodeException mismatch(String path, String expected) {
        return new LogEventDecodeException(LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH,
                "field " + path + " is not " + expected, null);
    }

    private String print(MessageOrBuilder message) {
        try {
            return PRINTER.print(message);
        } catch (InvalidProtocolBufferException e) {
            // Only Any fields with unregistered types fail to print; the log
            // contract has none.
            throw new IllegalStateException("Cannot print " + message.getDescriptorForType().getFullName(), e);
        }
    }

    private void merge(JsonFormat.Parser parser, ObjectNode node, Message.Builder builder) {
        try {
            parser.merge(mapper.writeValueAsString(node), builder);
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            // The parser's message can quote payload values; keep it out of logs.
            throw new LogEventDecodeException(LogEventDecodeException.Reason.FIELD_TYPE_MISMATCH,
                    "payload does not fit " + builder.getDescriptorForType().getFullName(), null);
        }
    }

    /** Maps the pre-ADR-0045 top-level keys onto v1 fields (spec: "Legacy shapes"). */
    private static void mapLegacyKeys(ObjectNode root, List<String> warnings) {
        JsonNode source = root.remove("source");
        if (source != null && !source.isNull()) {
            LegacyProducer producer = LEGACY_PRODUCERS.get(source.asText("").strip().toLowerCase(Locale.ROOT));
            if (producer == null) {
                warnings.add("dropped unknown legacy key source");
            } else {
                if (!hasText(root, "origin")) {
                    root.put("origin", producer.origin().name());
                }
                if (!hasText(root, "sourceFormat") && !hasText(root, "source_format")) {
                    root.put("sourceFormat", producer.sourceFormat());
                }
                warnings.add("mapped legacy key source to origin and sourceFormat");
            }
        }

        JsonNode existingTarget = root.has("targetResource") ? root.get("targetResource") : root.get("target_resource");
        boolean targetUsable = existingTarget == null || existingTarget.isNull() || existingTarget.isObject();

        for (String key : LEGACY_TARGET_ID_KEYS) {
            JsonNode alias = root.remove(key);
            if (alias != null && alias.isTextual() && !alias.textValue().isBlank() && targetUsable) {
                ObjectNode target = target(root);
                if (!hasText(target, "id")) {
                    target.put("id", alias.textValue());
                }
                warnings.add("mapped legacy key " + key + " to targetResource.id");
            }
        }
        for (String key : LEGACY_PROVIDER_KEYS) {
            JsonNode alias = root.remove(key);
            if (alias != null && !alias.isNull()) {
                if (!hasText(root, "sourceProvider") && !hasText(root, "source_provider")) {
                    root.set("sourceProvider", alias);
                }
                warnings.add("mapped legacy key " + key + " to sourceProvider");
            }
        }
        for (String key : LEGACY_TECH_KEYS) {
            JsonNode alias = root.remove(key);
            if (alias != null && alias.isObject() && targetUsable) {
                ObjectNode target = target(root);
                if (!target.has("technicalMetadata") && !target.has("technical_metadata")) {
                    target.set("technicalMetadata", alias);
                }
                warnings.add("mapped legacy key " + key + " to targetResource.technicalMetadata");
            }
        }
    }

    private static ObjectNode target(ObjectNode root) {
        JsonNode t = root.has("targetResource") ? root.get("targetResource") : root.get("target_resource");
        if (t != null && t.isObject()) {
            return (ObjectNode) t;
        }
        return root.putObject("targetResource");
    }

    private static boolean hasText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null && v.isValueNode() && !v.isNull() && !v.asText("").isBlank();
    }

    /**
     * Rewrites one JSON object in place so the strict proto3 JSON parser takes
     * it: canonical json names, unknown fields and unknown enum values dropped
     * (with a warning), JSON nulls removed, legacy value forms coerced.
     */
    private void normalizeMessage(ObjectNode node, Descriptor type, String path, List<String> warnings) {
        Map<String, FieldDescriptor> index = index(type);
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (String name : names) {
            String fieldPath = path + name;
            FieldDescriptor field = index.get(name);
            if (field == null) {
                warnings.add("dropped unknown field " + fieldPath);
                continue;
            }
            if (!seen.add(field.getNumber())) {
                warnings.add("dropped duplicate field " + fieldPath);
                continue;
            }
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            JsonNode normalized = normalizeField(field, value, fieldPath, warnings);
            if (normalized != null) {
                out.put(field.getJsonName(), normalized);
            }
        }
        node.removeAll();
        node.setAll(out);
    }

    private JsonNode normalizeField(FieldDescriptor field, JsonNode value, String path, List<String> warnings) {
        if (field.isMapField()) {
            if (!value.isObject()) {
                warnings.add("dropped non-object map " + path);
                return null;
            }
            FieldDescriptor valueField = field.getMessageType().findFieldByName("value");
            ObjectNode map = mapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode entry = value.get(key);
                if (entry == null || entry.isNull()) {
                    continue;
                }
                JsonNode normalized = normalizeSingular(valueField, entry, path + "." + key, warnings, true);
                if (normalized != null) {
                    map.set(key, normalized);
                }
            }
            return map;
        }
        if (field.isRepeated()) {
            ArrayNode list = mapper.createArrayNode();
            ArrayNode source;
            if (value.isArray()) {
                source = (ArrayNode) value;
            } else {
                warnings.add("wrapped single value into list " + path);
                source = mapper.createArrayNode().add(value);
            }
            for (int i = 0; i < source.size(); i++) {
                JsonNode element = source.get(i);
                if (element == null || element.isNull()) {
                    continue;
                }
                JsonNode normalized = normalizeSingular(field, element, path + "[" + i + "]", warnings, false);
                if (normalized != null) {
                    list.add(normalized);
                }
            }
            return list;
        }
        return normalizeSingular(field, value, path, warnings, false);
    }

    private JsonNode normalizeSingular(FieldDescriptor field, JsonNode value, String path,
                                       List<String> warnings, boolean mapValue) {
        switch (field.getJavaType()) {
            case MESSAGE: {
                String typeName = field.getMessageType().getFullName();
                if (STRUCT.equals(typeName)) {
                    return asStruct(value, path, warnings);
                }
                if (TIMESTAMP.equals(typeName)) {
                    return asTimestamp(value, path);
                }
                if (!value.isObject()) {
                    throw mismatch(path, "an object");
                }
                normalizeMessage((ObjectNode) value, field.getMessageType(), path + ".", warnings);
                return value;
            }
            case ENUM:
                return normalizeEnum(field.getEnumType(), value, path, warnings);
            case STRING:
                if (value.isTextual()) {
                    return value;
                }
                if (value.isValueNode()) {
                    warnings.add("coerced " + value.getNodeType().name().toLowerCase(Locale.ROOT)
                            + " to string " + path);
                    return TextNode.valueOf(value.asText());
                }
                if (mapValue) {
                    warnings.add("serialized nested JSON to string " + path);
                    return TextNode.valueOf(value.toString());
                }
                throw mismatch(path, "a string");
            case BOOLEAN:
                if (value.isBoolean()) {
                    return value;
                }
                if (value.isTextual()) {
                    String t = value.textValue().strip();
                    if (t.isEmpty()) {
                        return null;
                    }
                    if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                        warnings.add("coerced string to boolean " + path);
                        return BooleanNode.valueOf(Boolean.parseBoolean(t));
                    }
                }
                throw mismatch(path, "a boolean");
            case INT:
            case LONG:
                return asInteger(value, path);
            default:
                // FLOAT, DOUBLE and BYTE_STRING: none in the log contract today;
                // the proto3 JSON parser validates them.
                return value;
        }
    }

    private JsonNode asStruct(JsonNode value, String path, List<String> warnings) {
        if (value.isObject()) {
            return value;
        }
        if (value.isTextual()) {
            String t = value.textValue();
            if (t.isBlank()) {
                return null;
            }
            JsonNode parsed = tryParseObject(t);
            if (parsed != null) {
                warnings.add("parsed string-encoded object " + path);
                return parsed;
            }
        }
        warnings.add("wrapped non-object " + value.getNodeType().name().toLowerCase(Locale.ROOT)
                + " as {\"value\": ...} " + path);
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("value", value);
        return wrapper;
    }

    private JsonNode tryParseObject(String text) {
        try {
            JsonNode parsed = mapper.readTree(text);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (JsonProcessingException notJson) {
            // Not JSON: the caller wraps the text instead and records a warning.
            return null;
        }
    }

    private static JsonNode asTimestamp(JsonNode value, String path) {
        if (!value.isTextual()) {
            throw mismatch(path, "an RFC 3339 timestamp string");
        }
        String t = value.textValue().strip();
        if (t.isEmpty()) {
            return null;
        }
        try {
            Timestamps.parse(t);
        } catch (ParseException | IllegalArgumentException e) {
            throw mismatch(path, "an RFC 3339 timestamp with a zone");
        }
        return TextNode.valueOf(t);
    }

    private static JsonNode asInteger(JsonNode value, String path) {
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value;
        }
        if (value.isTextual()) {
            String t = value.textValue().strip();
            if (t.isEmpty()) {
                return null;
            }
            try {
                Long.parseLong(t);
                return TextNode.valueOf(t);
            } catch (NumberFormatException e) {
                throw mismatch(path, "an integer");
            }
        }
        throw mismatch(path, "an integer");
    }

    private static JsonNode normalizeEnum(EnumDescriptor type, JsonNode value, String path, List<String> warnings) {
        EnumValueDescriptor match = null;
        if (value.isTextual()) {
            String t = value.textValue().strip();
            if (t.isEmpty()) {
                return null;
            }
            match = resolveEnum(type, t);
            if (match != null && !match.getName().equals(value.textValue())) {
                warnings.add("normalized " + type.getName() + " value " + path);
            }
        } else if (value.isIntegralNumber() && value.canConvertToInt()) {
            match = type.findValueByNumber(value.intValue());
        }
        if (match == null) {
            warnings.add("dropped unknown " + type.getName() + " value " + path);
            return null;
        }
        return TextNode.valueOf(match.getName());
    }

    /**
     * Exact name, then upper case, then the enum-name prefix (DATA_READ to
     * EVENT_CATEGORY_DATA_READ), then a provider spelling from
     * {@link #LEGACY_ENUM_ALIASES}.
     */
    static EnumValueDescriptor resolveEnum(EnumDescriptor type, String text) {
        EnumValueDescriptor v = type.findValueByName(text);
        if (v != null) {
            return v;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        v = type.findValueByName(upper);
        if (v != null) {
            return v;
        }
        v = type.findValueByName(enumPrefix(type) + upper);
        if (v != null) {
            return v;
        }
        String alias = LEGACY_ENUM_ALIASES.getOrDefault(type.getFullName(), Map.of()).get(aliasKey(text));
        return alias == null ? null : type.findValueByName(alias);
    }

    /** Upper case without separators: {@code AssumedRole} and {@code ASSUMED_ROLE} both give {@code ASSUMEDROLE}. */
    static String aliasKey(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    /** {@code EventCategory} to {@code EVENT_CATEGORY_}. */
    static String enumPrefix(EnumDescriptor type) {
        String name = type.getName();
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.append('_').toString();
    }

    private Map<String, FieldDescriptor> index(Descriptor type) {
        return fieldIndex.computeIfAbsent(type, t -> {
            Map<String, FieldDescriptor> m = new HashMap<>();
            for (FieldDescriptor f : t.getFields()) {
                m.put(f.getName(), f);
                m.put(f.getJsonName(), f);
            }
            return Map.copyOf(m);
        });
    }
}
