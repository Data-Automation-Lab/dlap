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

package com.dlap.logingest.parse.core.mapping;

import com.dlap.logingest.parse.core.FieldPath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Splits a delivered unit into records according to an envelope's {@code framing}.
 *
 * <p>The unit is read as one JSON value first:
 * <ul>
 *   <li>an array, with {@code json-array} accepted: each element is a record;</li>
 *   <li>an object, with {@code json-object-array} accepted and an array at
 *       {@code records-pointer}: each element is a record;</li>
 *   <li>any other single value, with {@code record} or {@code json-lines} accepted: one record.</li>
 * </ul>
 * When the unit is not one JSON value and {@code json-lines} is accepted, each non-blank line is a
 * record, kept as exact text so a bad line is quarantined alone. Otherwise, with {@code record}
 * accepted, the whole unit is one record (and the registry quarantines it as
 * {@code INVALID_JSON}). Anything else is a container the envelope cannot frame.
 *
 * <p>The unit is held in memory; streaming readers for very large objects belong to transports.
 */
public final class RecordFraming {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private RecordFraming() {
    }

    /**
     * One record of a unit: exact text, or a JSON element split out of a container.
     *
     * @param index zero-based position in the unit: the array index, or the line number for JSON
     *              lines (blank lines count), for {@code RawRecordRef.record_index}
     * @param text  the record text, or {@code null}
     * @param json  the record element, or {@code null}
     */
    public record Piece(int index, String text, JsonNode json) {

        /**
         * Validates the piece.
         *
         * @param index see the record description
         * @param text  see the record description
         * @param json  see the record description
         */
        public Piece {
            if ((text == null) == (json == null)) {
                throw new IllegalArgumentException("a piece is text or json");
            }
        }
    }

    /** A unit the envelope's framing cannot split. */
    public static final class FramingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message what does not fit; never quotes content
         */
        public FramingException(String message) {
            super(message);
        }
    }

    /**
     * Splits a unit.
     *
     * @param unit           the delivered text, decompressed
     * @param accepted       the envelope's framings
     * @param recordsPointer the record array of {@link Framing#JSON_OBJECT_ARRAY}, or {@code null}
     * @return the records, in order; empty for a blank unit
     * @throws FramingException when the unit does not fit any accepted framing
     */
    public static List<Piece> split(String unit, Set<Framing> accepted, FieldPath recordsPointer) {
        Objects.requireNonNull(accepted, "accepted");
        String text = unit == null ? "" : stripBom(unit);
        if (text.isBlank()) {
            return List.of();
        }
        Optional<JsonNode> whole = readWhole(text);
        if (whole.isPresent()) {
            JsonNode node = whole.get();
            if (node.isArray()) {
                if (accepted.contains(Framing.JSON_ARRAY)) {
                    return elements(node);
                }
            } else if (node.isObject() && accepted.contains(Framing.JSON_OBJECT_ARRAY) && recordsPointer != null) {
                Optional<JsonNode> array = recordsPointer.select(node).stream().filter(JsonNode::isArray).findFirst();
                if (array.isPresent()) {
                    return elements(array.get());
                }
            }
            if (accepted.contains(Framing.RECORD) || accepted.contains(Framing.JSON_LINES)) {
                return List.of(new Piece(0, text.strip(), null));
            }
            throw new FramingException("the unit is one JSON value but no accepted framing " + ids(accepted)
                    + " applies to it");
        }
        if (accepted.contains(Framing.JSON_LINES)) {
            List<Piece> out = new ArrayList<>();
            String[] lines = text.split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    out.add(new Piece(i, lines[i].strip(), null));
                }
            }
            return out;
        }
        if (accepted.contains(Framing.RECORD)) {
            return List.of(new Piece(0, text, null));
        }
        throw new FramingException("the unit is not one JSON value and the framing " + ids(accepted)
                + " does not accept JSON lines");
    }

    private static List<Piece> elements(JsonNode array) {
        List<Piece> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(new Piece(i, null, array.get(i)));
        }
        return out;
    }

    private static Optional<JsonNode> readWhole(String text) {
        try {
            return Optional.of(JSON.readTree(text));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    private static List<String> ids(Set<Framing> accepted) {
        return accepted.stream().map(Framing::id).sorted().toList();
    }
}
