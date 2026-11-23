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

package com.dlap.logingest.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handling of U+0000, which PostgreSQL refuses in a {@code text} column and in a {@code jsonb}
 * string (including its keys, where it arrives as the JSON escape for code point zero). One place for the
 * two policies of the log layer (work package LOG-05, Q-661):
 * <ul>
 *   <li>{@link #strip(String)} for text that is only a human excerpt (a quarantine record's raw
 *       excerpt, detail and references): the character is dropped;</li>
 *   <li>{@link #escapeDeep(Map)} for a JSON document that is kept as evidence ({@code raw_event}):
 *       the character becomes their six-character escape text (backslash, u, four zeros), so the value stays
 *       visible and the document stores.</li>
 * </ul>
 */
public final class NulSafe {

    /** The text that stands for a NUL character in a stored JSON document. */
    public static final String ESCAPED = "\\u0000";

    private NulSafe() {
    }

    /**
     * Drops NUL characters.
     *
     * @param text the text, or {@code null}
     * @return the text without U+0000; the same instance when it has none
     */
    public static String strip(String text) {
        return text == null || text.indexOf('\0') < 0 ? text : text.replace("\0", "");
    }

    /**
     * Replaces NUL characters by their escape text.
     *
     * @param text the text, or {@code null}
     * @return the text with each U+0000 replaced by {@value #ESCAPED}; the same instance when it has none
     */
    public static String escape(String text) {
        return text == null || text.indexOf('\0') < 0 ? text : text.replace("\0", ESCAPED);
    }

    /**
     * Escapes NUL characters in every string and map key of a JSON-shaped value.
     *
     * @param value maps, lists, strings and scalars, as Jackson reads a document
     * @return an equal structure without U+0000; the same instance when it has none
     */
    public static Map<String, Object> escapeDeep(Map<String, Object> value) {
        if (value == null || !hasNul(value)) {
            return value;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) escapeAny(value);
        return out;
    }

    private static boolean hasNul(Object v) {
        if (v instanceof String s) {
            return s.indexOf('\0') >= 0;
        }
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (hasNul(e.getKey()) || hasNul(e.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                if (hasNul(o)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object escapeAny(Object v) {
        if (v instanceof String s) {
            return escape(s);
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                out.put(k instanceof String ks ? escape(ks) : k, escapeAny(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(escapeAny(o));
            }
            return out;
        }
        return v;
    }
}
