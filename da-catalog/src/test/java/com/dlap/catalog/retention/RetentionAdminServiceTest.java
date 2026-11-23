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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.dlap.catalog.common.ConflictException;
import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.retention.RetentionAdminService.Actor;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAssetStore.StoredOutcome;
import com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys;
import com.dlap.catalog.retention.RetentionRuleStore.StoredRule;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The operator actions: input checks, the audit row written with every change, the hold changing
 * only through the entity's own methods, the evaluation that follows, and the idempotent repeats.
 */
class RetentionAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Actor ALICE = new Actor("alice", UUID.fromString("00000000-0000-0000-0000-0000000000a1"));

    private final RetentionAssetStore assets = mock(RetentionAssetStore.class);
    private final RetentionRuleStore rules = mock(RetentionRuleStore.class);
    private final RetentionEvaluator evaluator = mock(RetentionEvaluator.class);
    private final ReadCoverageSource coverage = mock(ReadCoverageSource.class);
    private final RetentionAuditStore audit = mock(RetentionAuditStore.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RetentionAdminService service;

    RetentionAdminServiceTest() {
        service = new RetentionAdminService(assets, rules, evaluator, coverage, audit, entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(coverage.snapshot()).thenReturn(ReadCoverageSource.View.none());
    }

    private static AssetRow row() {
        return new AssetRow(ASSET, "gs://b", "GCP", "GCS_BUCKET", "ACTIVE", null, null, null, null, false,
                new TechnicalKeys(null, null, null, null, null, null),
                new StoredOutcome(RetentionStatus.UNKNOWN, null, null, null, null, null, null));
    }

    private static Asset asset() {
        Asset a = new Asset("gs://b", "GCP", "GCS_BUCKET");
        a.setAssetId(ASSET);
        return a;
    }

    private static StoredRule stored(UUID ruleId, RetentionBasis basis, Integer days, Instant expiry) {
        return new StoredRule(new RetentionRule(ruleId, RuleSource.MANUAL, basis, days, expiry, null, null), ASSET,
                "why", NOW, "alice");
    }

    // ---- rules ------------------------------------------------------------------------------

    @Test
    void aValidRuleIsStoredAuditedAndEvaluatedInThatOrder() {
        UUID ruleId = UUID.randomUUID();
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));
        when(rules.findByAsset(ASSET)).thenReturn(List.of());
        when(rules.insertManual(ASSET, RetentionBasis.LAST_ACCESSED, 90, null, "review", "alice"))
                .thenReturn(stored(ruleId, RetentionBasis.LAST_ACCESSED, 90, null));

        RetentionRuleView view = service.addManualRule(ASSET,
                new RetentionRuleRequest(RetentionBasis.LAST_ACCESSED, 90, null, "  review  "), ALICE);

        assertThat(view.ruleId()).isEqualTo(ruleId);
        assertThat(view.source()).isEqualTo("MANUAL");
        assertThat(view.days()).isEqualTo(90);
        InOrder order = inOrder(rules, audit, evaluator);
        order.verify(rules).insertManual(ASSET, RetentionBasis.LAST_ACCESSED, 90, null, "review", "alice");
        order.verify(audit).record(ASSET, RetentionAuditStore.Action.RULE_ADDED, "alice", ALICE.id(), "review",
                "basis=LAST_ACCESSED days=90");
        order.verify(evaluator).reevaluate(eq(ASSET), eq(NOW), any(), eq(null));
    }

    @Test
    void aFixedDateRuleCarriesItsExpiryAndNoDays() {
        Instant expiry = Instant.parse("2027-12-31T00:00:00Z");
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));
        when(rules.findByAsset(ASSET)).thenReturn(List.of());
        when(rules.insertManual(any(), any(), any(), any(), any(), any()))
                .thenReturn(stored(UUID.randomUUID(), RetentionBasis.FIXED_DATE, null, expiry));

        RetentionRuleView view = service.addManualRule(ASSET,
                new RetentionRuleRequest(RetentionBasis.FIXED_DATE, null, expiry, null), ALICE);

        assertThat(view.expiresAt()).isEqualTo(expiry);
        verify(audit).record(ASSET, RetentionAuditStore.Action.RULE_ADDED, "alice", ALICE.id(), null,
                "basis=FIXED_DATE expiresAt=2027-12-31T00:00:00Z");
    }

    @Test
    void malformedRulesAreRejectedBeforeAnythingIsWritten() {
        Instant t = Instant.parse("2027-12-31T00:00:00Z");
        List<RetentionRuleRequest> bad = List.of(
                new RetentionRuleRequest(null, 5, null, null),
                new RetentionRuleRequest(RetentionBasis.CREATED, null, null, null),
                new RetentionRuleRequest(RetentionBasis.CREATED, -1, null, null),
                new RetentionRuleRequest(RetentionBasis.CREATED, RetentionProperties.MAX_DAYS + 1, null, null),
                new RetentionRuleRequest(RetentionBasis.CREATED, 5, t, null),
                new RetentionRuleRequest(RetentionBasis.FIXED_DATE, null, null, null),
                new RetentionRuleRequest(RetentionBasis.FIXED_DATE, 5, t, null));
        for (RetentionRuleRequest request : bad) {
            assertThatThrownBy(() -> service.addManualRule(ASSET, request, ALICE)).as(request.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(rules, never()).insertManual(any(), any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRuleOnAnUnknownAssetIsNotFoundAndATwentyFirstRuleIsAConflict() {
        when(assets.findById(ASSET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addManualRule(ASSET,
                new RetentionRuleRequest(RetentionBasis.CREATED, 1, null, null), ALICE))
                .isInstanceOf(ResourceNotFoundException.class);

        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));
        StoredRule existing = stored(UUID.randomUUID(), RetentionBasis.CREATED, 1, null);
        when(rules.findByAsset(ASSET)).thenReturn(java.util.Collections.nCopies(
                RetentionAdminService.MAX_MANUAL_RULES, existing));
        assertThatThrownBy(() -> service.addManualRule(ASSET,
                new RetentionRuleRequest(RetentionBasis.CREATED, 1, null, null), ALICE))
                .isInstanceOf(ConflictException.class);
        verify(rules, never()).insertManual(any(), any(), any(), any(), any(), any());
    }

    @Test
    void policyRulesDoNotCountAgainstTheManualLimit() {
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));
        StoredRule policy = new StoredRule(new RetentionRule(UUID.randomUUID(), RuleSource.POLICY,
                RetentionBasis.CREATED, 1, null, UUID.randomUUID(), null), ASSET, null, NOW, null);
        when(rules.findByAsset(ASSET)).thenReturn(java.util.Collections.nCopies(
                RetentionAdminService.MAX_MANUAL_RULES, policy));
        when(rules.insertManual(any(), any(), any(), any(), any(), any()))
                .thenReturn(stored(UUID.randomUUID(), RetentionBasis.CREATED, 1, null));

        service.addManualRule(ASSET, new RetentionRuleRequest(RetentionBasis.CREATED, 1, null, null), ALICE);

        verify(rules).insertManual(any(), any(), any(), any(), any(), any());
    }

    @Test
    void removingARuleAuditsItAndEvaluatesAgainAndAMissingRuleIsNotFound() {
        UUID ruleId = UUID.randomUUID();
        when(rules.deleteManual(ASSET, ruleId)).thenReturn(Optional.of(stored(ruleId, RetentionBasis.CREATED, 30, null)));

        service.removeManualRule(ASSET, ruleId, ALICE);

        verify(audit).record(ASSET, RetentionAuditStore.Action.RULE_REMOVED, "alice", ALICE.id(), null,
                "basis=CREATED days=30");
        verify(evaluator).reevaluate(eq(ASSET), eq(NOW), any(), eq(null));

        UUID other = UUID.randomUUID();
        when(rules.deleteManual(ASSET, other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeManualRule(ASSET, other, ALICE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- legal hold -------------------------------------------------------------------------

    @Test
    void placingTheHoldGoesThroughTheEntityMethodAuditsAndEvaluatesWithThePreviousStatus() {
        Asset asset = asset();
        asset.setRetentionStatus(RetentionStatus.DUE);
        when(entityManager.find(Asset.class, ASSET, LockModeType.PESSIMISTIC_WRITE)).thenReturn(asset);
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));

        service.placeLegalHold(ASSET, "  litigation 42 ", ALICE);

        assertThat(asset.isLegalHold()).isTrue();
        assertThat(asset.getRetentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
        InOrder order = inOrder(entityManager, audit, evaluator);
        order.verify(entityManager).flush();
        order.verify(entityManager).detach(asset);
        order.verify(audit).record(ASSET, RetentionAuditStore.Action.HOLD_PLACED, "alice", ALICE.id(),
                "litigation 42", null);
        // The status before the action (DUE), not the half-way ON_HOLD the entity method sets.
        order.verify(evaluator).reevaluate(eq(ASSET), eq(NOW), any(), eq(RetentionStatus.DUE));
    }

    @Test
    void placingAHoldThatIsAlreadyInPlaceChangesNothingAndWritesNoAuditRow() {
        Asset asset = asset();
        asset.placeLegalHold();
        when(entityManager.find(Asset.class, ASSET, LockModeType.PESSIMISTIC_WRITE)).thenReturn(asset);
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));

        service.placeLegalHold(ASSET, "again", ALICE);

        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
        verify(evaluator, never()).reevaluate(any(), any(), any(), any());
        verify(entityManager, never()).flush();
    }

    @Test
    void releasingTheHoldEvaluatesAtOnceWithOnHoldAsThePreviousStatus() {
        Asset asset = asset();
        asset.placeLegalHold();
        when(entityManager.find(Asset.class, ASSET, LockModeType.PESSIMISTIC_WRITE)).thenReturn(asset);
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));

        service.releaseLegalHold(ASSET, "case closed", ALICE);

        assertThat(asset.isLegalHold()).isFalse();
        verify(audit).record(ASSET, RetentionAuditStore.Action.HOLD_RELEASED, "alice", ALICE.id(), "case closed", null);
        verify(evaluator).reevaluate(eq(ASSET), eq(NOW), any(), eq(RetentionStatus.ON_HOLD));
    }

    @Test
    void releasingAHoldThatIsNotInPlaceChangesNothing() {
        when(entityManager.find(Asset.class, ASSET, LockModeType.PESSIMISTIC_WRITE)).thenReturn(asset());
        when(assets.findById(ASSET)).thenReturn(Optional.of(row()));

        service.releaseLegalHold(ASSET, "x", ALICE);

        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
        verify(evaluator, never()).reevaluate(any(), any(), any(), any());
    }

    @Test
    void holdActionsOnAnUnknownAssetAreNotFound() {
        when(entityManager.find(Asset.class, ASSET, LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);

        assertThatThrownBy(() -> service.placeLegalHold(ASSET, "x", ALICE)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.releaseLegalHold(ASSET, "x", ALICE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- view -------------------------------------------------------------------------------

    @Test
    void theViewListsStoredRulesThenLabelDefaultsAndProviderRulesButNeverTheStoredOnesTwice() {
        AssetRow row = new AssetRow(ASSET, "gs://b", "GCP", "GCS_BUCKET", "ACTIVE", null, null, null, null, true,
                new TechnicalKeys("365", null, null, null, null, null),
                new StoredOutcome(RetentionStatus.ON_HOLD, 365, RetentionBasis.CREATED, "PROVIDER", null,
                        Instant.parse("2026-01-01T00:00:00Z"), NOW));
        StoredRule manual = stored(UUID.randomUUID(), RetentionBasis.LAST_ACCESSED, 90, null);
        RetentionRule label = RetentionRule.ofDays(RuleSource.LABEL_DEFAULT, RetentionBasis.CREATED, 30);
        RetentionRule provider = RetentionRule.ofDays(RuleSource.PROVIDER, RetentionBasis.CREATED, 365);
        when(assets.findById(ASSET)).thenReturn(Optional.of(row));
        when(rules.findByAsset(ASSET)).thenReturn(List.of(manual));
        when(evaluator.effectiveRules(any(), any(), any())).thenReturn(List.of(manual.rule(), label, provider));

        AssetRetentionView view = service.view(ASSET);

        assertThat(view.retentionStatus()).isEqualTo("ON_HOLD");
        assertThat(view.legalHold()).isTrue();
        assertThat(view.retentionSource()).isEqualTo("PROVIDER");
        assertThat(view.rules()).extracting(RetentionRuleView::source)
                .containsExactly("MANUAL", "LABEL_DEFAULT", "PROVIDER");
        assertThat(view.rules().get(0).createdBy()).isEqualTo("alice");
        assertThat(view.rules().get(1).ruleId()).isNull();
    }

    @Test
    void theViewOfAnUnknownAssetIsNotFound() {
        when(assets.findById(ASSET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.view(ASSET)).isInstanceOf(ResourceNotFoundException.class);
    }
}
