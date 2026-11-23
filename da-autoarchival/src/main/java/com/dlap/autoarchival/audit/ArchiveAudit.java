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

package com.dlap.autoarchival.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.AuditTarget;

/**
 * Writes the archive path to the tamper-evident audit log, stream {@code retire.archive}
 * (docs/specs/audit-log.md, AUD-01).
 *
 * <p>Intent first, outcome second: {@link #requested} is appended before any provider call and a
 * failed append means the action does not run (fail closed). The outcome records
 * ({@link #applied}, {@link #rendered}, {@link #failed}, {@link #executed}, {@link #removed}, {@link #reset})
 * share the approval id as correlation id, and each carries an idempotency key so a retry returns
 * the first entry instead of a second one. {@link #refused} records the executor saying no.
 *
 * <p>No personal data and no secrets: approvers appear as their JWT {@code sub}; the rule
 * document is represented by the rule id, not by its text. {@code occurredAt} is the time of the
 * event (the executor's injected {@link Clock} for its own steps, the provider's event time for
 * {@link #executed}), never the time of the write.
 */
@Component
public class ArchiveAudit {

    /** The service's own identity in the actor field. */
    public static final String ACTOR_SERVICE = "da-autoarchival";

    private final ObjectProvider<AuditLogWriter> writer;
    private final Clock clock;

    /**
     * Creates the auditor.
     *
     * @param writer the audit writer; resolved on first use so the service starts without a database
     * @param clock  the injected clock
     */
    public ArchiveAudit(ObjectProvider<AuditLogWriter> writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * Appends the intent before the provider call.
     *
     * @param rule     the rule row
     * @param approval the verified approval
     * @param actor    who asked: the person for a REST request, otherwise the service
     * @return the entry
     * @throws AuditException when the append fails; the caller must not run the action
     */
    public AuditEntry requested(ArchiveRuleEntity rule, ArchiveApproval approval, AuditActor actor) {
        return append("retire.archive.requested", AuditOutcome.REQUESTED, clock.instant(), actor, rule,
                rule.getApprovalId() + ":requested", details(rule, approval, null));
    }

    /**
     * Appends that the rule or the direct change is in place.
     *
     * @param rule     the rule row
     * @param approval the verified approval
     * @param extra    further facts (rule counts, alreadyPresent)
     * @return the entry
     */
    public AuditEntry applied(ArchiveRuleEntity rule, ArchiveApproval approval, Map<String, Object> extra) {
        return append("retire.archive.rule_applied", AuditOutcome.SUCCEEDED, clock.instant(),
                AuditActor.service(ACTOR_SERVICE), rule, rule.getApprovalId() + ":applied",
                details(rule, approval, extra));
    }

    /**
     * Appends that the rule document was produced and not applied.
     *
     * @param rule     the rule row
     * @param approval the verified approval
     * @return the entry
     */
    public AuditEntry rendered(ArchiveRuleEntity rule, ArchiveApproval approval) {
        return append("retire.archive.rule_rendered", AuditOutcome.SUCCEEDED, clock.instant(),
                AuditActor.service(ACTOR_SERVICE), rule, rule.getApprovalId() + ":rendered",
                details(rule, approval, Map.of("applied", false)));
    }

    /**
     * Appends a failure after the intent.
     *
     * @param rule     the rule row
     * @param approval the verified approval
     * @param reason   a machine code
     * @return the entry
     */
    public AuditEntry failed(ArchiveRuleEntity rule, ArchiveApproval approval, String reason) {
        return append("retire.archive.failed", AuditOutcome.FAILED, clock.instant(),
                AuditActor.service(ACTOR_SERVICE), rule, rule.getApprovalId() + ":failed",
                details(rule, approval, Map.of("reason", reason)));
    }

    /**
     * Appends the provider evidence that the transition happened. Business time is the provider's.
     *
     * @param rule      the rule row, already holding the evidence columns
     * @param approval  the approval, or {@code null} when it is not at hand (the row names it)
     * @return the entry
     */
    public AuditEntry executed(ArchiveRuleEntity rule, ArchiveApproval approval) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("evidenceEventId", rule.getEvidenceEventId());
        extra.put("evidenceOperation", rule.getEvidenceOperation());
        extra.put("evidenceKind", rule.getEvidenceKind());
        return append("retire.archive.executed", AuditOutcome.SUCCEEDED, rule.getEvidenceEventTime(),
                AuditActor.service(ACTOR_SERVICE), rule, rule.getApprovalId() + ":executed",
                details(rule, approval, extra));
    }

