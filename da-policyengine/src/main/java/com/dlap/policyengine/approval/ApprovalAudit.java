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

package com.dlap.policyengine.approval;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.AuditTarget;

/**
 * Writes approval events to the tamper-evident audit log, stream {@code govern.approval}
 * (docs/specs/audit-log.md, AUD-01): the withdrawal of a request by a service account (APPR-03) and
 * every decision on a request, approve, reject and break-glass (AUD-02a, Q-687).
 *
 * <p>Each entry is appended last in the transaction of the change it records, through
 * {@link GovernanceAudit}: a failed append throws {@link AuditUnavailableException}, the change rolls
 * back and the caller answers 503 {@code audit-unavailable} (fail closed). The idempotency key names
 * the change ({@code <approvalId>:withdrawn}, {@code <approvalId>:decision:<approver>}), so a retry
 * after an append that was not followed by a commit replays the stored entry and commits with its
 * time (Q-692).
 *
 * <p>No personal data and no free text: people appear as their JWT {@code sub}, and a reason, comment
 * or justification is represented by its SHA-256, so the row can be checked against the chain without
 * the chain holding the text.
 */
@Component
public class ApprovalAudit {

    /** Event type of a withdrawal. */
    public static final String WITHDRAWN_EVENT = "govern.approval.withdrawn";

    /** Event type of an approval or a rejection. */
    public static final String DECIDED_EVENT = "govern.approval.decided";

    /** Event type of a break-glass release. */
    public static final String BREAK_GLASS_EVENT = "govern.approval.break_glass";

    /** Target kind of an approval request. */
    public static final String TARGET_KIND = "APPROVAL_REQUEST";

    /** A subject short and plain enough to appear in an idempotency key as it is. */
    private static final Pattern PLAIN_SUBJECT = Pattern.compile("^[!-~]{1,64}$");

    /** What a replayed withdrawal must share with the stored entry besides the always compared members. */
    private static final List<String> WITHDRAWAL_IDENTITY =
            List.of("/details/approvalId", "/details/onBehalfOf", "/details/reasonSha256");

    /** What a replayed decision must share with the stored entry besides the always compared members. */
    private static final List<String> DECISION_IDENTITY =
            List.of("/details/approvalId", "/details/decisionId", "/details/decision", "/details/commentSha256");

    private final GovernanceAudit audit;

    /**
     * Creates the auditor.
     *
     * @param audit the policy engine's audit log access
     */
    @Autowired
    public ApprovalAudit(GovernanceAudit audit) {
        this.audit = audit;
    }

    /**
     * Creates the auditor with a writer and no replay lookup.
     *
     * @param writer the audit writer, resolved on first use
     */
    public ApprovalAudit(ObjectProvider<AuditLogWriter> writer) {
        this(new GovernanceAudit(writer));
    }

    /**
     * The idempotency key of the withdrawal of a request.
     *
     * @param approvalId the request
     * @return {@code <approvalId>:withdrawn}
     */
    public static String withdrawalKey(UUID approvalId) {
        return approvalId + ":withdrawn";
    }

    /**
     * The idempotency key of one person's decision on a request; each person decides a request once.
     *
     * @param approvalId  the request
     * @param approverSub the decider's JWT {@code sub}
     * @return {@code <approvalId>:decision:<sub>}, the sub replaced by {@code sha256-<32 hex>} when it is
     *         longer than 64 characters or not printable ASCII without spaces
     */
    public static String decisionKey(UUID approvalId, String approverSub) {
        String who = PLAIN_SUBJECT.matcher(approverSub).matches() ? approverSub
                : "sha256-" + GovernanceAudit.sha256(approverSub).substring(0, 32);
        return approvalId + ":decision:" + who;
    }

