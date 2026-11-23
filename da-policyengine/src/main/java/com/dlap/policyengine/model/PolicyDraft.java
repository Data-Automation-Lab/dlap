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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dlap.policyengine.dsl.PolicyDsl;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Entity representing a policy draft that goes through approval workflow.
 * Supports versioning, collaboration, and audit trails.
 */
@Entity
@Table(name = "policy_drafts")
public class PolicyDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * Name of the policy (must be unique when published).
     */
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    /**
     * Detailed description of the policy's purpose and scope.
     */
    @Size(max = 2000)
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Current status in the approval workflow.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyDraftStatus status = PolicyDraftStatus.CREATED;

    /**
     * Version number for this draft (incremented on each revision).
     */
    @Column(nullable = false)
    private Integer version = 1;

    /**
     * Reference to the published policy (if this is an update to existing policy).
     */
    @Column(columnDefinition = "UUID")
    private UUID basePolicyId;

    /**
     * DSL kind this draft authors (ADR-0040): {@code Policy} (lifecycle,
     * default) or {@code AccessPolicy} (Consumed-stage access intent).
     * Publication branches on this to pick the translator path.
     */
    @Column(name = "kind", nullable = false)
    private String kind = PolicyDsl.KIND_POLICY;

    /**
     * Free-text note on how the conditions combine, kept for display. It is never compiled
     * or evaluated (SEC-04, ADR-0057); publication translates rulesDefinition into the DSL.
     */
    @Column(columnDefinition = "TEXT")
    private String conditionLogic;

    /**
     * JSON representation of policy rules structure.
     * Stored as JSON to support complex rule configurations.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> rulesDefinition;

    /**
     * JSON representation of actions to be taken when policy is triggered.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> actions;

    /* ----- AccessPolicy structured fields (ADR-0040, kind == AccessPolicy) ----- */

    /**
     * AccessPolicy subjects: {@code {tiers: [...], roles: [...]}}. Null for a
     * lifecycle draft. The asset selector is reused from {@link #rulesDefinition}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_subjects", columnDefinition = "jsonb")
    private Map<String, Object> accessSubjects;

    /**
     * AccessPolicy AuthZEN action names (read/query/export/write/delete).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_actions", columnDefinition = "jsonb")
    private List<String> accessActions;

    /**
     * AccessPolicy request-context condition:
     * {@code {purpose: [...], consent: Boolean, region: [...]}}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_condition", columnDefinition = "jsonb")
    private Map<String, Object> accessCondition;

    /**
     * AccessPolicy effect: {@code allow} or {@code deny}.
     */
    @Column(name = "access_effect")
    private String accessEffect;

    /**
     * AccessPolicy obligations: rows of {@code {type, labels: [...]}}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_obligations", columnDefinition = "jsonb")
    private List<Map<String, Object>> accessObligations;

    /**
     * Change summary describing what was modified in this version.
     */
    @Size(max = 1000)
    @Column(columnDefinition = "TEXT")
    private String changeSummary;

    /**
     * Justification for the policy changes or creation.
     */
    @Size(max = 2000)
    @Column(columnDefinition = "TEXT")
    private String justification;

    /**
     * Expected impact of implementing this policy.
     */
    @Size(max = 1000)
    @Column(columnDefinition = "TEXT")
    private String expectedImpact;

    /**
     * Target implementation date for the policy.
     */
    private Instant targetImplementationDate;

    /**
     * Priority level for policy implementation (HIGH, MEDIUM, LOW).
     */
    @Size(max = 50)
    private String priority = "MEDIUM";

    /**
     * Business category or domain this policy applies to.
     */
    @Size(max = 100)
    private String category;

    /**
     * Tags for categorization and searchability.
     */
    @ElementCollection
    @CollectionTable(name = "policy_draft_tags", joinColumns = @JoinColumn(name = "draft_id"))
    @Column(name = "tag")
    private List<String> tags = new ArrayList<>();

    /**
     * Stakeholders who should be notified about this policy.
     */
    @ElementCollection
    @CollectionTable(name = "policy_draft_stakeholders", joinColumns = @JoinColumn(name = "draft_id"))
    @Column(name = "stakeholder_id", columnDefinition = "UUID")
    private List<UUID> stakeholders = new ArrayList<>();

    /**
     * Approval workflow metadata.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> approvalMetadata;

    /**
     * Reviewer comments and feedback.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> reviewComments = new ArrayList<>();

    // Audit fields
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Column(columnDefinition = "UUID", nullable = false)
    private UUID createdByUserId;

    @Column(columnDefinition = "UUID")
    private UUID updatedByUserId;

    private Instant submittedAt;

    @Column(columnDefinition = "UUID")
    private UUID submittedByUserId;

    private Instant approvedAt;

    @Column(columnDefinition = "UUID")
    private UUID approvedByUserId;

    private Instant rejectedAt;

    @Column(columnDefinition = "UUID")
    private UUID rejectedByUserId;

    private Instant publishedAt;

    @Column(columnDefinition = "UUID")
    private UUID publishedByUserId;

    // Constructors
    public PolicyDraft() {}

    public PolicyDraft(String name, String description, UUID createdByUserId) {
        this.name = name;
        this.description = description;
        this.createdByUserId = createdByUserId;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PolicyDraftStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyDraftStatus status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public UUID getBasePolicyId() {
        return basePolicyId;
    }

    public void setBasePolicyId(UUID basePolicyId) {
        this.basePolicyId = basePolicyId;
    }

    public String getConditionLogic() {
        return conditionLogic;
    }

    public void setConditionLogic(String conditionLogic) {
        this.conditionLogic = conditionLogic;
    }

    public List<Map<String, Object>> getRulesDefinition() {
        return rulesDefinition;
    }

    public void setRulesDefinition(List<Map<String, Object>> rulesDefinition) {
        this.rulesDefinition = rulesDefinition;
    }

    public Map<String, Object> getActions() {
        return actions;
    }

    public void setActions(Map<String, Object> actions) {
        this.actions = actions;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Map<String, Object> getAccessSubjects() {
        return accessSubjects;
    }

    public void setAccessSubjects(Map<String, Object> accessSubjects) {
        this.accessSubjects = accessSubjects;
    }

    public List<String> getAccessActions() {
        return accessActions;
    }

    public void setAccessActions(List<String> accessActions) {
        this.accessActions = accessActions;
    }

    public Map<String, Object> getAccessCondition() {
        return accessCondition;
    }

    public void setAccessCondition(Map<String, Object> accessCondition) {
        this.accessCondition = accessCondition;
    }

    public String getAccessEffect() {
        return accessEffect;
    }

    public void setAccessEffect(String accessEffect) {
        this.accessEffect = accessEffect;
    }

    public List<Map<String, Object>> getAccessObligations() {
        return accessObligations;
    }

    public void setAccessObligations(List<Map<String, Object>> accessObligations) {
        this.accessObligations = accessObligations;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
    }

    public String getExpectedImpact() {
        return expectedImpact;
    }

    public void setExpectedImpact(String expectedImpact) {
        this.expectedImpact = expectedImpact;
    }

    public Instant getTargetImplementationDate() {
        return targetImplementationDate;
    }

    public void setTargetImplementationDate(Instant targetImplementationDate) {
        this.targetImplementationDate = targetImplementationDate;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public List<UUID> getStakeholders() {
        return stakeholders;
    }

    public void setStakeholders(List<UUID> stakeholders) {
        this.stakeholders = stakeholders;
    }

    public Map<String, Object> getApprovalMetadata() {
        return approvalMetadata;
    }

    public void setApprovalMetadata(Map<String, Object> approvalMetadata) {
        this.approvalMetadata = approvalMetadata;
    }

    public List<Map<String, Object>> getReviewComments() {
        return reviewComments;
    }

    public void setReviewComments(List<Map<String, Object>> reviewComments) {
        this.reviewComments = reviewComments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public void setUpdatedByUserId(UUID updatedByUserId) {
        this.updatedByUserId = updatedByUserId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public UUID getSubmittedByUserId() {
        return submittedByUserId;
    }

    public void setSubmittedByUserId(UUID submittedByUserId) {
        this.submittedByUserId = submittedByUserId;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public UUID getApprovedByUserId() {
        return approvedByUserId;
    }

    public void setApprovedByUserId(UUID approvedByUserId) {
        this.approvedByUserId = approvedByUserId;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public UUID getRejectedByUserId() {
        return rejectedByUserId;
    }

    public void setRejectedByUserId(UUID rejectedByUserId) {
        this.rejectedByUserId = rejectedByUserId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public UUID getPublishedByUserId() {
        return publishedByUserId;
    }

    public void setPublishedByUserId(UUID publishedByUserId) {
        this.publishedByUserId = publishedByUserId;
    }

    /**
     * Utility method to add a review comment.
     */
    public void addReviewComment(String comment, UUID reviewerId, String reviewerRole) {
        Map<String, Object> commentData = Map.of(
            "comment", comment,
            "reviewerId", reviewerId.toString(),
            "reviewerRole", reviewerRole,
            "timestamp", Instant.now().toString()
        );
        this.reviewComments.add(commentData);
    }

    /**
     * Utility method to add a tag if not already present.
     */
    public void addTag(String tag) {
        if (!this.tags.contains(tag)) {
            this.tags.add(tag);
        }
    }

    /**
     * Utility method to add a stakeholder if not already present.
     */
    public void addStakeholder(UUID stakeholderId) {
        if (!this.stakeholders.contains(stakeholderId)) {
            this.stakeholders.add(stakeholderId);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
} 