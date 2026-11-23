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

package com.dlap.autodelete.nativerule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Helpers shared by the generators: one JSON writer with a stable member order, and the rule
 * name and date rules.
 */
public final class RuleDocuments {

    /** Prefix of every rule name DLAP writes, so an operator can tell DLAP's rules apart. */
    public static final String RULE_PREFIX = "dlap-retire-";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

    private RuleDocuments() {
    }

    /**
     * Writes a document. Maps keep their insertion order.
     *
     * @param document the document
     * @return the JSON text
     */
    public static String write(Map<String, Object> document) {
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write a rule document", e);
        }
    }

    /**
     * Reads a document.
     *
     * @param document the JSON text
     * @return the tree
     */
    public static JsonNode read(String document) {
        try {
            return JSON.readTree(document);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a rule document", e);
        }
    }

    /**
     * Writes one member of a document (the rule itself) as JSON text.
     *
     * @param node the member
     * @return the JSON text
     */
    public static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write a rule member", e);
        }
    }

    /**
     * The rule name of a delete: {@code dlap-retire-} and the key.
     *
     * @param key the soft-delete record id
     * @return the name
     */
    public static String ruleName(UUID key) {
        return RULE_PREFIX + key;
    }

    /**
     * The first UTC midnight after an instant (S3 expiration dates must be midnight UTC; GCS
     * {@code createdBefore} is a UTC date).
     *
     * @param now the instant
     * @return the next midnight
     */
    public static Instant nextUtcMidnight(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
