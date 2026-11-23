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

/**
 * Comprehensive DTO for policy impact analysis response.
 * Contains detailed analysis of what assets would be affected by policy changes.
 */
public class PolicyImpactResponseDTO {

    /**
     * Unique identifier for this impact analysis.
     */
    private String analysisId;

    /**
     * Timestamp when the analysis was performed.
     */
    private Instant analyzedAt;

    /**
     * Type of analysis performed: FULL, QUICK, or TARGETED.
     */
    private String analysisType;

    /**
     * Overall impact summary with key metrics.
     */
    private ImpactSummaryDTO summary;

    /**
     * Detailed breakdown of affected assets by category.
     */
    private List<AssetImpactDTO> affectedAssets;

    /**
     * Performance impact estimates (if requested).
     */
    private PerformanceImpactDTO performanceImpact;

    /**
     * Cost impact analysis (if requested).
     */
    private CostImpactDTO costImpact;

    /**
     * Compliance impact analysis (if requested).
     */
    private ComplianceImpactDTO complianceImpact;

    /**
     * Risk assessment for implementing this policy.
     */
    private RiskAssessmentDTO riskAssessment;

    /**
     * Recommendations for policy implementation.
     */
    private List<String> recommendations;

    /**
     * Analysis execution metadata.
     */
    private AnalysisMetadataDTO metadata;

    /**
     * Indicates whether usable impact analysis data is present in this response.
     * When false, callers should treat metrics as unavailable and consult {@link #reason}.
     */
    private Boolean dataAvailable;

    /**
     * Human-readable reason explaining why data is or is not available.
     * Populated when {@link #dataAvailable} is false.
     */
    private String reason;

    // Constructors
    public PolicyImpactResponseDTO() {}

    public PolicyImpactResponseDTO(String analysisId, String analysisType) {
        this.analysisId = analysisId;
        this.analysisType = analysisType;
        this.analyzedAt = Instant.now();
    }

    // Getters and Setters
    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public Instant getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(Instant analyzedAt) {
        this.analyzedAt = analyzedAt;
    }

    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public ImpactSummaryDTO getSummary() {
        return summary;
    }

    public void setSummary(ImpactSummaryDTO summary) {
        this.summary = summary;
    }

    public List<AssetImpactDTO> getAffectedAssets() {
        return affectedAssets;
    }

    public void setAffectedAssets(List<AssetImpactDTO> affectedAssets) {
        this.affectedAssets = affectedAssets;
    }

    public PerformanceImpactDTO getPerformanceImpact() {
        return performanceImpact;
    }

    public void setPerformanceImpact(PerformanceImpactDTO performanceImpact) {
        this.performanceImpact = performanceImpact;
    }

    public CostImpactDTO getCostImpact() {
        return costImpact;
    }

    public void setCostImpact(CostImpactDTO costImpact) {
        this.costImpact = costImpact;
    }

    public ComplianceImpactDTO getComplianceImpact() {
        return complianceImpact;
    }

    public void setComplianceImpact(ComplianceImpactDTO complianceImpact) {
        this.complianceImpact = complianceImpact;
    }

    public RiskAssessmentDTO getRiskAssessment() {
        return riskAssessment;
    }

