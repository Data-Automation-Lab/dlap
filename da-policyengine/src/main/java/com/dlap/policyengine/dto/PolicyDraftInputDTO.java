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

package com.dlap.policyengine.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for creating and updating policy drafts.
 * Contains all information needed for draft management and workflow.
 */
public class PolicyDraftInputDTO {

    /**
     * Name of the policy (must be unique when published).
     */
    @NotBlank(message = "Policy name is required")
    @Size(max = 255, message = "Policy name must not exceed 255 characters")
    private String name;

    /**
     * Detailed description of the policy's purpose and scope.
     */
    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    /**
     * Reference to the published policy if this is an update.
     */
    private UUID basePolicyId;

    /**
     * DSL kind (ADR-0040): {@code Policy} (lifecycle, default) or
     * {@code AccessPolicy} (Consumed-stage access intent). Optional; when omitted
     * the draft is a lifecycle Policy.
     */
    private String kind;

    /**
     * Free-text note on how the conditions combine, kept for display. It is never compiled
     * or evaluated (SEC-04, ADR-0057); publication translates rulesDefinition into the DSL.
     */
    private String conditionLogic;

    /**
     * JSON representation of policy rules structure. For an AccessPolicy this is
     * reused as the {@code spec.match} asset selector.
     */
    private List<Map<String, Object>> rulesDefinition;

    /**
     * JSON representation of actions to be taken when policy is triggered.
     * (kind == Policy)
     */
    private Map<String, Object> actions;

    /* ----- AccessPolicy structured fields (kind == AccessPolicy) ----- */

    /** {@code {tiers: [...], roles: [...]}} - who the access policy applies to. */
    private Map<String, Object> accessSubjects;

    /** AuthZEN action names: read/query/export/write/delete. */
    private List<String> accessActions;

    /** {@code {purpose: [...], consent: Boolean, region: [...]}} - request context. */
    private Map<String, Object> accessCondition;

    /** {@code allow} or {@code deny}. */
    private String accessEffect;

    /** Rows of {@code {type, labels: [...]}}. */
    private List<Map<String, Object>> accessObligations;

    /**
     * Change summary describing what was modified in this version.
     */
    @Size(max = 1000, message = "Change summary must not exceed 1000 characters")
    private String changeSummary;

    /**
     * Justification for the policy changes or creation.
     */
    @Size(max = 2000, message = "Justification must not exceed 2000 characters")
    private String justification;

    /**
     * Expected impact of implementing this policy.
     */
    @Size(max = 1000, message = "Expected impact must not exceed 1000 characters")
    private String expectedImpact;

    /**
     * Target implementation date for the policy.
     */
    private Instant targetImplementationDate;

    /**
     * Priority level for policy implementation.
     */
    private String priority = "MEDIUM";

    /**
     * Business category or domain this policy applies to.
     */
    @Size(max = 100, message = "Category must not exceed 100 characters")
    private String category;

    /**
     * Tags for categorization and searchability.
     */
    private List<String> tags;

    /**
     * Stakeholders who should be notified about this policy.
     */
    private List<UUID> stakeholders;

    /**
     * Additional metadata for workflow management.
     */
    private Map<String, Object> workflowMetadata;

    // Constructors
    public PolicyDraftInputDTO() {}

    public PolicyDraftInputDTO(String name, String description) {
        this.name = name;
        this.description = description;
    }

    // Getters and Setters
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

    public Map<String, Object> getWorkflowMetadata() {
        return workflowMetadata;
    }

    public void setWorkflowMetadata(Map<String, Object> workflowMetadata) {
        this.workflowMetadata = workflowMetadata;
    }
} 