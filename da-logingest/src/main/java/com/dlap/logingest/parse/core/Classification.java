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

package com.dlap.logingest.parse.core;

import com.dlap.common.assetid.AssetKind;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The classification of one event: change type, category and, from an explicit row, the target's
 * asset kind and extractor. {@link #heuristic()} is true when no row matched and the shared
 * {@link VerbHeuristics} decided; such events carry {@code PARSE_WARNING_HEURISTIC_CLASSIFICATION}.
 *
 * @param changeType the routing change type
 * @param category   the event category
 * @param assetKind  the target's kind from the matching row, if any
 * @param extractor  the family resource extractor from the matching row, if any
 * @param heuristic  true when decided by verb heuristics
 */
public record Classification(
        ChangeType changeType,
        EventCategory category,
        Optional<AssetKind> assetKind,
        Optional<String> extractor,
        boolean heuristic) {

    private static final Comparator<ClassificationRule> MOST_SPECIFIC_FIRST =
            Comparator.comparingInt(ClassificationRule::specificity).reversed();

    /**
     * Validates the parts.
     *
     * @param changeType see the record description
     * @param category   see the record description
     * @param assetKind  see the record description
     * @param extractor  see the record description
     * @param heuristic  see the record description
     */
    public Classification {
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(category, "category");
        assetKind = assetKind == null ? Optional.empty() : assetKind;
        extractor = extractor == null ? Optional.empty() : extractor;
    }

    /**
     * Classifies an event: the most specific binding override wins, then the most specific mapping
     * row, then the verb heuristics.
     *
     * @param overrides the binding's {@code operation-overrides}; may be empty
     * @param rules     the mapping's {@code classification.rules}; may be empty
     * @param service   the event's service
     * @param operation the event's operation
     * @return the classification
     */
    public static Classification classify(List<ClassificationRule> overrides, List<ClassificationRule> rules,
                                          String service, String operation) {
        Optional<ClassificationRule> hit = best(overrides, service, operation).or(() -> best(rules, service, operation));
        return hit.map(Classification::of).orElseGet(() -> VerbHeuristics.classify(operation));
    }

    /**
     * The classification an explicit row gives.
     *
     * @param rule the row
     * @return the classification, not heuristic
     */
    public static Classification of(ClassificationRule rule) {
        return new Classification(rule.changeType(), rule.category(), rule.assetKindOptional(),
                Optional.ofNullable(rule.extractor()), false);
    }

    private static Optional<ClassificationRule> best(List<ClassificationRule> rows, String service, String operation) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        return rows.stream().filter(r -> r.matches(service, operation)).min(MOST_SPECIFIC_FIRST);
    }
}
