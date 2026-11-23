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

package com.dlap.autodelete.service.impl;

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.ApprovalWithdrawer;
import com.dlap.autodelete.retire.HardDeleteExecutor;
import com.dlap.autodelete.retire.RefusalReason;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireSweeper;
import com.dlap.autodelete.retire.RuleRemover;
import com.dlap.autodelete.retire.SoftDeleteAdmission;
import com.dlap.autodelete.service.IDeletionService;
import com.dlap.autodelete.service.RestoreOutcome;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link IDeletionService}: the policy path through {@link SoftDeleteAdmission}, the hard
 * deletes through {@link RetireSweeper} and {@link HardDeleteExecutor}, and the restore.
 *
 * <p>Safe by default (decision D23): {@code dlap.autodelete.dry-run-cloud-delete} defaults to
 * {@code true}, so a deployment that sets nothing checks every rule and records what it would
 * apply, and touches no cloud data. A dry-run row ends {@code DRY_RUN_COMPLETE}, stamped
 * {@value HardDeleteExecutor#DRY_RUN_MARKER}; it is never {@code HARD_DELETED}.
 */
@Service
@Slf4j
public class DeletionServiceImpl implements IDeletionService {

    /** Stamped in {@code last_error} of a dry-run row. */
    public static final String DRY_RUN_MARKER = HardDeleteExecutor.DRY_RUN_MARKER;

    private final SoftDeleteRecordRepository softDeleteRepo;
    private final SoftDeleteAdmission admission;
    private final RetireSweeper sweeper;
    private final RuleRemover ruleRemover;
    private final ApprovalWithdrawer withdrawer;
    private final RetireAuditLog audit;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param softDeleteRepo the soft-delete rows
     * @param admission      the admission
     * @param sweeper        the sweeper
     * @param ruleRemover    removes the native rule of a restored record
     * @param withdrawer     withdraws a pending hard-delete approval of a restored record
     * @param audit          the audit log
     * @param clock          the clock
     */
    public DeletionServiceImpl(SoftDeleteRecordRepository softDeleteRepo, SoftDeleteAdmission admission,
            RetireSweeper sweeper, RuleRemover ruleRemover, ApprovalWithdrawer withdrawer, RetireAuditLog audit,
            Clock clock) {
        this.softDeleteRepo = softDeleteRepo;
        this.admission = admission;
        this.sweeper = sweeper;
        this.ruleRemover = ruleRemover;
        this.withdrawer = withdrawer;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public UUID queueSoftDelete(Map<String, Object> policyEvent) {
        if (policyEvent == null) {
            throw new IllegalArgumentException("policyEvent is null");
        }
        return admission.admitPolicyAction(policyEvent).recordId();
    }

    @Override
    public void recordPolicyDryRun(Map<String, Object> policyEvent) {
        Map<String, Object> event = policyEvent == null ? Map.of() : policyEvent;
        Object assetId = event.get("assetId");
        Object assetUri = event.get("assetUri");
        Object fireId = event.get("fireId");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stage", "policy_dry_run");
        details.put("assetId", assetId);
        details.put("policyId", event.get("policyId"));
        details.put("fireId", fireId);
        details.put("executed", false);
        String target = assetUri != null ? String.valueOf(assetUri) : assetId != null ? String.valueOf(assetId) : null;
        audit.record(RetireAuditLog.DRY_RUN, clock.instant(), RetireAuditLog.SERVICE, target, AuditOutcome.SUCCEEDED,
                fireId == null ? null : String.valueOf(fireId), details);
    }

    @Override
    @Transactional
    public RestoreOutcome restore(UUID softDeleteRecordId, String actorSubject, String notes) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("a restore needs the JWT subject of the person restoring");
        }
        Instant now = clock.instant();
        AuditActor actor = AuditActor.user(actorSubject);
        Optional<SoftDeleteRecordEntity> opt = softDeleteRepo.findByIdForUpdate(softDeleteRecordId);
        if (opt.isEmpty()) {
            log.warn("Restore: soft-delete record {} not found", softDeleteRecordId);
            audit.refused(now, actor, softDeleteRecordId.toString(), softDeleteRecordId.toString(), "restore",
                    RefusalReason.NOT_RESTORABLE, "no such soft-delete record", Map.of());
            return RestoreOutcome.notRestored("record-not-found", "No such soft-delete record.");
        }
        SoftDeleteRecordEntity rec = opt.get();
        String target = rec.getAssetUri() != null ? rec.getAssetUri() : rec.getAssetId().toString();
        String before = rec.getStatus();
        // RET-06 (Q-677): a container rule that was applied and still waits for its proof is in force on the
        // platform just the same; restoring it removes the rule.
        boolean appliedRuleAwaitingProof = SoftDeleteRecordEntity.AWAITING_PROOF.equals(before)
                && ruleRemover.appliedContainerRule(rec.getId()).isPresent();
        if (!SoftDeleteRecordEntity.RESTORABLE.contains(before) && !appliedRuleAwaitingProof) {
            log.warn("Restore: record {} is {}; nothing can be restored", softDeleteRecordId, before);
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("recordId", rec.getId());
            refusal.put("assetId", rec.getAssetId());
            refusal.put("status", before);
            audit.refused(now, actor, target, rec.getId().toString(), "restore", RefusalReason.NOT_RESTORABLE,
                    "the record is " + before, refusal);
            return RestoreOutcome.notRestored("record-is-" + before,
                    "The cloud deletion was already applied and cannot be taken back (an engine delete or a table "
                            + "expiration waiting for its proof, HARD_DELETED, HARD_DELETE_FAILED), the rule already "
                            + "ended (RULE_ENDED, RULE_ENDED_UNPROVEN, RESTORE_FAILED), or the record was restored "
                            + "before.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("recordId", rec.getId());
        details.put("assetId", rec.getAssetId());
        details.put("previousStatus", before);
        details.put("hardDeleteApprovalId", rec.getHardDeleteApprovalId());

        boolean ruleRemoved = false;
        if (SoftDeleteRecordEntity.RULE_IN_FORCE.equals(before) || appliedRuleAwaitingProof) {
            RuleRemover.Outcome removal = ruleRemover.remove(rec, RetireAuditLog.RESTORED, actor, target, details);
            switch (removal.result()) {
                case NO_STORED_RULE, FAILED -> {
                    if (removal.result() == RuleRemover.Result.NO_STORED_RULE) {
                        audit.refused(now, actor, target, rec.getId().toString(), "restore",
                                RefusalReason.RULE_REMOVAL_FAILED, removal.detail(), details);
                    }
                    return RestoreOutcome.notRestored(RefusalReason.RULE_REMOVAL_FAILED.code(),
                            "The lifecycle rule could not be removed, so the record is unchanged and objects keep "
                                    + "expiring: " + removal.detail());
                }
                case NOT_FOUND -> {
                    // Q-682: the removal found no matching rule. The record ends RESTORE_FAILED; the removal
                    // already wrote the FAILED record with reason rule-not-found.
                    rec.setStatus(SoftDeleteRecordEntity.RESTORE_FAILED);
                    rec.setLastError(RefusalReason.RULE_NOT_FOUND.code() + ": " + removal.detail());
                    rec.setNextCheckAt(null);
                    rec.setUpdatedAt(now);
                    softDeleteRepo.saveAndFlush(rec);
                    log.error("Restore of record {} by {}: DLAP's rule was not found; the record is RESTORE_FAILED",
                            rec.getId(), actorSubject);
                    return RestoreOutcome.ruleNotFound(RefusalReason.RULE_NOT_FOUND.code());
                }
                case REMOVED -> ruleRemoved = true;
            }
        }

        ApprovalWithdrawer.Outcome withdrawal = new ApprovalWithdrawer.Outcome(
                ApprovalWithdrawer.Result.NOT_ATTEMPTED, null);
        if (SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL.equals(before) && rec.getHardDeleteApprovalId() != null) {
            withdrawal = withdrawer.withdraw(rec.getHardDeleteApprovalId(), actorSubject, rec.getId(), notes);
        }
        details.put("ruleRemoved", ruleRemoved);
        details.put("approvalWithdrawal", withdrawal.text());
        if (withdrawal.result() == ApprovalWithdrawer.Result.ENDPOINT_NOT_AVAILABLE) {
            details.put("approvalWithdrawalNote", "the policy engine has no withdraw operation; the HARD_DELETE "
                    + "request stays until it is decided or expires, and a RESTORED record is never executed");
        } else if (withdrawal.result() == ApprovalWithdrawer.Result.REQUEST_NOT_FOUND) {
            details.put("approvalWithdrawalNote", "the policy engine does not know the HARD_DELETE request; "
                    + "nothing waits on it, and a RESTORED record is never executed");
        }

        rec.setStatus(SoftDeleteRecordEntity.RESTORED);
        rec.setRestoredAt(now);
        rec.setRestoredBy(actorSubject);
        rec.setNextCheckAt(null);
        rec.setUpdatedAt(now);
        if (notes != null && !notes.isBlank()) {
            rec.setAuditNote(rec.getAuditNote() == null ? "Restore: " + notes : rec.getAuditNote() + "\nRestore: " + notes);
        }
        softDeleteRepo.saveAndFlush(rec);
        audit.record(RetireAuditLog.RESTORED, now, actor, target, AuditOutcome.SUCCEEDED, rec.getId().toString(),
                details);
        log.info("Restored soft-delete record {} (asset {}) from {}; rule removed: {}; approval: {}", rec.getId(),
                rec.getAssetId(), before, ruleRemoved, withdrawal.text());
        return RestoreOutcome.done(ruleRemoved, withdrawal.text());
    }

    @Override
    public int processDueHardDeletes() {
        return sweeper.sweep();
    }
}
