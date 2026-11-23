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

package com.dlap.policyengine.mapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.dlap.policyengine.dto.PolicyDraftInputDTO;
import com.dlap.policyengine.dto.PolicyDraftOutputDTO;
import com.dlap.policyengine.model.PolicyDraft;
import com.dlap.policyengine.model.PolicyDraftStatus;

/**
 * Mapper for converting between PolicyDraft entities and DTOs.
 * Handles workflow status calculation and available actions based on current status and user permissions.
 */
@Component
public class PolicyDraftMapper {

    /**
     * Convert PolicyDraft entity to output DTO with workflow information.
     */
    public PolicyDraftOutputDTO toOutputDTO(PolicyDraft draft) {
        if (draft == null) {
            return null;
        }

        PolicyDraftOutputDTO dto = new PolicyDraftOutputDTO();
        
        // Basic information
        dto.setId(draft.getId());
        dto.setName(draft.getName());
        dto.setDescription(draft.getDescription());
        dto.setStatus(draft.getStatus().name());
        dto.setVersion(draft.getVersion());
        dto.setBasePolicyId(draft.getBasePolicyId());
        dto.setConditionLogic(draft.getConditionLogic());
        dto.setRulesDefinition(draft.getRulesDefinition());
        dto.setActions(draft.getActions());
        // AccessPolicy fields (ADR-0040)
        dto.setKind(draft.getKind());
        dto.setAccessSubjects(draft.getAccessSubjects());
        dto.setAccessActions(draft.getAccessActions());
        dto.setAccessCondition(draft.getAccessCondition());
        dto.setAccessEffect(draft.getAccessEffect());
        dto.setAccessObligations(draft.getAccessObligations());
        dto.setChangeSummary(draft.getChangeSummary());
        dto.setJustification(draft.getJustification());
        dto.setExpectedImpact(draft.getExpectedImpact());
        dto.setTargetImplementationDate(draft.getTargetImplementationDate());
        dto.setPriority(draft.getPriority());
        dto.setCategory(draft.getCategory());
        dto.setTags(draft.getTags());
        dto.setStakeholders(draft.getStakeholders());
        dto.setApprovalMetadata(draft.getApprovalMetadata());
        dto.setReviewComments(draft.getReviewComments());

        // Audit trail
        dto.setCreatedAt(draft.getCreatedAt());
        dto.setUpdatedAt(draft.getUpdatedAt());
        dto.setCreatedByUserId(draft.getCreatedByUserId());
        dto.setUpdatedByUserId(draft.getUpdatedByUserId());
        dto.setSubmittedAt(draft.getSubmittedAt());
        dto.setSubmittedByUserId(draft.getSubmittedByUserId());
        dto.setApprovedAt(draft.getApprovedAt());
        dto.setApprovedByUserId(draft.getApprovedByUserId());
        dto.setRejectedAt(draft.getRejectedAt());
        dto.setRejectedByUserId(draft.getRejectedByUserId());
        dto.setPublishedAt(draft.getPublishedAt());
        dto.setPublishedByUserId(draft.getPublishedByUserId());

        // Enhanced workflow information
        dto.setWorkflowStatus(createWorkflowStatus(draft));
        dto.setAvailableActions(createAvailableActions(draft));

        return dto;
    }

    /**
     * Convert input DTO to PolicyDraft entity.
     */
    public PolicyDraft toEntity(PolicyDraftInputDTO inputDTO) {
        if (inputDTO == null) {
            return null;
        }

        PolicyDraft draft = new PolicyDraft();
        
        draft.setName(inputDTO.getName());
        draft.setDescription(inputDTO.getDescription());
        draft.setBasePolicyId(inputDTO.getBasePolicyId());
        draft.setConditionLogic(inputDTO.getConditionLogic());
        draft.setRulesDefinition(inputDTO.getRulesDefinition());
        draft.setActions(inputDTO.getActions());
        applyKindAndAccessFields(draft, inputDTO);
        draft.setChangeSummary(inputDTO.getChangeSummary());
        draft.setJustification(inputDTO.getJustification());
        draft.setExpectedImpact(inputDTO.getExpectedImpact());
        draft.setTargetImplementationDate(inputDTO.getTargetImplementationDate());
        draft.setPriority(inputDTO.getPriority());
        draft.setCategory(inputDTO.getCategory());

        if (inputDTO.getTags() != null) {
            draft.setTags(new ArrayList<>(inputDTO.getTags()));
        }
        if (inputDTO.getStakeholders() != null) {
            draft.setStakeholders(new ArrayList<>(inputDTO.getStakeholders()));
        }

        return draft;
    }

