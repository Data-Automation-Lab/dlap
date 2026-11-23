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

import java.util.List;

/**
 * Result of evaluating one policy's match block against one asset snapshot.
 *
 * @param outcome         the three-valued outcome of the conjunction
 * @param unknownClauses  the clause names that evaluated {@link MatchOutcome#UNKNOWN}, in match
 *                        order; empty when none did. Recorded in evaluation details so an operator
 *                        sees which fact was missing.
 */
public record MatchResult(MatchOutcome outcome, List<String> unknownClauses) {

    /**
     * Copies the clause list.
     */
    public MatchResult {
        unknownClauses = unknownClauses == null ? List.of() : List.copyOf(unknownClauses);
    }

    /**
     * Whether the match block holds.
     *
     * @return true only for {@link MatchOutcome#TRUE}
     */
    public boolean isTrue() {
        return outcome == MatchOutcome.TRUE;
    }

    /**
     * Whether the match block could not be decided.
     *
     * @return true only for {@link MatchOutcome#UNKNOWN}
     */
    public boolean isUnknown() {
        return outcome == MatchOutcome.UNKNOWN;
    }
}