    /**
     * Appends that a rule was taken out (a restore request on a rule that still waits).
     *
     * @param rule  the rule row
     * @param actor who asked
     * @return the entry
     */
    public AuditEntry removed(ArchiveRuleEntity rule, AuditActor actor) {
        return removed(rule, actor, null);
    }

    /**
     * Appends that DLAP stopped managing a rule, and whether the provider rule stayed in place (Q-608).
     *
     * @param rule  the rule row
     * @param actor who asked
     * @param extra further facts, for example {@code providerRuleKept}; may be {@code null}
     * @return the entry
     */
    public AuditEntry removed(ArchiveRuleEntity rule, AuditActor actor, Map<String, Object> extra) {
        return append("retire.archive.rule_removed", AuditOutcome.SUCCEEDED, clock.instant(), actor, rule,
                rule.getApprovalId() + ":removed", details(rule, null, extra));
    }

    /**
     * Appends the intent to reset a row stuck in {@code EXECUTING} (Q-669), before the row changes. A failed append
     * means the reset does not run.
     *
     * @param rule  the rule row
     * @param actor the administrator
     * @param extra what the provider read showed and what the reset will do
     * @return the entry
     * @throws AuditException when the append fails
     */
    public AuditEntry resetRequested(ArchiveRuleEntity rule, AuditActor actor, Map<String, Object> extra) {
        return append("retire.archive.reset_requested", AuditOutcome.REQUESTED, clock.instant(), actor, rule,
                rule.getApprovalId() + ":reset-requested", details(rule, null, extra));
    }

    /**
     * Appends the outcome of a reset: the row left {@code EXECUTING}.
     *
     * @param rule  the rule row after the reset
     * @param actor the administrator
     * @param extra the new status and the reason
     * @return the entry
     */
    public AuditEntry reset(ArchiveRuleEntity rule, AuditActor actor, Map<String, Object> extra) {
        return append("retire.archive.rule_reset", AuditOutcome.SUCCEEDED, clock.instant(), actor, rule,
                rule.getApprovalId() + ":reset", details(rule, null, extra));
    }

    /**
     * Appends that a reset changed nothing because the row was no longer {@code EXECUTING}.
     *
     * @param rule  the rule row as it now stands
     * @param actor the administrator
     * @return the entry
     */
    public AuditEntry resetNotApplied(ArchiveRuleEntity rule, AuditActor actor) {
        return append("retire.archive.rule_reset", AuditOutcome.FAILED, clock.instant(), actor, rule,
                rule.getApprovalId() + ":reset-not-applied",
                details(rule, null, Map.of("reason", "ROW_CHANGED", "status", rule.getStatus())));
    }

    /**
     * Appends that the executor refused an action. The target is the asset URI when one is known.
     *
     * @param key       what makes a repeat the same refusal: the approval id, the fire id or the event id
     * @param assetUri  the asset's storage URI or id, may be {@code null}
     * @param reason    a machine code
     * @param detail    a sentence without secrets
     * @param approvalId the approval id when the action carried one, else {@code null}
     * @param taskId    the task, may be {@code null}
     * @return the entry
     */
    public AuditEntry refused(String key, String assetUri, String reason, String detail, String approvalId,
            String taskId) {
        return refused(key, assetUri, reason, detail, approvalId, taskId, AuditActor.service(ACTOR_SERVICE));
    }

    /**
     * Appends that an action was refused, naming who caused the refusal (for example the operator who
     * rejected the task).
     *
     * @param key       what makes a repeat the same refusal
     * @param assetUri  the asset's storage URI or id, may be {@code null}
     * @param reason    a machine code
     * @param detail    a sentence without secrets
     * @param approvalId the approval id when the action carried one, else {@code null}
     * @param taskId    the task, may be {@code null}
     * @param actor     who refused
     * @return the entry
     */
    public AuditEntry refused(String key, String assetUri, String reason, String detail, String approvalId,
            String taskId, AuditActor actor) {
        AuditEvent.Builder b = AuditEvent.builder("retire.archive.refused")
                .occurredAt(clock.instant())
                .actor(actor)
                .outcome(AuditOutcome.DENIED)
                .correlationId(clean(approvalId != null ? approvalId : key))
                .idempotencyKey(clean(key + ":refused"))
                .detail("reason", reason)
                .detail("message", cut(detail, 1000));
        if (assetUri != null && !assetUri.isBlank()) {
            b.target(AuditTarget.asset(assetUri));
        }
        if (approvalId != null) {
            b.detail("approvalId", approvalId);
        }
        if (taskId != null) {
            b.detail("taskId", taskId);
        }
        return write(b.build());
    }

