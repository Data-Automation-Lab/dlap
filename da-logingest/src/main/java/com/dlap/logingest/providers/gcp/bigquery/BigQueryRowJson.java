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

package com.dlap.logingest.providers.gcp.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.StandardSQLTypeName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Turns a BigQuery result row into the JSON object the parser reads: keyed by column name, nested for
 * records and arrays, timestamps as RFC 3339 text, nulls kept as JSON nulls.
 */
public final class BigQueryRowJson {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private BigQueryRowJson() {
    }

    /**
     * Converts one row.
     *
     * @param fields the result schema
     * @param row    the row, with one value per field in schema order
     * @return the row as a JSON object
     */
    public static ObjectNode toJson(FieldList fields, FieldValueList row) {
        ObjectNode out = JSON.objectNode();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            out.set(field.getName(), value(field, row.get(i)));
        }
        return out;
    }

    private static JsonNode value(Field field, FieldValue v) {
        if (v == null || v.isNull()) {
            return JSON.nullNode();
        }
        return switch (v.getAttribute()) {
            case REPEATED -> {
                ArrayNode array = JSON.arrayNode();
                for (FieldValue element : v.getRepeatedValue()) {
                    array.add(element == null || element.isNull() ? JSON.nullNode() : single(field, element));
                }
                yield array;
            }
            default -> single(field, v);
        };
    }

    private static JsonNode single(Field field, FieldValue v) {
        if (v.getAttribute() == FieldValue.Attribute.RECORD) {
            FieldList sub = field.getSubFields();
            FieldValueList record = v.getRecordValue();
            ObjectNode out = JSON.objectNode();
            for (int i = 0; i < sub.size(); i++) {
                out.set(sub.get(i).getName(), value(sub.get(i), record.get(i)));
            }
            return out;
        }
        StandardSQLTypeName type = field.getType() == null ? StandardSQLTypeName.STRING : field.getType().getStandardType();
        return switch (type) {
            case BOOL -> JSON.booleanNode(v.getBooleanValue());
            case INT64 -> JSON.numberNode(v.getLongValue());
            case FLOAT64 -> JSON.numberNode(v.getDoubleValue());
            case NUMERIC, BIGNUMERIC -> JSON.textNode(new BigDecimal(v.getStringValue()).toPlainString());
            case TIMESTAMP -> JSON.textNode(Instant.EPOCH.plus(v.getTimestampValue(), ChronoUnit.MICROS).toString());
            case BYTES -> JSON.textNode(Base64.getEncoder().encodeToString(v.getBytesValue()));
            default -> JSON.textNode(v.getStringValue());
        };
    }
}
