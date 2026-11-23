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

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * ADR-0031 safety guarantees for the evaluator: an unimplemented or unknown
 * match clause must NEVER widen a policy to every asset - it fails closed.
 * nameMatches actually filters by a regex on the asset name.
 */
class PolicyDslEvaluatorTest {

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator();

    private PolicyDsl policy(String matchBlock) {
        return parser.parse(""
                + "apiVersion: dlap/v1\n"
                + "kind: Policy\n"
                + "metadata: {id: t, name: t}\n"
                + "spec:\n"
                + "  triggers: [{type: event, lifecycle: created}]\n"
                + "  " + matchBlock + "\n"
                + "  action: {type: alert.email}\n");
    }

    @Test
    void nameMatchesFiltersByRegexAndFailsClosedWithoutAName() {
        PolicyDsl p = policy("match: {nameMatches: '^prod-.*'}");
        assertThat(evaluator.matches(p, Map.of("assetName", "prod-db"))).isTrue();
        assertThat(evaluator.matches(p, Map.of("assetName", "dev-db"))).isFalse();
        // No name in the snapshot -> cannot confirm the constraint -> no match.
        assertThat(evaluator.matches(p, Map.of("assetType", "GCS_BUCKET"))).isFalse();
    }

    @Test
    void tagMatches_matchesCloudNativeTags() {
        var p = policy("match: {tagMatches: {env: prod}}");
        // Asset carries env=prod (among others) -> match.
        assertThat(evaluator.matches(p, Map.of("tags", Map.of("env", "prod", "team", "data")))).isTrue();
        // Wrong tag value -> no match.
        assertThat(evaluator.matches(p, Map.of("tags", Map.of("env", "dev")))).isFalse();
        // No tags on the asset -> fail CLOSED (a scoped clause must never widen to all).
        assertThat(evaluator.matches(p, Map.of("assetName", "anything"))).isFalse();
    }

    @Test
    void unknownClauseFailsClosed() {
        assertThat(evaluator.matches(policy("match: {frobnicate: 42}"),
                Map.of("assetName", "anything"))).isFalse();
    }
}
