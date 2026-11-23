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

import com.dlap.common.logevent.LogEventIds;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The structural fingerprint of a record (design B1): which JSON paths are present, never what
 * they hold.
 *
 * <p>Paths are collected to {@link #DEFAULT_MAX_DEPTH} segments; array elements share the segment
 * {@code *}. A present path is <em>known</em> when a declared path equals it, lies above it (the
 * declared path covers its whole subtree, as for {@code /requestParameters}) or lies below it (an
 * intermediate object on the way to a declared leaf). Every other present path is <em>unknown</em>;
 * only the highest unknown path of a subtree is reported. Unknown paths feed
 * {@code PARSE_WARNING_UNKNOWN_FIELDS}, the unknown-fields counter and the drift report, never a
 * metric label.
 *
 * <p>{@link #hash()} is the SHA-256 of the sorted known present paths, so it changes when the
 * record shape changes within what the parser declared, and is independent of key order and
 * values. A binding records each new hash as a format observation (LOG-02c).
 *
 * @param hash         lowercase hex SHA-256 of the known present paths
 * @param knownPaths   present paths covered by the declaration
 * @param unknownPaths highest present paths not covered by the declaration
 */
public record ShapeFingerprint(String hash, SortedSet<String> knownPaths, SortedSet<String> unknownPaths) {

    /** Depth the design fixes: three segments. */
    public static final int DEFAULT_MAX_DEPTH = 3;

    /** Bound on collected paths, so a hostile record cannot exhaust memory. */
    public static final int MAX_PATHS = 4096;

    /**
     * Freezes the sets.
     *
     * @param hash         see the record description
     * @param knownPaths   see the record description
     * @param unknownPaths see the record description
     */
    public ShapeFingerprint {
        knownPaths = Collections.unmodifiableSortedSet(new TreeSet<>(knownPaths));
        unknownPaths = Collections.unmodifiableSortedSet(new TreeSet<>(unknownPaths));
    }

    /**
     * Fingerprints a record at the default depth.
     *
     * @param record   the record body
     * @param declared declared paths in {@link FieldPath} syntax; array indexes count as {@code *}
     * @return the fingerprint
     */
    public static ShapeFingerprint of(JsonNode record, Collection<FieldPath> declared) {
        return of(record, declared, DEFAULT_MAX_DEPTH);
    }

    /**
     * Fingerprints a record.
     *
     * @param record   the record body
     * @param declared declared paths
     * @param maxDepth path depth to collect, at least 1
     * @return the fingerprint
     */
    public static ShapeFingerprint of(JsonNode record, Collection<FieldPath> declared, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1");
        }
        List<List<String>> declaredSegments = new ArrayList<>();
        for (FieldPath p : declared) {
            declaredSegments.add(FieldPath.of(p.fingerprintPath()).segments());
        }
        SortedMap<String, List<String>> present = new TreeMap<>();
        collect(record, new ArrayList<>(), maxDepth, present);
        SortedSet<String> known = new TreeSet<>();
        SortedSet<String> unknown = new TreeSet<>();
        for (Map.Entry<String, List<String>> entry : present.entrySet()) {
            String path = entry.getKey();
            if (isKnown(entry.getValue(), declaredSegments)) {
                known.add(path);
            } else if (unknown.stream().noneMatch(u -> path.startsWith(u + "/"))) {
                unknown.add(path);
            }
        }
        return new ShapeFingerprint(LogEventIds.sha256Hex(String.join("\n", known)), known, unknown);
    }

    /**
     * Whether any present path is undeclared.
     *
     * @return true when {@link #unknownPaths()} is not empty
     */
    public boolean hasUnknownFields() {
        return !unknownPaths.isEmpty();
    }

    /**
     * A short form of the hash for logs and observation rows.
     *
     * @return the first 16 hex characters
     */
    public String shortHash() {
        return hash.substring(0, 16);
    }

    private static boolean isKnown(List<String> present, List<List<String>> declared) {
        for (List<String> d : declared) {
            int n = Math.min(present.size(), d.size());
            boolean prefix = true;
            for (int i = 0; i < n && prefix; i++) {
                prefix = d.get(i).equals(present.get(i));
            }
            if (prefix) {
                return true;
            }
        }
        return false;
    }

    private static void collect(JsonNode node, List<String> at, int maxDepth, SortedMap<String, List<String>> out) {
        if (out.size() >= MAX_PATHS || at.size() >= maxDepth || node == null) {
            return;
        }
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                at.add(e.getKey());
                out.put(render(at), List.copyOf(at));
                collect(e.getValue(), at, maxDepth, out);
                at.remove(at.size() - 1);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                at.add(FieldPath.WILDCARD);
                if (element.isContainerNode()) {
                    collect(element, at, maxDepth, out);
                }
                at.remove(at.size() - 1);
            }
        }
    }

    private static String render(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            sb.append('/').append(FieldPath.WILDCARD.equals(s) ? s : FieldPath.escape(s));
        }
        return sb.toString();
    }
}
