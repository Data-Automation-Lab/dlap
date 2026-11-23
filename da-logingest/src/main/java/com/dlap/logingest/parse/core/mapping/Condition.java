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
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * One test on a record, as written in a mapping YAML:
 * {@code {pointer: /errorCode, present: true}}, {@code {pointer: /status/code, not-equals: 0}}.
 *
 * <p>With a wildcard path the test holds when any selected value satisfies it ({@code ABSENT}: when
 * none holds a value). Values compare by JSON type: numbers numerically ({@code 0} equals
 * {@code 0.0}), strings and booleans exactly; a string never equals a number. A list of conditions
 * in YAML means all of them, except {@code result.failure-when} and {@code result.pending-when},
 * which mean any of them.
 *
 * @param path    where to look
 * @param op      the test
 * @param operand the scalar compared against, a JSON array of scalars for {@link Op#IN}, or
 *                {@code null} for {@link Op#PRESENT} and {@link Op#ABSENT}
 */
public record Condition(FieldPath path, Op op, JsonNode operand) {

    /** The tests. */
    public enum Op {
        /** Some selected value is present: not null, not blank text ({@code present: true}). */
        PRESENT,
        /** No selected value is present ({@code present: false}). */
        ABSENT,
        /** Some selected value equals the operand ({@code equals}). */
        EQUALS,
        /** Some selected value is present and differs from the operand ({@code not-equals}). */
        NOT_EQUALS,
        /** Some selected value equals one of the operands ({@code in}). */
        IN
    }

    /**
     * Validates the condition.
     *
     * @param path    see the record description
     * @param op      see the record description
     * @param operand see the record description
     */
    public Condition {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(op, "op");
        switch (op) {
            case PRESENT, ABSENT -> {
                if (operand != null) {
                    throw new IllegalArgumentException(op + " takes no operand");
                }
            }
            case EQUALS, NOT_EQUALS -> {
                if (operand == null || !operand.isValueNode() || operand.isNull()) {
                    throw new IllegalArgumentException(op + " needs a scalar operand");
                }
            }
            case IN -> {
                if (operand == null || !operand.isArray() || operand.isEmpty()) {
                    throw new IllegalArgumentException("IN needs a non-empty list of scalars");
                }
                operand.forEach(v -> {
                    if (!v.isValueNode() || v.isNull()) {
                        throw new IllegalArgumentException("IN needs a non-empty list of scalars");
                    }
                });
            }
            default -> throw new IllegalStateException("unhandled op " + op);
        }
        operand = operand == null ? null : operand.deepCopy();
    }

    /**
     * Applies the test.
     *
     * @param record the record body
     * @return true when the record satisfies it
     */
    public boolean test(JsonNode record) {
        List<JsonNode> values = path.select(record);
        return switch (op) {
            case PRESENT -> values.stream().anyMatch(FieldPath::hasValue);
            case ABSENT -> values.stream().noneMatch(FieldPath::hasValue);
            case EQUALS -> values.stream().anyMatch(v -> same(v, operand));
            case NOT_EQUALS -> values.stream().anyMatch(v -> FieldPath.hasValue(v) && !same(v, operand));
            case IN -> values.stream().anyMatch(v -> {
                for (JsonNode candidate : operand) {
                    if (same(v, candidate)) {
                        return true;
                    }
                }
                return false;
            });
        };
    }

    /**
     * Whether every condition holds.
     *
     * @param all    the conditions
     * @param record the record
     * @return true for an empty list
     */
    public static boolean allMatch(List<Condition> all, JsonNode record) {
        return all.stream().allMatch(c -> c.test(record));
    }

    /**
     * Whether some condition holds.
     *
     * @param any    the conditions
     * @param record the record
     * @return false for an empty list
     */
    public static boolean anyMatch(List<Condition> any, JsonNode record) {
        return any.stream().anyMatch(c -> c.test(record));
    }

    private static boolean same(JsonNode value, JsonNode operand) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isNumber() && operand.isNumber()) {
            return value.decimalValue().compareTo(operand.decimalValue()) == 0;
        }
        if (value.isTextual() && operand.isTextual()) {
            return value.textValue().equals(operand.textValue());
        }
        if (value.isBoolean() && operand.isBoolean()) {
            return value.booleanValue() == operand.booleanValue();
        }
        return false;
    }
}