    /**
     * Map the DSL kind + AccessPolicy structured fields (ADR-0040). A missing
     * or blank kind defaults to {@code Policy} so lifecycle drafts are unaffected.
     */
    private void applyKindAndAccessFields(PolicyDraft draft, PolicyDraftInputDTO inputDTO) {
        String kind = inputDTO.getKind();
        draft.setKind(kind != null && !kind.isBlank()
                ? kind.trim()
                : com.dlap.policyengine.dsl.PolicyDsl.KIND_POLICY);
        draft.setAccessSubjects(inputDTO.getAccessSubjects());
        draft.setAccessActions(inputDTO.getAccessActions());
        draft.setAccessCondition(inputDTO.getAccessCondition());
        draft.setAccessEffect(inputDTO.getAccessEffect());
        draft.setAccessObligations(inputDTO.getAccessObligations());
    }

    /**
     * Update entity from input DTO (for updates).
     */
    public void updateEntity(PolicyDraft draft, PolicyDraftInputDTO inputDTO) {
        if (draft == null || inputDTO == null) {
            return;
        }

        draft.setName(inputDTO.getName());
        draft.setDescription(inputDTO.getDescription());
        draft.setBasePolicyId(inputDTO.getBasePolicyId());
        draft.setConditionLogic(inputDTO.getConditionLogic());
        draft.setRulesDefinition(inputDTO.getRulesDefinition());
        draft.setActions(inputDTO.getActions());
        applyKindAndAccessFields(draft, inputDTO);
        draft.setChangeSummary(inputDTO.getChangeSummary());
        draft.setJustification(inputDTO.getJustification());
        draft.setExpectedImpact(inputDTO.getExpectedImpact());
        draft.setTargetImplementationDate(inputDTO.getTargetImplementationDate());
        draft.setPriority(inputDTO.getPriority());
        draft.setCategory(inputDTO.getCategory());

        if (inputDTO.getTags() != null) {
            draft.getTags().clear();
            draft.getTags().addAll(inputDTO.getTags());
        }
        if (inputDTO.getStakeholders() != null) {
            draft.getStakeholders().clear();
            draft.getStakeholders().addAll(inputDTO.getStakeholders());
        }
    }

    /**
     * Create workflow status information for the draft.
     */
    private PolicyDraftOutputDTO.WorkflowStatusDTO createWorkflowStatus(PolicyDraft draft) {
        PolicyDraftOutputDTO.WorkflowStatusDTO status = new PolicyDraftOutputDTO.WorkflowStatusDTO();
        
        PolicyDraftStatus currentStatus = draft.getStatus();
        status.setCurrentStage(currentStatus.name());
        status.setStatusDescription(getStatusDescription(currentStatus));
        status.setNextPossibleStates(getNextPossibleStates(currentStatus));
        status.setStageColor(getStageColor(currentStatus));
        
        // Calculate days in current status
        Instant statusChangeTime = getLastStatusChangeTime(draft, currentStatus);
        if (statusChangeTime != null) {
            long days = ChronoUnit.DAYS.between(statusChangeTime, Instant.now());
            status.setDaysInCurrentStatus((int) days);
        }

        // Set permission flags (simplified - would integrate with security context in real implementation)
        status.setCanEdit(canEdit(currentStatus));
        status.setCanSubmit(canSubmit(currentStatus));
        status.setCanApprove(canApprove(currentStatus));
        status.setCanReject(canReject(currentStatus));
        status.setCanPublish(canPublish(currentStatus));

        return status;
    }

