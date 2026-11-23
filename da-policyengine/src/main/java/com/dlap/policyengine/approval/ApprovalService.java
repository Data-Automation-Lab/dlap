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

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The approval service and its separation-of-duties rules (APPR-01, docs/specs/approvals.md).
 *
 * <ul>
 *   <li>A destructive action waits for N <b>distinct</b> approvers: by default 1 for archive and
 *       soft delete and 2 for hard delete, never fewer than the floor of the class (2 for hard
 *       delete). A policy or an API caller may raise N, never lower it.</li>
 *   <li>Nobody decides a request they asked for or submitted, or whose policy they authored.</li>
 *   <li>The decider is the JWT {@code sub}; every decision carries a comment and is immutable.</li>
 *   <li>A request expires at {@code expiresAt}; an expired request is never executable.</li>
 *   <li>One rejection by an eligible approver ends the request.</li>
 *   <li>A service account withdraws a pending request on behalf of a person (APPR-03, Q-676): a
 *       terminal state that is not a decision, written to the audit log in the same step.</li>
 *   <li>Every decision (approve, reject, break-glass) and every withdrawal is appended to the audit
 *       stream {@code govern.approval} as the last step of its transaction (AUD-02a, Q-687): when the
 *       append fails nothing is committed and the call answers 503 {@code audit-unavailable}. A retry
 *       after an append whose transaction did not commit replays the stored entry and commits with its
 *       time (Q-692).</li>
 *   <li>Break-glass: an administrator releases a request without the quorum, with a
 *       justification of at least 20 characters; it is logged at ERROR, counted and published on
 *       {@code dlap.approvals.break-glass}.</li>
 * </ul>
 *
 * <p>Every state change writes its Kafka messages to {@code approval_outbox} in the same
 * transaction; {@link ApprovalOutboxRelay} sends them. An approved policy fire releases the stored
 * {@code PolicyActionRequest} with {@code requireApproval: false} and its {@code approval} block,
 * unless policy actions are disabled (shadow mode), in which case nothing is released.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** Counter of requests created, tagged {@code actionClass} and {@code origin}. */
    static final String REQUESTED_COUNTER = "policyengine.approvals.requested";
    /** Counter of decisions, tagged {@code decision}. */
    static final String DECISION_COUNTER = "policyengine.approvals.decisions";
    /** Counter of terminal states, tagged {@code status} and {@code actionClass}. */
    static final String RESOLVED_COUNTER = "policyengine.approvals.resolved";
    /** Counter of break-glass releases, tagged {@code actionClass}. The alert rule watches it. */
    static final String BREAK_GLASS_COUNTER = "policyengine.approvals.break_glass";
    /** Counter of withdrawals by service accounts, tagged {@code actionClass}. */
    static final String WITHDRAWN_COUNTER = "policyengine.approvals.withdrawn";
    /** Counter of refused decisions, tagged {@code reason}. */
    static final String REFUSED_COUNTER = "policyengine.approvals.refused";

    /** Shortest reason a withdrawal accepts, in characters. */
    public static final int MIN_WITHDRAW_REASON = 10;

    private static final int EXPIRY_BATCH = 500;
    private static final int MAX_PAGE_SIZE = 200;

    private final ApprovalStore store;
    private final ApprovalProperties properties;
    private final PolicyRepository policies;
    private final PolicyActionProducer producer;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final String actionTopic;
    private final ApprovalAudit audit;

    /**
     * Creates the service without an audit writer, for callers that never withdraw: a withdrawal
     * fails closed because nothing can record it.
     *
     * @param store              the approval tables
     * @param properties         the settings
     * @param policies           the policy store, for the authors of a firing policy
     * @param producer           the action producer, for its shadow-mode setting
     * @param json               the JSON mapper
     * @param clock              the business clock
     * @param meters             metrics
     * @param transactionManager the transaction manager of the service data source
     * @param actionTopic        the topic approved policy actions are released on
     */
    public ApprovalService(ApprovalStore store, ApprovalProperties properties, PolicyRepository policies,
            PolicyActionProducer producer, ObjectMapper json, Clock clock, MeterRegistry meters,
            PlatformTransactionManager transactionManager, String actionTopic) {
        this(store, properties, policies, producer, json, clock, meters, transactionManager, actionTopic,
                new ApprovalAudit(new StaticListableBeanFactory().getBeanProvider(AuditLogWriter.class)));
    }

    /**
     * Creates the service.
     *
     * @param store              the approval tables
     * @param properties         the settings
     * @param policies           the policy store, for the authors of a firing policy
     * @param producer           the action producer, for its shadow-mode setting
     * @param json               the JSON mapper
     * @param clock              the business clock
     * @param meters             metrics
     * @param transactionManager the transaction manager of the service data source
     * @param actionTopic        the topic approved policy actions are released on
     * @param audit              the writer of the {@code govern.approval} audit stream
     */
    @Autowired
    public ApprovalService(ApprovalStore store, ApprovalProperties properties, PolicyRepository policies,
            PolicyActionProducer producer, ObjectMapper json, Clock clock, MeterRegistry meters,
            PlatformTransactionManager transactionManager,
            @Value("${app.kafka.topic.policy-action-event:" + DlapConstants.KafkaTopic.POLICY_ACTION_EVENT + "}")
            String actionTopic, ApprovalAudit audit) {
        this.store = store;
        this.properties = properties;
        this.policies = policies;
        this.producer = producer;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.tx = new TransactionTemplate(transactionManager);
        this.actionTopic = actionTopic;
        this.audit = audit;
        if (!properties.requireMfaMarker()) {
            log.warn("Approval decisions do not require the MFA marker role (dlap.policyengine.approvals."
                    + "require-mfa-marker=false); set it true in production");
        }
    }

    /**
     * A request and its decisions.
     *
     * @param request   the request
     * @param decisions its decisions, in order
     */
    public record Detail(ApprovalRequest request, List<ApprovalDecision> decisions) {

        /**
         * Copies the decisions.
         */
        public Detail {
            decisions = List.copyOf(decisions);
        }
    }

    /**
     * A page of requests.
     *
     * @param rows  the rows
     * @param page  the page number, from 0
     * @param size  the page size
     * @param total all matching rows
     */
    public record Page(List<ApprovalRequest> rows, int page, int size, long total) {

        /**
         * Copies the rows.
         */
        public Page {
            rows = List.copyOf(rows);
        }
    }

    /**
     * A request made through the API.
     *
     * @param actionClass       {@code ARCHIVE}, {@code SOFT_DELETE}, {@code HARD_DELETE} or {@code OTHER}
     * @param actionType        the action type, for example {@code delete}
     * @param assetId           the catalog asset id
     * @param assetUri          the canonical asset URI; may be null
     * @param provider          the cloud provider; may be null
     * @param serviceType       the asset kind; may be null
     * @param actionParameters  the parameters the executor needs; may be null
     * @param reason            why the action is wanted
     * @param requiredApprovals more approvals than the class needs; null for the class default
     * @param idempotencyKey    a key that makes a retried request return the first one; may be null
     * @param onBehalfOf        the person a service account acts for; only service accounts may set it
     */
    public record CreateCommand(String actionClass, String actionType, UUID assetId, String assetUri, String provider,
            String serviceType, Map<String, Object> actionParameters, String reason, Integer requiredApprovals,
            String idempotencyKey, String onBehalfOf) {
    }

    /**
     * Result of a create.
     *
     * @param detail  the request
     * @param created false when an earlier request with the same idempotency key was returned
     */
    public record Created(Detail detail, boolean created) {
    }

    /* ----------------------------------------------------------------- policy fires */

    /**
     * Holds a policy fire for approval: records a pending request whose release is the fire itself.
     * A fire already held (same {@code fireId}), or a fire for a policy, asset and action that
     * already wait, adds nothing.
     *
     * @param action      the fire
     * @param actionClass its class
     * @return true when a new request was recorded
     */
    public boolean hold(PolicyActionEvent action, ActionClass actionClass) {
        return hold(action, actionClass, null);
    }

    /**
     * Holds a policy fire for approval, excluding the person who triggered it from deciding.
     * The requester stays {@code policy:<id>}; the triggering {@code sub} joins the excluded
     * subjects next to the policy and its authors (docs/specs/approvals.md section 6.1.1).
     *
     * @param action      the fire
     * @param actionClass its class
     * @param triggeredBy the JWT {@code sub} of the person who triggered the fire; null or blank
     *                    when nobody did
     * @return true when a new request was recorded
     */
    public boolean hold(PolicyActionEvent action, ActionClass actionClass, String triggeredBy) {
        UUID assetId = uuid(action.assetId(), "assetId");
        UUID policyDbId = action.policyDbId() == null ? null : uuid(action.policyDbId(), "policyDbId");
        Instant now = now();
        String requester = "policy:" + (policyDbId != null ? policyDbId : action.policyId());
        Set<String> excluded = new LinkedHashSet<>(List.of(requester, ApprovalRequest.POLICY_ENGINE));
        excluded.addAll(policyAuthors(policyDbId));
        if (triggeredBy != null && !triggeredBy.isBlank()) {
            excluded.add(triggeredBy.strip());
        }
        String fireId = action.fireId() != null ? action.fireId() : UUID.randomUUID().toString();
        ApprovalRequest r = new ApprovalRequest(UUID.randomUUID(), "fire:" + fireId, ApprovalRequest.ORIGIN_POLICY,
                actionClass, action.actionType(), action.actionParameters(), assetId, action.assetUri(),
                action.provider(), action.serviceType(), policyDbId, action.policyId(), action.policyName(), fireId,
                requester, ApprovalRequest.REQUESTER_POLICY, ApprovalRequest.POLICY_ENGINE, List.copyOf(excluded),
                reasonOf(action), requiredFor(actionClass, action.actionParameters().get("requiredApprovers")), 0,
                ApprovalStatus.PENDING, now, now.plus(properties.ttl()), null, write(action),
                ApprovalRequest.DISPATCH_NONE, null);
        Boolean inserted = tx.execute(s -> {
            if (!store.insertRequest(r)) {
                return false;
            }
            outbox(ApprovalEvents.REQUESTED, r, List.of(), now, null);
            return true;
        });
        if (Boolean.TRUE.equals(inserted)) {
            meters.counter(REQUESTED_COUNTER, "actionClass", actionClass.name(), "origin", r.origin()).increment();
            log.info("Policy {} fire {} on asset {} waits for {} approval(s) as request {} ({})", action.policyId(),
                    fireId, assetId, r.requiredApprovals(), r.id(), actionClass);
            return true;
        }
        log.debug("Policy {} fire {} on asset {} already waits for approval", action.policyId(), fireId, assetId);
        return false;
    }

    /* ----------------------------------------------------------------- API requests */

    /**
     * Records a request made through the API.
     *
     * @param cmd    the request
     * @param caller the caller
     * @return the request, and whether it is new
     */
    public Created create(CreateCommand cmd, Caller caller) {
        ActionClass actionClass;
        try {
            actionClass = ActionClass.parse(cmd.actionClass());
        } catch (IllegalArgumentException e) {
            throw ApprovalException.invalid(e.getMessage());
        }
        String actionType = text(cmd.actionType(), "actionType", 1, 64);
        if (cmd.assetId() == null) {
            throw ApprovalException.invalid("assetId is required");
        }
        String reason = text(cmd.reason(), "reason", 1, properties.maxCommentLength());
        Integer asked = cmd.requiredApprovals();
        if (asked != null && (asked < ApprovalProperties.FLOOR || asked > ApprovalProperties.CEILING)) {
            throw ApprovalException.invalid("requiredApprovals must be " + ApprovalProperties.FLOOR + " to "
                    + ApprovalProperties.CEILING);
        }
        String onBehalfOf = cmd.onBehalfOf() == null || cmd.onBehalfOf().isBlank() ? null : cmd.onBehalfOf().strip();
        if (onBehalfOf != null && !caller.serviceAccount()) {
            throw ApprovalException.forbidden(ApprovalException.ON_BEHALF_REFUSED,
                    "only a service account may name the person it acts for");
        }
        String key = cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank() ? null
                : text(cmd.idempotencyKey(), "idempotencyKey", 1, 128);
        String requester = onBehalfOf != null ? onBehalfOf : caller.subject();
        String kind = onBehalfOf != null || !caller.serviceAccount()
                ? ApprovalRequest.REQUESTER_USER : ApprovalRequest.REQUESTER_SERVICE;
        Set<String> excluded = new LinkedHashSet<>(List.of(requester, caller.subject()));
        Instant now = now();
        ApprovalRequest r = new ApprovalRequest(UUID.randomUUID(),
                "api:" + caller.subject() + ":" + (key != null ? key : UUID.randomUUID().toString()),
                ApprovalRequest.ORIGIN_API, actionClass, actionType, cmd.actionParameters(), cmd.assetId(),
                trim(cmd.assetUri()), trim(cmd.provider()), trim(cmd.serviceType()), null, null, null, null,
                requester, kind, caller.subject(), List.copyOf(excluded), reason, requiredFor(actionClass, asked), 0,
                ApprovalStatus.PENDING, now, now.plus(properties.ttl()), null, null, ApprovalRequest.DISPATCH_NONE,
                null);
        Boolean inserted = tx.execute(s -> {
            if (!store.insertRequest(r)) {
                return false;
            }
            outbox(ApprovalEvents.REQUESTED, r, List.of(), now, null);
            return true;
        });
        if (Boolean.TRUE.equals(inserted)) {
            meters.counter(REQUESTED_COUNTER, "actionClass", actionClass.name(), "origin", r.origin()).increment();
            log.info("Approval request {} ({} {} on asset {}) submitted by {}", r.id(), actionClass, actionType,
                    r.assetId(), caller.subject());
            return new Created(get(r.id()), true);
        }
        ApprovalRequest existing = store.findByKey(r.requestKey())
                .orElseThrow(() -> ApprovalException.conflict(ApprovalException.INVALID, "the request was not stored"));
        return new Created(get(existing.id()), false);
    }

    /* ----------------------------------------------------------------- reads */

    /**
     * Reads a request with its decisions.
     *
     * @param id the request id
     * @return the request
     */
    public Detail get(UUID id) {
        ApprovalRequest r = store.find(id).orElseThrow(ApprovalException::notFound);
        return new Detail(r, store.decisions(id));
    }

    /**
     * Pages the requests, newest first.
     *
     * @param status      a state filter; may be null
     * @param actionClass a class filter; may be null
     * @param assetId     an asset filter; may be null
     * @param page        the page, from 0
     * @param size        the size, 1 to 200
     * @return the page
     */
    public Page list(ApprovalStatus status, ActionClass actionClass, UUID assetId, int page, int size) {
        int s = pageSize(size);
        int p = Math.max(page, 0);
        ApprovalStore.Slice slice = store.list(status, actionClass, assetId, s, (long) p * s);
        return new Page(slice.rows(), p, s, slice.total());
    }

    /**
     * Pages the pending requests the caller may still decide.
     *
     * @param caller the caller
     * @param page   the page, from 0
     * @param size   the size, 1 to 200
     * @return the page
     */
    public Page inbox(Caller caller, int page, int size) {
        int s = pageSize(size);
        int p = Math.max(page, 0);
        if (!mayDecide(caller)) {
            return new Page(List.of(), p, s, 0);
        }
        ApprovalStore.Slice slice = store.inbox(caller.subject(), now(), s, (long) p * s);
        return new Page(slice.rows(), p, s, slice.total());
    }

    /* ----------------------------------------------------------------- decisions */

    /**
     * Records an approval or a rejection.
     *
     * @param id       the request id
     * @param decision {@link DecisionKind#APPROVE} or {@link DecisionKind#REJECT}
     * @param comment  the comment; required
     * @param caller   the caller
     * @return the request after the decision
     */
    public Detail decide(UUID id, DecisionKind decision, String comment, Caller caller) {
        if (decision == null || decision == DecisionKind.BREAK_GLASS) {
            throw ApprovalException.invalid("decision must be APPROVE or REJECT");
        }
        String text = text(comment, "comment", 1, properties.maxCommentLength());
        requireApprover(caller);
        Instant now = now();
        boolean expired = auditedDecision(id, () -> Boolean.TRUE.equals(tx.execute(s -> {
            ApprovalRequest r = lockPending(id);
            Optional<AuditEntry> prior = audit.priorDecision(r.id(), caller.subject());
            Instant at = GovernanceAudit.timeOf(prior, now);
            if (!at.isBefore(r.expiresAt())) {
                expire(r, now);
                return true;
            }
            requireNotExcluded(r, caller);
            ApprovalDecision d = insertDecision(r, caller, decision, text, at);
            if (decision == DecisionKind.REJECT) {
                resolve(r, ApprovalStatus.REJECTED, r.approvals(), at, ApprovalRequest.DISPATCH_NONE);
            } else {
                int approvals = r.approvals() + 1;
                if (approvals >= r.requiredApprovals()) {
                    approve(r, approvals, at, ApprovalStatus.APPROVED);
                } else {
                    store.countApproval(r.id(), approvals);
                }
            }
            // Last, so nothing but the commit can still fail once the audit entry exists.
            audit.decided(r, d, prior);
            return false;
        })));
        if (expired) {
            throw ApprovalException.conflict(ApprovalException.EXPIRED, "the approval request expired");
        }
        meters.counter(DECISION_COUNTER, "decision", decision.name()).increment();
        log.info("Approval request {}: {} by {}", id, decision, caller.subject());
        return get(id);
    }

    /**
     * Releases a request without the quorum (break-glass). Only an administrator who did not ask
     * for, submit or decide the request may do it, with a justification; the release is logged at
     * ERROR, counted in {@code policyengine.approvals.break_glass} and published on
     * {@code dlap.approvals.break-glass}.
     *
     * @param id            the request id
     * @param justification why the quorum cannot wait
     * @param caller        the caller
     * @return the request after the release
     */
    public Detail breakGlass(UUID id, String justification, Caller caller) {
        if (!properties.breakGlass().enabled()) {
            throw refused(ApprovalException.forbidden(ApprovalException.BREAK_GLASS_DISABLED,
                    "break-glass is disabled (dlap.policyengine.approvals.break-glass.enabled=false)"));
        }
        String text = text(justification, "justification", properties.breakGlass().minJustificationLength(),
                properties.maxCommentLength());
        requireApprover(caller);
        if (!caller.has(Caller.ADMIN)) {
            throw refused(ApprovalException.forbidden(ApprovalException.NOT_AN_APPROVER,
                    "only ROLE_ADMIN may break glass"));
        }
        Instant now = now();
        ApprovalRequest[] released = new ApprovalRequest[1];
        boolean expired = auditedDecision(id, () -> Boolean.TRUE.equals(tx.execute(s -> {
            ApprovalRequest r = lockPending(id);
            Optional<AuditEntry> prior = audit.priorDecision(r.id(), caller.subject());
            Instant at = GovernanceAudit.timeOf(prior, now);
            if (!at.isBefore(r.expiresAt())) {
                expire(r, now);
                return true;
            }
            requireNotExcluded(r, caller);
            ApprovalDecision d = insertDecision(r, caller, DecisionKind.BREAK_GLASS, text, at);
            ApprovalRequest after = approve(r, r.approvals(), at, ApprovalStatus.APPROVED_BREAK_GLASS);
            outbox(ApprovalEvents.BREAK_GLASS, after, store.decisions(r.id()), at, text);
            // Last, so nothing but the commit can still fail once the audit entry exists.
            audit.decided(r, d, prior);
            released[0] = after;
            return false;
        })));
        if (expired) {
            throw ApprovalException.conflict(ApprovalException.EXPIRED, "the approval request expired");
        }
        ApprovalRequest r = released[0];
        meters.counter(BREAK_GLASS_COUNTER, "actionClass", r.actionClass().name()).increment();
        meters.counter(DECISION_COUNTER, "decision", DecisionKind.BREAK_GLASS.name()).increment();
        log.error("BREAK-GLASS: approval request {} ({} {} on asset {}) released by {} with {} of {} approvals",
                r.id(), r.actionClass(), r.actionType(), r.assetId(), caller.subject(), r.approvals(),
                r.requiredApprovals());
        return get(id);
    }

    /* ----------------------------------------------------------------- withdrawal */

    /**
     * Withdraws a pending request on behalf of a person (APPR-03, Q-676). Only a service account
     * may call it, and only a {@code PENDING} request can be withdrawn. The request moves to
     * {@link ApprovalStatus#WITHDRAWN}, a terminal state like every other: nothing about the result
     * changes afterwards, and a withdrawn request is never executable, so it can be neither approved
     * nor released. A withdrawal is not an approval: the separation-of-duties rules of the
     * specification (section 5) do not apply, and no decision row is written. The service principal,
     * the person, the reason and the time (the injected clock, or on a replay the time of the stored audit
     * entry, Q-692) are stored on the request, a
     * {@code DECIDED} event is queued and the withdrawal is appended to the {@code govern.approval}
     * audit stream before the transaction commits, so an audit failure undoes the withdrawal.
     *
     * @param id         the request id
     * @param reason     why; at least {@value #MIN_WITHDRAW_REASON} characters
     * @param onBehalfOf the {@code sub} of the person the service account acts for; required
     * @param caller     the caller, which must be a service account
     * @return the withdrawn request
     * @throws ApprovalException 403 for a caller that is not a service account, 400 for a bad body, 404 when
     *                           the request does not exist, 409 (with the current state) when it is not
     *                           pending or has expired, 503 when the audit log cannot record it
     */
    public Detail withdraw(UUID id, String reason, String onBehalfOf, Caller caller) {
        if (!caller.serviceAccount()) {
            throw refused(ApprovalException.forbidden(ApprovalException.SERVICE_ACCOUNT_ONLY,
                    "only a service account may withdraw a request"));
        }
        String why = text(reason, "reason", MIN_WITHDRAW_REASON, properties.maxCommentLength());
        String person = text(onBehalfOf, "onBehalfOf", 1, 255);
        Instant now = now();
        boolean[] late = new boolean[1];
        ApprovalRequest[] withdrawn = new ApprovalRequest[1];
        try {
            tx.executeWithoutResult(s -> {
                ApprovalRequest r = store.lock(id).orElseThrow(ApprovalException::notFound);
                if (r.status() != ApprovalStatus.PENDING) {
                    throw ApprovalException.conflict(ApprovalException.NOT_PENDING,
                            "the approval request is " + r.status(), r.status());
                }
                Optional<AuditEntry> prior = audit.priorWithdrawal(r.id());
                Instant at = GovernanceAudit.timeOf(prior, now);
                if (!at.isBefore(r.expiresAt())) {
                    expire(r, now);
                    late[0] = true;
                    return;
                }
                if (!store.withdraw(r.id(), at, caller.subject(), person, why)) {
                    throw ApprovalException.conflict(ApprovalException.NOT_PENDING,
                            "the approval request is no longer pending", ApprovalStatus.PENDING);
                }
                ApprovalRequest after = store.find(r.id()).orElseThrow(ApprovalException::notFound);
                outbox(ApprovalEvents.DECIDED, after, store.decisions(r.id()), at, null);
                // Last, so nothing but the commit can still fail once the audit entry exists. A retry
                // after an entry whose transaction did not commit replays it (Q-692).
                audit.withdrawn(after, prior);
                withdrawn[0] = after;
            });
        } catch (AuditException e) {
            log.error("Approval request {} was not withdrawn: the audit log refused the entry ({})", id,
                    e.getMessage());
            throw refused(ApprovalException.unavailable(ApprovalException.AUDIT_UNAVAILABLE,
                    "the audit log could not record the withdrawal; the request is unchanged"));
        }
        if (late[0]) {
            throw ApprovalException.conflict(ApprovalException.EXPIRED, "the approval request expired",
                    ApprovalStatus.EXPIRED);
        }
        ApprovalRequest r = withdrawn[0];
        meters.counter(RESOLVED_COUNTER, "status", ApprovalStatus.WITHDRAWN.name(),
                "actionClass", r.actionClass().name()).increment();
        meters.counter(WITHDRAWN_COUNTER, "actionClass", r.actionClass().name()).increment();
        log.info("Approval request {} ({} {} on asset {}) withdrawn by service account {} on behalf of {}", r.id(),
                r.actionClass(), r.actionType(), r.assetId(), caller.subject(), person);
        return get(id);
    }

    /* ----------------------------------------------------------------- expiry */

    /**
     * Expires every pending request whose time is up.
     *
     * @return how many requests expired
     */
    public int expireOverdue() {
        Instant now = now();
        int total = 0;
        while (true) {
            Integer n = tx.execute(s -> {
                List<ApprovalRequest> rows = store.expireOverdue(now, EXPIRY_BATCH);
                for (ApprovalRequest r : rows) {
                    outbox(ApprovalEvents.DECIDED, r, store.decisions(r.id()), now, null);
                    meters.counter(RESOLVED_COUNTER, "status", ApprovalStatus.EXPIRED.name(),
                            "actionClass", r.actionClass().name()).increment();
                    log.info("Approval request {} ({} on asset {}) expired unapproved", r.id(), r.actionClass(),
                            r.assetId());
                }
                return rows.size();
            });
            int expired = n == null ? 0 : n;
            total += expired;
            if (expired < EXPIRY_BATCH) {
                return total;
            }
        }
    }

    /* ----------------------------------------------------------------- rules */

    private boolean mayDecide(Caller caller) {
        if (caller.serviceAccount()) {
            return false;
        }
        if (properties.approverRoles().stream().noneMatch(caller::has)) {
            return false;
        }
        return !properties.requireMfaMarker() || caller.has(properties.mfaMarkerAuthority());
    }

    private void requireApprover(Caller caller) {
        if (caller.serviceAccount()) {
            throw refused(ApprovalException.forbidden(ApprovalException.NOT_AN_APPROVER,
                    "a service account never approves"));
        }
        if (properties.approverRoles().stream().noneMatch(caller::has)) {
            throw refused(ApprovalException.forbidden(ApprovalException.NOT_AN_APPROVER,
                    "approvers hold one of " + properties.approverRoles()));
        }
        if (properties.requireMfaMarker() && !caller.has(properties.mfaMarkerAuthority())) {
            throw refused(ApprovalException.forbidden(ApprovalException.MFA_REQUIRED,
                    "the token lacks the MFA marker role " + properties.mfaMarkerRole()));
        }
    }

    private ApprovalRequest lockPending(UUID id) {
        ApprovalRequest r = store.lock(id).orElseThrow(ApprovalException::notFound);
        if (r.status() != ApprovalStatus.PENDING) {
            throw ApprovalException.conflict(ApprovalException.NOT_PENDING, "the approval request is " + r.status());
        }
        return r;
    }

    private void requireNotExcluded(ApprovalRequest r, Caller caller) {
        if (r.excludedSubjects().contains(caller.subject())) {
            throw refused(ApprovalException.forbidden(ApprovalException.SELF_APPROVAL,
                    "the requester or submitter of a request, or the author of its policy, cannot decide it"));
        }
        if (store.hasDecided(r.id(), caller.subject())) {
            throw refused(ApprovalException.conflict(ApprovalException.ALREADY_DECIDED,
                    "each approver decides a request once"));
        }
    }

    private ApprovalDecision insertDecision(ApprovalRequest r, Caller caller, DecisionKind decision, String comment,
            Instant at) {
        List<String> roles = new ArrayList<>();
        for (String a : caller.authorities()) {
            if (a.startsWith("ROLE_")) {
                roles.add(a);
            }
        }
        ApprovalDecision d = new ApprovalDecision(ApprovalAudit.decisionId(r.id(), caller.subject()), r.id(),
                caller.subject(), caller.username(), roles, decision, comment, at);
        try {
            store.insertDecision(d);
        } catch (DuplicateKeyException e) {
            throw refused(ApprovalException.conflict(ApprovalException.ALREADY_DECIDED,
                    "each approver decides a request once"));
        }
        return d;
    }

    /**
     * Runs a decision's transaction and turns an audit failure into 503 {@code audit-unavailable}: the
     * transaction has rolled back, so the request is unchanged and no decision row exists (Q-687).
     */
    private boolean auditedDecision(UUID id, BooleanSupplier decision) {
        try {
            return decision.getAsBoolean();
        } catch (AuditException e) {
            log.error("Decision on approval request {} was not recorded: the audit log refused the entry ({})", id,
                    e.getMessage());
            throw refused(ApprovalException.unavailable(ApprovalException.AUDIT_UNAVAILABLE,
                    "the audit log could not record the decision; the request is unchanged"));
        }
    }

    private ApprovalRequest approve(ApprovalRequest r, int approvals, Instant now, ApprovalStatus status) {
        boolean release = ApprovalRequest.ORIGIN_POLICY.equals(r.origin()) && r.actionPayload() != null;
        String dispatch = !release ? ApprovalRequest.DISPATCH_NONE
                : producer.actionsEnabled() ? ApprovalRequest.DISPATCH_QUEUED : ApprovalRequest.DISPATCH_SHADOWED;
        ApprovalRequest after = resolve(r, status, approvals, now, dispatch);
        List<ApprovalDecision> decisions = store.decisions(r.id());
        if (ApprovalRequest.DISPATCH_QUEUED.equals(dispatch)) {
            PolicyActionEvent fire = read(r.actionPayload());
            PolicyActionEvent releasedAction = fire.approved(new PolicyActionEvent.Approval(r.id().toString(),
                    status.name(), r.actionClass().name(), r.requiredApprovals(), ApprovalEvents.approvers(decisions),
                    status == ApprovalStatus.APPROVED_BREAK_GLASS, now.toString()));
            store.insertOutbox(new ApprovalStore.OutboxMessage(UUID.randomUUID(), r.id(), "ACTION", actionTopic,
                    r.assetId().toString(), write(releasedAction), now));
        } else if (ApprovalRequest.DISPATCH_SHADOWED.equals(dispatch)) {
            log.info("SHADOW: approval request {} approved while policy actions are disabled; nothing released",
                    r.id());
        }
        return after;
    }

    private ApprovalRequest resolve(ApprovalRequest r, ApprovalStatus status, int approvals, Instant now,
            String dispatch) {
        if (!store.resolve(r.id(), status, approvals, now, dispatch)) {
            throw ApprovalException.conflict(ApprovalException.NOT_PENDING, "the approval request is no longer pending");
        }
        ApprovalRequest after = store.find(r.id()).orElseThrow(ApprovalException::notFound);
        outbox(ApprovalEvents.DECIDED, after, store.decisions(r.id()), now, null);
        meters.counter(RESOLVED_COUNTER, "status", status.name(), "actionClass", r.actionClass().name()).increment();
        return after;
    }

    private void expire(ApprovalRequest r, Instant now) {
        resolve(r, ApprovalStatus.EXPIRED, r.approvals(), now, ApprovalRequest.DISPATCH_NONE);
        log.info("Approval request {} ({} on asset {}) expired unapproved", r.id(), r.actionClass(), r.assetId());
    }

    private void outbox(String eventType, ApprovalRequest r, List<ApprovalDecision> decisions, Instant now,
            String justification) {
        Map<String, Object> event = ApprovalEvents.event(eventType, r, decisions, now, justification);
        store.insertOutbox(new ApprovalStore.OutboxMessage(UUID.randomUUID(), r.id(), eventType,
                ApprovalEvents.topic(eventType), r.id().toString(), write(event), now));
    }

    private ApprovalException refused(ApprovalException e) {
        meters.counter(REFUSED_COUNTER, "reason", e.reason()).increment();
        return e;
    }

    /**
     * The approvals a request of a class needs: the configured count, raised (never lowered) by the
     * policy's {@code requiredApprovers} parameter or the API caller, and never below the floor.
     */
    int requiredFor(ActionClass actionClass, Object asked) {
        int n = Math.max(properties.requiredFor(actionClass), ApprovalProperties.floorFor(actionClass));
        if (asked != null) {
            try {
                int a = (int) Math.min(Double.parseDouble(asked.toString().strip()), ApprovalProperties.CEILING);
                n = Math.max(n, a);
            } catch (NumberFormatException e) {
                log.warn("Ignoring a requiredApprovers value that is not a number; the class default applies");
            }
        }
        return n;
    }

    private List<String> policyAuthors(UUID policyDbId) {
        if (policyDbId == null) {
            return List.of();
        }
        Optional<Policy> policy = policies.findById(policyDbId);
        List<String> out = new ArrayList<>();
        policy.ifPresent(p -> {
            if (p.getCreatedByUserId() != null) {
                out.add(p.getCreatedByUserId().toString());
            }
            if (p.getUpdatedByUserId() != null) {
                out.add(p.getUpdatedByUserId().toString());
            }
        });
        return out;
    }

    private static String reasonOf(PolicyActionEvent action) {
        Object note = action.actionParameters().get("auditNote");
        if (note != null && !note.toString().isBlank()) {
            return note.toString().strip();
        }
        return "Policy " + action.policyId() + " fired (" + action.triggerType() + ") for " + action.actionType();
    }

    /**
     * The business instant, to the microsecond PostgreSQL keeps, so a read-back is equal.
     *
     * @return now
     */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static int pageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApprovalException.invalid("size must be 1 to " + MAX_PAGE_SIZE);
        }
        return size;
    }

    private static String text(String value, String field, int min, int max) {
        String v = value == null ? "" : value.strip();
        if (v.length() < min) {
            throw ApprovalException.invalid(min <= 1 ? field + " is required"
                    : field + " must be at least " + min + " characters");
        }
        if (v.length() > max) {
            throw ApprovalException.invalid(field + " must be at most " + max + " characters");
        }
        return v;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value.strip());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " is not a UUID: an approval needs a catalog asset id", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("approval message is not JSON-serialisable", e);
        }
    }

    private PolicyActionEvent read(String payload) {
        try {
            return json.readValue(payload, PolicyActionEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored policy action of an approval request cannot be read", e);
        }
    }
}
