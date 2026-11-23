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

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One approved archive action and what became of it ({@code dlap_archive_rules}, Flyway V2).
 *
 * <p>The approval id is unique: a redelivered policy fire or a repeated approval finds this row
 * and is treated as the same decision (docs/specs/approvals.md section 9, rule 5).
 *
 * <p>{@link #STATUS_APPLIED} and {@link #STATUS_RENDERED} say what DLAP did. Only
 * {@link #STATUS_EXECUTED} says the transition happened, and only a provider log event may set it:
 * the evidence columns name the event.
 */
@Entity
@Table(name = "dlap_archive_rules")
public class ArchiveRuleEntity {

    /** Planned, nothing written yet. */
    public static final String STATUS_PLANNED = "PLANNED";
    /**
     * Claimed by one worker, which is running it now (Flyway V3, Q-609). Only the conditional update
     * {@code ArchiveRuleRepository.claimPlanned} sets it; a row still here after a crash is listed by the
     * reconciliation job and never run again by itself.
     */
    public static final String STATUS_EXECUTING = "EXECUTING";
    /** DLAP wrote the rule (or the direct change) and read it back; no transition proven yet. */
    public static final String STATUS_APPLIED = "APPLIED";
    /** The provider has no API DLAP can call; the rule document is ready to apply. */
    public static final String STATUS_RENDERED = "RENDERED";
    /** A provider event proved the transition. */
    public static final String STATUS_EXECUTED = "EXECUTED";
    /** The attempt failed after the approval was verified. */
    public static final String STATUS_FAILED = "FAILED";
    /** The executor refused before it acted. */
    public static final String STATUS_REFUSED = "REFUSED";
    /** The rule was taken out again. */
    public static final String STATUS_REMOVED = "REMOVED";

    /** Mode: a lifecycle rule the provider runs. */
    public static final String MODE_NATIVE_RULE = "NATIVE_RULE";
    /** Mode: a direct storage class change of one object. */
    public static final String MODE_DIRECT_OBJECT = "DIRECT_OBJECT";

    @Id
    @Column(name = "rule_id", length = 64)
    private String ruleId;
    @Column(name = "approval_id", nullable = false, length = 64)
    private String approvalId;
    @Column(name = "fire_id", length = 128)
    private String fireId;
    @Column(name = "task_id", nullable = false)
    private String taskId;
    @Column(name = "asset_id", nullable = false)
    private String assetId;
    @Column(name = "asset_uri", nullable = false, length = 2048)
    private String assetUri;
    @Column(nullable = false, length = 16)
    private String provider;
    @Column(nullable = false, length = 16)
    private String mode;
    @Column(name = "scope_bucket", nullable = false)
    private String scopeBucket;
    @Column(name = "scope_prefix", nullable = false, length = 1024)
    private String scopePrefix = "";
    @Column(name = "native_class", nullable = false, length = 32)
    private String nativeClass;
    @Column(name = "after_days", nullable = false)
    private int afterDays;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(nullable = false)
    private boolean mock;
    @Column(name = "already_present", nullable = false)
    private boolean alreadyPresent;
    @Column(name = "rule_document", columnDefinition = "text")
    private String ruleDocument;
    @Column(name = "applied_at")
    private Instant appliedAt;
    @Column(name = "executed_at")
    private Instant executedAt;
    @Column(name = "evidence_event_id", length = 128)
    private String evidenceEventId;
    @Column(name = "evidence_operation", length = 128)
    private String evidenceOperation;
    @Column(name = "evidence_event_time")
    private Instant evidenceEventTime;
    @Column(name = "evidence_kind", length = 32)
    private String evidenceKind;
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Needed by JPA. */
    protected ArchiveRuleEntity() {
    }

    /**
     * Creates a planned row.
     *
     * @param ruleId      the rule id
     * @param approvalId  the approval it runs under
     * @param fireId      the policy fire, or {@code null} for an API request
     * @param taskId      the archival task
     * @param assetId     the catalog asset id
     * @param assetUri    the asset's storage URI
     * @param provider    the provider
     * @param mode        {@link #MODE_NATIVE_RULE} or {@link #MODE_DIRECT_OBJECT}
     * @param scopeBucket the bucket or container
     * @param scopePrefix the prefix, possibly empty
     * @param nativeClass the provider storage class
     * @param afterDays   the age in days
     * @param now         creation time from the injected clock
     */
    public ArchiveRuleEntity(String ruleId, String approvalId, String fireId, String taskId, String assetId,
            String assetUri, String provider, String mode, String scopeBucket, String scopePrefix,
            String nativeClass, int afterDays, Instant now) {
        this.ruleId = ruleId;
        this.approvalId = approvalId;
        this.fireId = fireId;
        this.taskId = taskId;
        this.assetId = assetId;
        this.assetUri = assetUri;
        this.provider = provider;
        this.mode = mode;
        this.scopeBucket = scopeBucket;
        this.scopePrefix = scopePrefix == null ? "" : scopePrefix;
        this.nativeClass = nativeClass;
        this.afterDays = afterDays;
        this.status = STATUS_PLANNED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** @return the rule id */
    public String getRuleId() {
        return ruleId;
    }

    /** @return the approval id */
    public String getApprovalId() {
        return approvalId;
    }

    /** @return the policy fire id, or null */
    public String getFireId() {
        return fireId;
    }

    /** @return the task id */
    public String getTaskId() {
        return taskId;
    }

    /** @return the catalog asset id */
    public String getAssetId() {
        return assetId;
    }

    /** @return the asset's storage URI */
    public String getAssetUri() {
        return assetUri;
    }

    /** @return the provider */
    public String getProvider() {
        return provider;
    }

    /** @return the mode */
    public String getMode() {
        return mode;
    }

    /** @return the bucket or container */
    public String getScopeBucket() {
        return scopeBucket;
    }

    /** @return the prefix, possibly empty */
    public String getScopePrefix() {
        return scopePrefix;
    }

    /** @return the provider storage class */
    public String getNativeClass() {
        return nativeClass;
    }

    /** @return the age in days */
    public int getAfterDays() {
        return afterDays;
    }

    /** @return the status */
    public String getStatus() {
        return status;
    }

    /** @return whether the provider call was a mock */
    public boolean isMock() {
        return mock;
    }

    /**
     * Whether the provider already held the identical rule when DLAP applied it (Q-608). Such a rule
     * may be the customer's own, so a restore never removes it.
     *
     * @return true when the rule was there before DLAP applied it
     */
    public boolean isAlreadyPresent() {
        return alreadyPresent;
    }

    /** @return the rule document, or null */
    public String getRuleDocument() {
        return ruleDocument;
    }

    /** @return when DLAP applied the rule, or null */
    public Instant getAppliedAt() {
        return appliedAt;
    }

    /** @return when the transition was proven, or null */
    public Instant getExecutedAt() {
        return executedAt;
    }

    /** @return the id of the provider event that proved the transition, or null */
    public String getEvidenceEventId() {
        return evidenceEventId;
    }

    /** @return the operation of the evidence event, or null */
    public String getEvidenceOperation() {
        return evidenceOperation;
    }

    /** @return the event time of the evidence, or null */
    public Instant getEvidenceEventTime() {
        return evidenceEventTime;
    }

    /** @return the kind of evidence, or null */
    public String getEvidenceKind() {
        return evidenceKind;
    }

    /** @return the last error text, or null */
    public String getLastError() {
        return lastError;
    }

    /** @return when the row was created */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return when the row last changed */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that DLAP wrote or rendered the rule.
     *
     * @param newStatus {@link #STATUS_APPLIED} or {@link #STATUS_RENDERED}
     * @param document  the rule document
     * @param isMock    whether the provider call was mocked
     * @param now       time from the injected clock
     */
    public void markApplied(String newStatus, String document, boolean isMock, Instant now) {
        markApplied(newStatus, document, isMock, false, now);
    }

    /**
     * Records that DLAP wrote or rendered the rule, and whether the provider already held it.
     *
     * @param newStatus      {@link #STATUS_APPLIED} or {@link #STATUS_RENDERED}
     * @param document       the rule document
     * @param isMock         whether the provider call was mocked
     * @param wasPresent     whether the identical rule was already in place before the apply (Q-608)
     * @param now            time from the injected clock
     */
    public void markApplied(String newStatus, String document, boolean isMock, boolean wasPresent, Instant now) {
        this.alreadyPresent = wasPresent;
        this.status = newStatus;
        this.ruleDocument = document;
        this.mock = isMock;
        this.appliedAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * Mirrors, on the loaded row, the claim that {@code ArchiveRuleRepository.claimPlanned} made in the database.
     *
     * @param now time from the injected clock
     */
    public void markClaimed(Instant now) {
        this.status = STATUS_EXECUTING;
        this.updatedAt = now;
    }

    /**
     * Records a failure or refusal.
     *
     * @param newStatus {@link #STATUS_FAILED} or {@link #STATUS_REFUSED}
     * @param error     what went wrong, without secrets
     * @param now       time from the injected clock
     */
    public void markFailed(String newStatus, String error, Instant now) {
        this.status = newStatus;
        this.lastError = clip(error);
        this.updatedAt = now;
    }

    /**
     * Cuts an error text to the column width.
     *
     * @param error the text, may be {@code null}
     * @return the text, at most 2000 characters
     */
    public static String clip(String error) {
        return error == null ? null : error.substring(0, Math.min(error.length(), 2000));
    }

    /**
     * Records the provider event that proves the transition.
     *
     * @param eventId   the normalized event id
     * @param operation the provider's operation name
     * @param eventTime the provider's event time
     * @param kind      the kind of evidence
     * @param now       time from the injected clock
     */
    public void markExecuted(String eventId, String operation, Instant eventTime, String kind, Instant now) {
        this.status = STATUS_EXECUTED;
        this.evidenceEventId = eventId;
        this.evidenceOperation = operation;
        this.evidenceEventTime = eventTime;
        this.evidenceKind = kind;
        this.executedAt = eventTime;
        this.updatedAt = now;
    }

    /**
     * Records that the rule was taken out.
     *
     * @param now time from the injected clock
     */
    public void markRemoved(Instant now) {
        this.status = STATUS_REMOVED;
        this.updatedAt = now;
    }
}
