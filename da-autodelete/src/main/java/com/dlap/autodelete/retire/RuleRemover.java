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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes DLAP's own bucket, prefix or container rule of a record: for a restore (RET-04) and when the rule's
 * maximum age passed (RET-06, Q-677). Both callers hold the record's row lock.
 *
 * <p>A {@code retire.delete.apply} SUCCEEDED record that is still pending from the apply (Q-633) is written
 * before anything else (RET-08, Q-699): otherwise a restore or the end of the rule would leave the record in a
 * state the sweeper never claims again, and the chain would never show the apply as done. When it cannot be
 * written, nothing is removed.
 *
 * <p>The intent is audited first (audit-log.md section 6: when it cannot be written, nothing is removed). A
 * refused or unreachable platform is audited {@code FAILED}; a removal that found no matching rule (Q-682)
 * is audited {@code FAILED} with reason {@code rule-not-found}. The caller writes the record of a removal
 * that succeeded, together with the record's new state.
 */
@Component
public class RuleRemover {

    private static final Logger LOG = LoggerFactory.getLogger(RuleRemover.class);

    /** What the removal did. */
    public enum Result {
        /** Every rule of the stored document was found and removed. */
        REMOVED,
        /** The platform answered, but not every rule of the stored document was found on it (Q-682). */
        NOT_FOUND,
        /** The platform refused or could not be reached; nothing is known to have changed. */
        FAILED,
        /** The record has no applied native container rule to remove. */
        NO_STORED_RULE
    }

    /**
     * The outcome.
     *
     * @param result      what happened
     * @param detail      the platform's error, or a sentence; null on success
     * @param disposition the record's disposition, or null when there is none
     */
    public record Outcome(Result result, String detail, RetireDispositionEntity disposition) {
    }

    private final RetireDispositionRepository dispositions;
    private final DispositionPlanner planner;
    private final RetireAuditLog audit;
    private final Clock clock;

    /**
     * Creates the remover.
     *
     * @param dispositions the dispositions
     * @param planner      the planner, which knows the appliers
     * @param audit        the audit log
     * @param clock        the clock
     */
    public RuleRemover(RetireDispositionRepository dispositions, DispositionPlanner planner, RetireAuditLog audit,
            Clock clock) {
        this.dispositions = dispositions;
        this.planner = planner;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The applied native container rule of a record, if it has one.
     *
     * @param recordId the record
     * @return the disposition of an applied container rule with a stored rule document
     */
    public Optional<RetireDispositionEntity> appliedContainerRule(UUID recordId) {
        return dispositions.findBySoftDeleteRecordId(recordId)
                .filter(d -> RetireDispositionEntity.MODE_NATIVE_RULE.equals(d.getMode()))
                .filter(d -> NativeRulePlatform.isContainerRule(d.getPlatform()))
                .filter(d -> d.getAppliedAt() != null && !d.isDryRun() && d.getRuleDocument() != null);
    }

    /**
     * Removes the record's rule.
     *
     * @param rec       the record, locked by the caller
     * @param eventType the audit event: {@link RetireAuditLog#RESTORED}, {@link RetireAuditLog#RULE_ENDED} or
     *                  {@link RetireAuditLog#RULE_ENDED_UNPROVEN}
     * @param actor     the restoring person, or the service
     * @param target    the audit target
     * @param details   facts to carry in the audit records; the removal adds its own
     * @return the outcome
     * @throws RuntimeException when the pending apply outcome or the intent cannot be audited; nothing was removed
     */
    public Outcome remove(SoftDeleteRecordEntity rec, String eventType, AuditActor actor, String target,
            Map<String, Object> details) {
        Optional<RetireDispositionEntity> found = appliedContainerRule(rec.getId());
        Optional<NativeRuleApplier> applier = found.flatMap(d -> planner.applierFor(d.getPlatform()));
        if (found.isEmpty() || applier.isEmpty()) {
            return new Outcome(Result.NO_STORED_RULE, "no stored rule to remove for record " + rec.getId(),
                    found.orElse(null));
        }
        RetireDispositionEntity d = found.get();
        // Q-699: the apply's own SUCCEEDED record comes first; a failure propagates and nothing is removed.
        boolean pendingWritten = PendingApplyOutcome.write(audit, dispositions, rec, d, clock.instant());
        Map<String, Object> removal = new LinkedHashMap<>(details);
        if (pendingWritten) {
            removal.put("pendingApplyOutcomeWritten", true);
            LOG.info("Record {}: wrote the pending apply SUCCEEDED record before removing the {} rule", rec.getId(),
                    d.getPlatform());
        }
        removal.put("dispositionId", d.getId());
        removal.put("platform", d.getPlatform());
        removal.put("ruleId", d.getRuleId());
        removal.put("ruleAppliedAt", d.getAppliedAt());
        // Intent first (audit-log.md section 6): when it cannot be written, nothing is removed.
        audit.record(eventType, clock.instant(), actor, target, AuditOutcome.REQUESTED, rec.getId().toString(),
                removal);
        boolean allFound;
        try {
            allFound = applier.get().remove(new NativeRule(NativeRulePlatform.valueOf(d.getPlatform()),
                    d.getTargetUri(), d.getRuleId() == null ? "dlap-retire" : d.getRuleId(),
                    d.getEffectiveAt() == null ? d.getAppliedAt() : d.getEffectiveAt(), d.getRuleDocument()));
        } catch (NativeRuleException | IllegalArgumentException e) {
            removal.put("detail", e.getMessage());
            audit.record(eventType, clock.instant(), actor, target, AuditOutcome.FAILED, rec.getId().toString(),
                    removal);
            LOG.warn("Record {}: removing the {} rule failed: {}", rec.getId(), d.getPlatform(), e.getMessage());
            return new Outcome(Result.FAILED, e.getMessage(), d);
        }
        if (!allFound) {
            String why = "no rule on the platform matches DLAP's " + d.getPlatform() + " rule " + d.getRuleId();
            removal.put("ruleRemoved", false);
            removal.put("reason", RefusalReason.RULE_NOT_FOUND.code());
            removal.put("detail", why);
            audit.record(eventType, clock.instant(), actor, target, AuditOutcome.FAILED, rec.getId().toString(),
                    removal);
            LOG.error("Record {}: {}; the record ends RESTORE_FAILED and a person has to check the bucket "
                    + "(Q-682)", rec.getId(), why);
            return new Outcome(Result.NOT_FOUND, why, d);
        }
        Instant removedAt = clock.instant();
        d.setRuleRemovedAt(removedAt);
        d.setUpdatedAt(removedAt);
        dispositions.saveAndFlush(d);
        return new Outcome(Result.REMOVED, null, d);
    }
}
