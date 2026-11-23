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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.dlap.autoarchival.approval.ApprovalRefusedException;
import com.dlap.autoarchival.approval.ApprovalVerifier;
import com.dlap.autoarchival.approval.ArchiveApproval;
import com.dlap.autoarchival.audit.ArchiveAudit;
import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlan;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.rule.RuleScope;
import com.dlap.autoarchival.rule.TierResolver;
import com.dlap.autoarchival.service.IArchivalConfigService;
import com.dlap.autoarchival.service.storage.ArchivalResult;
import com.dlap.autoarchival.service.storage.ICloudStorageService;
import com.dlap.autoarchival.service.storage.OperationStatus;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditException;

/**
 * Runs one approved archive action on the provider's native primitive and writes every step to the
 * audit log (RET-03, decision D20; docs/specs/approvals.md section 9).
 *
 * <h3>Two steps</h3>
 * <ol>
 *   <li>{@link #prepare}: plans the rule (tier, age, prefix, limits) and stores a {@code PLANNED}
 *       row keyed by the approval id. The same approval or the same policy fire always finds the
 *       same row, so a redelivery is the same decision (section 9, rule 5). Nothing is sent to the
 *       provider.</li>
 *   <li>{@link #execute}: re-reads the approval from the policy engine (rule 3) and checks that it was given for
 *       this place, prefix, tier and age and not only this asset id, claims the row with one conditional
 *       {@code PLANNED} to {@code EXECUTING} update (Q-609: of several workers racing for one row,
 *       the database lets one through), appends the
 *       {@code REQUESTED} audit record (no record, no action), writes the rule through the provider
 *       and appends the outcome (rule 6). The row ends {@code APPLIED} (written and read back),
 *       {@code RENDERED} (the provider has no API we call), {@code FAILED} or {@code REFUSED}.</li>
 * </ol>
 *
 * <p>The executor never sets {@code EXECUTED}. That status means the transition happened, and only
 * a provider log event proves it ({@code ArchiveProofService}).
 *
 * <p>Modes: a bucket or prefix becomes a lifecycle rule the provider runs on its own schedule
 * ({@code NATIVE_RULE}); one object, which a prefix rule could not isolate, gets a direct storage
 * class change ({@code DIRECT_OBJECT}).
 */
