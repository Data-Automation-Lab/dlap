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

package com.dlap.mcppep.obligation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tabular result set an {@link ObligationApplier} transforms: a shared
 * {@code schema} of {@link LabeledField}s plus the {@code rows}, each an
 * ordered field-name → value map over that schema.
 *
 * <p>This is the neutral shape the PEP hands to obligation enforcement - it
 * knows nothing about the underlying store. Values may be {@code null}
 * (mask preserves nulls; a null does not "match the label predicate" for
 * {@code redact_rows}).
 *
 * @param schema the ordered fields and their labels (never null)
 * @param rows   the data rows, each keyed by field name (never null;
 *               defensively deep-copied to preserve immutability)
 */
public record LabeledDataset(List<LabeledField> schema, List<Map<String, Object>> rows) {

    /** Normalizes {@code schema} and {@code rows} to non-null immutable copies. */
    public LabeledDataset {
        schema = (schema == null) ? List.of() : List.copyOf(schema);
        if (rows == null) {
            rows = List.of();
        } else {
            List<Map<String, Object>> copied = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                // A row may hold null values (mask preserves null; a null does
                // not match the redact_rows predicate). Map.copyOf/Map.of reject
                // nulls, so snapshot into an order-preserving unmodifiable map.
                copied.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
            }
            rows = List.copyOf(copied);
        }
    }

    /**
     * Convenience factory preserving insertion order of a single row's fields.
     * Primarily for tests and callers building datasets programmatically.
     *
     * @param schema the field schema
     * @param rows   the rows (order preserved)
     * @return a new dataset
     */
    public static LabeledDataset of(List<LabeledField> schema, List<Map<String, Object>> rows) {
        return new LabeledDataset(schema, rows);
    }

    /**
     * Returns the labels carried by the given field name, or an empty list
     * if the field is not part of the schema.
     *
     * @param fieldName the field name to look up
     * @return the field's labels (never null)
     */
    public List<String> labelsFor(String fieldName) {
        for (LabeledField f : schema) {
            if (f.name().equals(fieldName)) {
                return f.labels();
            }
        }
        return List.of();
    }

    /**
     * Builds a mutable, insertion-ordered copy of a row for transformation.
     *
     * @param row the source row
     * @return a mutable {@link LinkedHashMap} copy
     */
    static Map<String, Object> mutableCopy(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }
}
