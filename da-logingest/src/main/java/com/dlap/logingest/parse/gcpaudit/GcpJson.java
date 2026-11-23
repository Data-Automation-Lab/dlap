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

package com.dlap.logingest.parse.gcpaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Total accessors over untrusted JSON: every method answers for any input, including the wrong
 * node type, and never throws. Parsers are total (log-parser contract section 13).
 */
final class GcpJson {

    private GcpJson() {
    }

    /**
     * Walks object keys.
     *
     * @param node the start
     * @param keys the keys
     * @return the node reached, or a missing node
     */
    static JsonNode at(JsonNode node, String... keys) {
        JsonNode n = node == null ? MissingNode.getInstance() : node;
        for (String k : keys) {
            if (!n.isObject()) {
                return MissingNode.getInstance();
            }
            n = n.path(k);
        }
        return n;
    }

    /**
     * Non-blank text at a path; numbers and booleans as their text.
     *
     * @param node the start
     * @param keys the keys
     * @return the text, or empty
     */
    static Optional<String> text(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        if (n.isTextual()) {
            String s = n.textValue();
            return s.isBlank() ? Optional.empty() : Optional.of(s);
        }
        if (n.isNumber() || n.isBoolean()) {
            return Optional.of(n.asText());
        }
        return Optional.empty();
    }

    /**
     * Text at a path, or {@code ""}.
     *
     * @param node the start
     * @param keys the keys
     * @return the text
     */
    static String textOr(JsonNode node, String... keys) {
        return text(node, keys).orElse("");
    }

    /**
     * An object at a path.
     *
     * @param node the start
     * @param keys the keys
     * @return the object, or empty
     */
    static Optional<JsonNode> object(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        return n.isObject() ? Optional.of(n) : Optional.empty();
    }

    /**
     * The elements of an array at a path.
     *
     * @param node the start
     * @param keys the keys
     * @return the elements; empty when absent or not an array
     */
    static List<JsonNode> array(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        List<JsonNode> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(out::add);
        }
        return out;
    }

    /**
     * Whether a boolean at a path is true; {@code "true"} text counts.
     *
     * @param node the start
     * @param keys the keys
     * @return true only for a true value
     */
    static boolean isTrue(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        return n.isBoolean() ? n.booleanValue() : n.isTextual() && "true".equalsIgnoreCase(n.textValue().strip());
    }

    /**
     * Whether a boolean at a path is explicitly false; {@code "false"} text counts.
     *
     * @param node the start
     * @param keys the keys
     * @return true only for a false value
     */
    static boolean isFalse(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        return n.isBoolean() ? !n.booleanValue() : n.isTextual() && "false".equalsIgnoreCase(n.textValue().strip());
    }

    /**
     * An integral number at a path, from a JSON number or a decimal string (proto3 JSON writes
     * int64 as a string).
     *
     * @param node the start
     * @param keys the keys
     * @return the value, or empty
     */
    static Optional<Long> integer(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            return Optional.of(n.longValue());
        }
        if (n.isNumber()) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) {
                return Optional.of((long) d);
            }
            return Optional.empty();
        }
        if (n.isTextual()) {
            String s = n.textValue().strip();
            if (s.matches("-?\\d{1,18}")) {
                return Optional.of(Long.parseLong(s));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a value at a path holds anything: not missing, not null, not blank text, not an
     * empty object or array.
     *
     * @param node the start
     * @param keys the keys
     * @return true when present
     */
    static boolean present(JsonNode node, String... keys) {
        JsonNode n = at(node, keys);
        if (n.isMissingNode() || n.isNull()) {
            return false;
        }
        if (n.isTextual()) {
            return !n.textValue().isBlank();
        }
        if (n.isContainerNode()) {
            return !n.isEmpty();
        }
        return true;
    }
}
