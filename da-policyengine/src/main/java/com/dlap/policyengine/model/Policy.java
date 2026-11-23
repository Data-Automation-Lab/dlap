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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A governance policy. Since AUD-02a a policy is never deleted: a delete sets {@link #getDeletedAt()},
 * disables it and freezes the row (Flyway V9 trigger), and every change adds an immutable
 * {@link PolicyVersion}. A deleted policy is invisible to every entity query ({@code @SQLRestriction}),
 * so the evaluators, the schedule sweep and the API see live policies only; its versions stay readable
 * through the versions API.
 */
@Entity
@Table(name = "policies")
@SQLRestriction("deleted_at IS NULL")
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotBlank
    @Size(max = 255)
    // Unique among live policies only (partial index uq_policies_live_name, Flyway V9).
    @Column(nullable = false)
    private String name;

    @Size(max = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status = PolicyStatus.DISABLED;

    // Free-text note kept for display. It is never compiled or evaluated (SEC-04, ADR-0057);
    // the DSL document below is the only evaluated form of a policy.
    @Column(columnDefinition = "TEXT")
    private String conditionLogic;

    // Retired MVEL rules (SEC-04, ADR-0057). Flyway V5 archived them to retired_mvel_rules,
    // disabled their policies and emptied policy_rules, which now refuses new rows. Always
    // empty on a migrated database; kept mapped until the table is dropped.
    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<PolicyRule> rules = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> actions; // e.g., { "notify": { "email": "admin@example.com" }, "addLabel": "Sensitive" }

    // Canonical dlap/v1 DSL document for this policy (ADR-0031). This is the
    // single source of truth the PolicyDslEvaluator parses and runs. Authored by
    // the visual builder (via PolicyDslTranslator) or edited directly as YAML.
    // Supersedes the v0.1.0 overload that stashed the DSL in `description`.
    @Column(columnDefinition = "TEXT")
    private String policyDocument;

    // POL-04 (ADR-0136): LEGACY_RULE for a row that never had a document, DSL for the rest. The
    // default is DSL, so a row nobody classified is judged by its document and fails closed.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PolicyKind policyKind = PolicyKind.DSL;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Column(columnDefinition = "UUID")
    private UUID createdByUserId;

    @Column(columnDefinition = "UUID")
    private UUID updatedByUserId;

    // AUD-02a: soft delete and the number of the newest version in policy_versions (0 for a policy
    // created before Flyway V9 that has not changed since).
    private Instant deletedAt;

    @Column(columnDefinition = "UUID")
    private UUID deletedByUserId;

    @Column(nullable = false)
    private int currentVersion;

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

    public PolicyStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyStatus status) {
        this.status = status;
    }

    public String getConditionLogic() {
        return conditionLogic;
    }

    public void setConditionLogic(String conditionLogic) {
        this.conditionLogic = conditionLogic;
    }

    public List<PolicyRule> getRules() {
        return rules;
    }

    public void setRules(List<PolicyRule> rules) {
        this.rules = rules;
        this.rules.forEach(rule -> rule.setPolicy(this));
    }

    public void addRule(PolicyRule rule) {
        this.rules.add(rule);
        rule.setPolicy(this);
    }

    public void removeRule(PolicyRule rule) {
        this.rules.remove(rule);
        rule.setPolicy(null);
    }

    public Map<String, Object> getActions() {
        return actions;
    }

    public void setActions(Map<String, Object> actions) {
        this.actions = actions;
    }

    public String getPolicyDocument() {
        return policyDocument;
    }

    public void setPolicyDocument(String policyDocument) {
        this.policyDocument = policyDocument;
    }

    /**
     * How this row is evaluated.
     *
     * @return the kind; never null
     */
    public PolicyKind getPolicyKind() {
        return policyKind;
    }

    /**
     * Sets how this row is evaluated.
     *
     * @param policyKind the kind; a null value is kept as {@link PolicyKind#DSL}
     */
    public void setPolicyKind(PolicyKind policyKind) {
        this.policyKind = policyKind != null ? policyKind : PolicyKind.DSL;
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

    /**
     * When the policy was deleted; null while it is live.
     *
     * @return the delete time
     */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * Sets the delete time.
     *
     * @param deletedAt the delete time
     */
    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /**
     * Who deleted the policy.
     *
     * @return the user id, or null
     */
    public UUID getDeletedByUserId() {
        return deletedByUserId;
    }

    /**
     * Sets who deleted the policy.
     *
     * @param deletedByUserId the user id
     */
    public void setDeletedByUserId(UUID deletedByUserId) {
        this.deletedByUserId = deletedByUserId;
    }

    /**
     * The number of the newest version; 0 when the policy has none yet.
     *
     * @return the version number
     */
    public int getCurrentVersion() {
        return currentVersion;
    }

    /**
     * Sets the number of the newest version.
     *
     * @param currentVersion the version number
     */
    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    /**
     * Fills the creation stamps when the service did not set them; PolicyService sets them from its
     * clock, equal to the time of the version the change records.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
} 