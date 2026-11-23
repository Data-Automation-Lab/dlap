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

package com.dlap.policyengine.web.rest;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.dto.PolicyDraftActionDTO;
import com.dlap.policyengine.dto.PolicyDraftInputDTO;
import com.dlap.policyengine.dto.PolicyDraftOutputDTO;
import com.dlap.policyengine.dto.PolicyOutputDTO;
import com.dlap.policyengine.model.PolicyDraftStatus;
import com.dlap.policyengine.service.IPolicyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST controller for policy draft management operations.
 * Provides comprehensive draft workflow including creation, review, approval, and publication.
 */
@RestController
@RequestMapping("/api/v1/policyengine/drafts")
@Tag(name = "Policy Draft Management", description = "Endpoints for managing policy drafts and approval workflows")
public class PolicyDraftController {

    private static final Logger log = LoggerFactory.getLogger(PolicyDraftController.class);

    private final IPolicyService policyService;

    @Autowired
    public PolicyDraftController(IPolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(
        summary = "Create a new policy draft",
        description = "Creates a new policy draft that can be edited, submitted for review, and eventually published as an active policy."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Policy draft created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid draft data provided"),
        @ApiResponse(responseCode = "409", description = "Draft with this name already exists"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to create drafts")
    })
    public ResponseEntity<PolicyDraftOutputDTO> createDraft(
            @Parameter(description = "Policy draft creation data")
            @Valid @RequestBody PolicyDraftInputDTO draftInput) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Creating policy draft: {} by user: {}", draftInput.getName(), currentUserId);

        PolicyDraftOutputDTO createdDraft = policyService.createDraft(draftInput, currentUserId);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(createdDraft);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get all policy drafts with filtering",
        description = "Retrieves paginated list of policy drafts with optional filtering by status, category, priority, creator, and name search."
    )
    public ResponseEntity<Page<PolicyDraftOutputDTO>> getAllDrafts(
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable,
            @Parameter(description = "Filter by draft status") @RequestParam(required = false) PolicyDraftStatus status,
            @Parameter(description = "Filter by category") @RequestParam(required = false) String category,
            @Parameter(description = "Filter by priority level") @RequestParam(required = false) String priority,
            @Parameter(description = "Filter by creator user ID") @RequestParam(required = false) UUID createdBy,
            @Parameter(description = "Search in draft names") @RequestParam(required = false) String nameContains) {

        log.debug("Retrieving policy drafts with filters - status: {}, category: {}, priority: {}", 
                 status, category, priority);

        Page<PolicyDraftOutputDTO> drafts = policyService.getAllDrafts(
            pageable, status, category, priority, createdBy, nameContains);
        
        return ResponseEntity.ok(drafts);
    }

