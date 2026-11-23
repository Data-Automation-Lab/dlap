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

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.ApprovalViewDTO;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets an approved delete into its soft-delete window, or refuses it (RET-02,
 * docs/specs/approvals.md section 9).
 *
 * <p>The rules, in order; the first that fails refuses the delete, writes a {@code DENIED}
 * audit record and touches nothing:
 * <ol>
 *   <li>the action carries an approval (a policy action without one is refused even when it says
 *       {@code requireApproval: false});</li>
 *   <li>the policy engine's own record of the approval passes {@link ApprovalVerifier}: executable,
 *       the same request, asset and fire, a class that covers the delete, enough distinct
 *       approvers, none of them the requester;</li>
 *   <li>the catalog knows the asset and says it is not on legal hold;</li>
 *   <li>the asset has a canonical URI (ADR-0047) on which the action and the catalog agree.</li>
 * </ol>
 * The window is at least {@code dlap.autodelete.window-floor-days}: a shorter window, a zero
 * window or {@code hardDelete: true} is raised to the floor, so every delete stays recoverable
 * for the floor, and the hard delete at its end needs a HARD_DELETE approval of its own.
 * Read coverage is recorded here and enforced before the hard delete, the step that touches data.
 *
 * <p>A remote call that gives no answer throws {@link RetireRemoteException} (retryable): the
 * Kafka record is redelivered, never acknowledged as allowed.
 */
@Component
public class SoftDeleteAdmission {

    private static final Logger LOG = LoggerFactory.getLogger(SoftDeleteAdmission.class);

    /** Origin of a policy fire. */
    public static final String ORIGIN_POLICY = "POLICY";
    /** Origin of a task API submission. */
    public static final String ORIGIN_TASK = "TASK";

    private final SoftDeleteRecordRepository records;
    private final ApprovalClient approvals;
    private final CatalogFactsClient catalog;
    private final RetireAuditLog audit;
    private final RetireProperties properties;
    private final Clock clock;

