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

/**
 * DTO for returning policy draft information including workflow status and audit trail.
 * Contains comprehensive information for UI display and workflow management.
 */
public class PolicyDraftOutputDTO {

    private UUID id;
    private String name;
    private String description;
    private String status;
    private Integer version;
    private UUID basePolicyId;
    private String conditionLogic;
    private List<Map<String, Object>> rulesDefinition;
    private Map<String, Object> actions;

    // AccessPolicy structured fields (ADR-0040, kind == AccessPolicy)
    private String kind;
    private Map<String, Object> accessSubjects;
    private List<String> accessActions;
    private Map<String, Object> accessCondition;
    private String accessEffect;
    private List<Map<String, Object>> accessObligations;

    private String changeSummary;
    private String justification;
    private String expectedImpact;
    private Instant targetImplementationDate;
    private String priority;
    private String category;
    private List<String> tags;
    private List<UUID> stakeholders;
    private Map<String, Object> approvalMetadata;
    private List<Map<String, Object>> reviewComments;

    // Audit trail information
    private Instant createdAt;
    private Instant updatedAt;
    private UUID createdByUserId;
    private UUID updatedByUserId;
    private Instant submittedAt;
    private UUID submittedByUserId;
    private Instant approvedAt;
    private UUID approvedByUserId;
    private Instant rejectedAt;
    private UUID rejectedByUserId;
    private Instant publishedAt;
    private UUID publishedByUserId;

    // Enhanced workflow information
    private WorkflowStatusDTO workflowStatus;
    private List<WorkflowActionDTO> availableActions;

    // Constructors
    public PolicyDraftOutputDTO() {}

    public PolicyDraftOutputDTO(UUID id, String name, String status) {
        this.id = id;
        this.name = name;
        this.status = status;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public WorkflowStatusDTO getWorkflowStatus() {
        return workflowStatus;
    }

    public void setWorkflowStatus(WorkflowStatusDTO workflowStatus) {
        this.workflowStatus = workflowStatus;
    }

    public List<WorkflowActionDTO> getAvailableActions() {
        return availableActions;
    }

    public void setAvailableActions(List<WorkflowActionDTO> availableActions) {
        this.availableActions = availableActions;
    }

    /**
     * Nested DTO for workflow status information.
     */
    public static class WorkflowStatusDTO {
        private String currentStage;
        private String statusDescription;
        private List<String> nextPossibleStates;
        private boolean canEdit;
        private boolean canSubmit;
        private boolean canApprove;
        private boolean canReject;
        private boolean canPublish;
        private String stageColor; // For UI status indicators
        private Integer daysInCurrentStatus;

        // Constructors, getters, and setters
        public WorkflowStatusDTO() {}

        public String getCurrentStage() { return currentStage; }
        public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }

        public String getStatusDescription() { return statusDescription; }
        public void setStatusDescription(String statusDescription) { this.statusDescription = statusDescription; }

        public List<String> getNextPossibleStates() { return nextPossibleStates; }
        public void setNextPossibleStates(List<String> nextPossibleStates) { this.nextPossibleStates = nextPossibleStates; }

        public boolean isCanEdit() { return canEdit; }
        public void setCanEdit(boolean canEdit) { this.canEdit = canEdit; }

        public boolean isCanSubmit() { return canSubmit; }
        public void setCanSubmit(boolean canSubmit) { this.canSubmit = canSubmit; }

        public boolean isCanApprove() { return canApprove; }
        public void setCanApprove(boolean canApprove) { this.canApprove = canApprove; }

        public boolean isCanReject() { return canReject; }
        public void setCanReject(boolean canReject) { this.canReject = canReject; }

        public boolean isCanPublish() { return canPublish; }
        public void setCanPublish(boolean canPublish) { this.canPublish = canPublish; }

        public String getStageColor() { return stageColor; }
        public void setStageColor(String stageColor) { this.stageColor = stageColor; }

        public Integer getDaysInCurrentStatus() { return daysInCurrentStatus; }
        public void setDaysInCurrentStatus(Integer daysInCurrentStatus) { this.daysInCurrentStatus = daysInCurrentStatus; }
    }

    /**
     * Nested DTO for available workflow actions.
     */
    public static class WorkflowActionDTO {
        private String actionType;
        private String actionLabel;
        private String actionDescription;
        private boolean requiresComment;
        private String confirmationMessage;
        private String buttonStyle; // For UI styling

        // Constructors, getters, and setters
        public WorkflowActionDTO() {}

        public WorkflowActionDTO(String actionType, String actionLabel) {
            this.actionType = actionType;
            this.actionLabel = actionLabel;
        }

        public String getActionType() { return actionType; }
        public void setActionType(String actionType) { this.actionType = actionType; }

        public String getActionLabel() { return actionLabel; }
        public void setActionLabel(String actionLabel) { this.actionLabel = actionLabel; }

        public String getActionDescription() { return actionDescription; }
        public void setActionDescription(String actionDescription) { this.actionDescription = actionDescription; }

        public boolean isRequiresComment() { return requiresComment; }
        public void setRequiresComment(boolean requiresComment) { this.requiresComment = requiresComment; }

        public String getConfirmationMessage() { return confirmationMessage; }
        public void setConfirmationMessage(String confirmationMessage) { this.confirmationMessage = confirmationMessage; }

        public String getButtonStyle() { return buttonStyle; }
        public void setButtonStyle(String buttonStyle) { this.buttonStyle = buttonStyle; }
    }
} 