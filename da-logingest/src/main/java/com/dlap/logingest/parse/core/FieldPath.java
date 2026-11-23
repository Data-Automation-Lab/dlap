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

package com.dlap.logingest.parse.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A path into a JSON record: an RFC 6901 JSON pointer ({@code /userIdentity/arn},
 * {@code /protoPayload/metadata/@type}, with {@code ~0} for {@code ~} and {@code ~1} for
 * {@code /}), plus one extension: a segment that is exactly {@code *} selects every element of an
 * array or every member value of an object ({@code /protoPayload/authorizationInfo/*}{@code /granted}).
 *
 * <p>Mapping YAMLs and binding {@code field-overrides} use this syntax. A path never throws on a
 * record: a missing step selects nothing.
 *
 * @param pointer the path text; {@code ""} is the whole record
 */
public record FieldPath(String pointer) {

    /** The wildcard segment. */
    public static final String WILDCARD = "*";

    /** RFC 6901 syntax: empty, or {@code /}-prefixed segments where {@code ~} is only {@code ~0} or {@code ~1}. */
    public static final Pattern SYNTAX = Pattern.compile("(/([^~/]|~[01])*)*");

    /** Longest accepted path text. */
    public static final int MAX_LENGTH = 512;

    private static final Pattern DIGITS = Pattern.compile("0|[1-9][0-9]*");

    /**
     * Validates the syntax.
     *
     * @param pointer see the record description
     */
    public FieldPath {
        Objects.requireNonNull(pointer, "pointer");
        if (!isValid(pointer)) {
            throw new IllegalArgumentException("not a JSON pointer: must be empty or start with '/', and '~' must be"
                    + " written ~0 or ~1");
        }
    }

    /**
     * Parses a path.
     *
     * @param pointer the text
     * @return the path
     */
    public static FieldPath of(String pointer) {
        return new FieldPath(pointer);
    }

    /**
     * Whether a string is a valid path.
     *
     * @param pointer the candidate; may be {@code null}
     * @return true when {@link #of} accepts it
     */
    public static boolean isValid(String pointer) {
        return pointer != null && pointer.length() <= MAX_LENGTH && SYNTAX.matcher(pointer).matches();
    }

    /**
     * Returns the unescaped segments.
     *
     * @return the segments; empty for the whole record
     */
    public List<String> segments() {
        if (pointer.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            out.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Whether the path has a wildcard segment.
     *
     * @return true when some segment is {@code *}
     */
    public boolean hasWildcard() {
        return segments().contains(WILDCARD);
    }

    /**
     * Selects every node the path reaches, in document order. JSON {@code null} values are
     * included; missing steps select nothing.
     *
     * @param root the record
     * @return the selected nodes
     */
    public List<JsonNode> select(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root != null) {
            walk(root, segments(), 0, out);
        }
        return out;
    }

    /**
     * Returns the first selected node that holds a value: not JSON {@code null} and, for text, not
     * blank.
     *
     * @param root the record
     * @return the node, or empty
     */
    public Optional<JsonNode> first(JsonNode root) {
        for (JsonNode n : select(root)) {
            if (hasValue(n)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /**
     * The path as the shape fingerprint names declared paths: array indexes become {@code *}.
     *
     * @return the normalized pointer
     */
    public String fingerprintPath() {
        StringBuilder sb = new StringBuilder();
        for (String s : segments()) {
            sb.append('/').append(DIGITS.matcher(s).matches() ? WILDCARD : escape(s));
        }
        return sb.toString();
    }

    /**
     * Whether a node holds a value: not missing, not JSON {@code null}, and not blank text.
     *
     * @param node the node; may be {@code null}
     * @return true when the node holds a value
     */
    public static boolean hasValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        return !node.isTextual() || !node.asText().isBlank();
    }

    /**
     * Escapes one segment for pointer text.
     *
     * @param segment the raw key
     * @return the escaped segment
     */
    public static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static void walk(JsonNode node, List<String> segments, int at, List<JsonNode> out) {
        if (at == segments.size()) {
            out.add(node);
            return;
        }
        String s = segments.get(at);
        if (WILDCARD.equals(s)) {
            if (node.isArray() || node.isObject()) {
                for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    walk(it.next(), segments, at + 1, out);
                }
            }
            return;
        }
        JsonNode next = null;
        if (node.isObject()) {
            next = node.get(s);
        } else if (node.isArray() && DIGITS.matcher(s).matches() && s.length() < 10) {
            next = node.get(Integer.parseInt(s));
        }
        if (next != null) {
            walk(next, segments, at + 1, out);
        }
    }

    @Override
    public String toString() {
        return pointer;
    }
}
