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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.common.ConflictException;
import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAuditStore.Action;
import com.dlap.catalog.retention.RetentionRuleStore.StoredRule;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The operator actions on retention that need a named role, all audited
 * ({@link RetentionAuditStore}): placing and releasing the DLAP legal hold and adding and removing
 * MANUAL rules (docs/specs/asset-descriptor.md "Retention > Rule" and "Legal hold").
 *
 * <p>The hold changes only through {@link Asset#placeLegalHold()} and
 * {@link Asset#releaseLegalHold()}, as the spec requires, on the row locked with the same
 * {@code SELECT ... FOR UPDATE} the event consumers take. After every action the asset is
 * evaluated again in the same transaction, so the status an operator sees is the status the
 * rules give now, and a transition queues its retention-due event (the previous status is the one
 * before the action, not the half-way status the hold method sets).
 */
@Service
public class RetentionAdminService {

    /** The most MANUAL rules one asset may carry. */
    public static final int MAX_MANUAL_RULES = 20;

    /**
     * The operator behind an action.
     *
     * @param name the user name
     * @param id   the user id, or {@code null}
     */
    public record Actor(String name, UUID id) {
    }

    private final RetentionAssetStore assets;
    private final RetentionRuleStore rules;
    private final RetentionEvaluator evaluator;
    private final ReadCoverageSource coverage;
    private final RetentionAuditStore audit;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param assets    access to the asset columns
     * @param rules     the rule store
     * @param evaluator the evaluator
     * @param coverage  the read signal
     * @param audit     the audit trail
     * @param entityManager the shared entity manager, for the locked load of the asset entity
     * @param clock     the time source
     */
    public RetentionAdminService(RetentionAssetStore assets, RetentionRuleStore rules, RetentionEvaluator evaluator,
                                 ReadCoverageSource coverage, RetentionAuditStore audit,
                                 EntityManager entityManager, Clock clock) {
        this.assets = assets;
        this.rules = rules;
        this.evaluator = evaluator;
        this.coverage = coverage;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * The retention state of an asset: the stored result and every rule that applies.
     *
     * @param assetId the asset
     * @return the view
     * @throws ResourceNotFoundException when the asset does not exist
     */
    @Transactional(readOnly = true)
    public AssetRetentionView view(UUID assetId) {
        AssetRow row = assets.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", "id", assetId.toString()));
        Set<String> labels = assets.labelNames(List.of(assetId)).getOrDefault(assetId, Set.of());
        List<StoredRule> stored = rules.findByAsset(assetId);
        List<RetentionRule> storedRules = stored.stream().map(StoredRule::rule).toList();
        List<RetentionRuleView> views = new ArrayList<>();
        for (StoredRule s : stored) {
            views.add(view(s));
        }
        for (RetentionRule rule : evaluator.effectiveRules(row, labels, storedRules)) {
            if (!rule.source().isStored()) {
                views.add(new RetentionRuleView(null, rule.source().name(), rule.basis().name(), rule.days(),
                        rule.fixedExpiry(), null, null, null, null, null));
            }
        }
        RetentionAssetStore.StoredOutcome c = row.current();
        return new AssetRetentionView(assetId, row.assetUri(), c.status().name(), c.expiresAt(), c.evaluatedAt(),
                c.days(), c.basis() == null ? null : c.basis().name(), c.source(), c.policyId(), row.legalHold(),
                views);
    }

    /**
     * Adds an operator's rule, audits it and evaluates the asset again.
     *
     * @param assetId the asset
     * @param request the rule
     * @param actor   the operator
     * @return the stored rule
     * @throws ResourceNotFoundException when the asset does not exist
     * @throws IllegalArgumentException  when the rule is malformed
     * @throws ConflictException         when the asset already carries the maximum number of rules
     */
    @Transactional
    public RetentionRuleView addManualRule(UUID assetId, RetentionRuleRequest request, Actor actor) {
        RetentionBasis basis = request.basis();
        if (basis == null) {
            throw new IllegalArgumentException("basis is required");
        }
        Integer days = request.days();
        if (basis.requiresDays()) {
            if (days == null || days < 0 || days > RetentionProperties.MAX_DAYS) {
                throw new IllegalArgumentException("days must be between 0 and " + RetentionProperties.MAX_DAYS
                        + " for basis " + basis);
            }
            if (request.expiresAt() != null) {
                throw new IllegalArgumentException("expiresAt applies only to basis FIXED_DATE");
            }
        } else {
            if (request.expiresAt() == null) {
                throw new IllegalArgumentException("expiresAt is required for basis FIXED_DATE");
            }
            if (days != null) {
                throw new IllegalArgumentException("days does not apply to basis FIXED_DATE");
            }
        }
        if (assets.findById(assetId).isEmpty()) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }
        long existing = rules.findByAsset(assetId).stream()
                .filter(r -> r.rule().source() == RuleSource.MANUAL).count();
        if (existing >= MAX_MANUAL_RULES) {
            throw new ConflictException("The asset already carries " + MAX_MANUAL_RULES + " manual retention rules");
        }
        String reason = blankToNull(request.reason());
        StoredRule stored = rules.insertManual(assetId, basis, days, request.expiresAt(), reason, actor.name());
        audit.record(assetId, Action.RULE_ADDED, actor.name(), actor.id(), reason, describe(stored.rule()));
        evaluator.reevaluate(assetId, clock.instant(), coverage.snapshot(), null);
        return view(stored);
    }

    /**
     * Removes an operator's rule, audits it and evaluates the asset again.
     *
     * @param assetId the asset
     * @param ruleId  the rule
     * @param actor   the operator
     * @throws ResourceNotFoundException when the asset has no MANUAL rule with that id
     */
    @Transactional
    public void removeManualRule(UUID assetId, UUID ruleId, Actor actor) {
        StoredRule removed = rules.deleteManual(assetId, ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Manual retention rule", "id", ruleId.toString()));
        audit.record(assetId, Action.RULE_REMOVED, actor.name(), actor.id(), null, describe(removed.rule()));
        evaluator.reevaluate(assetId, clock.instant(), coverage.snapshot(), null);
    }

    /**
     * Places the DLAP legal hold. Placing a hold that is already in place changes nothing and
     * writes no audit row.
     *
     * @param assetId the asset
     * @param reason  why
     * @param actor   the operator
     * @return the retention state after the action
     * @throws ResourceNotFoundException when the asset does not exist
     */
    @Transactional
    public AssetRetentionView placeLegalHold(UUID assetId, String reason, Actor actor) {
        Asset asset = entityManager.find(Asset.class, assetId, LockModeType.PESSIMISTIC_WRITE);
        if (asset == null) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }
        if (asset.isLegalHold()) {
            return view(assetId);
        }
        RetentionStatus previous = asset.getRetentionStatus();
        asset.placeLegalHold();
        entityManager.flush();
        entityManager.detach(asset);
        audit.record(assetId, Action.HOLD_PLACED, actor.name(), actor.id(), blankToNull(reason), null);
        evaluator.reevaluate(assetId, clock.instant(), coverage.snapshot(), previous);
        return view(assetId);
    }

    /**
     * Releases the DLAP legal hold. Releasing a hold that is not in place changes nothing and
     * writes no audit row. The asset is evaluated at once, so it is never left {@code UNKNOWN}
     * waiting for the next sweep.
     *
     * @param assetId the asset
     * @param reason  why
     * @param actor   the operator
     * @return the retention state after the action
     * @throws ResourceNotFoundException when the asset does not exist
     */
    @Transactional
    public AssetRetentionView releaseLegalHold(UUID assetId, String reason, Actor actor) {
        Asset asset = entityManager.find(Asset.class, assetId, LockModeType.PESSIMISTIC_WRITE);
        if (asset == null) {
            throw new ResourceNotFoundException("Asset", "id", assetId.toString());
        }
        if (!asset.isLegalHold()) {
            return view(assetId);
        }
        RetentionStatus previous = asset.getRetentionStatus();
        asset.releaseLegalHold();
        entityManager.flush();
        entityManager.detach(asset);
        audit.record(assetId, Action.HOLD_RELEASED, actor.name(), actor.id(), blankToNull(reason), null);
        evaluator.reevaluate(assetId, clock.instant(), coverage.snapshot(), previous);
        return view(assetId);
    }

    private static RetentionRuleView view(StoredRule s) {
        RetentionRule r = s.rule();
        return new RetentionRuleView(r.ruleId(), r.source().name(), r.basis().name(), r.days(), r.fixedExpiry(),
                r.policyId(), r.mode(), s.reason(), s.issuedAt(), s.createdBy());
    }

    private static String describe(RetentionRule rule) {
        return rule.basis() == RetentionBasis.FIXED_DATE
                ? "basis=FIXED_DATE expiresAt=" + rule.fixedExpiry()
                : "basis=" + rule.basis() + " days=" + rule.days();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
