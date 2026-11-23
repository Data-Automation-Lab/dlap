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

package com.dlap.policyengine.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One immutable version of a policy (AUD-02a, Flyway V9 {@code policy_versions}). The database refuses
 * UPDATE, DELETE and TRUNCATE, a version that does not follow the newest one of its policy, and any
 * version after a delete.
 *
 * <p>{@link #getSnapshot()} is the exact JSON text of the policy's content at this version and
 * {@link #getContentSha256()} its SHA-256. The same hash is in the {@code govern.policy} audit entry
 * at {@link #getAuditSeq()}, whose entry hash is {@link #getAuditEntryHash()}: a version row changed
 * afterwards no longer matches the chain.
 */
@Entity
@Immutable
@Table(name = "policy_versions")
public class PolicyVersion {

    @Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "policy_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID policyId;

    @Column(nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 16, nullable = false, updatable = false)
    private PolicyChangeType changeType;

    @Column(length = 255, nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false, updatable = false)
    private PolicyStatus status;

    @Column(nullable = false, updatable = false)
    private boolean deleted;

    @Column(columnDefinition = "TEXT", nullable = false, updatable = false)
    private String snapshot;

    @Column(name = "content_sha256", length = 64, nullable = false, updatable = false)
    private String contentSha256;

    @Column(name = "changed_by", length = 255, nullable = false, updatable = false)
    private String changedBy;

    @Column(name = "changed_by_kind", length = 16, nullable = false, updatable = false)
    private String changedByKind;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "audit_seq", nullable = false, updatable = false)
    private long auditSeq;

    @Column(name = "audit_entry_hash", length = 64, nullable = false, updatable = false)
    private String auditEntryHash;

    /** For JPA. */
    protected PolicyVersion() {
    }

    /**
     * Creates a version.
     *
     * @param id             the row id
     * @param policyId       the policy
     * @param version        1 for the first version, then one more for each change
     * @param changeType     what made it
     * @param name           the policy name at this version
     * @param status         the policy status at this version
     * @param deleted        true only for a {@link PolicyChangeType#DELETED} version
     * @param snapshot       the JSON text of the content
     * @param contentSha256  the SHA-256 of {@code snapshot}
     * @param changedBy      the actor id (JWT {@code sub}, client id or component)
     * @param changedByKind  {@code USER}, {@code SERVICE} or {@code SYSTEM}
     * @param changedAt      when the change happened (business time)
     * @param auditSeq       the position of its {@code govern.policy} audit entry
     * @param auditEntryHash the entry hash of that audit entry
     */
    public PolicyVersion(UUID id, UUID policyId, int version, PolicyChangeType changeType, String name,
            PolicyStatus status, boolean deleted, String snapshot, String contentSha256, String changedBy,
            String changedByKind, Instant changedAt, long auditSeq, String auditEntryHash) {
        this.id = id;
        this.policyId = policyId;
        this.version = version;
        this.changeType = changeType;
        this.name = name;
        this.status = status;
        this.deleted = deleted;
        this.snapshot = snapshot;
        this.contentSha256 = contentSha256;
        this.changedBy = changedBy;
        this.changedByKind = changedByKind;
        this.changedAt = changedAt;
        this.auditSeq = auditSeq;
        this.auditEntryHash = auditEntryHash;
    }

    /**
     * The row id.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The policy.
     *
     * @return the policy id
     */
    public UUID getPolicyId() {
        return policyId;
    }

    /**
     * The version number.
     *
     * @return 1 or more
     */
    public int getVersion() {
        return version;
    }

    /**
     * What made the version.
     *
     * @return the change type
     */
    public PolicyChangeType getChangeType() {
        return changeType;
    }

    /**
     * The policy name at this version.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * The policy status at this version.
     *
     * @return the status
     */
    public PolicyStatus getStatus() {
        return status;
    }

    /**
     * Whether this version deleted the policy.
     *
     * @return true for a delete
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * The JSON text of the content at this version.
     *
     * @return the snapshot
     */
    public String getSnapshot() {
        return snapshot;
    }

    /**
     * The SHA-256 of {@link #getSnapshot()}.
     *
     * @return 64 lower-case hex characters
     */
    public String getContentSha256() {
        return contentSha256;
    }

    /**
     * Who made the change.
     *
     * @return the actor id
     */
    public String getChangedBy() {
        return changedBy;
    }

    /**
     * The kind of actor.
     *
     * @return {@code USER}, {@code SERVICE} or {@code SYSTEM}
     */
    public String getChangedByKind() {
        return changedByKind;
    }

    /**
     * When the change happened.
     *
     * @return the business time
     */
    public Instant getChangedAt() {
        return changedAt;
    }

    /**
     * The position of the version's entry in the {@code govern.policy} audit stream.
     *
     * @return the seq
     */
    public long getAuditSeq() {
        return auditSeq;
    }

    /**
     * The entry hash of the version's audit entry.
     *
     * @return 64 lower-case hex characters
     */
    public String getAuditEntryHash() {
        return auditEntryHash;
    }
}
