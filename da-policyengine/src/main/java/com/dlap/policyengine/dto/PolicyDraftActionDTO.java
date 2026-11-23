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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for policy draft workflow actions (submit, approve, reject, etc.).
 * Used for all workflow transition operations.
 */
public class PolicyDraftActionDTO {

    /**
     * The action to perform (SUBMIT, APPROVE, REJECT, REQUEST_CHANGES, ARCHIVE).
     */
    @NotBlank(message = "Action type is required")
    private String actionType;

    /**
     * Comment or reason for the action (required for rejection and optional for others).
     */
    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String comment;

    /**
     * Priority level if submitting or approving (HIGH, MEDIUM, LOW).
     */
    private String priority;

    /**
     * Target implementation date if approving for publication.
     */
    private String targetImplementationDate;

    /**
     * Additional metadata for the action.
     */
    private String metadata;

    // Constructors
    public PolicyDraftActionDTO() {}

    public PolicyDraftActionDTO(String actionType) {
        this.actionType = actionType;
    }

    public PolicyDraftActionDTO(String actionType, String comment) {
        this.actionType = actionType;
        this.comment = comment;
    }

    // Getters and Setters
    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getTargetImplementationDate() {
        return targetImplementationDate;
    }

    public void setTargetImplementationDate(String targetImplementationDate) {
        this.targetImplementationDate = targetImplementationDate;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}

/**
 * DTO for bulk operations on multiple policy drafts.
 */
class PolicyDraftBulkActionDTO {

    /**
     * List of draft IDs to perform action on.
     */
    @NotBlank(message = "Draft IDs are required")
    private java.util.List<java.util.UUID> draftIds;

    /**
     * The action to perform on all selected drafts.
     */
    @NotBlank(message = "Action type is required")
    private String actionType;

    /**
     * Comment for the bulk action.
     */
    @Size(max = 1000, message = "Comment must not exceed 1000 characters")
    private String comment;

    // Constructors
    public PolicyDraftBulkActionDTO() {}

    // Getters and Setters
    public java.util.List<java.util.UUID> getDraftIds() {
        return draftIds;
    }

    public void setDraftIds(java.util.List<java.util.UUID> draftIds) {
        this.draftIds = draftIds;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}

/**
 * DTO for policy draft summary information (for list views).
 */
class PolicyDraftSummaryDTO {

    private java.util.UUID id;
    private String name;
    private String status;
    private Integer version;
    private String priority;
    private String category;
    private java.time.Instant createdAt;
    private java.time.Instant updatedAt;
    private java.util.UUID createdByUserId;
    private java.time.Instant targetImplementationDate;
    private int reviewCommentsCount;
    private boolean isOverdue;

    // Constructors
    public PolicyDraftSummaryDTO() {}

    public PolicyDraftSummaryDTO(java.util.UUID id, String name, String status) {
        this.id = id;
        this.name = name;
        this.status = status;
    }

    // Getters and Setters
    public java.util.UUID getId() {
        return id;
    }

    public void setId(java.util.UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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

    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.Instant createdAt) {
        this.createdAt = createdAt;
    }

    public java.time.Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public java.util.UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(java.util.UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public java.time.Instant getTargetImplementationDate() {
        return targetImplementationDate;
    }

    public void setTargetImplementationDate(java.time.Instant targetImplementationDate) {
        this.targetImplementationDate = targetImplementationDate;
    }

    public int getReviewCommentsCount() {
        return reviewCommentsCount;
    }

    public void setReviewCommentsCount(int reviewCommentsCount) {
        this.reviewCommentsCount = reviewCommentsCount;
    }

    public boolean isOverdue() {
        return isOverdue;
    }

    public void setOverdue(boolean overdue) {
        isOverdue = overdue;
    }
} 