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

package com.dlap.logingest.providers.gcp.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.StandardSQLTypeName;
import java.time.Instant;
import java.util.Base64;

/**
 * Turns one BigQuery result row into the JSON object of the {@code bq-sink-row} envelope: one member
 * per column, named as the table schema names it; {@code RECORD} columns as objects, {@code REPEATED}
 * columns as arrays, {@code TIMESTAMP} as RFC 3339 text with up to microseconds, {@code INTEGER} and
 * {@code FLOAT} as JSON numbers, {@code BOOLEAN} as a boolean, {@code BYTES} as base64 text, a
 * {@code JSON} column parsed, everything else as text. NULL columns are left out.
 */
final class BqRowJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();

    private BqRowJson() {
    }

    /**
     * Converts a row.
     *
     * @param fields the result schema
     * @param row    the row
     * @return the JSON object
     */
    static ObjectNode row(FieldList fields, FieldValueList row) {
        ObjectNode out = NODES.objectNode();
        for (int i = 0; i < fields.size() && i < row.size(); i++) {
            Field f = fields.get(i);
            JsonNode v = value(f, row.get(i));
            if (v != null) {
                out.set(f.getName(), v);
            }
        }
        return out;
    }

    private static JsonNode value(Field field, FieldValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (field.getMode() == Field.Mode.REPEATED || value.getAttribute() == FieldValue.Attribute.REPEATED) {
            ArrayNode a = NODES.arrayNode();
            for (FieldValue element : value.getRepeatedValue()) {
                JsonNode e = single(field, element);
                if (e != null) {
                    a.add(e);
                }
            }
            return a;
        }
        return single(field, value);
    }

    private static JsonNode single(Field field, FieldValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        StandardSQLTypeName type = field.getType().getStandardType();
        switch (type) {
            case STRUCT:
                return row(field.getSubFields(), value.getRecordValue());
            case TIMESTAMP:
                long micros = value.getTimestampValue();
                return NODES.textNode(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                        Math.floorMod(micros, 1_000_000L) * 1_000L).toString());
            case INT64:
                return NODES.numberNode(value.getLongValue());
            case FLOAT64:
                return NODES.numberNode(value.getDoubleValue());
            case BOOL:
                return NODES.booleanNode(value.getBooleanValue());
            case BYTES:
                return NODES.textNode(Base64.getEncoder().encodeToString(value.getBytesValue()));
            case JSON:
                try {
                    return JSON.readTree(value.getStringValue());
                } catch (JsonProcessingException e) {
                    return NODES.textNode(value.getStringValue());
                }
            default:
                return NODES.textNode(value.getStringValue());
        }
    }
}
