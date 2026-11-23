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

package com.dlap.autoarchival.executor;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;

/**
 * Closes an archive row that a dead worker left in {@code EXECUTING} (Q-669).
 *
 * <p>A worker claims a row with one conditional update and then writes the provider rule. If it dies in between,
 * nothing runs the row again (by design: the rule must never be applied twice). An administrator resets such a row
 * with this service, which <b>reads the provider first and never applies anything</b>:
 * <ul>
 *   <li>the rule is present: the row becomes {@code APPLIED}, and the proof service takes over from there;</li>
 *   <li>the rule is absent: the row becomes {@code FAILED} with reason {@value #REASON_RULE_ABSENT}.</li>
 * </ul>
 * The reset is audit-chained the way the executor is: the intent record is appended before the row changes (a failed
 * append means no reset), and the outcome record follows.
 *
 * <p>A reset works only on a row in {@code EXECUTING}. A single-object archive and a provider DLAP cannot read have no
 * rule to look for, so those rows are refused rather than guessed at.
 */
@Service
public class ArchiveRuleResetService {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveRuleResetService.class);

    /** The failure reason of a row whose provider rule is absent. */
    public static final String REASON_RULE_ABSENT = "RESET_RULE_ABSENT";

    /** What a reset did. */
    public enum Outcome {
        /** The provider holds the rule; the row is now {@code APPLIED}. */
        RESET_TO_APPLIED,
        /** The provider holds no rule; the row is now {@code FAILED}. */
        RESET_TO_FAILED,
        /** No row has this id. */
        NOT_FOUND,
        /** The row is not {@code EXECUTING}; nothing was changed. */
        NOT_EXECUTING,
        /** The provider cannot be read for this row (single object, render-only provider); nothing was changed. */
        UNREADABLE,
        /** The provider read failed; nothing was changed. */
        PROVIDER_ERROR,
        /** The audit log refused the intent record; nothing was changed. */
        AUDIT_UNAVAILABLE,
        /** The row left {@code EXECUTING} while the reset ran; nothing was changed. */
        ROW_CHANGED
    }

    /**
     * The result of a reset.
     *
     * @param outcome what happened
     * @param row     the row as it now stands; {@code null} for {@link Outcome#NOT_FOUND}
     * @param message a sentence for the caller
     */
    public record Result(Outcome outcome, ArchiveRuleEntity row, String message) {
    }

    private final ArchiveRuleRepository rules;
    private final RulePlanner planner;
    private final ArchiveAudit audit;
    private final ArchiveTaskSync sync;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param rules   the rule rows
     * @param planner gives the provider for a row
     * @param audit   the audit log
     * @param sync    recomputes the task after a reset
     * @param clock   the injected clock
     */
    public ArchiveRuleResetService(ArchiveRuleRepository rules, RulePlanner planner, ArchiveAudit audit,
            ArchiveTaskSync sync, Clock clock) {
        this.rules = rules;
        this.planner = planner;
        this.audit = audit;
        this.sync = sync;
        this.clock = clock;
    }

    /**
     * Resets a row that is stuck in {@code EXECUTING}.
     *
     * @param ruleId the rule id
     * @param actor  the administrator who asked
     * @return what happened
     */
    public Result reset(String ruleId, AuditActor actor) {
        Optional<ArchiveRuleEntity> found = rules.findById(ruleId);
        if (found.isEmpty()) {
            return new Result(Outcome.NOT_FOUND, null, "no archive rule " + ruleId);
        }
        ArchiveRuleEntity row = found.get();
        if (!ArchiveRuleEntity.STATUS_EXECUTING.equals(row.getStatus())) {
            return new Result(Outcome.NOT_EXECUTING, row,
                    "the rule is " + row.getStatus() + ", not EXECUTING; a reset only closes a claimed row");
        }
        if (ArchiveRuleEntity.MODE_DIRECT_OBJECT.equals(row.getMode())) {
            return new Result(Outcome.UNREADABLE, row, "a single-object archive has no provider rule to read; check "
                    + "the object's storage class and the audit log");
        }
        NativeRuleProvider provider;
        NativeRuleProvider.Inventory inventory;
        try {
            provider = planner.providerFor(row.getProvider());
            if (!provider.canApply()) {
                return new Result(Outcome.UNREADABLE, row, "DLAP cannot read " + row.getProvider()
                        + " lifecycle rules; check the provider and the audit log");
            }
            inventory = provider.inventory(ArchiveExecutor.specOf(row));
        } catch (RuleException e) {
            LOG.warn("reset of archive {}: the provider could not be read: {}", ruleId, e.reason());
            return new Result(Outcome.PROVIDER_ERROR, row, e.reason() + ": " + e.getMessage());
        }
        if (inventory.totalRules() < 0 && !provider.isMock()) {
            return new Result(Outcome.UNREADABLE, row,
                    "the provider could not be asked for its rules; check the provider and the audit log");
        }
        boolean present = inventory.specPresent();
        // A GCS rule has no id, so a rule found there cannot be told from the customer's own identical rule. A
        // restore of this row then leaves the provider rule in place (the safe direction, as in Q-608). S3 rules
        // carry DLAP's own rule id, so a present S3 rule is DLAP's.
        boolean alreadyPresent = present && "GCP".equals(row.getProvider());
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("providerRulePresent", present);
        plan.put("newStatus", present ? ArchiveRuleEntity.STATUS_APPLIED : ArchiveRuleEntity.STATUS_FAILED);
        plan.put("providerRuleKept", alreadyPresent);
        try {
            audit.resetRequested(row, actor, plan);
        } catch (AuditException | IllegalArgumentException e) {
            LOG.error("reset of archive {} not run: the audit log refused the intent record: {}", ruleId,
                    e.getMessage());
            return new Result(Outcome.AUDIT_UNAVAILABLE, row, "the audit log is unavailable; nothing was changed");
        }
        int changed = present
                ? rules.resetExecutingToApplied(ruleId, alreadyPresent, clock.instant())
                : rules.resetExecutingToFailed(ruleId, ArchiveRuleEntity.clip(REASON_RULE_ABSENT
                        + ": reset by an administrator; the provider holds no rule for this row"), clock.instant());
        ArchiveRuleEntity after = rules.findById(ruleId).orElse(row);
        if (changed != 1) {
            audit(() -> audit.resetNotApplied(after, actor), ruleId);
            return new Result(Outcome.ROW_CHANGED, after, "the rule left EXECUTING while the reset ran; it is now "
                    + after.getStatus() + " and was not changed");
        }
        Map<String, Object> done = new LinkedHashMap<>(plan);
        if (!present) {
            done.put("reason", REASON_RULE_ABSENT);
        }
        audit(() -> audit.reset(after, actor, done), ruleId);
        sync.sync(after.getTaskId());
        LOG.info("archive {} reset from EXECUTING to {} (provider rule {})", ruleId, after.getStatus(),
                present ? "present" : "absent");
        return present
                ? new Result(Outcome.RESET_TO_APPLIED, after, "the provider holds the rule; the row is APPLIED and "
                        + "waits for provider proof" + (alreadyPresent
                                ? ". A GCS rule has no id, so a restore will leave the provider rule in place." : ""))
                : new Result(Outcome.RESET_TO_FAILED, after, REASON_RULE_ABSENT
                        + ": the provider holds no rule; the row is FAILED. Nothing was applied.");
    }

    /** The row has changed: an audit failure here is logged loudly, never undone. */
    private void audit(Runnable append, String ruleId) {
        try {
            append.run();
        } catch (AuditException | IllegalArgumentException e) {
            LOG.error("reset of archive {}: the outcome record could not be written to the audit log ({})", ruleId,
                    e.getMessage());
        }
    }
}