    /**
     * The id of one person's decision on a request: name-based, so a retried decision carries the id of
     * the entry it replays.
     *
     * @param approvalId  the request
     * @param approverSub the decider's JWT {@code sub}
     * @return the id
     */
    public static UUID decisionId(UUID approvalId, String approverSub) {
        return UUID.nameUUIDFromBytes(("dlap-approval-decision\n" + approvalId + "\n" + approverSub)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The withdrawal entry an earlier attempt left in the chain, if any.
     *
     * @param approvalId the request
     * @return the stored entry
     * @throws AuditUnavailableException when the audit database cannot be read
     */
    public Optional<AuditEntry> priorWithdrawal(UUID approvalId) {
        return audit.prior(AuditStreams.GOVERN_APPROVAL, withdrawalKey(approvalId));
    }

    /**
     * The decision entry an earlier attempt of this person left in the chain, if any.
     *
     * @param approvalId  the request
     * @param approverSub the decider's JWT {@code sub}
     * @return the stored entry
     * @throws AuditUnavailableException when the audit database cannot be read
     */
    public Optional<AuditEntry> priorDecision(UUID approvalId, String approverSub) {
        return audit.prior(AuditStreams.GOVERN_APPROVAL, decisionKey(approvalId, approverSub));
    }

    /**
     * Appends that a service account withdrew a pending request; an identical earlier entry is returned.
     *
     * @param request the request as it is after the withdrawal
     * @return the stored entry
     * @throws AuditUnavailableException when the append fails or no writer is configured
     */
    public AuditEntry withdrawn(ApprovalRequest request) {
        return withdrawn(request, Optional.empty());
    }

    /**
     * Appends that a service account withdrew a pending request, or replays the entry of an earlier
     * attempt of the same withdrawal.
     *
     * @param request the request as it is after the withdrawal
     * @param prior   the result of {@link #priorWithdrawal}
     * @return the stored entry
     * @throws AuditUnavailableException when the append fails, no writer is configured, or the prior entry
     *                                   records another withdrawal (another reason, person or principal)
     */
    public AuditEntry withdrawn(ApprovalRequest request, Optional<AuditEntry> prior) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvalId", request.id().toString());
        details.put("actionClass", request.actionClass().name());
        details.put("actionType", request.actionType());
        details.put("assetId", request.assetId().toString());
        details.put("previousStatus", ApprovalStatus.PENDING.name());
        details.put("requiredApprovals", request.requiredApprovals());
        details.put("approvals", request.approvals());
        details.put("onBehalfOf", request.onBehalfOf());
        details.put("reasonSha256", sha256(request.withdrawReason()));
        AuditEvent event = new AuditEvent(WITHDRAWN_EVENT, request.withdrawnAt(),
                AuditActor.service(request.withdrawnBy()), AuditTarget.of(TARGET_KIND, request.id().toString()),
                AuditOutcome.SUCCEEDED, request.id().toString(), withdrawalKey(request.id()), details);
        return audit.record(AuditStreams.GOVERN_APPROVAL, event, prior, WITHDRAWAL_IDENTITY);
    }

    /**
     * Appends a decision on a request (approve, reject or break-glass), or replays the entry of an earlier
     * attempt of the same decision. The entry holds what the person decided, not the state the request
     * reached: the approvals already counted can change between two attempts, the decision cannot. The
     * resulting state is on the request row and in its {@code DECIDED} event.
     *
     * @param request  the request the decision is on
     * @param decision the decision row
     * @param prior    the result of {@link #priorDecision}
     * @return the stored entry
     * @throws AuditUnavailableException when the append fails, no writer is configured, or the prior entry
     *                                   records another decision (another kind or comment)
     */
    public AuditEntry decided(ApprovalRequest request, ApprovalDecision decision, Optional<AuditEntry> prior) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvalId", request.id().toString());
        details.put("decisionId", decision.id().toString());
        details.put("decision", decision.decision().name());
        details.put("actionClass", request.actionClass().name());
        details.put("actionType", request.actionType());
        details.put("assetId", request.assetId().toString());
        details.put("requiredApprovals", request.requiredApprovals());
        details.put("origin", request.origin());
        details.put("commentSha256", sha256(decision.comment()));
        String type = decision.decision() == DecisionKind.BREAK_GLASS ? BREAK_GLASS_EVENT : DECIDED_EVENT;
        AuditEvent event = new AuditEvent(type, decision.decidedAt(), AuditActor.user(decision.approverSub()),
                AuditTarget.of(TARGET_KIND, request.id().toString()), AuditOutcome.SUCCEEDED,
                request.id().toString(), decisionKey(request.id(), decision.approverSub()), details);
        return audit.record(AuditStreams.GOVERN_APPROVAL, event, prior, DECISION_IDENTITY);
    }

    /**
     * Hex SHA-256 of a text, UTF-8.
     *
     * @param text the text
     * @return 64 lower-case hex characters
     */
    static String sha256(String text) {
        return GovernanceAudit.sha256(text);
    }

    /**
     * Derives the {@code dlap_common} URL from the service's own database URL.
     *
     * @param configured the explicit URL, or empty
     * @param mainUrl    the service's JDBC URL
     * @return the URL of the {@code dlap_common} database on the same server
     */
    static String commonUrl(String configured, String mainUrl) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (mainUrl == null || mainUrl.isBlank()) {
            return "jdbc:postgresql://localhost:5432/dlap_common";
        }
        int query = mainUrl.indexOf('?');
        String base = query < 0 ? mainUrl : mainUrl.substring(0, query);
        String tail = query < 0 ? "" : mainUrl.substring(query);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + "dlap_common" + tail;
    }
}
