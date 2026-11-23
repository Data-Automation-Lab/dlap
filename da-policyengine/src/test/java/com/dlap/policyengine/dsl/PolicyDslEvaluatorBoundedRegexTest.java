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

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.filter.BoundedRegex;

/**
 * SEC-04: the DSL {@code nameMatches} clause runs on RE2/J with a 256-character cap, and
 * fails closed (no match) on a pattern that breaks either rule.
 */
class PolicyDslEvaluatorBoundedRegexTest {

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator();

    private PolicyDsl nameMatches(String regex) {
        return parser.parse(""
                + "apiVersion: dlap/v1\n"
                + "kind: Policy\n"
                + "metadata: {id: t, name: t}\n"
                + "spec:\n"
                + "  triggers: [{type: event, lifecycle: created}]\n"
                + "  match: {nameMatches: '" + regex + "'}\n"
                + "  action: {type: alert.email}\n");
    }

    @Test
    void ordinaryPatternsStillMatch() {
        PolicyDsl p = nameMatches("^prod-[a-z0-9]+$");
        assertThat(evaluator.matches(p, Map.of("assetName", "prod-db1"))).isTrue();
        assertThat(evaluator.matches(p, Map.of("name", "prod-db1"))).isTrue();
        assertThat(evaluator.matches(p, Map.of("assetName", "dev-db1"))).isFalse();
    }

    @Test
    void patternOverTheCapFailsClosed() {
        String tooLong = "a".repeat(BoundedRegex.MAX_PATTERN_LENGTH + 1);
        assertThat(evaluator.matches(nameMatches(tooLong), Map.of("assetName", "a".repeat(400)))).isFalse();
        String atCap = "a".repeat(BoundedRegex.MAX_PATTERN_LENGTH);
        assertThat(evaluator.matches(nameMatches(atCap), Map.of("assetName", "a".repeat(400)))).isTrue();
    }

    @Test
    void lookaroundFailsClosedInsteadOfBeingApproximated() {
        assertThat(evaluator.matches(nameMatches("^(?=prod).*"), Map.of("assetName", "prod-db"))).isFalse();
    }

    @Test
    void catastrophicBacktrackingPatternIsLinear() {
        PolicyDsl p = nameMatches("^(a+)+$");
        long start = System.nanoTime();
        assertThat(evaluator.matches(p, Map.of("assetName", "a".repeat(20_000) + "!"))).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