    @GetMapping("/{draftId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get policy draft by ID",
        description = "Retrieves a specific policy draft with complete workflow information and available actions."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Policy draft retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to view draft")
    })
    public ResponseEntity<PolicyDraftOutputDTO> getDraftById(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId) {
        
        log.debug("Retrieving policy draft with ID: {}", draftId);
        
        PolicyDraftOutputDTO draft = policyService.getDraftById(draftId);
        
        return ResponseEntity.ok(draft);
    }

    @PutMapping("/{draftId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(
        summary = "Update policy draft",
        description = "Updates an existing policy draft. Only allowed for drafts in CREATED or REQUIRES_CHANGES status."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Policy draft updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid draft data provided"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Draft cannot be updated in current status or name conflict"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to update draft")
    })
    public ResponseEntity<PolicyDraftOutputDTO> updateDraft(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Updated draft data") @Valid @RequestBody PolicyDraftInputDTO draftInput) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Updating policy draft: {} by user: {}", draftId, currentUserId);

        PolicyDraftOutputDTO updatedDraft = policyService.updateDraft(draftId, draftInput, currentUserId);
        
        return ResponseEntity.ok(updatedDraft);
    }

    @DeleteMapping("/{draftId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Delete policy draft",
        description = "Deletes a policy draft. Only allowed for drafts in CREATED status."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Policy draft deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Draft cannot be deleted in current status"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to delete draft")
    })
    public ResponseEntity<Void> deleteDraft(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Deleting policy draft: {} by user: {}", draftId, currentUserId);

        policyService.deleteDraft(draftId, currentUserId);
        
        return ResponseEntity.noContent().build();
    }

    // ========== WORKFLOW ACTION ENDPOINTS ==========

    @PostMapping("/{draftId}/submit")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(
        summary = "Submit draft for review",
        description = "Submits a policy draft for review, transitioning it from CREATED or REQUIRES_CHANGES to SUBMITTED status."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft submitted successfully"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Draft cannot be submitted in current status"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to submit draft")
    })
    public ResponseEntity<PolicyDraftOutputDTO> submitDraft(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Submission action with optional comment") @Valid @RequestBody PolicyDraftActionDTO action) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Submitting policy draft: {} by user: {}", draftId, currentUserId);

        PolicyDraftOutputDTO submittedDraft = policyService.submitDraft(draftId, action, currentUserId);
        
        return ResponseEntity.ok(submittedDraft);
    }

    @PostMapping("/{draftId}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Approve policy draft",
        description = "Approves a policy draft, transitioning it to APPROVED status and making it ready for publication."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft approved successfully"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Draft cannot be approved in current status"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to approve draft")
    })
    public ResponseEntity<PolicyDraftOutputDTO> approveDraft(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Approval action with optional comment") @Valid @RequestBody PolicyDraftActionDTO action) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Approving policy draft: {} by user: {}", draftId, currentUserId);

        PolicyDraftOutputDTO approvedDraft = policyService.approveDraft(draftId, action, currentUserId);
        
        return ResponseEntity.ok(approvedDraft);
    }

    @PostMapping("/{draftId}/reject")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Reject policy draft",
        description = "Rejects a policy draft, transitioning it to REJECTED status. A comment explaining the rejection is required."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft rejected successfully"),
        @ApiResponse(responseCode = "400", description = "Comment is required for rejection"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Draft cannot be rejected in current status"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to reject draft")
    })
    public ResponseEntity<PolicyDraftOutputDTO> rejectDraft(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Rejection action with required comment") @Valid @RequestBody PolicyDraftActionDTO action) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Rejecting policy draft: {} by user: {}", draftId, currentUserId);

        PolicyDraftOutputDTO rejectedDraft = policyService.rejectDraft(draftId, action, currentUserId);
        
        return ResponseEntity.ok(rejectedDraft);
    }

    @PostMapping("/{draftId}/request-changes")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Request changes to draft",
        description = "Requests changes to a policy draft, transitioning it to REQUIRES_CHANGES status. A comment explaining required changes is mandatory."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Changes requested successfully"),
        @ApiResponse(responseCode = "400", description = "Comment is required for change request"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Changes cannot be requested for draft in current status"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to request changes")
    })
    public ResponseEntity<PolicyDraftOutputDTO> requestChanges(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Change request action with required comment") @Valid @RequestBody PolicyDraftActionDTO action) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Requesting changes for policy draft: {} by user: {}", draftId, currentUserId);

        PolicyDraftOutputDTO updatedDraft = policyService.requestChanges(draftId, action, currentUserId);
        
        return ResponseEntity.ok(updatedDraft);
    }

    @PostMapping("/{draftId}/publish")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Publish approved draft as policy",
        description = "Publishes an approved policy draft as an active policy. Only APPROVED drafts can be published."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft published successfully"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "409", description = "Only approved drafts can be published"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to publish draft"),
        @ApiResponse(responseCode = "503", description = "The audit log could not record the new policy; nothing was published (reason audit-unavailable)")
    })
    public ResponseEntity<PolicyOutputDTO> publishDraft(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Publication action with optional metadata") @Valid @RequestBody PolicyDraftActionDTO action) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.info("Publishing policy draft: {} by user: {}", draftId, currentUserId);

        PolicyOutputDTO publishedPolicy = policyService.publishDraft(draftId, action, currentUserId);
        
        return ResponseEntity.ok(publishedPolicy);
    }

    @PostMapping("/{draftId}/comments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @Operation(
        summary = "Add review comment to draft",
        description = "Adds a review comment to a policy draft for collaboration and feedback purposes."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Comment added successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid comment data"),
        @ApiResponse(responseCode = "404", description = "Policy draft not found"),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to comment on draft")
    })
    public ResponseEntity<PolicyDraftOutputDTO> addComment(
            @Parameter(description = "Draft ID") @PathVariable UUID draftId,
            @Parameter(description = "Review comment") @RequestParam String comment) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        String userRole = "REVIEWER"; // Simplified - would get actual roles from security context
        
        log.debug("Adding comment to policy draft: {} by user: {}", draftId, currentUserId);

        PolicyDraftOutputDTO updatedDraft = policyService.addReviewComment(draftId, comment, currentUserId, userRole);
        
        return ResponseEntity.ok(updatedDraft);
    }

    // ========== SPECIALIZED QUERY ENDPOINTS ==========

    @GetMapping("/my-drafts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get drafts requiring my attention",
        description = "Retrieves drafts that require attention from the current user (created by them or they are a stakeholder)."
    )
    public ResponseEntity<Page<PolicyDraftOutputDTO>> getMyDrafts(
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        
        UUID currentUserId = SecurityUtils.getAuthenticatedUserId();
        log.debug("Retrieving drafts requiring attention for user: {}", currentUserId);

        Page<PolicyDraftOutputDTO> drafts = policyService.getDraftsRequiringAttention(currentUserId, pageable);
        
        return ResponseEntity.ok(drafts);
    }

    @GetMapping("/pending-review")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Get drafts pending review",
        description = "Retrieves all drafts that are currently pending review (SUBMITTED or UNDER_REVIEW status)."
    )
    public ResponseEntity<Page<PolicyDraftOutputDTO>> getDraftsPendingReview(
            @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.ASC) Pageable pageable) {
        
        log.debug("Retrieving drafts pending review");

        Page<PolicyDraftOutputDTO> drafts = policyService.getDraftsPendingReview(pageable);
        
        return ResponseEntity.ok(drafts);
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Get overdue drafts",
        description = "Retrieves drafts that have passed their target implementation date but haven't been published yet."
    )
    public ResponseEntity<List<PolicyDraftOutputDTO>> getOverdueDrafts() {
        
        log.debug("Retrieving overdue drafts");

        List<PolicyDraftOutputDTO> overdueDrafts = policyService.getOverdueDrafts();
        
        return ResponseEntity.ok(overdueDrafts);
    }

    // ========== METADATA ENDPOINTS ==========

    @GetMapping("/categories")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get available draft categories",
        description = "Retrieves list of all categories used in policy drafts for filtering and organization."
    )
    public ResponseEntity<List<String>> getDraftCategories() {
        
        log.debug("Retrieving draft categories");

        List<String> categories = policyService.getDraftCategories();
        
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/tags")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get available draft tags",
        description = "Retrieves list of all tags used in policy drafts for filtering and organization."
    )
    public ResponseEntity<List<String>> getDraftTags() {
        
        log.debug("Retrieving draft tags");

        List<String> tags = policyService.getDraftTags();
        
        return ResponseEntity.ok(tags);
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    @Operation(
        summary = "Get draft statistics",
        description = "Retrieves statistical information about policy drafts grouped by status for dashboard displays."
    )
    public ResponseEntity<List<Object[]>> getDraftStatistics() {
        
        log.debug("Retrieving draft statistics");

        List<Object[]> statistics = policyService.getDraftStatistics();
        
        return ResponseEntity.ok(statistics);
    }
} 