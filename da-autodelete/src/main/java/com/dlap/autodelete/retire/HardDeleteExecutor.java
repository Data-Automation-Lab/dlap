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
import com.dlap.autodelete.dto.ApprovalCreateRequest;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.ContainerRuleAge;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.repository.RetireDispositionRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.service.storage.CloudDeletionServiceFactory;
import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.audit.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves one claimed soft-delete row forward (RET-02, decision D20). The sweeper calls it inside
 * the transaction that holds the row lock ({@code FOR UPDATE SKIP LOCKED}).
 *
 * <ul>
 *   <li><b>{@code SOFT_DELETED}, window ended.</b> Plans the deletion (refusing a kind nothing can
 *       delete, a bucket, prefix or container rule that is not age-based, and a versioned bucket; RET-06),
 *       then asks the policy engine for a {@code HARD_DELETE} approval with at least
 *       {@code dlap.autodelete.hard-delete-required-approvals} (never fewer than 2) approvers, as the
 *       service account and, for a task, on behalf of its submitter so the submitter cannot approve.</li>
 *   <li><b>{@code HARD_DELETE_PENDING_APPROVAL}.</b> Re-reads the approval. Still pending: looks again
 *       later. Rejected or expired: refused. Executable: every rule again, with fresh facts:
 *       {@link ApprovalVerifier} (class {@code HARD_DELETE}, 2 distinct approvers, nobody excluded),
 *       not on legal hold, read coverage {@code FULL}, canonical URI agreed with the catalog, and a
 *       plan. Then, under dry run (the default, decision D23), the plan is recorded and nothing is
 *       applied; otherwise the intent is audited, the native rule is applied (or the engine deletes a
 *       single object) and the row waits for the provider's proof.</li>
 *   <li><b>{@code AWAITING_PROOF}, proof overdue.</b> Marks the disposition {@code PROOF_OVERDUE} and
 *       audits it; the row keeps listening for the proof.</li>
 *   <li><b>{@code AWAITING_PROOF}, outcome record pending (Q-633).</b> When the provider call succeeded but
 *       the {@code retire.delete.apply} SUCCEEDED append failed, the disposition is saved as applied with
 *       {@code success_audit_pending}; the next sweep writes the missing record. A disposition that was
 *       applied ({@code applied_at} set) is never applied again, whatever its state (idempotency by
 *       disposition id, Q-683).</li>
 * </ul>
 * A remote call without an answer leaves the row for a later sweep ({@code next_check_at}).
 */
