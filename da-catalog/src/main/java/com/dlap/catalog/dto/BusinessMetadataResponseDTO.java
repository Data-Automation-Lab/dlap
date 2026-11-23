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

package com.dlap.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Enhanced response DTO for business metadata.
 * Includes audit information, validation status, and complete business context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessMetadataResponseDTO {

    private BusinessMetadataDTO businessMetadata;
    private ComplianceClassificationDTO complianceClassification;
    private MetadataAuditDTO auditInfo;
    private ValidationStatusDTO validationStatus;
    private List<RecommendationDTO> recommendations;

    public BusinessMetadataResponseDTO() {}

    public BusinessMetadataDTO getBusinessMetadata() {
        return businessMetadata;
    }

    public void setBusinessMetadata(BusinessMetadataDTO businessMetadata) {
        this.businessMetadata = businessMetadata;
    }

    public ComplianceClassificationDTO getComplianceClassification() {
        return complianceClassification;
    }

    public void setComplianceClassification(ComplianceClassificationDTO complianceClassification) {
        this.complianceClassification = complianceClassification;
    }

    public MetadataAuditDTO getAuditInfo() {
        return auditInfo;
    }

    public void setAuditInfo(MetadataAuditDTO auditInfo) {
        this.auditInfo = auditInfo;
    }

    public ValidationStatusDTO getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ValidationStatusDTO validationStatus) {
        this.validationStatus = validationStatus;
    }

    public List<RecommendationDTO> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<RecommendationDTO> recommendations) {
        this.recommendations = recommendations;
    }

    /**
     * Reusing BusinessMetadataDTO from input for consistency
     */
    public static class BusinessMetadataDTO extends BusinessMetadataInputDTO.BusinessMetadataDTO {
        // Inherits all fields from input DTO for consistency
    }

    /**
     * Reusing ComplianceClassificationDTO from input for consistency
     */
    public static class ComplianceClassificationDTO extends BusinessMetadataInputDTO.ComplianceClassificationDTO {
        // Inherits all fields from input DTO for consistency
    }

    /**
     * Audit information for metadata changes
     */
    public static class MetadataAuditDTO {
        
        private UUID lastUpdatedBy;
        private String lastUpdatedByName;
        private Instant lastUpdatedAt;
        private String updateReason;
        private Integer versionNumber;
        private UUID createdBy;
        private String createdByName;
        private Instant createdAt;
        private List<String> changeHistory; // Summary of recent changes

        public MetadataAuditDTO() {}

        public UUID getLastUpdatedBy() {
            return lastUpdatedBy;
        }

        public void setLastUpdatedBy(UUID lastUpdatedBy) {
            this.lastUpdatedBy = lastUpdatedBy;
        }

        public String getLastUpdatedByName() {
            return lastUpdatedByName;
        }

        public void setLastUpdatedByName(String lastUpdatedByName) {
            this.lastUpdatedByName = lastUpdatedByName;
        }

        public Instant getLastUpdatedAt() {
            return lastUpdatedAt;
        }

        public void setLastUpdatedAt(Instant lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
        }

        public String getUpdateReason() {
            return updateReason;
        }

        public void setUpdateReason(String updateReason) {
            this.updateReason = updateReason;
        }

        public Integer getVersionNumber() {
            return versionNumber;
        }

        public void setVersionNumber(Integer versionNumber) {
            this.versionNumber = versionNumber;
        }

        public UUID getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(UUID createdBy) {
            this.createdBy = createdBy;
        }

        public String getCreatedByName() {
            return createdByName;
        }

        public void setCreatedByName(String createdByName) {
            this.createdByName = createdByName;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public List<String> getChangeHistory() {
            return changeHistory;
        }

        public void setChangeHistory(List<String> changeHistory) {
            this.changeHistory = changeHistory;
        }
    }

    /**
     * Validation status for business metadata completeness
     */
    public static class ValidationStatusDTO {
        
        private Boolean isComplete;
        private Double completenessScore; // 0.0 to 1.0
        private List<String> missingFields;
        private List<String> validationWarnings;
        private List<String> validationErrors;
        private String overallStatus; // COMPLETE, PARTIAL, INCOMPLETE, INVALID

        public ValidationStatusDTO() {}

        public Boolean getIsComplete() {
            return isComplete;
        }

        public void setIsComplete(Boolean isComplete) {
            this.isComplete = isComplete;
        }

        public Double getCompletenessScore() {
            return completenessScore;
        }

        public void setCompletenessScore(Double completenessScore) {
            this.completenessScore = completenessScore;
        }

        public List<String> getMissingFields() {
            return missingFields;
        }

        public void setMissingFields(List<String> missingFields) {
            this.missingFields = missingFields;
        }

        public List<String> getValidationWarnings() {
            return validationWarnings;
        }

        public void setValidationWarnings(List<String> validationWarnings) {
            this.validationWarnings = validationWarnings;
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }

        public void setValidationErrors(List<String> validationErrors) {
            this.validationErrors = validationErrors;
        }

        public String getOverallStatus() {
            return overallStatus;
        }

        public void setOverallStatus(String overallStatus) {
            this.overallStatus = overallStatus;
        }
    }

    /**
     * Recommendations for improving metadata quality
     */
    public static class RecommendationDTO {
        
        private String type; // COMPLETENESS, COMPLIANCE, GOVERNANCE, QUALITY
        private String priority; // HIGH, MEDIUM, LOW
        private String title;
        private String description;
        private String actionRequired;
        private String potentialImpact;

        public RecommendationDTO() {}

        public RecommendationDTO(String type, String priority, String title, String description, String actionRequired, String potentialImpact) {
            this.type = type;
            this.priority = priority;
            this.title = title;
            this.description = description;
            this.actionRequired = actionRequired;
            this.potentialImpact = potentialImpact;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getActionRequired() {
            return actionRequired;
        }

        public void setActionRequired(String actionRequired) {
            this.actionRequired = actionRequired;
        }

        public String getPotentialImpact() {
            return potentialImpact;
        }

        public void setPotentialImpact(String potentialImpact) {
            this.potentialImpact = potentialImpact;
        }
    }
} 