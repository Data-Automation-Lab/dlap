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

package com.dlap.fixtures.gen.scale;

/** Minimal JSON text helpers, so the generators need nothing beyond the JDK. */
final class JsonText {

    private JsonText() {
    }

    /**
     * Quotes and escapes a string as a JSON string literal.
     *
     * @param value the text, not null
     * @return the literal, with the quotes
     */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        append(sb, value);
        return sb.toString();
    }

    /**
     * Appends a quoted, escaped string.
     *
     * @param sb    the target
     * @param value the text
     */
    static void append(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Appends {@code "name":"value"}.
     *
     * @param sb    the target
     * @param name  the member name
     * @param value the member text
     */
    static void member(StringBuilder sb, String name, String value) {
        append(sb, name);
        sb.append(':');
        append(sb, value);
    }
}
