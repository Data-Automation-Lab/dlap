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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * POL-01: the Kleene conjunction is tested over its whole truth table, so no cell can change
 * without a failing test. FALSE dominates, then UNKNOWN, then TRUE, in either operand order.
 */
class MatchOutcomeTest {

    @ParameterizedTest(name = "{0} AND {1} = {2}")
    @CsvSource({
            "TRUE,    TRUE,    TRUE",
            "TRUE,    FALSE,   FALSE",
            "TRUE,    UNKNOWN, UNKNOWN",
            "FALSE,   TRUE,    FALSE",
            "FALSE,   FALSE,   FALSE",
            "FALSE,   UNKNOWN, FALSE",
            "UNKNOWN, TRUE,    UNKNOWN",
            "UNKNOWN, FALSE,   FALSE",
            "UNKNOWN, UNKNOWN, UNKNOWN"
    })
    void theConjunctionFollowsTheKleeneTruthTable(MatchOutcome left, MatchOutcome right, MatchOutcome expected) {
        assertThat(left.and(right)).isEqualTo(expected);
    }

    @Test
    void theTruthTableCoversEveryPair() {
        // Guards the CsvSource above: a fourth constant would need new rows.
        assertThat(MatchOutcome.values()).containsExactly(MatchOutcome.TRUE, MatchOutcome.FALSE, MatchOutcome.UNKNOWN);
    }

    @Test
    void aTwoValuedResultLiftsToTrueOrFalseNeverUnknown() {
        assertThat(MatchOutcome.of(true)).isEqualTo(MatchOutcome.TRUE);
        assertThat(MatchOutcome.of(false)).isEqualTo(MatchOutcome.FALSE);
    }
}