    /**
     * Create available workflow actions for the draft.
     */
    private List<PolicyDraftOutputDTO.WorkflowActionDTO> createAvailableActions(PolicyDraft draft) {
        List<PolicyDraftOutputDTO.WorkflowActionDTO> actions = new ArrayList<>();
        PolicyDraftStatus status = draft.getStatus();

        switch (status) {
            case CREATED:
            case REQUIRES_CHANGES:
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("SUBMIT", "Submit for Review"));
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("ARCHIVE", "Archive Draft"));
                break;
            case SUBMITTED:
            case UNDER_REVIEW:
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("APPROVE", "Approve Draft"));
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("REJECT", "Reject Draft"));
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("REQUEST_CHANGES", "Request Changes"));
                break;
            case APPROVED:
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("PUBLISH", "Publish Policy"));
                actions.add(new PolicyDraftOutputDTO.WorkflowActionDTO("REJECT", "Reject Draft"));
                break;
            default:
                // No actions available for REJECTED, PUBLISHED, ARCHIVED
                break;
        }

        // Set action properties
        for (PolicyDraftOutputDTO.WorkflowActionDTO action : actions) {
            setActionProperties(action);
        }

        return actions;
    }

    /**
     * Get human-readable description for status.
     */
    private String getStatusDescription(PolicyDraftStatus status) {
        switch (status) {
            case CREATED:
                return "Draft has been created and is ready for editing";
            case SUBMITTED:
                return "Draft has been submitted and is awaiting review";
            case UNDER_REVIEW:
                return "Draft is currently being reviewed";
            case REQUIRES_CHANGES:
                return "Draft requires changes based on reviewer feedback";
            case APPROVED:
                return "Draft has been approved and is ready for publication";
            case REJECTED:
                return "Draft has been rejected and will not be published";
            case PUBLISHED:
                return "Draft has been published as an active policy";
            case ARCHIVED:
                return "Draft has been archived and is no longer active";
            default:
                return "Unknown status";
        }
    }

    /**
     * Get next possible states for workflow transitions.
     */
    private List<String> getNextPossibleStates(PolicyDraftStatus status) {
        switch (status) {
            case CREATED:
            case REQUIRES_CHANGES:
                return Arrays.asList("SUBMITTED", "ARCHIVED");
            case SUBMITTED:
                return Arrays.asList("UNDER_REVIEW", "APPROVED", "REJECTED", "REQUIRES_CHANGES");
            case UNDER_REVIEW:
                return Arrays.asList("APPROVED", "REJECTED", "REQUIRES_CHANGES");
            case APPROVED:
                return Arrays.asList("PUBLISHED", "REJECTED");
            default:
                return new ArrayList<>(); // Terminal states
        }
    }

    /**
     * Get color for UI status indicators.
     */
    private String getStageColor(PolicyDraftStatus status) {
        switch (status) {
            case CREATED:
                return "blue";
            case SUBMITTED:
            case UNDER_REVIEW:
                return "orange";
            case REQUIRES_CHANGES:
                return "yellow";
            case APPROVED:
                return "green";
            case REJECTED:
                return "red";
            case PUBLISHED:
                return "purple";
            case ARCHIVED:
                return "gray";
            default:
                return "gray";
        }
    }

    /**
     * Get the timestamp of last status change.
     */
    private Instant getLastStatusChangeTime(PolicyDraft draft, PolicyDraftStatus currentStatus) {
        switch (currentStatus) {
            case SUBMITTED:
                return draft.getSubmittedAt();
            case APPROVED:
                return draft.getApprovedAt();
            case REJECTED:
                return draft.getRejectedAt();
            case PUBLISHED:
                return draft.getPublishedAt();
            default:
                return draft.getUpdatedAt();
        }
    }

    /**
     * Check if draft can be edited in current status.
     */
    private boolean canEdit(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.CREATED || status == PolicyDraftStatus.REQUIRES_CHANGES;
    }

    /**
     * Check if draft can be submitted in current status.
     */
    private boolean canSubmit(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.CREATED || status == PolicyDraftStatus.REQUIRES_CHANGES;
    }

    /**
     * Check if draft can be approved in current status.
     */
    private boolean canApprove(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.SUBMITTED || status == PolicyDraftStatus.UNDER_REVIEW;
    }

    /**
     * Check if draft can be rejected in current status.
     */
    private boolean canReject(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.SUBMITTED || 
               status == PolicyDraftStatus.UNDER_REVIEW || 
               status == PolicyDraftStatus.APPROVED;
    }

    /**
     * Check if draft can be published in current status.
     */
    private boolean canPublish(PolicyDraftStatus status) {
        return status == PolicyDraftStatus.APPROVED;
    }

    /**
     * Set properties for workflow actions.
     */
    private void setActionProperties(PolicyDraftOutputDTO.WorkflowActionDTO action) {
        switch (action.getActionType()) {
            case "SUBMIT":
                action.setActionDescription("Submit the draft for review by approvers");
                action.setRequiresComment(false);
                action.setConfirmationMessage("Are you sure you want to submit this draft for review?");
                action.setButtonStyle("primary");
                break;
            case "APPROVE":
                action.setActionDescription("Approve the draft for publication");
                action.setRequiresComment(false);
                action.setConfirmationMessage("Are you sure you want to approve this draft?");
                action.setButtonStyle("success");
                break;
            case "REJECT":
                action.setActionDescription("Reject the draft and prevent publication");
                action.setRequiresComment(true);
                action.setConfirmationMessage("Are you sure you want to reject this draft? This action cannot be undone.");
                action.setButtonStyle("danger");
                break;
            case "REQUEST_CHANGES":
                action.setActionDescription("Request changes from the draft author");
                action.setRequiresComment(true);
                action.setConfirmationMessage("Please provide details about the required changes");
                action.setButtonStyle("warning");
                break;
            case "PUBLISH":
                action.setActionDescription("Publish the draft as an active policy");
                action.setRequiresComment(false);
                action.setConfirmationMessage("Are you sure you want to publish this draft as an active policy?");
                action.setButtonStyle("success");
                break;
            case "ARCHIVE":
                action.setActionDescription("Archive the draft");
                action.setRequiresComment(false);
                action.setConfirmationMessage("Are you sure you want to archive this draft?");
                action.setButtonStyle("secondary");
                break;
        }
    }
} 