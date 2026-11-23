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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.policyengine.approval.ActionClass;
import com.dlap.policyengine.approval.ApprovalGate;

/**
 * POL-02, decision D42 on Q-472: an action that shortens an asset's effective retention
 * ({@code setRetention}) counts as destructive for the read-coverage rule, so a stale read under
 * PARTIAL, NONE or unknown coverage is UNKNOWN for it, as for delete. It does not need an approval.
 */
class RetentionShorteningNeedsFullCoverageTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final String OLD_READ = "2026-01-01T00:00:00Z";

    private final PolicyDslParser parser = new PolicyDslParser();
    private final PolicyDslEvaluator evaluator = new PolicyDslEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));

    private PolicyDsl policy(String action) {
        return parser.parse("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: r}\nspec:\n"
                + "  triggers: [{type: schedule, cron: '0 0 2 * * ?'}]\n"
                + "  match: {lastAccessedAt: {olderThan: P90D}}\n"
                + "  action: {type: " + action + ", parameters: {retentionDays: 30, basis: LAST_ACCESSED}}\n");
    }

    @ParameterizedTest(name = "setRetention under {0} coverage is {1}, as delete is")
    @CsvSource({
            "FULL,    TRUE",
            "PARTIAL, UNKNOWN",
            "NONE,    UNKNOWN",
            "'',      UNKNOWN"
    })
    void aStaleReadNeedsFullCoverageForSetRetentionAsForDelete(String coverage, MatchOutcome expected) {
        Map<String, Object> snapshot = coverage.isEmpty()
                ? Map.of("lastAccessedAt", OLD_READ)
                : Map.of("lastAccessedAt", OLD_READ, "readCoverage", coverage);

        PolicyDsl retention = policy("setRetention");
        PolicyDsl delete = policy("delete");

        assertThat(evaluator.evaluate(retention, snapshot, evaluator.optionsFor(retention)).outcome())
                .isEqualTo(expected);
        assertThat(evaluator.evaluate(delete, snapshot, evaluator.optionsFor(delete)).outcome()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} counts as retention-shortening")
    @ValueSource(strings = {"setRetention", "SETRETENTION", "set_retention", "set-retention", "set.Retention"})
    void everySpellingDaCatalogAcceptsCounts(String type) {
        assertThat(PolicyDslEvaluator.isRetentionShortening(type)).isTrue();
        assertThat(PolicyDslEvaluator.needsFullReadCoverage(type)).isTrue();
        assertThat(PolicyDslEvaluator.requiresFullReadCoverage(policy(type))).isTrue();
    }

    @Test
    void otherNonDestructiveActionsStillReadTheLastReadAtFaceValue() {
        assertThat(PolicyDslEvaluator.isRetentionShortening("addLabel")).isFalse();
        assertThat(PolicyDslEvaluator.isRetentionShortening(null)).isFalse();
        assertThat(PolicyDslEvaluator.needsFullReadCoverage("alert.webhook")).isFalse();
        PolicyDsl label = policy("addLabel");
        assertThat(evaluator.evaluate(label, Map.of("lastAccessedAt", OLD_READ, "readCoverage", "PARTIAL"),
                evaluator.optionsFor(label)).outcome()).isEqualTo(MatchOutcome.TRUE);
    }

    @Test
    void setRetentionNeedsNoApproval() {
        PolicyDsl retention = policy("setRetention");
        var fire = PolicyActionRequests.forSchedule(retention, java.util.UUID.randomUUID(),
                Map.of("assetId", "1f2d3a4b-0000-4000-8000-000000000072"), NOW, NOW);

        assertThat(ActionClass.of("setRetention", Map.of("retentionDays", 30)).destructive()).isFalse();
        assertThat(ApprovalGate.gated(fire)).isFalse();
    }
}
