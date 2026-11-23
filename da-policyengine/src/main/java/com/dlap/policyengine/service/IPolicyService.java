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

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.dlap.policyengine.dto.PolicyDraftActionDTO;
import com.dlap.policyengine.dto.PolicyDraftInputDTO;
import com.dlap.policyengine.dto.PolicyDraftOutputDTO;
import com.dlap.policyengine.dto.PolicyImpactRequestDTO;
import com.dlap.policyengine.dto.PolicyImpactResponseDTO;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.dto.PolicySummaryDTO;
import com.dlap.policyengine.model.PolicyDraftStatus;

public interface IPolicyService {
    Page<PolicySummaryDTO> getAllPolicies(Pageable pageable, String status, String nameContains);
    PolicyOutputDTO getPolicyById(UUID policyId);
    PolicyOutputDTO createPolicy(PolicyInputDTO policyInputDTO, UUID creatorUserId);
    PolicyOutputDTO updatePolicy(UUID policyId, PolicyInputDTO policyInputDTO, UUID updaterUserId);
    PolicyOutputDTO setPolicyStatus(UUID policyId, com.dlap.policyengine.model.PolicyStatus status, UUID updaterUserId);
    void deletePolicy(UUID policyId);
    
    /**
     * Analyze the potential impact of a policy before implementation.
     * Provides comprehensive assessment of affected assets, performance impact,
     * cost implications, and compliance analysis.
     * 
     * @param request the policy impact analysis request containing rules and analysis parameters
     * @return comprehensive impact analysis response with affected assets and risk assessment
     */
    PolicyImpactResponseDTO analyzePolicy(PolicyImpactRequestDTO request);

    /**
     * Create a new policy draft.
     * 
     * @param draftInput the draft input data
     * @param creatorUserId the ID of the user creating the draft
     * @return the created draft output DTO
     */
    PolicyDraftOutputDTO createDraft(PolicyDraftInputDTO draftInput, UUID creatorUserId);

    /**
     * Update an existing policy draft (only allowed in CREATED or REQUIRES_CHANGES status).
     * 
     * @param draftId the ID of the draft to update
     * @param draftInput the updated draft data
     * @param updaterUserId the ID of the user updating the draft
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO updateDraft(UUID draftId, PolicyDraftInputDTO draftInput, UUID updaterUserId);

    /**
     * Get a policy draft by ID with full details including workflow information.
     * 
     * @param draftId the ID of the draft
     * @return the draft output DTO with complete information
     */
    PolicyDraftOutputDTO getDraftById(UUID draftId);

    /**
     * Get all policy drafts with filtering and pagination.
     * 
     * @param pageable pagination information
     * @param status optional status filter
     * @param category optional category filter
     * @param priority optional priority filter
     * @param createdBy optional creator filter
     * @param nameContains optional name search filter
     * @return paginated list of draft summaries
     */
    Page<PolicyDraftOutputDTO> getAllDrafts(
        Pageable pageable, 
        PolicyDraftStatus status, 
        String category, 
        String priority, 
        UUID createdBy, 
        String nameContains
    );

    /**
     * Get drafts requiring attention from a specific user (created by them or they are stakeholder).
     * 
     * @param userId the user ID
     * @param pageable pagination information
     * @return paginated list of drafts requiring attention
     */
    Page<PolicyDraftOutputDTO> getDraftsRequiringAttention(UUID userId, Pageable pageable);

    /**
     * Get drafts pending review (submitted or under review status).
     * 
     * @param pageable pagination information
     * @return paginated list of drafts pending review
     */
    Page<PolicyDraftOutputDTO> getDraftsPendingReview(Pageable pageable);

    /**
     * Submit a draft for review (transition from CREATED to SUBMITTED).
     * 
     * @param draftId the ID of the draft to submit
     * @param action the submit action DTO with optional comment
     * @param submittedByUserId the ID of the user submitting the draft
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO submitDraft(UUID draftId, PolicyDraftActionDTO action, UUID submittedByUserId);

    /**
     * Approve a draft (transition to APPROVED status).
     * 
     * @param draftId the ID of the draft to approve
     * @param action the approval action DTO with optional comment
     * @param approverUserId the ID of the user approving the draft
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO approveDraft(UUID draftId, PolicyDraftActionDTO action, UUID approverUserId);

    /**
     * Reject a draft (transition to REJECTED status).
     * 
     * @param draftId the ID of the draft to reject
     * @param action the rejection action DTO with required comment
     * @param rejectorUserId the ID of the user rejecting the draft
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO rejectDraft(UUID draftId, PolicyDraftActionDTO action, UUID rejectorUserId);

    /**
     * Request changes to a draft (transition to REQUIRES_CHANGES status).
     * 
     * @param draftId the ID of the draft requiring changes
     * @param action the action DTO with required comment explaining needed changes
     * @param reviewerUserId the ID of the user requesting changes
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO requestChanges(UUID draftId, PolicyDraftActionDTO action, UUID reviewerUserId);

    /**
     * Publish an approved draft as an active policy.
     * 
     * @param draftId the ID of the draft to publish
     * @param action the publish action DTO with optional metadata
     * @param publisherUserId the ID of the user publishing the draft
     * @return the published policy output DTO
     */
    PolicyOutputDTO publishDraft(UUID draftId, PolicyDraftActionDTO action, UUID publisherUserId);

    /**
     * Archive a draft (transition to ARCHIVED status).
     * 
     * @param draftId the ID of the draft to archive
     * @param action the archive action DTO with optional comment
     * @param archiverUserId the ID of the user archiving the draft
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO archiveDraft(UUID draftId, PolicyDraftActionDTO action, UUID archiverUserId);

    /**
     * Add a review comment to a draft.
     * 
     * @param draftId the ID of the draft
     * @param comment the review comment
     * @param reviewerUserId the ID of the reviewer
     * @param reviewerRole the role of the reviewer
     * @return the updated draft output DTO
     */
    PolicyDraftOutputDTO addReviewComment(UUID draftId, String comment, UUID reviewerUserId, String reviewerRole);

    /**
     * Delete a draft (only allowed in CREATED status).
     * 
     * @param draftId the ID of the draft to delete
     * @param deleterUserId the ID of the user deleting the draft
     */
    void deleteDraft(UUID draftId, UUID deleterUserId);

    /**
     * Get available categories for drafts.
     * 
     * @return list of available categories
     */
    List<String> getDraftCategories();

    /**
     * Get available tags used in drafts.
     * 
     * @return list of available tags
     */
    List<String> getDraftTags();

    /**
     * Get draft statistics for dashboard displays.
     * 
     * @return draft statistics grouped by status
     */
    List<Object[]> getDraftStatistics();

    /**
     * Get overdue drafts (target implementation date passed and not published).
     * 
     * @return list of overdue drafts
     */
    List<PolicyDraftOutputDTO> getOverdueDrafts();

    /**
     * Clone an existing policy as a new draft for modification.
     * 
     * @param policyId the ID of the policy to clone
     * @param creatorUserId the ID of the user creating the draft
     * @return the created draft output DTO
     */
    PolicyDraftOutputDTO clonePolicyAsDraft(UUID policyId, UUID creatorUserId);

    /**
     * Create a new version of an existing draft.
     * 
     * @param draftId the ID of the draft to create new version from
     * @param draftInput the updated draft data
     * @param creatorUserId the ID of the user creating the new version
     * @return the new version draft output DTO
     */
    PolicyDraftOutputDTO createDraftVersion(UUID draftId, PolicyDraftInputDTO draftInput, UUID creatorUserId);
} 