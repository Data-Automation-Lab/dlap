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

package com.dlap.fixtures.anon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Iterator;
import java.util.Map;

/**
 * Reads and writes JSON the same way on every platform, so that a regenerated fixture is byte for byte
 * the committed one. Writing is by hand: two space indent, {@code "key": value}, {@code \n} line ends
 * (Jackson's pretty printer uses the platform line separator), insertion order of fields, numbers
 * exactly as parsed (big decimals and big integers are kept).
 */
final class SeedJson {

    private static final ObjectMapper READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private SeedJson() {
    }

    /**
     * Parses one JSON document strictly: duplicate keys and trailing content are errors.
     *
     * @param text the document
     * @return the tree
     * @throws IllegalArgumentException when the text is not a single valid JSON value
     */
    static JsonNode parse(String text) {
        try {
            return READER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Writes a document with a two space indent and a final line feed.
     *
     * @param node the document
     * @return the text
     */
    static String pretty(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out, 0, true);
        return out.append('\n').toString();
    }

    /**
     * Writes a document on one line, without a line feed.
     *
     * @param node the document
     * @return the text
     */
    static String compact(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out, 0, false);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out, int depth, boolean pretty) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, depth + 1, pretty);
                quote(e.getKey(), out);
                out.append(pretty ? ": " : ":");
                write(e.getValue(), out, depth + 1, pretty);
            }
            newline(out, depth, pretty);
            out.append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (JsonNode v : node) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, depth + 1, pretty);
                write(v, out, depth + 1, pretty);
            }
            newline(out, depth, pretty);
            out.append(']');
        } else if (node.isTextual()) {
            quote(node.asText(), out);
        } else {
            out.append(node.toString());
        }
    }

    private static void newline(StringBuilder out, int depth, boolean pretty) {
        if (pretty) {
            out.append('\n').append("  ".repeat(depth));
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        JsonStringEncoder.getInstance().quoteAsString(s, out);
        out.append('"');
    }
}
