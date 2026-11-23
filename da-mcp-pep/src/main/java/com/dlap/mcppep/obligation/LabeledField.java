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

import java.util.List;

/**
 * A column/field in a {@link LabeledDataset} together with the sensitivity
 * labels it carries.
 *
 * <p>Labels are the predicate obligations match against
 * (docs/specs/enforcement-contract.md §3): a {@code mask} or
 * {@code redact_rows} obligation applies to this field when its labels
 * intersect the obligation's labels.
 *
 * @param name   the field/column name (never null)
 * @param labels the sensitivity labels this field carries (never null;
 *               normalized to an immutable list)
 */
public record LabeledField(String name, List<String> labels) {

    /** Normalizes {@code labels} to a non-null immutable list. */
    public LabeledField {
        labels = (labels == null) ? List.of() : List.copyOf(labels);
    }
}
