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

import com.dlap.mcppep.Obligation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies ALLOW-with-obligations verdicts to data before a PEP returns it
 * (docs/specs/enforcement-contract.md §3). Pure and reusable - no Spring, no
 * I/O, no shared mutable state - so the reference PEP and any AuthZEN gateway
 * can honor obligations identically.
 *
 * <p>Supported v1 obligation types:
 * <ul>
 *   <li>{@code mask} - for every field whose labels intersect the obligation's
 *       labels, replace each value with the mask token ({@code "***"} by
 *       default). {@code null} values stay {@code null}. Non-matching fields
 *       are untouched.</li>
 *   <li>{@code redact_rows} - drop every row that has a non-null value in any
 *       field whose labels intersect the obligation's labels (the row "matches
 *       the label predicate").</li>
 *   <li>{@code log_only} - no transformation; recorded as observed (advisory).</li>
 * </ul>
 *
 * <p><strong>Fail-closed rule.</strong> An obligation type this applier does
 * not recognize cannot be honored, so per §3 the whole application fails
 * closed: {@link #apply} returns {@link ApplicationResult#failClosed(String)}
 * and the caller MUST deny/withhold the data. Unknown obligations are never
 * silently passed through.
 *
 * <p>All obligations are applied in sequence over one working copy of the
 * dataset (mask and redact are order-independent for these types, but all are
 * applied). The input dataset is never mutated.
 */
public class ObligationApplier {

    /** The default token substituted for masked values. */
    public static final String DEFAULT_MASK_TOKEN = "***";

    static final String TYPE_MASK = "mask";
    static final String TYPE_REDACT_ROWS = "redact_rows";
    static final String TYPE_LOG_ONLY = "log_only";

    private final String maskToken;

    /** Creates an applier using the default mask token ({@code "***"}). */
    public ObligationApplier() {
        this(DEFAULT_MASK_TOKEN);
    }

    /**
     * Creates an applier with a custom mask token.
     *
     * @param maskToken the token substituted for masked non-null values
     *                  (must not be null)
     */
    public ObligationApplier(String maskToken) {
        if (maskToken == null) {
            throw new IllegalArgumentException("maskToken must not be null");
        }
        this.maskToken = maskToken;
    }

    /**
     * Applies the obligations to the dataset, honoring the fail-closed rule.
     *
     * @param obligations the obligations to honor (null treated as empty)
     * @param dataset     the data to transform (must not be null)
     * @return a satisfied result with the transformed data, or a fail-closed
     *         result naming the first unrecognized obligation type
     */
    public ApplicationResult apply(List<Obligation> obligations, LabeledDataset dataset) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset must not be null");
        }
        List<Obligation> toApply = (obligations == null) ? List.of() : obligations;

        // Fail-closed pre-check: if ANY obligation type is unknown, honor none.
        for (Obligation o : toApply) {
            if (!isKnown(o.type())) {
                return ApplicationResult.failClosed(o.type());
            }
        }

        LabeledDataset working = dataset;
        List<String> applied = new ArrayList<>(toApply.size());
        for (Obligation o : toApply) {
            switch (o.type()) {
                case TYPE_MASK -> working = mask(working, o.labels());
                case TYPE_REDACT_ROWS -> working = redactRows(working, o.labels());
                case TYPE_LOG_ONLY -> { /* advisory: no transformation, only recorded */ }
                default -> throw new IllegalStateException("unreachable: unknown type " + o.type());
            }
            applied.add(o.type());
        }
        return ApplicationResult.ok(working, applied);
    }

    private boolean isKnown(String type) {
        return TYPE_MASK.equals(type)
                || TYPE_REDACT_ROWS.equals(type)
                || TYPE_LOG_ONLY.equals(type);
    }

    /** Names of fields whose labels intersect the obligation's labels. */
    private Set<String> matchingFields(LabeledDataset dataset, List<String> obligationLabels) {
        Set<String> obLabels = new HashSet<>(obligationLabels);
        Set<String> matched = new HashSet<>();
        for (LabeledField f : dataset.schema()) {
            for (String label : f.labels()) {
                if (obLabels.contains(label)) {
                    matched.add(f.name());
                    break;
                }
            }
        }
        return matched;
    }

    private LabeledDataset mask(LabeledDataset dataset, List<String> obligationLabels) {
        Set<String> targets = matchingFields(dataset, obligationLabels);
        if (targets.isEmpty()) {
            return dataset;
        }
        List<Map<String, Object>> out = new ArrayList<>(dataset.rows().size());
        for (Map<String, Object> row : dataset.rows()) {
            Map<String, Object> copy = LabeledDataset.mutableCopy(row);
            for (String field : targets) {
                if (copy.containsKey(field) && copy.get(field) != null) {
                    copy.put(field, maskToken);
                }
            }
            out.add(copy);
        }
        return new LabeledDataset(dataset.schema(), out);
    }

    private LabeledDataset redactRows(LabeledDataset dataset, List<String> obligationLabels) {
        Set<String> targets = matchingFields(dataset, obligationLabels);
        if (targets.isEmpty()) {
            return dataset;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : dataset.rows()) {
            if (!rowMatches(row, targets)) {
                kept.add(row);
            }
        }
        return new LabeledDataset(dataset.schema(), kept);
    }

    /** A row matches when it holds a non-null value in any target field. */
    private boolean rowMatches(Map<String, Object> row, Set<String> targets) {
        for (String field : targets) {
            if (row.get(field) != null) {
                return true;
            }
        }
        return false;
    }
}
