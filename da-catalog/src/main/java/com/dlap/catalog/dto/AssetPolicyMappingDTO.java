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
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for Asset-Policy Mapping information.
 * Priority 2 endpoint: Shows which policies apply to an asset and their compliance status.
 *
 * <p>The catalog does not store per-asset policy evaluations (the policy engine owns
 * them), so until a mapping source is wired the response carries {@code available=false}
 * and an explanation in {@code basis}; every other field is {@code null} and omitted from
 * the JSON. Consumers must treat an absent field as "not available".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetPolicyMappingDTO {

    private UUID assetId;
    private String assetName;
    /** False when the catalog has no stored policy mapping for the asset. */
    private Boolean available;
    /** Short, human-readable statement of where the values come from (or why there are none). */
    private String basis;
    private List<PolicyMappingDTO> applicablePolicies;
    private List<PolicyMappingDTO> inheritedPolicies;
    private PolicyComplianceSummaryDTO complianceSummary;
    private List<PolicyRiskDTO> riskAssessment;
    private List<PolicyRecommendationDTO> recommendations;
    private Instant lastEvaluated;
    private String evaluationStatus;

    /**
     * Default constructor.
     */
    public AssetPolicyMappingDTO() {}

    /**
     * Constructor with basic information.
     */
    public AssetPolicyMappingDTO(UUID assetId, String assetName) {
        this.assetId = assetId;
        this.assetName = assetName;
    }

    /**
     * DTO for individual policy mapping information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PolicyMappingDTO {
        
        private UUID policyId;
        private String policyName;
        private String policyType; // DATA_GOVERNANCE, SECURITY, COMPLIANCE, RETENTION, etc.
        private String policyVersion;
        private String applicabilityReason; // DIRECT, INHERITED, LABEL_BASED, TYPE_BASED
        private String complianceStatus; // COMPLIANT, NON_COMPLIANT, PARTIALLY_COMPLIANT, UNKNOWN
        private Double complianceScore; // 0.0 to 100.0
        private List<PolicyRuleComplianceDTO> ruleCompliance;
        private Instant lastEvaluated;
        private String evaluatedBy;
        private List<String> exemptions;
        private Map<String, Object> metadata;

        public PolicyMappingDTO() {}

        public PolicyMappingDTO(UUID policyId, String policyName, String policyType) {
            this.policyId = policyId;
            this.policyName = policyName;
            this.policyType = policyType;
        }

        // Getters and setters
        public UUID getPolicyId() { return policyId; }
        public void setPolicyId(UUID policyId) { this.policyId = policyId; }
        public String getPolicyName() { return policyName; }
        public void setPolicyName(String policyName) { this.policyName = policyName; }
        public String getPolicyType() { return policyType; }
        public void setPolicyType(String policyType) { this.policyType = policyType; }
        public String getPolicyVersion() { return policyVersion; }
        public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
        public String getApplicabilityReason() { return applicabilityReason; }
        public void setApplicabilityReason(String applicabilityReason) { this.applicabilityReason = applicabilityReason; }
        public String getComplianceStatus() { return complianceStatus; }
        public void setComplianceStatus(String complianceStatus) { this.complianceStatus = complianceStatus; }
        public Double getComplianceScore() { return complianceScore; }
        public void setComplianceScore(Double complianceScore) { this.complianceScore = complianceScore; }
        public List<PolicyRuleComplianceDTO> getRuleCompliance() { return ruleCompliance; }
        public void setRuleCompliance(List<PolicyRuleComplianceDTO> ruleCompliance) { this.ruleCompliance = ruleCompliance; }
        public Instant getLastEvaluated() { return lastEvaluated; }
        public void setLastEvaluated(Instant lastEvaluated) { this.lastEvaluated = lastEvaluated; }
        public String getEvaluatedBy() { return evaluatedBy; }
        public void setEvaluatedBy(String evaluatedBy) { this.evaluatedBy = evaluatedBy; }
        public List<String> getExemptions() { return exemptions; }
        public void setExemptions(List<String> exemptions) { this.exemptions = exemptions; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    /**
     * DTO for policy rule compliance details.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PolicyRuleComplianceDTO {
        
        private String ruleId;
        private String ruleName;
        private String ruleType;
        private String complianceStatus;
        private String actualValue;
        private String expectedValue;
        private String nonComplianceReason;
        private String severity; // CRITICAL, HIGH, MEDIUM, LOW
        private List<String> recommendedActions;

        public PolicyRuleComplianceDTO() {}

        public PolicyRuleComplianceDTO(String ruleId, String ruleName, String complianceStatus) {
            this.ruleId = ruleId;
            this.ruleName = ruleName;
            this.complianceStatus = complianceStatus;
        }

        // Getters and setters
        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }
        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }
        public String getRuleType() { return ruleType; }
        public void setRuleType(String ruleType) { this.ruleType = ruleType; }
        public String getComplianceStatus() { return complianceStatus; }
        public void setComplianceStatus(String complianceStatus) { this.complianceStatus = complianceStatus; }
        public String getActualValue() { return actualValue; }
        public void setActualValue(String actualValue) { this.actualValue = actualValue; }
        public String getExpectedValue() { return expectedValue; }
        public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }
        public String getNonComplianceReason() { return nonComplianceReason; }
        public void setNonComplianceReason(String nonComplianceReason) { this.nonComplianceReason = nonComplianceReason; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public List<String> getRecommendedActions() { return recommendedActions; }
        public void setRecommendedActions(List<String> recommendedActions) { this.recommendedActions = recommendedActions; }
    }

    /**
     * DTO for overall policy compliance summary.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PolicyComplianceSummaryDTO {
        
        private Integer totalPolicies;
        private Integer compliantPolicies;
        private Integer nonCompliantPolicies;
        private Integer partiallyCompliantPolicies;
        private Double overallComplianceScore;
        private String overallStatus;
        private Map<String, Integer> policyTypeBreakdown;
        private Map<String, Integer> complianceStatusBreakdown;
        private List<String> criticalIssues;
        private Instant lastFullEvaluation;

        public PolicyComplianceSummaryDTO() {}

        // Getters and setters
        public Integer getTotalPolicies() { return totalPolicies; }
        public void setTotalPolicies(Integer totalPolicies) { this.totalPolicies = totalPolicies; }
        public Integer getCompliantPolicies() { return compliantPolicies; }
        public void setCompliantPolicies(Integer compliantPolicies) { this.compliantPolicies = compliantPolicies; }
        public Integer getNonCompliantPolicies() { return nonCompliantPolicies; }
        public void setNonCompliantPolicies(Integer nonCompliantPolicies) { this.nonCompliantPolicies = nonCompliantPolicies; }
        public Integer getPartiallyCompliantPolicies() { return partiallyCompliantPolicies; }
        public void setPartiallyCompliantPolicies(Integer partiallyCompliantPolicies) { this.partiallyCompliantPolicies = partiallyCompliantPolicies; }
        public Double getOverallComplianceScore() { return overallComplianceScore; }
        public void setOverallComplianceScore(Double overallComplianceScore) { this.overallComplianceScore = overallComplianceScore; }
        public String getOverallStatus() { return overallStatus; }
        public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }
        public Map<String, Integer> getPolicyTypeBreakdown() { return policyTypeBreakdown; }
        public void setPolicyTypeBreakdown(Map<String, Integer> policyTypeBreakdown) { this.policyTypeBreakdown = policyTypeBreakdown; }
        public Map<String, Integer> getComplianceStatusBreakdown() { return complianceStatusBreakdown; }
        public void setComplianceStatusBreakdown(Map<String, Integer> complianceStatusBreakdown) { this.complianceStatusBreakdown = complianceStatusBreakdown; }
        public List<String> getCriticalIssues() { return criticalIssues; }
        public void setCriticalIssues(List<String> criticalIssues) { this.criticalIssues = criticalIssues; }
        public Instant getLastFullEvaluation() { return lastFullEvaluation; }
        public void setLastFullEvaluation(Instant lastFullEvaluation) { this.lastFullEvaluation = lastFullEvaluation; }
    }

    /**
     * DTO for policy risk assessment.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PolicyRiskDTO {
        
        private String riskType; // COMPLIANCE, SECURITY, GOVERNANCE, OPERATIONAL
        private String riskLevel; // CRITICAL, HIGH, MEDIUM, LOW
        private String description;
        private String impact;
        private String likelihood;
        private List<String> mitigationActions;
        private String owner;
        private Instant identifiedAt;

        public PolicyRiskDTO() {}

        public PolicyRiskDTO(String riskType, String riskLevel, String description) {
            this.riskType = riskType;
            this.riskLevel = riskLevel;
            this.description = description;
        }

        // Getters and setters
        public String getRiskType() { return riskType; }
        public void setRiskType(String riskType) { this.riskType = riskType; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getImpact() { return impact; }
        public void setImpact(String impact) { this.impact = impact; }
        public String getLikelihood() { return likelihood; }
        public void setLikelihood(String likelihood) { this.likelihood = likelihood; }
        public List<String> getMitigationActions() { return mitigationActions; }
        public void setMitigationActions(List<String> mitigationActions) { this.mitigationActions = mitigationActions; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        public Instant getIdentifiedAt() { return identifiedAt; }
        public void setIdentifiedAt(Instant identifiedAt) { this.identifiedAt = identifiedAt; }
    }

    /**
     * DTO for policy recommendations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PolicyRecommendationDTO {
        
        private String recommendationType; // POLICY_UPDATE, EXEMPTION_REQUEST, REMEDIATION_ACTION
        private String priority; // HIGH, MEDIUM, LOW
        private String title;
        private String description;
        private String actionRequired;
        private String expectedOutcome;
        private Integer estimatedEffort; // in hours
        private String assignedTo;
        private Instant createdAt;

        public PolicyRecommendationDTO() {}

        public PolicyRecommendationDTO(String recommendationType, String priority, String title) {
            this.recommendationType = recommendationType;
            this.priority = priority;
            this.title = title;
        }

        // Getters and setters
        public String getRecommendationType() { return recommendationType; }
        public void setRecommendationType(String recommendationType) { this.recommendationType = recommendationType; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getActionRequired() { return actionRequired; }
        public void setActionRequired(String actionRequired) { this.actionRequired = actionRequired; }
        public String getExpectedOutcome() { return expectedOutcome; }
        public void setExpectedOutcome(String expectedOutcome) { this.expectedOutcome = expectedOutcome; }
        public Integer getEstimatedEffort() { return estimatedEffort; }
        public void setEstimatedEffort(Integer estimatedEffort) { this.estimatedEffort = estimatedEffort; }
        public String getAssignedTo() { return assignedTo; }
        public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    }

    // Main class getters and setters
    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }
    public List<PolicyMappingDTO> getApplicablePolicies() { return applicablePolicies; }
    public void setApplicablePolicies(List<PolicyMappingDTO> applicablePolicies) { this.applicablePolicies = applicablePolicies; }
    public List<PolicyMappingDTO> getInheritedPolicies() { return inheritedPolicies; }
    public void setInheritedPolicies(List<PolicyMappingDTO> inheritedPolicies) { this.inheritedPolicies = inheritedPolicies; }
    public PolicyComplianceSummaryDTO getComplianceSummary() { return complianceSummary; }
    public void setComplianceSummary(PolicyComplianceSummaryDTO complianceSummary) { this.complianceSummary = complianceSummary; }
    public List<PolicyRiskDTO> getRiskAssessment() { return riskAssessment; }
    public void setRiskAssessment(List<PolicyRiskDTO> riskAssessment) { this.riskAssessment = riskAssessment; }
    public List<PolicyRecommendationDTO> getRecommendations() { return recommendations; }
    public void setRecommendations(List<PolicyRecommendationDTO> recommendations) { this.recommendations = recommendations; }
    public Instant getLastEvaluated() { return lastEvaluated; }
    public void setLastEvaluated(Instant lastEvaluated) { this.lastEvaluated = lastEvaluated; }
    public String getEvaluationStatus() { return evaluationStatus; }
    public void setEvaluationStatus(String evaluationStatus) { this.evaluationStatus = evaluationStatus; }
} 