@Service
public class ArchiveExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveExecutor.class);

    /**
     * What the caller knows about one archive action.
     *
     * @param taskId       the archival task
     * @param assetId      the catalog asset id
     * @param assetUri     the asset's resolved storage URI
     * @param provider     the asset's provider
     * @param parameters   the action parameters (tier, prefix, afterDays); may be {@code null}
     * @param defaultTier  the configured default tier; may be {@code null}
     * @param approvalId   the approval the action runs under
     * @param fireId       the policy fire id, or {@code null} for an API request
     */
    public record Request(String taskId, String assetId, String assetUri, String provider,
            Map<String, Object> parameters, String defaultTier, String approvalId, String fireId) {
    }

    private final RulePlanner planner;
    private final ArchiveRuleRepository rules;
    private final ApprovalVerifier verifier;
    private final ArchiveAudit audit;
    private final Clock clock;
    private final List<ICloudStorageService> storageServices;
    private final IArchivalConfigService config;

    /**
     * Creates the executor.
     *
     * @param planner         plans rules and checks limits
     * @param rules           the rule rows
     * @param verifier        checks approvals
     * @param audit           the audit log
     * @param clock           the injected clock
     * @param storageServices the direct storage services, for single objects
     * @param config          the archival configuration, for the default tier an approval that names none is bound to
     */
    public ArchiveExecutor(RulePlanner planner, ArchiveRuleRepository rules, ApprovalVerifier verifier,
            ArchiveAudit audit, Clock clock, List<ICloudStorageService> storageServices,
            IArchivalConfigService config) {
        this.planner = planner;
        this.rules = rules;
        this.verifier = verifier;
        this.audit = audit;
        this.clock = clock;
        this.storageServices = storageServices;
        this.config = config;
    }

    /**
     * Plans the action and stores its row, or returns the row an earlier delivery stored.
     *
     * @param req the action
     * @return the row, {@code PLANNED} for a new action
     * @throws RuleException when the plan is refused (invalid tier, limit, unsupported asset); no row is stored
     */
    public ArchiveRuleEntity prepare(Request req) {
        Optional<ArchiveRuleEntity> known = rules.findByApprovalId(req.approvalId());
        if (known.isEmpty() && req.fireId() != null) {
            known = rules.findByFireId(req.fireId());
        }
        if (known.isPresent()) {
            return known.get();
        }
        RulePlan plan = planner.plan(req.provider(), req.assetUri(), req.parameters(), req.defaultTier(),
                req.approvalId(), 0);
        NativeRuleProvider.RuleSpec spec = plan.spec();
        ArchiveRuleEntity row = new ArchiveRuleEntity(spec.ruleId(), req.approvalId(), req.fireId(), req.taskId(),
                req.assetId(), req.assetUri(), spec.scope().provider(),
                spec.scope().singleObject() ? ArchiveRuleEntity.MODE_DIRECT_OBJECT : ArchiveRuleEntity.MODE_NATIVE_RULE,
                spec.scope().bucket(), spec.scope().prefix(), spec.nativeClass(), spec.afterDays(), clock.instant());
        try {
            return rules.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            Optional<ArchiveRuleEntity> same = rules.findByApprovalId(req.approvalId()).or(() -> req.fireId() == null
                    ? Optional.empty() : rules.findByFireId(req.fireId()));
            if (same.isPresent()) {
                return same.get();
            }
            // Q-710: two approvals planned in the same instant both passed the planner's read; the partial unique
            // index on live GCS rows let one insert through. The loser is the same duplicate the planner reports.
            if (ArchiveRuleEntity.MODE_NATIVE_RULE.equals(row.getMode()) && "GCP".equals(row.getProvider())) {
                List<ArchiveRuleEntity> holders = rules.findLiveScope(row.getProvider(), row.getScopeBucket(),
                        row.getScopePrefix(), row.getNativeClass(), row.getAfterDays(), RulePlanner.liveStatuses(),
                        row.getRuleId());
                if (!holders.isEmpty()) {
                    throw RulePlanner.duplicate(holders.get(0), spec.scope(), spec.nativeClass(), spec.afterDays());
                }
            }
            throw e;
        }
    }

    /**
     * Carries out a {@code PLANNED} action. A row in any other status is returned unchanged, so a
     * redelivered event never acts twice, and a row that two workers try at once is claimed by exactly
     * one of them (the other returns the row as it then stands).
     *
     * @param approvalId the approval id of the row
     * @param actor      who is recorded as asking: the person for a REST request, else the service
     * @return the row after the attempt
     * @throws ApprovalRefusedException when the approval could not be checked (transient); the row stays {@code PLANNED}
     */
    public ArchiveRuleEntity execute(String approvalId, AuditActor actor) {
        ArchiveRuleEntity row = rules.findByApprovalId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("no archive action for approval " + approvalId));
        if (!ArchiveRuleEntity.STATUS_PLANNED.equals(row.getStatus())) {
            return row;
        }
        ArchiveApproval approval;
        try {
            approval = verifier.verifyById(row.getApprovalId(), row.getAssetId());
        } catch (ApprovalRefusedException e) {
            if (e.isPending()) {
                return row;
            }
            if (e.isTransient()) {
                // Conditional: the row stays PLANNED for the retry, and a worker that lost the race must not
                // write its old copy over the winner's claim or outcome.
                rules.endIfPlanned(row.getRuleId(), ArchiveRuleEntity.STATUS_PLANNED,
                        ArchiveRuleEntity.clip("approval could not be checked: " + e.getMessage()), clock.instant());
                throw e;
            }
            return refuse(row, e.reason().name(), e.getMessage());
        }
        String mismatch = approvedScopeMismatch(row, approval);
        if (mismatch != null) {
            return refuse(row, ApprovalRefusedException.Reason.ASSET_MISMATCH.name(), mismatch);
        }
        NativeRuleProvider.RuleSpec spec = specOf(row);
        try {
            planner.recheck(spec);
        } catch (RuleException e) {
            return refuse(row, e.reason().name(), e.getMessage());
        }
        if (!claim(row)) {
            LOG.info("archive {} was claimed by another worker; not run twice", row.getRuleId());
            return rules.findById(row.getRuleId()).orElse(row);
        }
        try {
            audit.requested(row, approval, actor);
        } catch (AuditException | IllegalArgumentException e) {
            LOG.error("archive {} not run: the audit log refused the intent record: {}", row.getRuleId(),
                    e.getMessage());
            return endClaim(row, "the audit log is unavailable; nothing was changed");
        }
        return apply(row, spec, approval);
    }

    /** Claims the row for this worker (Q-609); false when another worker already did. */
    private boolean claim(ArchiveRuleEntity row) {
        Instant now = clock.instant();
        if (rules.claimPlanned(row.getRuleId(), now) != 1) {
            return false;
        }
        row.markClaimed(now);
        return true;
    }

    /**
     * Section 9, rule 3 in full: the approval is for this asset id and also for this place and this
     * rule. Where and how the archive runs comes from the policy event (location, prefix, tier, age),
     * which anyone who can write to the topic can forge, so the executor compares it with what the
     * policy engine holds for the approval itself.
     *
     * @return why the stored action is not what the approval covers, or {@code null} when it is
     */
    private String approvedScopeMismatch(ArchiveRuleEntity row, ArchiveApproval approval) {
        if (approval.assetUri() == null || approval.provider() == null) {
            return "approval " + approval.approvalId() + " names no asset location or provider, so it cannot "
                    + "be bound to this archive";
        }
        RuleScope approved;
        try {
            approved = planner.scopeOf(approval.provider(), approval.assetUri(), approval.actionParameters());
        } catch (RuleException e) {
            return "the location the approval was given for cannot be used: " + e.reason();
        }
        RuleScope actual = specOf(row).scope();
        if (!approved.provider().equals(actual.provider()) || !approved.bucket().equals(actual.bucket())
                || !approved.prefix().equals(actual.prefix()) || approved.singleObject() != actual.singleObject()
                || !approved.account().equals(rowAccount(row))) {
            return "approval " + approval.approvalId() + " was given for " + approved.uri()
                    + (approved.singleObject() ? " (one object)" : "") + ", not for the place this archive targets";
        }
        try {
            // The tier the planner would have used: the one the approval names, else the configured default.
            // An approval silent on the tier is therefore bound to the default, not to whatever the event says.
            String named = firstText(approval.actionParameters(), "tier", "targetStorageClass", "targetTier");
            String tier = named != null ? named : defaultTier();
            TierResolver.NativeTier approvedTier = TierResolver.resolve(actual.provider(), tier);
            if (!approvedTier.name().equals(row.getNativeClass())) {
                return "approval " + approval.approvalId() + " was given for tier "
                        + (named != null ? named : "the default tier " + approvedTier.name())
                        + ", not for the storage class this archive writes";
            }
            String days = firstText(approval.actionParameters(), "afterDays");
            int approvedDays = days == null ? approvedTier.minDays() : Integer.parseInt(days.strip());
            if (approvedDays != row.getAfterDays()) {
                return "approval " + approval.approvalId() + " was given for an age of " + approvedDays
                        + " days, not for " + row.getAfterDays();
            }
        } catch (RuleException | NumberFormatException e) {
            return "the tier or age the approval was given for cannot be read";
        }
        return null;
    }

    /** The configured default tier, or {@code null} when unset or unreadable (the resolver then uses its own default). */
    private String defaultTier() {
        try {
            ArchivalConfigDTO cfg = config == null ? null : config.getArchivalConfig();
            return cfg == null ? null : cfg.getDefaultArchivalTier();
        } catch (RuntimeException e) {
            LOG.warn("the archival configuration could not be read; the built-in default tier is used: {}",
                    e.getMessage());
            return null;
        }
    }

    private String rowAccount(ArchiveRuleEntity row) {
        try {
            return planner.scopeOf(row.getProvider(), row.getAssetUri(), null).account();
        } catch (RuleException e) {
            return "";
        }
    }

    private static String firstText(Map<String, Object> params, String... keys) {
        for (String k : keys) {
            Object v = params == null ? null : params.get(k);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
    }

    private ArchiveRuleEntity apply(ArchiveRuleEntity row, NativeRuleProvider.RuleSpec spec, ArchiveApproval approval) {
        NativeRuleProvider provider = planner.providerFor(row.getProvider());
        Instant started = clock.instant();
        try {
            if (ArchiveRuleEntity.MODE_NATIVE_RULE.equals(row.getMode())) {
                NativeRuleProvider.ApplyResult result = provider.apply(spec);
                boolean recorded = finish(row, ArchiveRuleEntity.STATUS_APPLIED, provider.render(spec),
                        provider.isMock(), result.alreadyPresent(), started);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("alreadyPresent", result.alreadyPresent());
                extra.put("rulesBefore", result.rulesBefore());
                extra.put("rulesAfter", result.rulesAfter());
                auditOutcome(() -> audit.applied(row, approval, withRowUpdated(extra, recorded)), row);
                return recorded ? row : current(row);
            }
            boolean recorded = applyDirect(row, spec, provider.isMock(), started);
            auditOutcome(() -> audit.applied(row, approval, withRowUpdated(Map.of("alreadyPresent", false), recorded)),
                    row);
            return recorded ? row : current(row);
        } catch (RuleException e) {
            if (e.reason() == RuleException.Reason.APPLY_NOT_SUPPORTED) {
                boolean recorded = finish(row, ArchiveRuleEntity.STATUS_RENDERED, provider.render(spec),
                        provider.isMock(), false, started);
                auditOutcome(() -> audit.rendered(row, approval), row);
                return recorded ? row : current(row);
            }
            return fail(row, approval, e.reason().name(), e.getMessage());
        } catch (RuntimeException e) {
            return fail(row, approval, "UNEXPECTED", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Applies one object directly; returns whether the {@code APPLIED} outcome was recorded on the row (Q-712). */
    private boolean applyDirect(ArchiveRuleEntity row, NativeRuleProvider.RuleSpec spec, boolean mock, Instant started) {
        ICloudStorageService service = storageServices == null ? null : storageServices.stream()
                .filter(s -> s.supportsCloudProvider(row.getProvider())).findFirst().orElse(null);
        if (service == null) {
            throw new RuleException(RuleException.Reason.UNSUPPORTED_PROVIDER,
                    "no storage service registered for " + row.getProvider());
        }
        ArchivalResult r = service.archiveData(toUuid(row.getAssetId()), row.getAssetUri(), spec.nativeClass(),
                Map.of("dlap.taskId", row.getTaskId(), "dlap.approvalId", row.getApprovalId()));
        if (r == null || r.getStatus() != OperationStatus.COMPLETED) {
            throw new RuleException(RuleException.Reason.PROVIDER_ERROR,
                    r == null ? "the storage service returned nothing" : String.valueOf(r.getErrorMessage()));
        }
        return finish(row, ArchiveRuleEntity.STATUS_APPLIED,
                "{\"action\":\"set-storage-class\",\"storageClass\":\"" + spec.nativeClass() + "\"}", mock, false,
                started);
    }

    /**
     * Records the claimer's own success with one conditional update on {@code EXECUTING} (Q-712). If an
     * administrator reset the row (or anything else changed it) while this worker was only slow, the row keeps
     * its new state and this late outcome is not written over it.
     *
     * @return true when the outcome was recorded; false when the row had left {@code EXECUTING}
     */
    private boolean finish(ArchiveRuleEntity row, String status, String document, boolean mock,
            boolean alreadyPresent, Instant now) {
        if (rules.finishIfExecuting(row.getRuleId(), status, document, mock, alreadyPresent, now) == 1) {
            row.markApplied(status, document, mock, alreadyPresent, now);
            return true;
        }
        LOG.error("archive {}: the provider call finished ({}) but the row had left EXECUTING (it is now {}), so "
                + "the outcome was not written over it; check the provider for the rule and reconcile the row",
                row.getRuleId(), status, current(row).getStatus());
        return false;
    }

    /** The row as the database holds it now; the loaded copy when it cannot be read. */
    private ArchiveRuleEntity current(ArchiveRuleEntity row) {
        return rules.findById(row.getRuleId()).orElse(row);
    }

    private static Map<String, Object> withRowUpdated(Map<String, Object> extra, boolean recorded) {
        if (recorded) {
            return extra;
        }
        Map<String, Object> out = new LinkedHashMap<>(extra);
        out.put("rowUpdated", false);
        return out;
    }

    /** Ends a claimed row as {@code FAILED}; conditional on {@code EXECUTING} (Q-712). */
    private ArchiveRuleEntity endClaim(ArchiveRuleEntity row, String error) {
        Instant now = clock.instant();
        String clipped = ArchiveRuleEntity.clip(error);
        if (rules.failIfExecuting(row.getRuleId(), clipped, now) == 1) {
            row.markFailed(ArchiveRuleEntity.STATUS_FAILED, clipped, now);
            return row;
        }
        LOG.warn("archive {}: its failure ({}) was not recorded, the row had left EXECUTING", row.getRuleId(), error);
        return current(row);
    }

    private ArchiveRuleEntity fail(ArchiveRuleEntity row, ArchiveApproval approval, String reason, String message) {
        LOG.warn("archive {} failed: {} {}", row.getRuleId(), reason, message);
        ArchiveRuleEntity after = endClaim(row, reason + ": " + message);
        auditOutcome(() -> audit.failed(row, approval, reason), row);
        return after;
    }

    private ArchiveRuleEntity refuse(ArchiveRuleEntity row, String reason, String message) {
        Instant now = clock.instant();
        String error = ArchiveRuleEntity.clip(reason + ": " + message);
        if (rules.endIfPlanned(row.getRuleId(), ArchiveRuleEntity.STATUS_REFUSED, error, now) != 1) {
            // Another worker claimed the row (or decided it) while this one was checking; the row is theirs and
            // the rule may be live, so this worker writes neither a status nor a refused record.
            LOG.info("archive {} was claimed by another worker; its refusal here ({}) is not recorded",
                    row.getRuleId(), reason);
            return rules.findById(row.getRuleId()).orElse(row);
        }
        LOG.warn("archive {} refused: {} {}", row.getRuleId(), reason, message);
        row.markFailed(ArchiveRuleEntity.STATUS_REFUSED, error, now);
        auditOutcome(() -> audit.refused(row.getApprovalId(), row.getAssetUri(), reason, message,
                row.getApprovalId(), row.getTaskId()), row);
        return row;
    }

    /** The effect is done: an audit failure here is logged loudly and reconciled from the provider, never undone. */
    private void auditOutcome(Runnable append, ArchiveRuleEntity row) {
        try {
            append.run();
        } catch (AuditException | IllegalArgumentException e) {
            LOG.error("archive {}: the outcome record could not be written to the audit log ({}); the REQUESTED "
                    + "record stands without a final record until it is reconciled", row.getRuleId(), e.getMessage());
        }
    }

    /**
     * Rebuilds the rule request from a stored row.
     *
     * @param row the row
     * @return the rule spec
     */
    public static NativeRuleProvider.RuleSpec specOf(ArchiveRuleEntity row) {
        boolean single = ArchiveRuleEntity.MODE_DIRECT_OBJECT.equals(row.getMode());
        return new NativeRuleProvider.RuleSpec(row.getRuleId(),
                new RuleScope(row.getProvider(), "", row.getScopeBucket(), row.getScopePrefix(), single),
                row.getNativeClass(), row.getAfterDays());
    }

    private static UUID toUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