@Component
public class HardDeleteExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(HardDeleteExecutor.class);

    /** Stamped in {@code last_error} of a row that completed under dry run. */
    public static final String DRY_RUN_MARKER = "DRY-RUN: no cloud call";

    private final SoftDeleteRecordRepository records;
    private final RetireDispositionRepository dispositions;
    private final ApprovalClient approvals;
    private final CatalogFactsClient catalog;
    private final DispositionPlanner planner;
    private final CloudDeletionServiceFactory engines;
    private final RetireAuditLog audit;
    private final RetireProperties properties;
    private final Clock clock;

    /**
     * Creates the executor.
     *
     * @param records      the soft-delete rows
     * @param dispositions the dispositions
     * @param approvals    the approval API
     * @param catalog      the catalog
     * @param planner      the planner
     * @param engines      the engine's delete services
     * @param audit        the audit log
     * @param properties   the settings
     * @param clock        the clock
     */
    public HardDeleteExecutor(SoftDeleteRecordRepository records, RetireDispositionRepository dispositions,
            ApprovalClient approvals, CatalogFactsClient catalog, DispositionPlanner planner,
            CloudDeletionServiceFactory engines, RetireAuditLog audit, RetireProperties properties, Clock clock) {
        this.records = records;
        this.dispositions = dispositions;
        this.approvals = approvals;
        this.catalog = catalog;
        this.planner = planner;
        this.engines = engines;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Handles one claimed row.
     *
     * @param row the row, locked by the caller's transaction
     */
    public void handle(SoftDeleteRecordEntity row) {
        Instant now = clock.instant();
        switch (row.getStatus()) {
            case SoftDeleteRecordEntity.SOFT_DELETED -> requestHardDeleteApproval(row, now);
            case SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL -> decide(row, now);
            case SoftDeleteRecordEntity.AWAITING_PROOF -> awaitingProof(row, now);
            default -> LOG.debug("Record {} in state {} needs nothing from the sweeper", row.getId(), row.getStatus());
        }
    }

    private void requestHardDeleteApproval(SoftDeleteRecordEntity row, Instant now) {
        Optional<CanonicalAssetId> canonical = CanonicalTarget.parse(row.getAssetUri());
        if (canonical.isEmpty()) {
            refuse(row, now, RefusalReason.URI_UNRESOLVABLE, "the record has no canonical asset URI", Map.of());
            return;
        }
        Optional<DispositionPlanner.Plan> plan = planner.plan(canonical.get(), row.getCloudProvider(), row.getId(),
                row.getScheduledHardDeleteAt());
        if (plan.isEmpty()) {
            refuse(row, now, RefusalReason.NO_EXECUTOR, "no native rule and no engine delete for "
                    + canonical.get().kind(), Map.of());
            return;
        }
        // RET-06: a container rule must be age-based and its bucket unversioned before anyone is asked to approve.
        Map<String, Object> planning = new LinkedHashMap<>();
        if (!containerRuleAllowed(row, now, plan.get(), planning)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("softDeleteRecordId", row.getId().toString());
        params.put("mode", plan.get().mode());
        params.put("platform", plan.get().platform());
        params.put("windowDays", row.getWindowDays());
        params.put("dryRun", properties.dryRunCloudDelete());
        if (plan.get().ruleId() != null) {
            params.put("ruleId", plan.get().ruleId());
        }
        String reason = "Hard delete of " + row.getAssetUri() + " at the end of its " + row.getWindowDays()
                + "-day soft-delete window (record " + row.getId() + ", " + plan.get().mode() + " "
                + plan.get().platform() + ")";
        ApprovalCreateRequest request = new ApprovalCreateRequest(ApprovalVerifier.HARD_DELETE, "delete",
                row.getAssetId(), row.getAssetUri(), row.getCloudProvider(), row.getServiceType(), params, reason,
                properties.hardDeleteRequiredApprovals(), "retire.delete:hard:" + row.getId(),
                SoftDeleteAdmission.ORIGIN_TASK.equals(row.getOrigin()) ? row.getRequestedBy() : null);
        ApprovalViewDTO view;
        try {
            view = approvals.create(request);
        } catch (RetireRemoteException e) {
            if (e.isRetryable()) {
                later(row, now, properties.sweep().retryBackoff(), e.getMessage());
                return;
            }
            refuse(row, now, RefusalReason.REMOTE_REFUSED, e.getMessage(), Map.of());
            return;
        }
        row.setHardDeleteApprovalId(view.id());
        row.setHardDeleteRequestedAt(now);
        row.setStatus(SoftDeleteRecordEntity.HARD_DELETE_PENDING_APPROVAL);
        row.setNextCheckAt(now.plus(properties.sweep().approvalPollInterval()));
        row.setLastError(null);
        row.setUpdatedAt(now);
        records.saveAndFlush(row);
        Map<String, Object> d = base(row);
        d.put("hardDeleteApprovalId", view.id());
        d.put("requiredApprovals", (long) properties.hardDeleteRequiredApprovals());
        d.put("mode", plan.get().mode());
        d.put("platform", plan.get().platform());
        d.putAll(planning);
        audit.record(RetireAuditLog.HARD_DELETE_REQUESTED, now, RetireAuditLog.SERVICE, row.getAssetUri(),
                AuditOutcome.REQUESTED, row.getId().toString(), d);
    }

    private void decide(SoftDeleteRecordEntity row, Instant now) {
        ApprovalViewDTO view;
        try {
            view = approvals.read(row.getHardDeleteApprovalId()).orElse(null);
        } catch (RetireRemoteException e) {
            if (e.isRetryable()) {
                later(row, now, properties.sweep().retryBackoff(), e.getMessage());
                return;
            }
            refuse(row, now, RefusalReason.REMOTE_REFUSED, e.getMessage(), Map.of());
            return;
        }
        if (view != null && "PENDING".equalsIgnoreCase(view.status())) {
            later(row, now, properties.sweep().approvalPollInterval(), null);
            return;
        }
        Set<String> extraExcluded = SoftDeleteAdmission.ORIGIN_TASK.equals(row.getOrigin())
                && row.getRequestedBy() != null ? Set.of(row.getRequestedBy()) : Set.of();
        ApprovalVerifier.Verdict verdict = ApprovalVerifier.verify(new ApprovalVerifier.Expectation(
                row.getHardDeleteApprovalId(), row.getAssetId(), Set.of(ApprovalVerifier.HARD_DELETE), null,
                extraExcluded), null, view);
        if (!verdict.allowed()) {
            refuse(row, now, verdict.reason(), verdict.detail(), Map.of());
            return;
        }

        AssetFacts facts;
        try {
            facts = catalog.read(row.getAssetId()).orElse(null);
        } catch (RetireRemoteException e) {
            if (e.isRetryable()) {
                later(row, now, properties.sweep().retryBackoff(), e.getMessage());
                return;
            }
            refuse(row, now, RefusalReason.REMOTE_REFUSED, e.getMessage(), Map.of());
            return;
        }
        Map<String, Object> approvalDetails = new LinkedHashMap<>();
        approvalDetails.put("hardDeleteApprovalId", row.getHardDeleteApprovalId());
        approvalDetails.put("approvers", verdict.approvers());
        approvalDetails.put("breakGlass", verdict.breakGlass());
        if (facts == null) {
            refuse(row, now, RefusalReason.ASSET_UNKNOWN, "the catalog does not know the asset", approvalDetails);
            return;
        }
        if (facts.sizeBytes() != null) {
            row.setSizeBytes(facts.sizeBytes());
        }
        if (facts.heldOrUnknown()) {
            refuse(row, now, RefusalReason.LEGAL_HOLD, Boolean.TRUE.equals(facts.legalHold())
                    ? "the asset is on legal hold" : "the catalog did not say the asset is free of legal hold",
                    approvalDetails);
            return;
        }
        if (!facts.fullReadCoverage()) {
            approvalDetails.put("readCoverage", facts.readCoverage() == null ? "UNKNOWN" : facts.readCoverage());
            approvalDetails.put("readCoverageReasons", facts.readCoverageReasons());
            refuse(row, now, RefusalReason.COVERAGE_NOT_FULL, "read coverage is "
                    + (facts.readCoverage() == null ? "unknown" : facts.readCoverage())
                    + "; a destructive action needs FULL", approvalDetails);
            return;
        }
        CanonicalTarget.Resolution resolution = CanonicalTarget.resolve(row.getAssetUri(), facts.assetUri(),
                row.getCloudProvider(), row.getServiceType());
        if (!resolution.ok()) {
            refuse(row, now, resolution.reason(), resolution.detail(), approvalDetails);
            return;
        }
        Optional<DispositionPlanner.Plan> planned = planner.plan(resolution.id(), row.getCloudProvider(), row.getId(),
                now);
        if (planned.isEmpty()) {
            refuse(row, now, RefusalReason.NO_EXECUTOR, "no native rule and no engine delete for "
                    + resolution.id().kind(), approvalDetails);
            return;
        }
        execute(row, now, planned.get(), verdict, approvalDetails);
    }

    private void execute(SoftDeleteRecordEntity row, Instant now, DispositionPlanner.Plan plan,
            ApprovalVerifier.Verdict verdict, Map<String, Object> approvalDetails) {
        boolean dryRun = properties.dryRunCloudDelete();
        RetireDispositionEntity d = dispositions.findBySoftDeleteRecordId(row.getId()).orElseGet(() ->
                RetireDispositionEntity.builder()
                        .id(SoftDeleteAdmission.nameUuid("disposition:" + row.getId()))
                        .softDeleteRecordId(row.getId())
                        .build());
        if (d.getAppliedAt() != null) {
            // Idempotency by disposition id (Q-633, Q-683): this disposition was applied by an earlier attempt, so
            // the provider call is never made a second time, whatever state the disposition is in.
            LOG.warn("Disposition {} of record {} was applied at {} and is {}; not applying again", d.getId(),
                    row.getId(), d.getAppliedAt(), d.getStatus());
            if (RetireDispositionEntity.AWAITING_PROOF.equals(d.getStatus())
                    || RetireDispositionEntity.PROOF_OVERDUE.equals(d.getStatus())) {
                row.setStatus(SoftDeleteRecordEntity.AWAITING_PROOF);
                row.setNextCheckAt(d.isSuccessAuditPending() ? now.plus(properties.sweep().retryBackoff())
                        : d.getProofDueAt());
                row.setUpdatedAt(now);
                records.saveAndFlush(row);
                return;
            }
            Map<String, Object> applied = new LinkedHashMap<>(approvalDetails);
            applied.put("dispositionId", d.getId());
            applied.put("dispositionStatus", d.getStatus());
            applied.put("appliedAt", d.getAppliedAt());
            refuse(row, now, RefusalReason.ALREADY_APPLIED, "disposition " + d.getId() + " was applied at "
                    + d.getAppliedAt() + " and is " + d.getStatus() + "; it is never applied again", applied);
            return;
        }
        // RET-06: the rule is checked again right before the apply (and before a dry run records it): age-based,
        // and the bucket's versioning read afresh (Q-656).
        Map<String, Object> checks = new LinkedHashMap<>();
        if (!containerRuleAllowed(row, now, plan, checks)) {
            return;
        }
        d.setAssetId(row.getAssetId());
        d.setTargetUri(row.getAssetUri());
        d.setCloudProvider(row.getCloudProvider());
        d.setServiceType(row.getServiceType());
        d.setMode(plan.mode());
        d.setPlatform(plan.platform());
        d.setRuleId(plan.ruleId());
        d.setRuleDocument(plan.nativeRule() ? plan.ruleDocument() : engineDocument(plan));
        d.setDryRun(dryRun);
        d.setApprovalId(row.getHardDeleteApprovalId());
        d.setApprovers(RetireAuditLog.join(verdict.approvers()));
        d.setPlannedAt(now);
        d.setEffectiveAt(plan.effectiveAt());
        d.setUpdatedAt(now);

        Map<String, Object> details = base(row);
        details.putAll(approvalDetails);
        details.put("dispositionId", d.getId());
        details.put("mode", plan.mode());
        details.put("platform", plan.platform());
        details.put("ruleId", plan.ruleId());
        details.put("ruleDocumentSha256", sha256(d.getRuleDocument()));
        details.put("dryRun", dryRun);
        details.putAll(checks);

        if (dryRun) {
            d.setStatus(RetireDispositionEntity.DRY_RUN);
            dispositions.saveAndFlush(d);
            row.setStatus(SoftDeleteRecordEntity.DRY_RUN_COMPLETE);
            row.setLastError(DRY_RUN_MARKER);
            row.setNextCheckAt(null);
            row.setUpdatedAt(now);
            records.saveAndFlush(row);
            audit.record(RetireAuditLog.DRY_RUN, now, RetireAuditLog.SERVICE, row.getAssetUri(),
                    AuditOutcome.SUCCEEDED, row.getId().toString(), details);
            LOG.info("DRY-RUN hard delete of {} (record {}): {} {} planned, nothing applied", row.getAssetUri(),
                    row.getId(), plan.mode(), plan.platform());
            return;
        }

        // Intent first: if it cannot be audited, nothing is applied (audit-log.md section 6).
        audit.record(RetireAuditLog.APPLY, now, RetireAuditLog.SERVICE, row.getAssetUri(), AuditOutcome.REQUESTED,
                row.getId().toString(), details);
        String failure = null;
        try {
            if (plan.nativeRule()) {
                plan.applier().apply(plan.rule());
            } else {
                failure = engineDelete(row, plan);
            }
        } catch (NativeRuleException | UnsupportedOperationException | IllegalArgumentException e) {
            failure = e.getMessage();
        }
        Instant applied = clock.instant();
        if (failure != null) {
            d.setStatus(RetireDispositionEntity.FAILED);
            d.setLastError(failure);
            d.setUpdatedAt(applied);
            dispositions.saveAndFlush(d);
            row.setStatus(SoftDeleteRecordEntity.HARD_DELETE_FAILED);
            row.setLastError(failure);
            row.setNextCheckAt(null);
            row.setUpdatedAt(applied);
            records.saveAndFlush(row);
            details.put("detail", failure);
            audit.record(RetireAuditLog.APPLY, applied, RetireAuditLog.SERVICE, row.getAssetUri(), AuditOutcome.FAILED,
                    row.getId().toString(), details);
            LOG.warn("Hard delete of {} (record {}) failed: {}", row.getAssetUri(), row.getId(), failure);
            return;
        }
        Instant start = plan.effectiveAt() != null && plan.effectiveAt().isAfter(applied) ? plan.effectiveAt() : applied;
        Instant due = start.plus(properties.proof().overdueAfter());
        details.put("proofDueAt", due);
        // The provider call has happened. A failed outcome append must not roll that fact back (the next sweep
        // would apply again, Q-633): the disposition is saved as applied, flagged, and the append is retried.
        boolean auditPending = false;
        try {
            audit.record(RetireAuditLog.APPLY, applied, RetireAuditLog.SERVICE, row.getAssetUri(),
                    AuditOutcome.SUCCEEDED, row.getId().toString(), details);
        } catch (RuntimeException e) {
            auditPending = true;
            LOG.error("Applied {} {} for {} (record {}) but the audit append of the outcome failed: {}; it is "
                    + "retried, the rule is not applied again", plan.mode(), plan.platform(), row.getAssetUri(),
                    row.getId(), e.toString());
        }
        d.setStatus(RetireDispositionEntity.AWAITING_PROOF);
        d.setAppliedAt(applied);
        d.setProofDueAt(due);
        d.setSuccessAuditPending(auditPending);
        d.setUpdatedAt(applied);
        dispositions.saveAndFlush(d);
        row.setStatus(SoftDeleteRecordEntity.AWAITING_PROOF);
        row.setLastError(null);
        row.setNextCheckAt(auditPending ? applied.plus(properties.sweep().retryBackoff()) : due);
        row.setUpdatedAt(applied);
        records.saveAndFlush(row);
        LOG.info("Applied {} {} for {} (record {}); executed only when the provider's own event is seen",
                plan.mode(), plan.platform(), row.getAssetUri(), row.getId());
    }

    /**
     * The RET-06 checks of a bucket, prefix or container rule; anything else passes. Refuses a date-based or
     * zero-day rule ({@code DATE_RULE_UNSUPPORTED}, Q-677) and a bucket whose versioning is Enabled or
     * Suspended ({@code VERSIONED_BUCKET_UNSUPPORTED}, Q-656). A versioning answer the platform did not give
     * is never read as unversioned: the row waits for a later sweep, except under dry run, which applies
     * nothing and records the answer as {@code UNKNOWN}.
     *
     * @param row   the row
     * @param now   the sweep time
     * @param plan  the plan
     * @param facts receives {@code bucketVersioning} for the audit record
     * @return true when the plan may go on
     */
    private boolean containerRuleAllowed(SoftDeleteRecordEntity row, Instant now, DispositionPlanner.Plan plan,
            Map<String, Object> facts) {
        if (!plan.nativeRule() || !plan.rule().platform().containerRule()) {
            return true;
        }
        Optional<String> dated = ContainerRuleAge.violation(plan.rule());
        if (dated.isPresent()) {
            refuse(row, now, RefusalReason.DATE_RULE_UNSUPPORTED, dated.get(), Map.of("platform", plan.platform()));
            return false;
        }
        BucketVersioning versioning = plan.applier().versioning(plan.rule());
        if (versioning == null) {
            versioning = BucketVersioning.UNKNOWN;
        }
        facts.put("bucketVersioning", versioning.name());
        if (versioning.versioned()) {
            refuse(row, now, RefusalReason.VERSIONED_BUCKET_UNSUPPORTED, "the bucket's versioning is "
                    + versioning.name() + "; a native delete rule there only adds delete markers, which prove nothing",
                    Map.of("platform", plan.platform(), "bucketVersioning", versioning.name()));
            return false;
        }
        if (versioning == BucketVersioning.UNKNOWN && !properties.dryRunCloudDelete()) {
            later(row, now, properties.sweep().retryBackoff(), "the bucket's versioning could not be read; "
                    + "the rule waits until it can");
            return false;
        }
        return true;
    }

    /** The engine's delete call; null on success, the reason otherwise. */
    private String engineDelete(SoftDeleteRecordEntity row, DispositionPlanner.Plan plan) {
        DeletionResult result = engines.forProvider(row.getCloudProvider()).deleteData(row.getAssetId(),
                plan.engineLocation(), DeletionType.HARD, Map.of("softDeleteRecordId", row.getId().toString(),
                        "approvalId", String.valueOf(row.getHardDeleteApprovalId())));
        if (result != null && result.getStatus() == DeletionStatus.COMPLETED) {
            return null;
        }
        return result != null && result.getErrorMessage() != null ? result.getErrorMessage()
                : "the engine delete did not complete";
    }

    /** An applied row: first the outcome record that did not land (Q-633), else the overdue check. */
    private void awaitingProof(SoftDeleteRecordEntity row, Instant now) {
        Optional<RetireDispositionEntity> d = dispositions.findBySoftDeleteRecordId(row.getId());
        if (d.isPresent() && d.get().isSuccessAuditPending()) {
            retryApplyOutcome(row, d.get(), now);
            return;
        }
        proofOverdue(row, d, now);
    }

    /**
     * Writes the {@code retire.delete.apply} SUCCEEDED record whose append failed right after the provider
     * call. Never calls the provider.
     */
    private void retryApplyOutcome(SoftDeleteRecordEntity row, RetireDispositionEntity d, Instant now) {
        try {
            PendingApplyOutcome.write(audit, dispositions, row, d, now);
        } catch (RuntimeException e) {
            LOG.error("Record {}: the audit append of the apply outcome failed again: {}", row.getId(), e.toString());
            row.setNextCheckAt(now.plus(properties.sweep().retryBackoff()));
            row.setUpdatedAt(now);
            records.saveAndFlush(row);
            return;
        }
        row.setNextCheckAt(d.getProofDueAt());
        row.setUpdatedAt(now);
        records.saveAndFlush(row);
        LOG.info("Record {}: the audit record of the apply outcome was written", row.getId());
    }

    private void proofOverdue(SoftDeleteRecordEntity row, Optional<RetireDispositionEntity> d, Instant now) {
        row.setNextCheckAt(null);
        row.setUpdatedAt(now);
        records.saveAndFlush(row);
        if (d.isEmpty() || !RetireDispositionEntity.AWAITING_PROOF.equals(d.get().getStatus())) {
            return;
        }
        RetireDispositionEntity disposition = d.get();
        disposition.setStatus(RetireDispositionEntity.PROOF_OVERDUE);
        disposition.setUpdatedAt(now);
        dispositions.saveAndFlush(disposition);
        Map<String, Object> details = base(row);
        details.put("dispositionId", disposition.getId());
        details.put("mode", disposition.getMode());
        details.put("platform", disposition.getPlatform());
        details.put("proofDueAt", disposition.getProofDueAt());
        audit.record(RetireAuditLog.PROOF_OVERDUE, now, RetireAuditLog.SERVICE, row.getAssetUri(), AuditOutcome.FAILED,
                row.getId().toString(), details);
        LOG.warn("No provider proof for {} (record {}) by {}; still listening", row.getAssetUri(), row.getId(),
                disposition.getProofDueAt());
    }

    private void refuse(SoftDeleteRecordEntity row, Instant now, RefusalReason reason, String detail,
            Map<String, Object> extra) {
        row.setStatus(SoftDeleteRecordEntity.HARD_DELETE_REFUSED);
        row.setLastError(reason.code() + (detail == null ? "" : ": " + detail));
        row.setNextCheckAt(null);
        row.setUpdatedAt(now);
        records.saveAndFlush(row);
        Map<String, Object> details = base(row);
        details.putAll(extra);
        audit.refused(now, RetireAuditLog.SERVICE, row.getAssetUri(), row.getId().toString(), "hard_delete", reason,
                detail, details);
        LOG.warn("Refused the hard delete of {} (record {}): {} ({})", row.getAssetUri(), row.getId(), reason.code(),
                detail);
    }

    private void later(SoftDeleteRecordEntity row, Instant now, java.time.Duration after, String error) {
        row.setNextCheckAt(now.plus(after));
        if (error != null) {
            row.setLastError(error);
            LOG.warn("Record {} waits: {}", row.getId(), error);
        }
        row.setUpdatedAt(now);
        records.saveAndFlush(row);
    }

    private static Map<String, Object> base(SoftDeleteRecordEntity row) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("recordId", row.getId());
        d.put("assetId", row.getAssetId());
        d.put("origin", row.getOrigin());
        d.put("softDeleteApprovalId", row.getApprovalId());
        d.put("windowDays", row.getWindowDays() == null ? null : row.getWindowDays().longValue());
        return d;
    }

    private static String engineDocument(DispositionPlanner.Plan plan) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("api", "engine:deleteData");
        doc.put("location", plan.engineLocation());
        doc.put("deletionType", "HARD");
        return com.dlap.autodelete.nativerule.RuleDocuments.write(doc);
    }

    static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
