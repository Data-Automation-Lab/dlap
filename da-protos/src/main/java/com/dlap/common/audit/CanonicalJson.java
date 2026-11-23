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

package com.dlap.common.audit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical JSON form of audit records and checkpoints (docs/specs/audit-log.md, section
 * "Canonical JSON").
 *
 * <p>One value has exactly one text: object members sorted by key (plain {@link String} order;
 * keys are ASCII), no insignificant white space, integers in plain decimal, and strings with
 * every character outside printable ASCII (U+0020 to U+007E) written as a {@code \}{@code uXXXX}
 * escape of its UTF-16 code unit, except the short escapes {@code \b \f \n \r \t}. The output is
 * therefore printable ASCII, so its bytes are the same in every encoding a client may use.
 *
 * <p>Allowed values: {@link Map} with {@link String} keys, {@link List}, {@link String},
 * {@link Boolean}, {@link Integer}, {@link Long}, {@link Short}, {@link Byte}, a {@link BigInteger}
 * in the {@code long} range, and {@code null}. Floating-point numbers are refused: their
 * shortest decimal form is not portable between languages, and no audit field needs one.
 */
public final class CanonicalJson {

    /** Maximum nesting of objects and arrays. */
    public static final int MAX_DEPTH = 16;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() {
    }

    /**
     * Writes a value in canonical form.
     *
     * @param value the value; see the class comment for the allowed types
     * @return the canonical text, printable ASCII only
     * @throws IllegalArgumentException for a type that has no canonical form, a non-string map
     *                                  key, or nesting deeper than {@link #MAX_DEPTH}
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting deeper than " + MAX_DEPTH);
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            out.append(((Number) value).longValue());
        } else if (value instanceof BigInteger big) {
            if (big.bitLength() > 63) {
                throw new IllegalArgumentException("integer outside the long range");
            }
            out.append(big.longValue());
        } else if (value instanceof Map<?, ?> map) {
            object(out, map, depth);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i), depth + 1);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("no canonical JSON form for " + value.getClass().getName());
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map, int depth) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            sorted.put(key, e.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, e.getKey());
            out.append(':');
            write(out, e.getValue(), depth + 1);
        }
        out.append('}');
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c >= 0x20 && c <= 0x7e) {
                        out.append(c);
                    } else {
                        out.append("\\u").append(HEX[(c >> 12) & 0xf]).append(HEX[(c >> 8) & 0xf])
                                .append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Whether a text consists of printable ASCII only (U+0020 to U+007E), as every canonical
     * text does. The {@code dlap_common.audit_log} table enforces the same rule.
     *
     * @param text the text; may be {@code null}
     * @return true when {@code text} is non-empty printable ASCII
     */
    public static boolean isPrintableAscii(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies a list so that later changes to the source cannot change a value already validated.
     *
     * @param list the source
     * @return an unmodifiable copy whose nested maps and lists are copied too
     */
    static List<Object> copyList(List<?> list) {
        List<Object> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            copy.add(copyValue(item));
        }
        return java.util.Collections.unmodifiableList(copy);
    }

    /**
     * Copies a map in key order, deeply.
     *
     * @param map the source
     * @return an unmodifiable sorted copy
     */
    static Map<String, Object> copyMap(Map<String, ?> map) {
        TreeMap<String, Object> copy = new TreeMap<>();
        for (Map.Entry<String, ?> e : map.entrySet()) {
            copy.put(e.getKey(), copyValue(e.getValue()));
        }
        return java.util.Collections.unmodifiableSortedMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap((Map<String, ?>) map);
        }
        if (value instanceof List<?> list) {
            return copyList(list);
        }
        return value;
    }
}