    public void setRiskAssessment(RiskAssessmentDTO riskAssessment) {
        this.riskAssessment = riskAssessment;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

    public AnalysisMetadataDTO getMetadata() {
        return metadata;
    }

    public void setMetadata(AnalysisMetadataDTO metadata) {
        this.metadata = metadata;
    }

    public Boolean getDataAvailable() {
        return dataAvailable;
    }

    public void setDataAvailable(Boolean dataAvailable) {
        this.dataAvailable = dataAvailable;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * Overall impact summary with key metrics.
     */
    public static class ImpactSummaryDTO {
        private Integer totalAssetsAnalyzed;
        private Integer totalAssetsAffected;
        private Integer highImpactAssets;
        private Integer mediumImpactAssets;
        private Integer lowImpactAssets;
        private String overallRiskLevel; // LOW, MEDIUM, HIGH, CRITICAL
        private Double impactPercentage;

        // Constructors, getters, and setters
        public ImpactSummaryDTO() {}

        public Integer getTotalAssetsAnalyzed() { return totalAssetsAnalyzed; }
        public void setTotalAssetsAnalyzed(Integer totalAssetsAnalyzed) { this.totalAssetsAnalyzed = totalAssetsAnalyzed; }

        public Integer getTotalAssetsAffected() { return totalAssetsAffected; }
        public void setTotalAssetsAffected(Integer totalAssetsAffected) { this.totalAssetsAffected = totalAssetsAffected; }

        public Integer getHighImpactAssets() { return highImpactAssets; }
        public void setHighImpactAssets(Integer highImpactAssets) { this.highImpactAssets = highImpactAssets; }

        public Integer getMediumImpactAssets() { return mediumImpactAssets; }
        public void setMediumImpactAssets(Integer mediumImpactAssets) { this.mediumImpactAssets = mediumImpactAssets; }

        public Integer getLowImpactAssets() { return lowImpactAssets; }
        public void setLowImpactAssets(Integer lowImpactAssets) { this.lowImpactAssets = lowImpactAssets; }

        public String getOverallRiskLevel() { return overallRiskLevel; }
        public void setOverallRiskLevel(String overallRiskLevel) { this.overallRiskLevel = overallRiskLevel; }

        public Double getImpactPercentage() { return impactPercentage; }
        public void setImpactPercentage(Double impactPercentage) { this.impactPercentage = impactPercentage; }
    }

    /**
     * Individual asset impact details.
     */
    public static class AssetImpactDTO {
        private String assetId;
        private String assetName;
        private String assetType;
        private String impactLevel; // HIGH, MEDIUM, LOW
        private List<String> affectedAttributes;
        private List<String> appliedActions;
        private String riskAssessment;
        private Map<String, Object> impactDetails;

        // Constructors, getters, and setters
        public AssetImpactDTO() {}

        public String getAssetId() { return assetId; }
        public void setAssetId(String assetId) { this.assetId = assetId; }

        public String getAssetName() { return assetName; }
        public void setAssetName(String assetName) { this.assetName = assetName; }

        public String getAssetType() { return assetType; }
        public void setAssetType(String assetType) { this.assetType = assetType; }

        public String getImpactLevel() { return impactLevel; }
        public void setImpactLevel(String impactLevel) { this.impactLevel = impactLevel; }

        public List<String> getAffectedAttributes() { return affectedAttributes; }
        public void setAffectedAttributes(List<String> affectedAttributes) { this.affectedAttributes = affectedAttributes; }

        public List<String> getAppliedActions() { return appliedActions; }
        public void setAppliedActions(List<String> appliedActions) { this.appliedActions = appliedActions; }

        public String getRiskAssessment() { return riskAssessment; }
        public void setRiskAssessment(String riskAssessment) { this.riskAssessment = riskAssessment; }

        public Map<String, Object> getImpactDetails() { return impactDetails; }
        public void setImpactDetails(Map<String, Object> impactDetails) { this.impactDetails = impactDetails; }
    }

    /**
     * Performance impact estimates.
     */
    public static class PerformanceImpactDTO {
        private Long estimatedProcessingTimeMs;
        private Double cpuUtilizationIncrease;
        private Double memoryUtilizationIncrease;
        private Integer estimatedApiCalls;
        private String performanceRiskLevel;

        // Constructors, getters, and setters
        public PerformanceImpactDTO() {}

        public Long getEstimatedProcessingTimeMs() { return estimatedProcessingTimeMs; }
        public void setEstimatedProcessingTimeMs(Long estimatedProcessingTimeMs) { this.estimatedProcessingTimeMs = estimatedProcessingTimeMs; }

        public Double getCpuUtilizationIncrease() { return cpuUtilizationIncrease; }
        public void setCpuUtilizationIncrease(Double cpuUtilizationIncrease) { this.cpuUtilizationIncrease = cpuUtilizationIncrease; }

        public Double getMemoryUtilizationIncrease() { return memoryUtilizationIncrease; }
        public void setMemoryUtilizationIncrease(Double memoryUtilizationIncrease) { this.memoryUtilizationIncrease = memoryUtilizationIncrease; }

        public Integer getEstimatedApiCalls() { return estimatedApiCalls; }
        public void setEstimatedApiCalls(Integer estimatedApiCalls) { this.estimatedApiCalls = estimatedApiCalls; }

        public String getPerformanceRiskLevel() { return performanceRiskLevel; }
        public void setPerformanceRiskLevel(String performanceRiskLevel) { this.performanceRiskLevel = performanceRiskLevel; }
    }

    /**
     * Cost impact analysis.
     */
    public static class CostImpactDTO {
        private Double estimatedMonthlyCost;
        private Double estimatedImplementationCost;
        private Double potentialSavings;
        private String costRiskLevel;
        private Map<String, Double> costBreakdown;

        // Constructors, getters, and setters
        public CostImpactDTO() {}

        public Double getEstimatedMonthlyCost() { return estimatedMonthlyCost; }
        public void setEstimatedMonthlyCost(Double estimatedMonthlyCost) { this.estimatedMonthlyCost = estimatedMonthlyCost; }

        public Double getEstimatedImplementationCost() { return estimatedImplementationCost; }
        public void setEstimatedImplementationCost(Double estimatedImplementationCost) { this.estimatedImplementationCost = estimatedImplementationCost; }

        public Double getPotentialSavings() { return potentialSavings; }
        public void setPotentialSavings(Double potentialSavings) { this.potentialSavings = potentialSavings; }

        public String getCostRiskLevel() { return costRiskLevel; }
        public void setCostRiskLevel(String costRiskLevel) { this.costRiskLevel = costRiskLevel; }

        public Map<String, Double> getCostBreakdown() { return costBreakdown; }
        public void setCostBreakdown(Map<String, Double> costBreakdown) { this.costBreakdown = costBreakdown; }
    }

    /**
     * Compliance impact analysis.
     */
    public static class ComplianceImpactDTO {
        private Integer conflictingPolicies;
        private List<String> complianceFrameworksAffected;
        private String complianceRiskLevel;
        private List<String> potentialViolations;

        // Constructors, getters, and setters
        public ComplianceImpactDTO() {}

        public Integer getConflictingPolicies() { return conflictingPolicies; }
        public void setConflictingPolicies(Integer conflictingPolicies) { this.conflictingPolicies = conflictingPolicies; }

        public List<String> getComplianceFrameworksAffected() { return complianceFrameworksAffected; }
        public void setComplianceFrameworksAffected(List<String> complianceFrameworksAffected) { this.complianceFrameworksAffected = complianceFrameworksAffected; }

        public String getComplianceRiskLevel() { return complianceRiskLevel; }
        public void setComplianceRiskLevel(String complianceRiskLevel) { this.complianceRiskLevel = complianceRiskLevel; }

        public List<String> getPotentialViolations() { return potentialViolations; }
        public void setPotentialViolations(List<String> potentialViolations) { this.potentialViolations = potentialViolations; }
    }

    /**
     * Risk assessment for policy implementation.
     */
    public static class RiskAssessmentDTO {
        private String overallRiskLevel;
        private List<String> identifiedRisks;
        private List<String> mitigationStrategies;
        private Double riskScore;

        // Constructors, getters, and setters
        public RiskAssessmentDTO() {}

        public String getOverallRiskLevel() { return overallRiskLevel; }
        public void setOverallRiskLevel(String overallRiskLevel) { this.overallRiskLevel = overallRiskLevel; }

        public List<String> getIdentifiedRisks() { return identifiedRisks; }
        public void setIdentifiedRisks(List<String> identifiedRisks) { this.identifiedRisks = identifiedRisks; }

        public List<String> getMitigationStrategies() { return mitigationStrategies; }
        public void setMitigationStrategies(List<String> mitigationStrategies) { this.mitigationStrategies = mitigationStrategies; }

        public Double getRiskScore() { return riskScore; }
        public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
    }

    /**
     * Analysis execution metadata.
     */
    public static class AnalysisMetadataDTO {
        private Long executionTimeMs;
        private String analysisVersion;
        private Boolean usesCachedData;
        private Instant dataFreshnessTimestamp;

        // Constructors, getters, and setters
        public AnalysisMetadataDTO() {}

        public Long getExecutionTimeMs() { return executionTimeMs; }
        public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

        public String getAnalysisVersion() { return analysisVersion; }
        public void setAnalysisVersion(String analysisVersion) { this.analysisVersion = analysisVersion; }

        public Boolean getUsesCachedData() { return usesCachedData; }
        public void setUsesCachedData(Boolean usesCachedData) { this.usesCachedData = usesCachedData; }

        public Instant getDataFreshnessTimestamp() { return dataFreshnessTimestamp; }
        public void setDataFreshnessTimestamp(Instant dataFreshnessTimestamp) { this.dataFreshnessTimestamp = dataFreshnessTimestamp; }
    }
} 