    /**
     * Appends that an identical live row already holds a GCS scope, so a new fire or request for it has nothing to
     * plan (Q-711). It is not a refusal. One record exists per holding row and asset: the event is built only from
     * the holding row, so a later fire for the same scope appends the identical event and the idempotency key returns
     * the first entry. The event time is the holding row's creation time, the moment the scope began to be held.
     *
     * @param assetUri       the asset's storage URI, may be {@code null}
     * @param assetId        the catalog asset id
     * @param holding        the live row that holds the scope
     * @return the entry
     */
    public AuditEntry alreadyHeld(String assetUri, String assetId, ArchiveRuleEntity holding) {
        AuditEvent.Builder b = AuditEvent.builder("retire.archive.already_held")
                .occurredAt(holding.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .actor(AuditActor.service(ACTOR_SERVICE))
                .outcome(AuditOutcome.SUCCEEDED)
                .correlationId(clean(holding.getApprovalId()))
                .idempotencyKey(clean(holding.getRuleId() + ":already-held:" + assetId))
                .detail("holdingRuleId", holding.getRuleId())
                .detail("holdingApprovalId", holding.getApprovalId())
                .detail("holdingTaskId", holding.getTaskId())
                .detail("provider", holding.getProvider())
                .detail("bucket", holding.getScopeBucket())
                .detail("prefix", holding.getScopePrefix())
                .detail("nativeClass", holding.getNativeClass())
                .detail("afterDays", holding.getAfterDays())
                .detail("assetId", assetId);
        if (assetUri != null && !assetUri.isBlank()) {
            b.target(AuditTarget.asset(assetUri));
        }
        return write(b.build());
    }

    /**
     * Appends a restore step: {@code requested}, {@code restored} or {@code restore_failed}.
     *
     * @param step     one of {@code requested}, {@code restored}, {@code restore_failed}
     * @param taskId   the task
     * @param assetUri the asset's storage URI
     * @param outcome  the outcome
     * @param actor    who asked
     * @param tier     the tier restored to
     * @return the entry
     */
    public AuditEntry restore(String step, String taskId, String assetUri, AuditOutcome outcome, AuditActor actor,
            String tier) {
        AuditEvent.Builder b = AuditEvent.builder("retire.archive.restore_" + step)
                .occurredAt(clock.instant())
                .actor(actor)
                .outcome(outcome)
                .correlationId(clean(taskId))
                .idempotencyKey(clean(taskId + ":restore:" + step + ":" + assetUri.hashCode()))
                .detail("taskId", taskId)
                .detail("tier", tier == null ? "" : tier);
        b.target(AuditTarget.asset(assetUri));
        return write(b.build());
    }

    private AuditEntry append(String type, AuditOutcome outcome, Instant occurredAt, AuditActor actor,
            ArchiveRuleEntity rule, String idempotencyKey, Map<String, Object> details) {
        AuditEvent.Builder b = AuditEvent.builder(type)
                .occurredAt(occurredAt)
                .actor(actor)
                .target(AuditTarget.asset(rule.getAssetUri()))
                .outcome(outcome)
                .correlationId(rule.getApprovalId())
                .idempotencyKey(idempotencyKey);
        details.forEach(b::detail);
        return write(b.build());
    }

    private AuditEntry write(AuditEvent event) {
        AuditLogWriter w = writer.getIfAvailable();
        if (w == null) {
            throw new AuditException("the audit log writer is not configured", null);
        }
        return w.append(AuditStreams.RETIRE_ARCHIVE, event);
    }

    private static Map<String, Object> details(ArchiveRuleEntity rule, ArchiveApproval approval,
            Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approvalId", rule.getApprovalId());
        d.put("ruleId", rule.getRuleId());
        d.put("taskId", rule.getTaskId());
        d.put("mode", rule.getMode());
        d.put("provider", rule.getProvider());
        d.put("bucket", rule.getScopeBucket());
        d.put("prefix", rule.getScopePrefix());
        d.put("nativeClass", rule.getNativeClass());
        d.put("afterDays", rule.getAfterDays());
        d.put("mock", rule.isMock());
        if (rule.getFireId() != null) {
            d.put("fireId", rule.getFireId());
        }
        if (approval != null) {
            List<String> approvers = new ArrayList<>(approval.approvers());
            d.put("approvers", approvers);
            d.put("approvalStatus", approval.status());
            d.put("breakGlass", approval.breakGlass());
        }
        if (extra != null) {
            d.putAll(extra);
        }
        return d;
    }

    private static String clean(String v) {
        if (v == null) {
            return null;
        }
        String s = v.replaceAll("[^!-~]", "_");
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    private static String cut(String v, int max) {
        return v == null ? "" : v.length() > max ? v.substring(0, max) : v;
    }
}
