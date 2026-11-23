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

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Enhanced DTO for business metadata management.
 * Supports steward relationships, compliance classification, and business context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessMetadataInputDTO {

    private BusinessMetadataDTO businessMetadata;
    private ComplianceClassificationDTO complianceClassification;
    private String updateReason;

    public BusinessMetadataInputDTO() {}

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

    public String getUpdateReason() {
        return updateReason;
    }

    public void setUpdateReason(String updateReason) {
        this.updateReason = updateReason;
    }

    /**
     * Core business metadata information
     */
    public static class BusinessMetadataDTO {
        
        @Size(max = 255, message = "Business name cannot exceed 255 characters")
        private String businessName;
        
        @Size(max = 1000, message = "Business description cannot exceed 1000 characters")
        private String businessDescription;
        
        private BusinessContactDTO businessOwner;
        private BusinessContactDTO dataSteward;
        
        @NotBlank(message = "Business criticality is required")
        private String businessCriticality; // HIGH, MEDIUM, LOW, CRITICAL
        
        @Size(max = 100, message = "Business domain cannot exceed 100 characters")
        private String businessDomain; // CUSTOMER_ANALYTICS, FINANCE, OPERATIONS, etc.
        
        private List<String> useCases;
        
        @DecimalMin(value = "0.0", message = "Data quality score must be between 0 and 10")
        @DecimalMax(value = "10.0", message = "Data quality score must be between 0 and 10")
        private Double dataQualityScore;
        
        private String updateFrequency; // REAL_TIME, HOURLY, DAILY, WEEKLY, MONTHLY, BATCH
        
        private RetentionRequirementsDTO retentionRequirements;

        public BusinessMetadataDTO() {}

        public String getBusinessName() {
            return businessName;
        }

        public void setBusinessName(String businessName) {
            this.businessName = businessName;
        }

        public String getBusinessDescription() {
            return businessDescription;
        }

        public void setBusinessDescription(String businessDescription) {
            this.businessDescription = businessDescription;
        }

        public BusinessContactDTO getBusinessOwner() {
            return businessOwner;
        }

        public void setBusinessOwner(BusinessContactDTO businessOwner) {
            this.businessOwner = businessOwner;
        }

        public BusinessContactDTO getDataSteward() {
            return dataSteward;
        }

        public void setDataSteward(BusinessContactDTO dataSteward) {
            this.dataSteward = dataSteward;
        }

        public String getBusinessCriticality() {
            return businessCriticality;
        }

        public void setBusinessCriticality(String businessCriticality) {
            this.businessCriticality = businessCriticality;
        }

        public String getBusinessDomain() {
            return businessDomain;
        }

        public void setBusinessDomain(String businessDomain) {
            this.businessDomain = businessDomain;
        }

        public List<String> getUseCases() {
            return useCases;
        }

        public void setUseCases(List<String> useCases) {
            this.useCases = useCases;
        }

        public Double getDataQualityScore() {
            return dataQualityScore;
        }

        public void setDataQualityScore(Double dataQualityScore) {
            this.dataQualityScore = dataQualityScore;
        }

        public String getUpdateFrequency() {
            return updateFrequency;
        }

        public void setUpdateFrequency(String updateFrequency) {
            this.updateFrequency = updateFrequency;
        }

        public RetentionRequirementsDTO getRetentionRequirements() {
            return retentionRequirements;
        }

        public void setRetentionRequirements(RetentionRequirementsDTO retentionRequirements) {
            this.retentionRequirements = retentionRequirements;
        }
    }

    /**
     * Business contact information for owners and stewards
     */
    public static class BusinessContactDTO {
        
        private UUID userId;
        
        @NotBlank(message = "Contact name is required")
        @Size(max = 255, message = "Name cannot exceed 255 characters")
        private String name;
        
        @NotBlank(message = "Contact email is required")
        @Size(max = 255, message = "Email cannot exceed 255 characters")
        private String email;
        
        @Size(max = 100, message = "Department cannot exceed 100 characters")
        private String department;
        
        @Size(max = 100, message = "Role cannot exceed 100 characters")
        private String role;
        
        @Size(max = 50, message = "Phone cannot exceed 50 characters")
        private String phone;

        public BusinessContactDTO() {}

        public BusinessContactDTO(UUID userId, String name, String email, String department) {
            this.userId = userId;
            this.name = name;
            this.email = email;
            this.department = department;
        }

        public UUID getUserId() {
            return userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getDepartment() {
            return department;
        }

        public void setDepartment(String department) {
            this.department = department;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    /**
     * Data retention requirements and policies
     */
    public static class RetentionRequirementsDTO {
        
        private String legalRetention; // 7_YEARS, 10_YEARS, INDEFINITE, etc.
        private String businessRetention; // 5_YEARS, 3_YEARS, etc.
        private String archiveAfter; // 2_YEARS, 1_YEAR, etc.
        private String deleteAfter; // 10_YEARS, NEVER, etc.
        private List<String> retentionReasons; // LEGAL_REQUIREMENT, BUSINESS_NEED, REGULATORY_COMPLIANCE

        public RetentionRequirementsDTO() {}

        public String getLegalRetention() {
            return legalRetention;
        }

        public void setLegalRetention(String legalRetention) {
            this.legalRetention = legalRetention;
        }

        public String getBusinessRetention() {
            return businessRetention;
        }

        public void setBusinessRetention(String businessRetention) {
            this.businessRetention = businessRetention;
        }

        public String getArchiveAfter() {
            return archiveAfter;
        }

        public void setArchiveAfter(String archiveAfter) {
            this.archiveAfter = archiveAfter;
        }

        public String getDeleteAfter() {
            return deleteAfter;
        }

        public void setDeleteAfter(String deleteAfter) {
            this.deleteAfter = deleteAfter;
        }

        public List<String> getRetentionReasons() {
            return retentionReasons;
        }

        public void setRetentionReasons(List<String> retentionReasons) {
            this.retentionReasons = retentionReasons;
        }
    }

    /**
     * Compliance and data classification information
     */
    public static class ComplianceClassificationDTO {
        
        @NotBlank(message = "Data classification is required")
        private String dataClassification; // PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED
        
        private Boolean containsPii;
        private Boolean containsPhi; // Protected Health Information
        private Boolean containsFinancial;
        private Boolean gdprApplicable;
        private Boolean soxRelevant; // Sarbanes-Oxley
        private Boolean hipaaRelevant;
        private Boolean pciRelevant; // Payment Card Industry
        
        private List<String> regulatoryFrameworks; // GDPR, CCPA, SOX, HIPAA, PCI_DSS
        private List<String> dataSubjectCategories; // CUSTOMER, EMPLOYEE, PARTNER, VENDOR
        private String privacyImpactLevel; // HIGH, MEDIUM, LOW, NONE

        public ComplianceClassificationDTO() {}

        public String getDataClassification() {
            return dataClassification;
        }

        public void setDataClassification(String dataClassification) {
            this.dataClassification = dataClassification;
        }

        public Boolean getContainsPii() {
            return containsPii;
        }

        public void setContainsPii(Boolean containsPii) {
            this.containsPii = containsPii;
        }

        public Boolean getContainsPhi() {
            return containsPhi;
        }

        public void setContainsPhi(Boolean containsPhi) {
            this.containsPhi = containsPhi;
        }

        public Boolean getContainsFinancial() {
            return containsFinancial;
        }

        public void setContainsFinancial(Boolean containsFinancial) {
            this.containsFinancial = containsFinancial;
        }

        public Boolean getGdprApplicable() {
            return gdprApplicable;
        }

        public void setGdprApplicable(Boolean gdprApplicable) {
            this.gdprApplicable = gdprApplicable;
        }

        public Boolean getSoxRelevant() {
            return soxRelevant;
        }

        public void setSoxRelevant(Boolean soxRelevant) {
            this.soxRelevant = soxRelevant;
        }

        public Boolean getHipaaRelevant() {
            return hipaaRelevant;
        }

        public void setHipaaRelevant(Boolean hipaaRelevant) {
            this.hipaaRelevant = hipaaRelevant;
        }

        public Boolean getPciRelevant() {
            return pciRelevant;
        }

        public void setPciRelevant(Boolean pciRelevant) {
            this.pciRelevant = pciRelevant;
        }

        public List<String> getRegulatoryFrameworks() {
            return regulatoryFrameworks;
        }

        public void setRegulatoryFrameworks(List<String> regulatoryFrameworks) {
            this.regulatoryFrameworks = regulatoryFrameworks;
        }

        public List<String> getDataSubjectCategories() {
            return dataSubjectCategories;
        }

        public void setDataSubjectCategories(List<String> dataSubjectCategories) {
            this.dataSubjectCategories = dataSubjectCategories;
        }

        public String getPrivacyImpactLevel() {
            return privacyImpactLevel;
        }

        public void setPrivacyImpactLevel(String privacyImpactLevel) {
            this.privacyImpactLevel = privacyImpactLevel;
        }
    }
} 