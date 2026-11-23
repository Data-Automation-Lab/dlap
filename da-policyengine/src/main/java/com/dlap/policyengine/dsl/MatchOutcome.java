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

package com.dlap.policyengine.dsl;

/**
 * Three-valued outcome of a match clause or a whole match block (Kleene logic).
 *
 * <p>{@link #UNKNOWN} means the snapshot does not carry the fact the clause needs, or carries it
 * with too little evidence to decide (ADR-0046: unknown is never stale or fresh). A conjunction is
 * {@link #FALSE} as soon as one clause is false, {@link #UNKNOWN} when no clause is false and at
 * least one is unknown, and {@link #TRUE} only when every clause holds.
 *
 * <p>How a caller treats {@link #UNKNOWN} is fixed by the spec ({@code docs/specs/policy-dsl.md},
 * "Unknown values"): a lifecycle policy fires only on {@link #TRUE}; an AccessPolicy with
 * {@code effect: deny} applies on {@link #TRUE} or {@link #UNKNOWN}, one with {@code effect: allow}
 * only on {@link #TRUE}.
 */
public enum MatchOutcome {

    /** Every clause holds. */
    TRUE,

    /** At least one clause does not hold. */
    FALSE,

    /** No clause is false, and at least one cannot be decided from the snapshot. */
    UNKNOWN;

    /**
     * Conjunction of two outcomes.
     *
     * @param other the other outcome
     * @return {@link #FALSE} if either is false, else {@link #UNKNOWN} if either is unknown, else {@link #TRUE}
     */
    public MatchOutcome and(MatchOutcome other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        if (this == UNKNOWN || other == UNKNOWN) {
            return UNKNOWN;
        }
        return TRUE;
    }

    /**
     * Lifts a two-valued test.
     *
     * @param value the known result
     * @return {@link #TRUE} or {@link #FALSE}
     */
    public static MatchOutcome of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
