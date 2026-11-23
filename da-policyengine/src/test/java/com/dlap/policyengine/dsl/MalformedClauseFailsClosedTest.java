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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * POL-02, decision D42 on Q-441: a malformed clause in a deny AccessPolicy evaluates UNKNOWN, so
 * the deny fails closed; in every other policy it stays FALSE. The defect test the evaluator
 * applies is the one {@link PolicyDslEvaluator#defects} reports.
 */
class MalformedClauseFailsClosedTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));

    private PolicyDsl access(String effect, String match) {
        return parser.parse("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: a}\nspec:\n"
                + "  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
                + "  match: " + match + "\n  effect: " + effect + "\n");
    }

    private PolicyDsl lifecycle(String action, String match) {
        return parser.parse("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: p}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n"
                + "  match: " + match + "\n  action: {type: " + action + "}\n");
    }

    private MatchResult evaluate(PolicyDsl p, Map<String, Object> snapshot) {
        return evaluator.evaluate(p, snapshot, evaluator.optionsFor(p));
    }

    /** Malformed clauses of every clause kind, each with a snapshot that carries the fact. */
    @ParameterizedTest(name = "deny with {0} is UNKNOWN and applies")
    @ValueSource(strings = {
            "{lastAccessedAt: {olderThan: 90 days}}",
            "{lastAccessedAt: {}}",
            "{lastAccessedAt: {olderthan: P90D}}",
            "{createdAt: P90D}",
            "{sizeBytes: 5}",
            "{sizeBytes: {gte: lots}}",
            "{labels: [PII]}",
            "{labels: {anyof: [PII]}}",
            "{labels: {anyOf: PII}}",
            "{cloudProvider: {anyOf: GCP}}",
            "{cloudProvider: {oneOf: [GCP]}}",
            "{region: 5}",
            "{nameMatches: '^(?=prod).*'}",
            "{tagMatches: {}}",
            "{frobnicate: 42}"
    })
    void aMalformedClauseInADenyAccessPolicyIsUnknownSoTheDenyApplies(String match) {
        PolicyDsl deny = access("deny", match);
        Map<String, Object> knownEverything = Map.of("cloudProvider", "GCP", "region", "us",
                "labels", List.of("PUBLIC"), "lastAccessedAt", "2026-10-30T00:00:00Z", "readCoverage", "FULL",
                "cloudCreatedAt", "2026-10-30T00:00:00Z", "sizeBytes", 10, "assetName", "dev-x",
                "tags", Map.of("env", "dev"));

        MatchResult r = evaluate(deny, knownEverything);

        assertThat(r.outcome()).isEqualTo(MatchOutcome.UNKNOWN);
        assertThat(r.unknownClauses()).hasSize(1);
        assertThat(PolicyDslEvaluator.applies(deny, r)).isTrue();
        assertThat(PolicyDslEvaluator.defects(deny)).hasSize(1);
    }

    @ParameterizedTest(name = "{0} with a malformed clause does not apply")
    @ValueSource(strings = {"allow", "archive", "alert.webhook", "setRetention"})
    void aMalformedClauseStaysFalseInEveryOtherPolicy(String kind) {
        String match = "{lastAccessedAt: {olderThan: 90 days}}";
        PolicyDsl p = "allow".equals(kind) ? access("allow", match) : lifecycle(kind, match);

        MatchResult r = evaluate(p, Map.of("lastAccessedAt", "2020-01-01T00:00:00Z", "readCoverage", "FULL"));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.FALSE);
        assertThat(PolicyDslEvaluator.applies(p, r)).isFalse();
    }

    @Test
    void aFalseClauseStillBeatsTheMalformedOneInADeny() {
        PolicyDsl deny = access("deny", "{labels: {anyOf: [PII]}, lastAccessedAt: {olderThan: soon}}");

        MatchResult r = evaluate(deny, Map.of("labels", List.of("PUBLIC")));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.FALSE);
        assertThat(PolicyDslEvaluator.applies(deny, r)).isFalse();
    }

    @Test
    void aMistypedOperatorNoLongerMatchesEverythingInALifecyclePolicy() {
        // Before POL-02 the unknown operator was ignored and the clause was TRUE for every asset.
        PolicyDsl archive = lifecycle("archive", "{labels: {anyof: [PII]}}");

        assertThat(evaluate(archive, Map.of("labels", List.of("PUBLIC"))).outcome()).isEqualTo(MatchOutcome.FALSE);
    }

    @Test
    void theDefectsNameTheClauseAndTheReason() {
        PolicyDsl p = lifecycle("archive",
                "{cloudProvider: GCP, lastAccessedAt: {olderThan: 90 days}, frobnicate: 1, sizeBytes: {gte: x}}");

        assertThat(PolicyDslEvaluator.defects(p)).containsExactly(
                "lastAccessedAt: olderThan is not an ISO-8601 duration: 90 days",
                "frobnicate: is not a match clause",
                "sizeBytes: gte must be a whole number");
    }

    @Test
    void aWellFormedBlockHasNoDefects() {
        PolicyDsl p = lifecycle("archive", "{cloudProvider: {anyOf: [GCP], noneOf: [AWS]}, region: us,"
                + " labels: {anyOf: [PII], noneOf: [LEGAL_HOLD], allOf: [PII]}, lastAccessedAt: {olderThan: P90D},"
                + " createdAt: {youngerThan: P1D}, sizeBytes: {gte: 1, lte: '9', eq: 5}, nameMatches: '^prod',"
                + " tagMatches: {env: prod}}");

        assertThat(PolicyDslEvaluator.defects(p)).isEmpty();
        assertThat(PolicyDslEvaluator.defects(null)).isEmpty();
    }

    @Test
    void anyOfAndNoneOfOnOneStringClauseAreBothApplied() {
        PolicyDsl p = lifecycle("alert", "{region: {anyOf: [us, eu], noneOf: [eu]}}");

        assertThat(evaluate(p, Map.of("region", "us")).outcome()).isEqualTo(MatchOutcome.TRUE);
        assertThat(evaluate(p, Map.of("region", "eu")).outcome()).isEqualTo(MatchOutcome.FALSE);
    }
}
