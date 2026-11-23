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

package com.dlap.policyengine.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.AuditTarget;
import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.approval.GovernanceAudit;
import com.dlap.policyengine.common.ConflictException;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyChangeType;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.model.PolicyVersion;
import com.dlap.policyengine.model.PolicyVersionView;
import com.dlap.policyengine.repository.PolicyRepository;
import com.dlap.policyengine.repository.PolicyVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;

/**
 * Policy history (AUD-02a): every change of a policy becomes one immutable {@link PolicyVersion} and
 * one entry of the audit stream {@code govern.policy}, in the transaction of the change.
 *
 * <p>The order inside that transaction is: lock the policy row ({@link #lockForChange}), apply the
 * change, save and flush the policy, {@link #prepare} the version (its number, snapshot, idempotency
 * key and time), then {@link #record} it, which appends the audit entry and inserts the version row.
 * When the append fails, {@link AuditUnavailableException} rolls the whole change back and the API
 * answers 503 {@code audit-unavailable}: no change without its audit entry.
 *
 * <p>A retried change replays: its idempotency key is the policy, the version number and a hash of
 * the change (type, actor, content), so an entry an uncommitted earlier attempt left is found and the
 * change commits with that entry's time (Q-692). A policy created before Flyway V9 has no version; its
 * first change first records its state as a {@link PolicyChangeType#BASELINE} version by the system.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PolicyHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PolicyHistoryService.class);

    /** The {@code schema} member of every snapshot. */
    public static final String SNAPSHOT_SCHEMA = "dlap-policy-version/1";

    /** Target kind of a policy in the audit log. */
    public static final String TARGET_KIND = "POLICY";

    /** The actor of changes nobody made through the API, such as a baseline. */
    public static final String SYSTEM_ACTOR = "da-policyengine";

    /** What a replayed change must share with the stored entry besides the always compared members. */
    private static final List<String> IDENTITY =
            List.of("/details/policyId", "/details/version", "/details/contentSha256");

    private static final ObjectMapper SNAPSHOT_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PolicyVersionRepository versions;
    private final PolicyRepository policies;
    private final GovernanceAudit audit;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * Creates the history.
     *
     * @param versions      the version rows
     * @param policies      the policy rows, to tell an unknown policy from one without history
     * @param audit         the audit log access
     * @param entityManager the persistence context, for the row lock
     * @param clock         the business clock
     */
    public PolicyHistoryService(PolicyVersionRepository versions, PolicyRepository policies, GovernanceAudit audit,
            EntityManager entityManager, Clock clock) {
        this.versions = versions;
        this.policies = policies;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * A version about to be recorded.
     *
     * @param policyId      the policy
     * @param version       its number
     * @param changeType    what makes it
     * @param actor         who makes it
     * @param name          the policy name
     * @param status        the policy status
     * @param snapshot      the JSON text of the content
     * @param contentSha256 the SHA-256 of {@code snapshot}
     * @param previousSha   the content hash of the version before, or null for the first
     * @param key           the idempotency key of the audit entry
     * @param prior         the entry an uncommitted earlier attempt left under {@code key}, if any
     * @param at            the business time of the change: the prior entry's, else the clock's
     */
    public record Pending(UUID policyId, int version, PolicyChangeType changeType, AuditActor actor, String name,
            PolicyStatus status, String snapshot, String contentSha256, String previousSha, String key,
            Optional<AuditEntry> prior, Instant at) {
    }

    /**
     * The business instant, to the microsecond PostgreSQL keeps.
     *
     * @return now
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Locks a live policy's row for a change, re-reading it under the lock, so two concurrent changes
     * of one policy take turns and number their versions one after the other.
     *
     * @param policy the policy, as loaded
     * @return the same entity, refreshed under the lock
     */
    public Policy lockForChange(Policy policy) {
        if (entityManager.contains(policy)) {
            try {
                entityManager.refresh(policy, LockModeType.PESSIMISTIC_WRITE);
            } catch (EntityNotFoundException e) {
                throw new ResourceNotFoundException("Policy", "id", String.valueOf(policy.getId()));
            }
        }
        if (policy.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Policy", "id", String.valueOf(policy.getId()));
        }
        return policy;
    }

    /**
     * Records the state of a policy that has no version yet (one created before Flyway V9) as a
     * {@link PolicyChangeType#BASELINE} version by the system, at the policy's last update time. Call it
     * before applying a change. Does nothing for a policy that has versions.
     *
     * @param policy the locked policy, unchanged
     * @return the baseline version, or empty
     */
    public Optional<PolicyVersion> baselineIfNeeded(Policy policy) {
        if (policy.getCurrentVersion() > 0) {
            return Optional.empty();
        }
        Instant stamp = policy.getUpdatedAt() != null ? policy.getUpdatedAt() : policy.getCreatedAt();
        Instant at = stamp != null ? stamp.truncatedTo(ChronoUnit.MICROS) : now();
        Pending baseline = prepare(policy, PolicyChangeType.BASELINE, AuditActor.system(SYSTEM_ACTOR), at);
        PolicyVersion v = record(baseline);
        policy.setCurrentVersion(v.getVersion());
        log.info("Recorded the pre-history state of policy {} as version {} (BASELINE)", policy.getId(),
                v.getVersion());
        return Optional.of(v);
    }

    /**
     * Prepares the next version of a policy as it now is, made by the caller.
     *
     * @param policy     the policy after the change, saved so it has its id
     * @param changeType what the change is
     * @param userId     the caller's user id from the JWT {@code sub}; null or the system id for nobody
     * @return the pending version; apply {@link Pending#at()} and {@link Pending#version()} to the policy
     */
    public Pending prepare(Policy policy, PolicyChangeType changeType, UUID userId) {
        return prepare(policy, changeType, actorOf(userId), now());
    }

    private Pending prepare(Policy policy, PolicyChangeType changeType, AuditActor actor, Instant now) {
        UUID policyId = policy.getId();
        if (policyId == null) {
            throw new IllegalStateException("a policy gets its id before its first version");
        }
        int version = policy.getCurrentVersion() + 1;
        String snapshot = snapshot(policy, changeType == PolicyChangeType.DELETED);
        String contentSha = GovernanceAudit.sha256(snapshot);
        String previousSha = version == 1 ? null
                : versions.findByPolicyIdAndVersion(policyId, version - 1).map(PolicyVersion::getContentSha256)
                        .orElse(null);
        String changeSha = GovernanceAudit.sha256(changeType.name() + "\n" + actor.kind().name() + ":" + actor.id()
                + "\n" + contentSha);
        String key = policyId + ":v" + version + ":" + changeSha.substring(0, 32);
        Optional<AuditEntry> prior = audit.prior(AuditStreams.GOVERN_POLICY, key);
        Instant at = GovernanceAudit.timeOf(prior, now);
        return new Pending(policyId, version, changeType, actor, policy.getName(), policy.getStatus(), snapshot,
                contentSha, previousSha, key, prior, at);
    }

    /**
     * Appends the version's audit entry (or replays the one an earlier attempt left) and inserts the
     * version row. Call it last in the change's transaction, after the policy row is saved and flushed.
     *
     * @param pending the prepared version
     * @return the version row
     * @throws AuditUnavailableException when the audit log cannot record it; the change must roll back
     * @throws ConflictException         when the version row is refused (a concurrent change took the number)
     */
    public PolicyVersion record(Pending pending) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyId", pending.policyId().toString());
        details.put("version", pending.version());
        details.put("changeType", pending.changeType().name());
        details.put("name", pending.name());
        details.put("status", pending.status().name());
        details.put("deleted", pending.changeType() == PolicyChangeType.DELETED);
        details.put("contentSha256", pending.contentSha256());
        if (pending.previousSha() != null) {
            details.put("previousContentSha256", pending.previousSha());
        }
        AuditEvent event = new AuditEvent(pending.changeType().eventType(), pending.at(), pending.actor(),
                AuditTarget.of(TARGET_KIND, pending.policyId().toString()), AuditOutcome.SUCCEEDED,
                pending.policyId().toString(), pending.key(), details);
        AuditEntry entry = audit.record(AuditStreams.GOVERN_POLICY, event, pending.prior(), IDENTITY);
        PolicyVersion row = new PolicyVersion(UUID.randomUUID(), pending.policyId(), pending.version(),
                pending.changeType(), pending.name(), pending.status(), pending.changeType() == PolicyChangeType.DELETED,
                pending.snapshot(), pending.contentSha256(), pending.actor().id(), pending.actor().kind().name(),
                pending.at(), entry.seq(), entry.entryHash());
        try {
            return versions.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            log.warn("Version {} of policy {} was refused ({}); a concurrent change took it", pending.version(),
                    pending.policyId(), e.getMostSpecificCause().getMessage());
            throw new ConflictException("Policy " + pending.policyId() + " changed concurrently; reload it and retry.");
        }
    }

    /**
     * Every version of a policy, oldest first, without their content. A policy that exists but has not
     * changed since Flyway V9 has none.
     *
     * @param policyId the policy
     * @return the versions
     * @throws ResourceNotFoundException when no policy and no version has the id
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public List<PolicyVersionView> versions(UUID policyId) {
        List<PolicyVersion> rows = versions.findByPolicyIdOrderByVersionAsc(policyId);
        if (rows.isEmpty() && !policies.existsById(policyId)) {
            throw new ResourceNotFoundException("Policy", "id", policyId.toString());
        }
        return rows.stream().map(v -> view(v, false)).toList();
    }

    /**
     * One version of a policy with its content.
     *
     * @param policyId the policy
     * @param version  the version number
     * @return the version
     * @throws ResourceNotFoundException when the policy has no such version
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public PolicyVersionView version(UUID policyId, int version) {
        return versions.findByPolicyIdAndVersion(policyId, version).map(v -> view(v, true))
                .orElseThrow(() -> new ResourceNotFoundException("Policy version", "policyId/version",
                        policyId + "/" + version));
    }

    /**
     * The snapshot of a policy's content: canonical JSON with sorted keys and explicit nulls. Times and
     * authors are not content; they are on the version row and in the audit entry.
     *
     * @param policy  the policy
     * @param deleted whether this snapshot deletes it
     * @return the JSON text
     */
    static String snapshot(Policy policy, boolean deleted) {
        Map<String, Object> content = new TreeMap<>();
        content.put("schema", SNAPSHOT_SCHEMA);
        content.put("name", policy.getName());
        content.put("description", policy.getDescription());
        content.put("status", policy.getStatus() == null ? null : policy.getStatus().name());
        content.put("conditionLogic", policy.getConditionLogic());
        content.put("actions", policy.getActions());
        content.put("policyDocument", policy.getPolicyDocument());
        content.put("deleted", deleted);
        try {
            return SNAPSHOT_JSON.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("policy " + policy.getId() + " has content that is not JSON", e);
        }
    }

    /**
     * The actor of a change: the system for no user, a service for a service-account token, else the user.
     *
     * @param userId the caller's user id, or null
     * @return the actor
     */
    static AuditActor actorOf(UUID userId) {
        if (userId == null || userId.equals(SecurityUtils.getSystemUserId())) {
            return AuditActor.system(SYSTEM_ACTOR);
        }
        if (SecurityUtils.hasAuthority("ROLE_SERVICE_ACCOUNT")) {
            return AuditActor.service(userId.toString());
        }
        return AuditActor.user(userId.toString());
    }

    private static PolicyVersionView view(PolicyVersion v, boolean withContent) {
        Map<String, Object> content = withContent ? content(v) : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> actions = withContent ? (Map<String, Object>) content.get("actions") : null;
        return new PolicyVersionView(v.getPolicyId(), v.getVersion(), v.getChangeType(), v.getName(), v.getStatus(),
                v.isDeleted(), v.getContentSha256(), v.getChangedBy(), v.getChangedByKind(), v.getChangedAt(),
                AuditStreams.GOVERN_POLICY, v.getAuditSeq(), v.getAuditEntryHash(),
                (String) content.get("description"), (String) content.get("conditionLogic"), actions,
                (String) content.get("policyDocument"));
    }

    private static Map<String, Object> content(PolicyVersion v) {
        try {
            return SNAPSHOT_JSON.readValue(v.getSnapshot(), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("snapshot of version " + v.getVersion() + " of policy "
                    + v.getPolicyId() + " is not JSON", e);
        }
    }
}
