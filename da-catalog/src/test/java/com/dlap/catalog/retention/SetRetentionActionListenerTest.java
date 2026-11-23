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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.dlap.catalog.kafka.AssetMatcher;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.SetRetentionActionListener.Outcome;
import com.dlap.common.model.RetentionBasis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The listener turns the policy engine's {@code setRetention} actions into POLICY rules. It acts
 * on nothing else on the shared topic, honours dry runs and pending approvals, orders actions by
 * {@code firedAt}, and sends an action it cannot understand to the error handler.
 */
class SetRetentionActionListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID POLICY = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private final RetentionRuleStore rules = mock(RetentionRuleStore.class);
    private final RetentionAssetStore assets = mock(RetentionAssetStore.class);
    private final RetentionEvaluator evaluator = mock(RetentionEvaluator.class);
    private final ReadCoverageSource coverage = mock(ReadCoverageSource.class);
    private final AssetMatcher matcher = mock(AssetMatcher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final SetRetentionActionListener listener;

    SetRetentionActionListenerTest() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(coverage.snapshot()).thenReturn(ReadCoverageSource.View.none());
        listener = new SetRetentionActionListener(rules, assets, evaluator, coverage, matcher,
                new RetentionMetrics(registry), tx, Clock.fixed(NOW, ZoneOffset.UTC));
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));
    }

    private static AssetRow row() {
        return new AssetRow(ASSET, "gs://b", "GCP", "GCS_BUCKET", "ACTIVE", null, null, null, null, false,
                new RetentionAssetStore.TechnicalKeys(null, null, null, null, null, null),
                new RetentionAssetStore.StoredOutcome(com.dlap.common.model.RetentionStatus.UNKNOWN, null, null, null,
                        null, null, null));
    }

    private static String action(String extra) {
        return "{\"schemaVersion\":\"v1\",\"policyId\":\"retain-pii\",\"policyDbId\":\"" + POLICY + "\","
                + "\"actionType\":\"setRetention\",\"assetId\":\"" + ASSET + "\","
                + "\"firedAt\":\"2026-10-20T11:00:00Z\","
                + "\"actionParameters\":{\"retentionDays\":365}" + extra + "}";
    }

    private double count(String outcome) {
        var c = registry.find(RetentionMetrics.ACTIONS).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void aSetRetentionActionBecomesAPolicyRuleKeyedByAssetAndPolicyAndTheAssetIsEvaluated() {
        when(rules.upsertPolicyRule(eq(ASSET), eq(POLICY), eq(RetentionBasis.LAST_ACCESSED), eq(365), any(), any()))
                .thenReturn(true);

        Outcome outcome = listener.handle(action(""));

        assertThat(outcome).isEqualTo(Outcome.RECORDED);
        verify(rules).upsertPolicyRule(ASSET, POLICY, RetentionBasis.LAST_ACCESSED, 365, null,
                Instant.parse("2026-10-20T11:00:00Z"));
        verify(evaluator).reevaluate(eq(ASSET), eq(NOW), any(), eq(null));
        assertThat(count("recorded")).isEqualTo(1);
    }

    @Test
    void basisAndModeComeFromTheParametersAndDefaultToLastAccessedAndNone() {
        when(rules.upsertPolicyRule(any(), any(), any(), anyInt(), any(), any())).thenReturn(true);
        String payload = "{\"policyDbId\":\"" + POLICY + "\",\"actionType\":\"SET_RETENTION\",\"assetId\":\"" + ASSET
                + "\",\"actionParameters\":{\"retentionDays\":\"30\",\"basis\":\"created\",\"mode\":\"HARD\"}}";

        listener.handle(payload);

        // No firedAt: the injected clock orders the action.
        verify(rules).upsertPolicyRule(ASSET, POLICY, RetentionBasis.CREATED, 30, "hard", NOW);
    }

    @Test
    void aPolicyIdThatIsAUuidIsUsedWhenThereIsNoPolicyDbId() {
        when(rules.upsertPolicyRule(any(), any(), any(), anyInt(), any(), any())).thenReturn(true);
        String payload = "{\"policyId\":\"" + POLICY + "\",\"actionType\":\"setRetention\",\"targetAssetId\":\""
                + ASSET + "\",\"actionParameters\":{\"retentionDays\":7}}";

        assertThat(listener.handle(payload)).isEqualTo(Outcome.RECORDED);

        verify(rules).upsertPolicyRule(eq(ASSET), eq(POLICY), any(), eq(7), any(), any());
    }

    @Test
    void aRepeatOrAnEarlierActionOfTheSamePolicyIsStaleAndTriggersNoEvaluation() {
        when(rules.upsertPolicyRule(any(), any(), any(), anyInt(), any(), any())).thenReturn(false);

        assertThat(listener.handle(action(""))).isEqualTo(Outcome.STALE);

        verify(evaluator, never()).reevaluate(any(), any(), any(), any());
        assertThat(count("stale")).isEqualTo(1);
    }

    @Test
    void everyOtherActionTypeAndEveryNonJsonRecordIsIgnored() {
        assertThat(listener.handle("{\"actionType\":\"archive\",\"assetId\":\"" + ASSET + "\"}"))
                .isEqualTo(Outcome.IGNORED);
        assertThat(listener.handle("{\"actionType\":\"addLabel\"}")).isEqualTo(Outcome.IGNORED);
        assertThat(listener.handle("{\"no\":\"type\"}")).isEqualTo(Outcome.IGNORED);
        assertThat(listener.handle("not json")).isEqualTo(Outcome.IGNORED);
        assertThat(listener.handle("[1]")).isEqualTo(Outcome.IGNORED);
        assertThat(listener.handle(null)).isEqualTo(Outcome.IGNORED);
        verify(rules, never()).upsertPolicyRule(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aDoubleEncodedPayloadIsReadLikeASingleEncodedOne() throws Exception {
        when(rules.upsertPolicyRule(any(), any(), any(), anyInt(), any(), any())).thenReturn(true);
        String doubleEncoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(action(""));

        assertThat(listener.handle(doubleEncoded)).isEqualTo(Outcome.RECORDED);
    }

    @Test
    void dryRunAndPendingApprovalRecordNothing() {
        assertThat(listener.handle(action(",\"dryRun\":true"))).isEqualTo(Outcome.DRY_RUN);
        assertThat(listener.handle(action(",\"requireApproval\":true"))).isEqualTo(Outcome.APPROVAL_PENDING);

        verify(rules, never()).upsertPolicyRule(any(), any(), any(), anyInt(), any(), any());
        assertThat(count("dry_run")).isEqualTo(1);
        assertThat(count("approval_pending")).isEqualTo(1);
    }

    @Test
    void anActionForAnAssetTheCatalogDoesNotKnowChangesNothing() {
        UUID other = UUID.randomUUID();
        when(assets.findById(other)).thenReturn(Optional.empty());
        String payload = action("").replace(ASSET.toString(), other.toString());

        assertThat(listener.handle(payload)).isEqualTo(Outcome.UNMATCHED);

        verify(rules, never()).upsertPolicyRule(any(), any(), any(), anyInt(), any(), any());
        assertThat(count("unmatched")).isEqualTo(1);
    }

    @Test
    void anAssetUriIsResolvedThroughTheMatcherWhenThereIsNoUsableId() {
        when(matcher.find(any())).thenReturn(Optional.of(new AssetMatcher.Match(ASSET, AssetMatcher.Method.EXACT)));
        when(rules.upsertPolicyRule(any(), any(), any(), anyInt(), any(), any())).thenReturn(true);
        String payload = "{\"policyDbId\":\"" + POLICY + "\",\"actionType\":\"setRetention\","
                + "\"assetUri\":\"gs://Some-Bucket\",\"actionParameters\":{\"retentionDays\":1}}";

        assertThat(listener.handle(payload)).isEqualTo(Outcome.RECORDED);

        verify(matcher).find(argThatUri("gs://some-bucket"));
    }

    private static com.dlap.common.assetid.CanonicalAssetId argThatUri(String uri) {
        return org.mockito.ArgumentMatchers.argThat(id -> id != null && id.uri().equals(uri));
    }

    @Test
    void anActionThatCannotBeUnderstoodIsRejectedForTheErrorHandler() {
        String noDays = "{\"policyDbId\":\"" + POLICY + "\",\"actionType\":\"setRetention\",\"assetId\":\"" + ASSET
                + "\",\"actionParameters\":{}}";
        String badDays = action("").replace("365", "-5");
        String hugeDays = action("").replace("365", "999999999");
        String textDays = action("").replace("365", "\"soon\"");
        String badBasis = action("").replace("{\"retentionDays\":365}", "{\"retentionDays\":365,\"basis\":\"FIXED_DATE\"}");
        String unknownBasis = action("").replace("{\"retentionDays\":365}", "{\"retentionDays\":365,\"basis\":\"X\"}");
        String badMode = action("").replace("{\"retentionDays\":365}", "{\"retentionDays\":365,\"mode\":\"loud\"}");
        String noPolicy = action("").replace(POLICY.toString(), "not-a-uuid");
        String badFiredAt = action("").replace("2026-10-20T11:00:00Z", "yesterday");

        for (String payload : new String[] {noDays, badDays, hugeDays, textDays, badBasis, unknownBasis, badMode,
                noPolicy, badFiredAt}) {
            assertThatThrownBy(() -> listener.handle(payload)).as(payload).isInstanceOf(IllegalArgumentException.class);
        }
        verify(rules, never()).upsertPolicyRule(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void theListenerAcknowledgesOnlyAfterHandling() {
        when(rules.upsertPolicyRule(any(), any(), any(), anyInt(), any(), any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onPolicyAction(action(""), ack);
        verify(ack).acknowledge();

        Acknowledgment rejected = mock(Acknowledgment.class);
        assertThatThrownBy(() -> listener.onPolicyAction(action("").replace("365", "-1"), rejected))
                .isInstanceOf(IllegalArgumentException.class);
        verify(rejected, never()).acknowledge();
    }
}
