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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * POL-01: the evaluator is three-valued. Unknown is never stale or fresh; an unknown value
 * matches nothing in a lifecycle policy and is denied by a deny AccessPolicy; a recorded last
 * read proves staleness for a destructive action only under full read coverage.
 */
class PolicyDslEvaluatorTriStateTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final String OLD_READ = "2026-01-01T00:00:00Z";     // 292 days before NOW
    private static final String RECENT_READ = "2026-10-10T00:00:00Z";  // 10 days before NOW

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));

    private PolicyDsl lifecycle(String action, String match) {
        return parser.parse("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: p}\nspec:\n"
                + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
                + "  match: " + match + "\n"
                + "  action: {type: " + action + "}\n");
    }

    private PolicyDsl access(String effect, String match) {
        return parser.parse("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: a}\nspec:\n"
                + "  subjects: {roles: [DATA_ANALYST]}\n  actions: [read]\n"
                + "  match: " + match + "\n  effect: " + effect + "\n");
    }

    private MatchOutcome outcome(PolicyDsl p, Map<String, Object> snapshot) {
        return evaluator.evaluate(p, snapshot, evaluator.optionsFor(p)).outcome();
    }

    private static Map<String, Object> snap(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    class UnknownValues {

        @Test
        void aMissingFactIsUnknownForEveryClauseKind() {
            Map<String, Object> empty = Map.of();
            assertThat(outcome(lifecycle("alert", "{cloudProvider: GCP}"), empty)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{region: {noneOf: [us]}}"), empty)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{labels: {noneOf: [LEGAL_HOLD]}}"), empty))
                    .isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{sizeBytes: {gte: 1}}"), empty)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{nameMatches: '^prod'}"), empty)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{tagMatches: {env: prod}}"), empty)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{lastAccessedAt: {olderThan: P90D}}"), empty))
                    .isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(outcome(lifecycle("alert", "{createdAt: {olderThan: P90D}}"), empty))
                    .isEqualTo(MatchOutcome.UNKNOWN);
        }

        @Test
        void unknownMatchesNothingInALifecyclePolicy() {
            PolicyDsl p = lifecycle("archive", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(evaluator.matches(p, Map.of("assetId", "a1"))).isFalse();
        }

        @Test
        void aKnownEmptyLabelSetIsNotUnknown() {
            PolicyDsl p = lifecycle("alert", "{labels: {noneOf: [LEGAL_HOLD]}}");
            assertThat(outcome(p, snap("labels", List.of()))).isEqualTo(MatchOutcome.TRUE);
        }

        @Test
        void falseBeatsUnknownInTheConjunction() {
            PolicyDsl p = lifecycle("alert", "{cloudProvider: GCP, lastAccessedAt: {olderThan: P90D}}");
            MatchResult r = evaluator.evaluate(p, snap("cloudProvider", "AWS"), evaluator.optionsFor(p));
            assertThat(r.outcome()).isEqualTo(MatchOutcome.FALSE);
            assertThat(r.unknownClauses()).isEmpty();
        }

        @Test
        void theUnknownClausesAreNamed() {
            PolicyDsl p = lifecycle("alert", "{cloudProvider: GCP, sizeBytes: {gte: 1}, createdAt: {olderThan: P1D}}");
            MatchResult r = evaluator.evaluate(p, snap("cloudProvider", "GCP"), evaluator.optionsFor(p));
            assertThat(r.outcome()).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(r.unknownClauses()).containsExactly("sizeBytes", "createdAt");
        }

        @Test
        void aMalformedClauseFailsClosedEvenWhenTheFactIsMissing() {
            assertThat(outcome(lifecycle("alert", "{lastAccessedAt: {olderThan: 90 days}}"), Map.of()))
                    .isEqualTo(MatchOutcome.FALSE);
            assertThat(outcome(lifecycle("alert", "{lastAccessedAt: {}}"), Map.of())).isEqualTo(MatchOutcome.FALSE);
            assertThat(outcome(lifecycle("alert", "{sizeBytes: 5}"), Map.of())).isEqualTo(MatchOutcome.FALSE);
        }
    }

    @Nested
    class EventTimeAges {

        @Test
        void agesAreMeasuredAgainstTheInjectedClock() {
            PolicyDsl p = lifecycle("alert", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(evaluator.matches(p, snap("lastAccessedAt", OLD_READ))).isTrue();
            assertThat(evaluator.matches(p, snap("lastAccessedAt", RECENT_READ))).isFalse();
            PolicyDslEvaluator earlier = new PolicyDslEvaluator(
                    Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC));
            assertThat(earlier.matches(p, snap("lastAccessedAt", OLD_READ))).isFalse();
        }

        @Test
        void createdAtReadsTheProviderCreationTimeOnly() {
            PolicyDsl old = lifecycle("alert", "{createdAt: {olderThan: P365D}}");
            assertThat(outcome(old, snap("cloudCreatedAt", "2024-01-01T00:00:00Z"))).isEqualTo(MatchOutcome.TRUE);
            assertThat(outcome(old, snap("cloudCreatedAt", "2026-06-01T00:00:00Z"))).isEqualTo(MatchOutcome.FALSE);
            // The catalog row's insert time and the discovery time are not the provider's creation time.
            assertThat(outcome(old, snap("createdAt", "2024-01-01T00:00:00Z", "discoveredAt", "2024-01-01T00:00:00Z")))
                    .isEqualTo(MatchOutcome.UNKNOWN);
            PolicyDsl young = lifecycle("alert", "{createdAt: {youngerThan: P7D}}");
            assertThat(outcome(young, snap("cloudCreatedAt", "2026-10-18T00:00:00Z"))).isEqualTo(MatchOutcome.TRUE);
        }

        @Test
        void createdAtIsNotSubjectToReadCoverage() {
            PolicyDsl p = lifecycle("delete", "{createdAt: {olderThan: P365D}}");
            assertThat(outcome(p, snap("cloudCreatedAt", "2024-01-01T00:00:00Z", "readCoverage", "NONE")))
                    .isEqualTo(MatchOutcome.TRUE);
        }

        @Test
        void aWindowCombinesBothBounds() {
            PolicyDsl p = lifecycle("alert", "{lastAccessedAt: {olderThan: P7D, youngerThan: P30D}}");
            assertThat(outcome(p, snap("lastAccessedAt", RECENT_READ))).isEqualTo(MatchOutcome.TRUE);
            assertThat(outcome(p, snap("lastAccessedAt", OLD_READ))).isEqualTo(MatchOutcome.FALSE);
        }
    }

    @Nested
    class ReadCoverage {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"PARTIAL", "NONE", "partial"})
        void anIncompleteCoverageMakesStalenessUnknownForADestructiveAction(String coverage) {
            PolicyDsl archive = lifecycle("archive", "{lastAccessedAt: {olderThan: P90D}}");
            Map<String, Object> s = snap("lastAccessedAt", OLD_READ, "readCoverage", coverage);
            assertThat(outcome(archive, s)).isEqualTo(MatchOutcome.UNKNOWN);
            assertThat(evaluator.matches(archive, s)).isFalse();
            assertThat(evaluator.matches(lifecycle("delete", "{lastAccessedAt: {olderThan: P90D}}"), s)).isFalse();
        }

        @Test
        void fullCoverageLetsARecordedReadProveStaleness() {
            PolicyDsl archive = lifecycle("archive", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(evaluator.matches(archive, snap("lastAccessedAt", OLD_READ, "readCoverage", "FULL"))).isTrue();
        }

        @Test
        void aRecentRecordedReadIsDecidedWhateverTheCoverage() {
            PolicyDsl archive = lifecycle("archive", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(outcome(archive, snap("lastAccessedAt", RECENT_READ, "readCoverage", "PARTIAL")))
                    .isEqualTo(MatchOutcome.FALSE);
            PolicyDsl fresh = lifecycle("delete", "{lastAccessedAt: {youngerThan: P30D}}");
            assertThat(outcome(fresh, snap("lastAccessedAt", RECENT_READ, "readCoverage", "NONE")))
                    .isEqualTo(MatchOutcome.TRUE);
            assertThat(outcome(fresh, snap("lastAccessedAt", OLD_READ, "readCoverage", "NONE")))
                    .isEqualTo(MatchOutcome.UNKNOWN);
        }

        @Test
        void aNonDestructiveActionTakesTheRecordedReadAtFaceValue() {
            PolicyDsl label = lifecycle("addLabel", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(evaluator.matches(label, snap("lastAccessedAt", OLD_READ))).isTrue();
        }

        @Test
        void fullCoverageWithoutARecordedReadIsStillUnknown() {
            PolicyDsl archive = lifecycle("archive", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(outcome(archive, snap("readCoverage", "FULL"))).isEqualTo(MatchOutcome.UNKNOWN);
        }

        @Test
        void destructiveTypesAreRecognisedTheWayConsumersMatchThem() {
            assertThat(PolicyDslEvaluator.isDestructive("archive")).isTrue();
            assertThat(PolicyDslEvaluator.isDestructive("AUTO_DELETE")).isTrue();
            assertThat(PolicyDslEvaluator.isDestructive("ARCHIVE")).isTrue();
            assertThat(PolicyDslEvaluator.isDestructive("addLabel")).isFalse();
            assertThat(PolicyDslEvaluator.isDestructive("setRetention")).isFalse();
            assertThat(PolicyDslEvaluator.isDestructive(null)).isFalse();
        }
    }

    @Nested
    class AccessPolicies {

        @Test
        void aDenyIfStalePolicyDeniesUnknownAge() {
            PolicyDsl deny = access("deny", "{lastAccessedAt: {olderThan: P90D}}");
            assertThat(evaluator.matches(deny, Map.of())).isTrue();
            assertThat(evaluator.matches(deny, snap("lastAccessedAt", OLD_READ, "readCoverage", "PARTIAL"))).isTrue();
            assertThat(evaluator.matches(deny, snap("lastAccessedAt", RECENT_READ))).isFalse();
        }

        @Test
        void anAllowPolicyNeverAllowsOnUnknown() {
            PolicyDsl allow = access("allow", "{labels: {anyOf: [EU_RESIDENT]}, lastAccessedAt: {youngerThan: P30D}}");
            assertThat(evaluator.matches(allow, snap("labels", List.of("EU_RESIDENT")))).isFalse();
            assertThat(evaluator.matches(allow, snap("labels", List.of("EU_RESIDENT"), "lastAccessedAt", RECENT_READ)))
                    .isTrue();
        }

        @Test
        void aDenyPolicyOnLabelsDeniesAResourceWithUnknownLabels() {
            PolicyDsl deny = access("deny", "{labels: {allOf: [EU_RESIDENT, PII]}}");
            assertThat(evaluator.matches(deny, snap("assetUri", "gs://b"))).isTrue();
            assertThat(evaluator.matches(deny, snap("labels", List.of("PII")))).isFalse();
        }

        @Test
        void aDisabledDenyPolicyNeverApplies() {
            PolicyDsl deny = parser.parse("apiVersion: dlap/v1\nkind: AccessPolicy\nmetadata: {id: a, enabled: false}\n"
                    + "spec:\n  subjects: {roles: [X]}\n  actions: [read]\n  match: {labels: {anyOf: [PII]}}\n"
                    + "  effect: deny\n");
            assertThat(evaluator.matches(deny, Map.of())).isFalse();
        }
    }

    /**
     * The decision tables of the spec ("Unknown values", "Read coverage and destructive
     * actions"), one row per cell, so a change to any cell fails a named row.
     */
    @Nested
    class DecisionTables {

        private PolicyDsl policyOfKind(String kind) {
            return switch (kind) {
                case "Policy" -> lifecycle("archive", "{cloudProvider: GCP}");
                case "allow" -> access("allow", "{cloudProvider: GCP}");
                case "deny" -> access("deny", "{cloudProvider: GCP}");
                default -> throw new IllegalArgumentException(kind);
            };
        }

        @ParameterizedTest(name = "{0} on {1} applies: {2}")
        @CsvSource({
                "Policy, TRUE,    true",
                "Policy, FALSE,   false",
                "Policy, UNKNOWN, false",
                "allow,  TRUE,    true",
                "allow,  FALSE,   false",
                "allow,  UNKNOWN, false",
                "deny,   TRUE,    true",
                "deny,   FALSE,   false",
                "deny,   UNKNOWN, true"
        })
        void appliesFollowsThePolicyKindAndEffect(String kind, MatchOutcome outcome, boolean expected) {
            assertThat(PolicyDslEvaluator.applies(policyOfKind(kind), new MatchResult(outcome, List.of())))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} on a null result applies: false")
        @ValueSource(strings = {"Policy", "allow", "deny"})
        void aMissingResultNeverApplies(String kind) {
            assertThat(PolicyDslEvaluator.applies(policyOfKind(kind), null)).isFalse();
        }

        @ParameterizedTest(name = "{0} with read {1} under coverage {2} is {3}")
        @CsvSource({
                // olderThan, recorded read older than the bound
                "olderThan,   OLD,    FULL,    TRUE",
                "olderThan,   OLD,    PARTIAL, UNKNOWN",
                "olderThan,   OLD,    NONE,    UNKNOWN",
                "olderThan,   OLD,    ,        UNKNOWN",
                // olderThan, recorded read within the bound
                "olderThan,   RECENT, FULL,    FALSE",
                "olderThan,   RECENT, PARTIAL, FALSE",
                "olderThan,   RECENT, NONE,    FALSE",
                "olderThan,   RECENT, ,        FALSE",
                // youngerThan, recorded read within the bound
                "youngerThan, RECENT, FULL,    TRUE",
                "youngerThan, RECENT, PARTIAL, TRUE",
                "youngerThan, RECENT, NONE,    TRUE",
                "youngerThan, RECENT, ,        TRUE",
                // youngerThan, recorded read older than the bound
                "youngerThan, OLD,    FULL,    FALSE",
                "youngerThan, OLD,    PARTIAL, UNKNOWN",
                "youngerThan, OLD,    NONE,    UNKNOWN",
                "youngerThan, OLD,    ,        UNKNOWN",
                // no recorded read
                "olderThan,   ABSENT, FULL,    UNKNOWN",
                "olderThan,   ABSENT, PARTIAL, UNKNOWN",
                "olderThan,   ABSENT, NONE,    UNKNOWN",
                "olderThan,   ABSENT, ,        UNKNOWN",
                "youngerThan, ABSENT, FULL,    UNKNOWN",
                "youngerThan, ABSENT, PARTIAL, UNKNOWN",
                "youngerThan, ABSENT, NONE,    UNKNOWN",
                "youngerThan, ABSENT, ,        UNKNOWN"
        })
        void theReadCoverageTableHoldsForEveryPolicyThatNeedsFullCoverage(
                String bound, String read, String coverage, MatchOutcome expected) {
            String duration = "olderThan".equals(bound) ? "P90D" : "P30D";
            String match = "{lastAccessedAt: {" + bound + ": " + duration + "}}";
            Map<String, Object> s = snap("readCoverage", coverage);
            switch (read) {
                case "OLD" -> s.put("lastAccessedAt", OLD_READ);
                case "RECENT" -> s.put("lastAccessedAt", RECENT_READ);
                case "ABSENT" -> { }
                default -> throw new IllegalArgumentException(read);
            }
            assertThat(outcome(lifecycle("archive", match), s)).as("archive").isEqualTo(expected);
            assertThat(outcome(lifecycle("delete", match), s)).as("delete").isEqualTo(expected);
            assertThat(outcome(access("deny", match), s)).as("deny AccessPolicy").isEqualTo(expected);
            assertThat(outcome(access("allow", match), s)).as("allow AccessPolicy").isEqualTo(expected);
        }

        @Test
        void anUnknownClauseBeforeAFalseClauseIsFalseAndADenyDoesNotApply() {
            String match = "{lastAccessedAt: {olderThan: P90D}, labels: {anyOf: [PII]}}";
            PolicyDsl deny = access("deny", match);
            // The clause order is the YAML order, so the unknown clause really is evaluated first.
            assertThat(deny.getSpec().getMatch().keySet()).containsExactly("lastAccessedAt", "labels");
            Map<String, Object> nonPiiNeverRead = snap("labels", List.of("PUBLIC"));

            MatchResult r = evaluator.evaluate(deny, nonPiiNeverRead, evaluator.optionsFor(deny));
            assertThat(r.outcome()).isEqualTo(MatchOutcome.FALSE);
            assertThat(r.unknownClauses()).isEmpty();
            assertThat(PolicyDslEvaluator.applies(deny, r)).isFalse();
            assertThat(evaluator.matches(deny, nonPiiNeverRead)).isFalse();
            assertThat(evaluator.matches(lifecycle("archive", match), nonPiiNeverRead)).isFalse();
        }

        @Test
        void aTrueThenUnknownThenFalseBlockIsFalse() {
            PolicyDsl p = lifecycle("alert", "{cloudProvider: GCP, sizeBytes: {gte: 1}, region: {anyOf: [us]}}");
            assertThat(p.getSpec().getMatch().keySet()).containsExactly("cloudProvider", "sizeBytes", "region");
            assertThat(outcome(p, snap("cloudProvider", "GCP", "region", "eu"))).isEqualTo(MatchOutcome.FALSE);
        }
    }
}