    /**
     * Creates the admission.
     *
     * @param records    the soft-delete rows
     * @param approvals  the approval API
     * @param catalog    the catalog
     * @param audit      the audit log
     * @param properties the settings
     * @param clock      the clock
     */
    public SoftDeleteAdmission(SoftDeleteRecordRepository records, ApprovalClient approvals, CatalogFactsClient catalog,
            RetireAuditLog audit, RetireProperties properties, Clock clock) {
        this.records = records;
        this.approvals = approvals;
        this.catalog = catalog;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The outcome.
     *
     * @param recordId the soft-delete record, or null when refused
     * @param reason   why it was refused, or null
     * @param created  true when this call wrote the row
     */
    public record Outcome(UUID recordId, RefusalReason reason, boolean created) {

        /** @return true when the delete is in (or was already in) its window */
        public boolean admitted() {
            return recordId != null;
        }
    }

    /**
     * What to admit.
     *
     * @param recordId            the deterministic record id
     * @param assetId             the catalog asset id
     * @param actionUri           the URI the action names, or null
     * @param provider            the provider the action names, or null
     * @param serviceType         the kind the action names, or null
     * @param origin              {@link #ORIGIN_POLICY} or {@link #ORIGIN_TASK}
     * @param requestedBy         {@code policy:<id>} or the task submitter's JWT subject
     * @param policyId            the policy id, or null
     * @param fireId              the fire id, or null
     * @param taskId              the task id, or null
     * @param approvalId          the approval the action names
     * @param evidence            the Kafka approval block, or null
     * @param requestedWindowDays the window asked for, or null for the default
     * @param hardDeleteAsked     true when the action asked for an immediate hard delete
     * @param auditNote           a note for the row, or null
     * @param extraExcluded       subjects that may not have approved (the task submitter)
     */
    public record Command(UUID recordId, UUID assetId, String actionUri, String provider, String serviceType,
            String origin, String requestedBy, String policyId, UUID fireId, String taskId, UUID approvalId,
            ApprovalEvidence evidence, Integer requestedWindowDays, boolean hardDeleteAsked, String auditNote,
            Set<String> extraExcluded) {
    }

    /**
     * Admits a policy action from {@code dlap.policies.actions}.
     *
     * @param event the decoded {@code PolicyActionRequest}
     * @return the outcome
     * @throws RetireRemoteException when the policy engine or the catalog gave no usable answer
     */
    @Transactional
    public Outcome admitPolicyAction(Map<String, Object> event) {
        Instant now = clock.instant();
        UUID assetId = ApprovalEvidence.uuid(event.get("assetId"));
        String assetUri = str(event.get("assetUri"));
        String fireText = str(event.get("fireId"));
        UUID fireId = ApprovalEvidence.uuid(fireText);
        String policyDbId = str(event.get("policyDbId"));
        String policyId = str(event.get("policyId"));
        String requestedBy = "policy:" + (policyDbId != null ? policyDbId : policyId);
        if (assetId == null) {
            audit.refused(now, RetireAuditLog.SERVICE, assetUri, fireText, "soft_delete", RefusalReason.INVALID,
                    "the action has no catalog asset id", Map.of("policyId", String.valueOf(policyId)));
            return new Outcome(null, RefusalReason.INVALID, false);
        }
        ApprovalEvidence evidence = ApprovalEvidence.from(event.get("approval"));
        if (evidence == null) {
            audit.refused(now, RetireAuditLog.SERVICE, assetUri != null ? assetUri : assetId.toString(), fireText,
                    "soft_delete", RefusalReason.APPROVAL_MISSING,
                    "a destructive action without an approval block is never executed",
                    details(assetId, policyId, fireText, null));
            return new Outcome(null, RefusalReason.APPROVAL_MISSING, false);
        }
        Map<String, Object> params = asMap(event.get("actionParameters"));
        WindowAsk ask = windowAsk(params);
        UUID recordId = nameUuid(fireText != null ? "fire:" + fireText : "approval:" + evidence.approvalId());
        return admit(new Command(recordId, assetId, assetUri, str(event.get("provider")), str(event.get("serviceType")),
                ORIGIN_POLICY, requestedBy, policyId, fireId, null, evidence.approvalId(), evidence, ask.days(),
                ask.hard(), str(params.get("auditNote")), Set.of()));
    }

    /**
     * Admits one asset of a task whose approval the sweeper found executable.
     *
     * @param taskId        the task
     * @param assetId       the asset
     * @param approvalId    the approval
     * @param requesterSub  the task submitter's JWT subject
     * @param windowDays    the window the task asked for, or null
     * @param hardDelete    true when the task asked for a hard delete
     * @param justification the task's justification, or null
     * @return the outcome
     */
    @Transactional
    public Outcome admitTaskAsset(String taskId, UUID assetId, UUID approvalId, String requesterSub,
            Integer windowDays, boolean hardDelete, String justification) {
        UUID recordId = nameUuid("task:" + taskId + ":" + assetId);
        return admit(new Command(recordId, assetId, null, null, null, ORIGIN_TASK, requesterSub, null, null, taskId,
                approvalId, null, windowDays, hardDelete, justification,
                requesterSub == null ? Set.of() : Set.of(requesterSub)));
    }

    /**
     * Applies the rules of the class description.
     *
     * @param cmd what to admit
     * @return the outcome
     */
    Outcome admit(Command cmd) {
        Instant now = clock.instant();
        Optional<SoftDeleteRecordEntity> same = records.findById(cmd.recordId());
        if (same.isPresent()) {
            return new Outcome(same.get().getId(), null, false);
        }
        String correlation = cmd.recordId().toString();
        String target = cmd.actionUri() != null ? cmd.actionUri() : cmd.assetId().toString();
        Map<String, Object> base = details(cmd.assetId(), cmd.policyId(), cmd.fireId() == null ? null
                : cmd.fireId().toString(), cmd.approvalId());
        if (cmd.taskId() != null) {
            base.put("taskId", cmd.taskId());
        }

        Set<String> classes = cmd.hardDeleteAsked() ? Set.of(ApprovalVerifier.HARD_DELETE)
                : Set.of(ApprovalVerifier.SOFT_DELETE, ApprovalVerifier.HARD_DELETE);
        ApprovalViewDTO view;
        try {
            view = approvals.read(cmd.approvalId()).orElse(null);
        } catch (RetireRemoteException e) {
            if (e.isRetryable()) {
                throw e;
            }
            return refuse(now, target, correlation, RefusalReason.REMOTE_REFUSED, e.getMessage(), base);
        }
        ApprovalVerifier.Verdict verdict = ApprovalVerifier.verify(new ApprovalVerifier.Expectation(cmd.approvalId(),
                cmd.assetId(), classes, cmd.fireId() == null ? null : cmd.fireId().toString(), cmd.extraExcluded()),
                cmd.evidence(), view);
        if (!verdict.allowed()) {
            return refuse(now, target, correlation, verdict.reason(), verdict.detail(), base);
        }
        base.put("actionClass", verdict.actionClass());
        base.put("approvers", verdict.approvers());
        base.put("breakGlass", verdict.breakGlass());

        AssetFacts facts;
        try {
            facts = catalog.read(cmd.assetId()).orElse(null);
        } catch (RetireRemoteException e) {
            if (e.isRetryable()) {
                throw e;
            }
            return refuse(now, target, correlation, RefusalReason.REMOTE_REFUSED, e.getMessage(), base);
        }
        if (facts == null) {
            return refuse(now, target, correlation, RefusalReason.ASSET_UNKNOWN,
                    "the catalog does not know asset " + cmd.assetId(), base);
        }
        if (facts.heldOrUnknown()) {
            return refuse(now, target, correlation, RefusalReason.LEGAL_HOLD,
                    Boolean.TRUE.equals(facts.legalHold()) ? "the asset is on legal hold"
                            : "the catalog did not say the asset is free of legal hold", base);
        }
        String provider = cmd.provider() != null ? cmd.provider() : facts.cloudProvider();
        String serviceType = cmd.serviceType() != null ? cmd.serviceType() : facts.serviceType();
        CanonicalTarget.Resolution resolution = CanonicalTarget.resolve(cmd.actionUri(), facts.assetUri(), provider,
                serviceType);
        if (!resolution.ok()) {
            return refuse(now, target, correlation, resolution.reason(), resolution.detail(), base);
        }
        CanonicalAssetId canonical = resolution.id();

        Optional<SoftDeleteRecordEntity> active = records.findFirstByAssetIdAndStatusInOrderByQueuedAtDesc(
                cmd.assetId(), SoftDeleteRecordEntity.ACTIVE);
        if (active.isPresent()) {
            LOG.info("Asset {} already has an active delete (record {}); the new request joins it",
                    cmd.assetId(), active.get().getId());
            return new Outcome(active.get().getId(), null, false);
        }

        int requested = cmd.requestedWindowDays() == null ? properties.softDeleteWindowDays()
                : cmd.requestedWindowDays();
        if (cmd.hardDeleteAsked()) {
            requested = Math.min(requested, 0);
        }
        int window = Math.max(requested, properties.windowFloorDays());
        SoftDeleteRecordEntity row = SoftDeleteRecordEntity.builder()
                .id(cmd.recordId())
                .assetId(cmd.assetId())
                .policyId(cmd.policyId())
                .policyFireId(cmd.fireId())
                .queuedAt(now)
                .scheduledHardDeleteAt(now.plus(Duration.ofDays(window)))
                .status(SoftDeleteRecordEntity.SOFT_DELETED)
                .auditNote(cmd.auditNote())
                .cloudProvider(canonical.cloudProvider() != null ? canonical.cloudProvider()
                        : (provider == null ? "UNKNOWN" : provider.toUpperCase(Locale.ROOT)))
                .assetUri(canonical.uri())
                .serviceType(canonical.serviceType() != null ? canonical.serviceType() : serviceType)
                .origin(cmd.origin())
                .taskId(cmd.taskId())
                .requestedBy(cmd.requestedBy())
                .requestedWindowDays(requested)
                .windowDays(window)
                .approvalId(cmd.approvalId())
                .approvalClass(verdict.actionClass())
                .approvers(RetireAuditLog.join(verdict.approvers()))
                .readCoverage(facts.readCoverage())
                .sizeBytes(facts.sizeBytes())
                .updatedAt(now)
                .build();
        records.saveAndFlush(row);

        base.put("recordId", cmd.recordId());
        base.put("origin", cmd.origin());
        base.put("requestedWindowDays", (long) requested);
        base.put("windowDays", (long) window);
        base.put("windowClamped", window != requested);
        base.put("scheduledHardDeleteAt", row.getScheduledHardDeleteAt());
        base.put("readCoverage", facts.readCoverage() == null ? "UNKNOWN" : facts.readCoverage());
        audit.record(RetireAuditLog.SOFT_DELETED, now, RetireAuditLog.SERVICE, canonical.uri(), AuditOutcome.SUCCEEDED,
                correlation, base);
        LOG.info("Soft-deleted asset {} ({}) as record {}: window {} days (asked {}), approval {}", cmd.assetId(),
                canonical.uri(), cmd.recordId(), window, requested, cmd.approvalId());
        return new Outcome(cmd.recordId(), null, true);
    }

    private Outcome refuse(Instant now, String target, String correlation, RefusalReason reason, String detail,
            Map<String, Object> details) {
        LOG.warn("Refused a delete of {}: {} ({})", target, reason.code(), detail);
        audit.refused(now, RetireAuditLog.SERVICE, target, correlation, "soft_delete", reason, detail, details);
        return new Outcome(null, reason, false);
    }

    private static Map<String, Object> details(UUID assetId, String policyId, String fireId, UUID approvalId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("assetId", assetId);
        d.put("policyId", policyId);
        d.put("fireId", fireId);
        d.put("approvalId", approvalId);
        return d;
    }

    /** What a policy's parameters ask for (policy-dsl.md and approvals.md section 3). */
    record WindowAsk(Integer days, boolean hard) {
    }

    static WindowAsk windowAsk(Map<String, Object> params) {
        boolean hard = Boolean.TRUE.equals(params.get("hardDelete"))
                || "true".equalsIgnoreCase(String.valueOf(params.get("hardDelete")));
        Object raw = params.get("softDeleteWindowDays");
        if (raw == null) {
            return new WindowAsk(null, hard);
        }
        Integer days = null;
        if (raw instanceof Number n) {
            days = n.intValue();
        } else {
            try {
                days = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                // A window that is not a number is a hard delete (approvals.md section 3).
                return new WindowAsk(0, true);
            }
        }
        return new WindowAsk(days, hard || days <= 0);
    }

    /**
     * The deterministic record id of a fire or a task asset.
     *
     * @param key the key
     * @return a name-based UUID
     */
    public static UUID nameUuid(String key) {
        return UUID.nameUUIDFromBytes(("retire.delete:" + key).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
