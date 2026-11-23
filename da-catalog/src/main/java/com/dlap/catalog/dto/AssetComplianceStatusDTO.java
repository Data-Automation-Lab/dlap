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
 * DTO for Asset Compliance Status information.
 * Priority 2 endpoint: Provides detailed compliance status and monitoring for assets.
 *
 * <p>The catalog does not store compliance evaluations (da-autocompliance owns them), so
 * until an evaluation source is wired the response carries {@code available=false} with
 * {@code overallComplianceStatus=UNKNOWN} and an explanation in {@code basis}; every other
 * field is {@code null} and omitted from the JSON. Consumers must treat an absent field as
 * "not available", never as compliant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetComplianceStatusDTO {

    private UUID assetId;
    private String assetName;
    /** False when the catalog has no stored compliance evaluation for the asset. */
    private Boolean available;
    /** Short, human-readable statement of where the values come from (or why there are none). */
    private String basis;
    private String overallComplianceStatus; // COMPLIANT, NON_COMPLIANT, PARTIALLY_COMPLIANT, UNKNOWN, MONITORING
    private Double overallComplianceScore; // 0.0 to 100.0
    private List<ComplianceFrameworkStatusDTO> frameworkCompliance;
    private List<ComplianceViolationDTO> violations;
    private List<ComplianceControlDTO> controls;
    private ComplianceRiskAssessmentDTO riskAssessment;
    private List<ComplianceRecommendationDTO> recommendations;
    private ComplianceHistoryDTO complianceHistory;
    private Instant lastEvaluated;
    private String evaluatedBy;
    private Instant nextEvaluation;

    /**
     * Default constructor.
     */
    public AssetComplianceStatusDTO() {}

    /**
     * Constructor with basic information.
     */
    public AssetComplianceStatusDTO(UUID assetId, String assetName) {
        this.assetId = assetId;
        this.assetName = assetName;
    }

    /**
     * DTO for compliance framework status.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceFrameworkStatusDTO {
        
        private String frameworkName; // GDPR, SOX, HIPAA, PCI_DSS, ISO_27001, etc.
        private String frameworkVersion;
        private String complianceStatus;
        private Double complianceScore;
        private List<String> applicableControls;
        private List<String> passedControls;
        private List<String> failedControls;
        private Integer totalRequirements;
        private Integer metRequirements;
        private Instant lastAssessment;
        private String certificationStatus;
        private Instant certificationExpiry;

        public ComplianceFrameworkStatusDTO() {}

        public ComplianceFrameworkStatusDTO(String frameworkName, String complianceStatus, Double complianceScore) {
            this.frameworkName = frameworkName;
            this.complianceStatus = complianceStatus;
            this.complianceScore = complianceScore;
        }

        // Getters and setters
        public String getFrameworkName() { return frameworkName; }
        public void setFrameworkName(String frameworkName) { this.frameworkName = frameworkName; }
        public String getFrameworkVersion() { return frameworkVersion; }
        public void setFrameworkVersion(String frameworkVersion) { this.frameworkVersion = frameworkVersion; }
        public String getComplianceStatus() { return complianceStatus; }
        public void setComplianceStatus(String complianceStatus) { this.complianceStatus = complianceStatus; }
        public Double getComplianceScore() { return complianceScore; }
        public void setComplianceScore(Double complianceScore) { this.complianceScore = complianceScore; }
        public List<String> getApplicableControls() { return applicableControls; }
        public void setApplicableControls(List<String> applicableControls) { this.applicableControls = applicableControls; }
        public List<String> getPassedControls() { return passedControls; }
        public void setPassedControls(List<String> passedControls) { this.passedControls = passedControls; }
        public List<String> getFailedControls() { return failedControls; }
        public void setFailedControls(List<String> failedControls) { this.failedControls = failedControls; }
        public Integer getTotalRequirements() { return totalRequirements; }
        public void setTotalRequirements(Integer totalRequirements) { this.totalRequirements = totalRequirements; }
        public Integer getMetRequirements() { return metRequirements; }
        public void setMetRequirements(Integer metRequirements) { this.metRequirements = metRequirements; }
        public Instant getLastAssessment() { return lastAssessment; }
        public void setLastAssessment(Instant lastAssessment) { this.lastAssessment = lastAssessment; }
        public String getCertificationStatus() { return certificationStatus; }
        public void setCertificationStatus(String certificationStatus) { this.certificationStatus = certificationStatus; }
        public Instant getCertificationExpiry() { return certificationExpiry; }
        public void setCertificationExpiry(Instant certificationExpiry) { this.certificationExpiry = certificationExpiry; }
    }

    /**
     * DTO for compliance violations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceViolationDTO {
        
        private String violationId;
        private String violationType; // DATA_RETENTION, ACCESS_CONTROL, ENCRYPTION, AUDIT_TRAIL, etc.
        private String severity; // CRITICAL, HIGH, MEDIUM, LOW
        private String description;
        private String framework;
        private String controlId;
        private String currentValue;
        private String expectedValue;
        private Instant detectedAt;
        private String detectedBy;
        private String status; // OPEN, IN_PROGRESS, RESOLVED, ACCEPTED_RISK
        private String assignedTo;
        private Instant dueDate;
        private List<String> remediationActions;
        private Map<String, Object> evidence;

        public ComplianceViolationDTO() {}

        public ComplianceViolationDTO(String violationId, String violationType, String severity) {
            this.violationId = violationId;
            this.violationType = violationType;
            this.severity = severity;
        }

        // Getters and setters
        public String getViolationId() { return violationId; }
        public void setViolationId(String violationId) { this.violationId = violationId; }
        public String getViolationType() { return violationType; }
        public void setViolationType(String violationType) { this.violationType = violationType; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getFramework() { return framework; }
        public void setFramework(String framework) { this.framework = framework; }
        public String getControlId() { return controlId; }
        public void setControlId(String controlId) { this.controlId = controlId; }
        public String getCurrentValue() { return currentValue; }
        public void setCurrentValue(String currentValue) { this.currentValue = currentValue; }
        public String getExpectedValue() { return expectedValue; }
        public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }
        public Instant getDetectedAt() { return detectedAt; }
        public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
        public String getDetectedBy() { return detectedBy; }
        public void setDetectedBy(String detectedBy) { this.detectedBy = detectedBy; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAssignedTo() { return assignedTo; }
        public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
        public Instant getDueDate() { return dueDate; }
        public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }
        public List<String> getRemediationActions() { return remediationActions; }
        public void setRemediationActions(List<String> remediationActions) { this.remediationActions = remediationActions; }
        public Map<String, Object> getEvidence() { return evidence; }
        public void setEvidence(Map<String, Object> evidence) { this.evidence = evidence; }
    }

    /**
     * DTO for compliance controls.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceControlDTO {
        
        private String controlId;
        private String controlName;
        private String controlType; // PREVENTIVE, DETECTIVE, CORRECTIVE
        private String framework;
        private String description;
        private String status; // PASSED, FAILED, NOT_APPLICABLE, MANUAL_REVIEW
        private String implementationStatus; // IMPLEMENTED, PARTIALLY_IMPLEMENTED, NOT_IMPLEMENTED
        private Double effectivenessScore;
        private Instant lastTested;
        private String testedBy;
        private Instant nextTest;
        private String testFrequency; // DAILY, WEEKLY, MONTHLY, QUARTERLY, ANNUALLY
        private List<String> evidenceRequired;
        private Map<String, Object> testResults;

        public ComplianceControlDTO() {}

        public ComplianceControlDTO(String controlId, String controlName, String status) {
            this.controlId = controlId;
            this.controlName = controlName;
            this.status = status;
        }

        // Getters and setters
        public String getControlId() { return controlId; }
        public void setControlId(String controlId) { this.controlId = controlId; }
        public String getControlName() { return controlName; }
        public void setControlName(String controlName) { this.controlName = controlName; }
        public String getControlType() { return controlType; }
        public void setControlType(String controlType) { this.controlType = controlType; }
        public String getFramework() { return framework; }
        public void setFramework(String framework) { this.framework = framework; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getImplementationStatus() { return implementationStatus; }
        public void setImplementationStatus(String implementationStatus) { this.implementationStatus = implementationStatus; }
        public Double getEffectivenessScore() { return effectivenessScore; }
        public void setEffectivenessScore(Double effectivenessScore) { this.effectivenessScore = effectivenessScore; }
        public Instant getLastTested() { return lastTested; }
        public void setLastTested(Instant lastTested) { this.lastTested = lastTested; }
        public String getTestedBy() { return testedBy; }
        public void setTestedBy(String testedBy) { this.testedBy = testedBy; }
        public Instant getNextTest() { return nextTest; }
        public void setNextTest(Instant nextTest) { this.nextTest = nextTest; }
        public String getTestFrequency() { return testFrequency; }
        public void setTestFrequency(String testFrequency) { this.testFrequency = testFrequency; }
        public List<String> getEvidenceRequired() { return evidenceRequired; }
        public void setEvidenceRequired(List<String> evidenceRequired) { this.evidenceRequired = evidenceRequired; }
        public Map<String, Object> getTestResults() { return testResults; }
        public void setTestResults(Map<String, Object> testResults) { this.testResults = testResults; }
    }

    /**
     * DTO for compliance risk assessment.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceRiskAssessmentDTO {
        
        private String overallRiskLevel; // CRITICAL, HIGH, MEDIUM, LOW
        private Double riskScore; // 0.0 to 100.0
        private List<String> riskFactors;
        private Map<String, String> riskCategories; // category -> risk level
        private String residualRisk;
        private List<String> mitigationMeasures;
        private Instant lastAssessed;
        private String assessedBy;
        private Instant nextAssessment;

        public ComplianceRiskAssessmentDTO() {}

        // Getters and setters
        public String getOverallRiskLevel() { return overallRiskLevel; }
        public void setOverallRiskLevel(String overallRiskLevel) { this.overallRiskLevel = overallRiskLevel; }
        public Double getRiskScore() { return riskScore; }
        public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
        public List<String> getRiskFactors() { return riskFactors; }
        public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }
        public Map<String, String> getRiskCategories() { return riskCategories; }
        public void setRiskCategories(Map<String, String> riskCategories) { this.riskCategories = riskCategories; }
        public String getResidualRisk() { return residualRisk; }
        public void setResidualRisk(String residualRisk) { this.residualRisk = residualRisk; }
        public List<String> getMitigationMeasures() { return mitigationMeasures; }
        public void setMitigationMeasures(List<String> mitigationMeasures) { this.mitigationMeasures = mitigationMeasures; }
        public Instant getLastAssessed() { return lastAssessed; }
        public void setLastAssessed(Instant lastAssessed) { this.lastAssessed = lastAssessed; }
        public String getAssessedBy() { return assessedBy; }
        public void setAssessedBy(String assessedBy) { this.assessedBy = assessedBy; }
        public Instant getNextAssessment() { return nextAssessment; }
        public void setNextAssessment(Instant nextAssessment) { this.nextAssessment = nextAssessment; }
    }

    /**
     * DTO for compliance recommendations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceRecommendationDTO {
        
        private String recommendationId;
        private String type; // REMEDIATION, ENHANCEMENT, PREVENTIVE
        private String priority; // CRITICAL, HIGH, MEDIUM, LOW
        private String title;
        private String description;
        private String framework;
        private List<String> affectedControls;
        private String actionRequired;
        private Integer estimatedEffort; // in hours
        private String costEstimate;
        private String expectedOutcome;
        private String assignedTo;
        private Instant createdAt;
        private Instant targetDate;

        public ComplianceRecommendationDTO() {}

        public ComplianceRecommendationDTO(String recommendationId, String type, String priority) {
            this.recommendationId = recommendationId;
            this.type = type;
            this.priority = priority;
        }

        // Getters and setters
        public String getRecommendationId() { return recommendationId; }
        public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getFramework() { return framework; }
        public void setFramework(String framework) { this.framework = framework; }
        public List<String> getAffectedControls() { return affectedControls; }
        public void setAffectedControls(List<String> affectedControls) { this.affectedControls = affectedControls; }
        public String getActionRequired() { return actionRequired; }
        public void setActionRequired(String actionRequired) { this.actionRequired = actionRequired; }
        public Integer getEstimatedEffort() { return estimatedEffort; }
        public void setEstimatedEffort(Integer estimatedEffort) { this.estimatedEffort = estimatedEffort; }
        public String getCostEstimate() { return costEstimate; }
        public void setCostEstimate(String costEstimate) { this.costEstimate = costEstimate; }
        public String getExpectedOutcome() { return expectedOutcome; }
        public void setExpectedOutcome(String expectedOutcome) { this.expectedOutcome = expectedOutcome; }
        public String getAssignedTo() { return assignedTo; }
        public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getTargetDate() { return targetDate; }
        public void setTargetDate(Instant targetDate) { this.targetDate = targetDate; }
    }

    /**
     * DTO for compliance history tracking.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceHistoryDTO {
        
        private List<ComplianceEventDTO> recentEvents;
        private Map<String, Double> scoreHistory; // timestamp -> score
        private List<String> trendAnalysis;
        private Integer totalEvaluations;
        private Instant firstEvaluation;
        private Double averageScore;
        private String trend; // IMPROVING, DECLINING, STABLE

        public ComplianceHistoryDTO() {}

        // Getters and setters
        public List<ComplianceEventDTO> getRecentEvents() { return recentEvents; }
        public void setRecentEvents(List<ComplianceEventDTO> recentEvents) { this.recentEvents = recentEvents; }
        public Map<String, Double> getScoreHistory() { return scoreHistory; }
        public void setScoreHistory(Map<String, Double> scoreHistory) { this.scoreHistory = scoreHistory; }
        public List<String> getTrendAnalysis() { return trendAnalysis; }
        public void setTrendAnalysis(List<String> trendAnalysis) { this.trendAnalysis = trendAnalysis; }
        public Integer getTotalEvaluations() { return totalEvaluations; }
        public void setTotalEvaluations(Integer totalEvaluations) { this.totalEvaluations = totalEvaluations; }
        public Instant getFirstEvaluation() { return firstEvaluation; }
        public void setFirstEvaluation(Instant firstEvaluation) { this.firstEvaluation = firstEvaluation; }
        public Double getAverageScore() { return averageScore; }
        public void setAverageScore(Double averageScore) { this.averageScore = averageScore; }
        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }
    }

    /**
     * DTO for compliance events.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComplianceEventDTO {
        
        private String eventType; // EVALUATION, VIOLATION_DETECTED, VIOLATION_RESOLVED, CONTROL_UPDATED
        private String description;
        private Instant timestamp;
        private String triggeredBy;
        private Map<String, Object> details;

        public ComplianceEventDTO() {}

        public ComplianceEventDTO(String eventType, String description, Instant timestamp) {
            this.eventType = eventType;
            this.description = description;
            this.timestamp = timestamp;
        }

        // Getters and setters
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public String getTriggeredBy() { return triggeredBy; }
        public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
        public Map<String, Object> getDetails() { return details; }
        public void setDetails(Map<String, Object> details) { this.details = details; }
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
    public String getOverallComplianceStatus() { return overallComplianceStatus; }
    public void setOverallComplianceStatus(String overallComplianceStatus) { this.overallComplianceStatus = overallComplianceStatus; }
    public Double getOverallComplianceScore() { return overallComplianceScore; }
    public void setOverallComplianceScore(Double overallComplianceScore) { this.overallComplianceScore = overallComplianceScore; }
    public List<ComplianceFrameworkStatusDTO> getFrameworkCompliance() { return frameworkCompliance; }
    public void setFrameworkCompliance(List<ComplianceFrameworkStatusDTO> frameworkCompliance) { this.frameworkCompliance = frameworkCompliance; }
    public List<ComplianceViolationDTO> getViolations() { return violations; }
    public void setViolations(List<ComplianceViolationDTO> violations) { this.violations = violations; }
    public List<ComplianceControlDTO> getControls() { return controls; }
    public void setControls(List<ComplianceControlDTO> controls) { this.controls = controls; }
    public ComplianceRiskAssessmentDTO getRiskAssessment() { return riskAssessment; }
    public void setRiskAssessment(ComplianceRiskAssessmentDTO riskAssessment) { this.riskAssessment = riskAssessment; }
    public List<ComplianceRecommendationDTO> getRecommendations() { return recommendations; }
    public void setRecommendations(List<ComplianceRecommendationDTO> recommendations) { this.recommendations = recommendations; }
    public ComplianceHistoryDTO getComplianceHistory() { return complianceHistory; }
    public void setComplianceHistory(ComplianceHistoryDTO complianceHistory) { this.complianceHistory = complianceHistory; }
    public Instant getLastEvaluated() { return lastEvaluated; }
    public void setLastEvaluated(Instant lastEvaluated) { this.lastEvaluated = lastEvaluated; }
    public String getEvaluatedBy() { return evaluatedBy; }
    public void setEvaluatedBy(String evaluatedBy) { this.evaluatedBy = evaluatedBy; }
    public Instant getNextEvaluation() { return nextEvaluation; }
    public void setNextEvaluation(Instant nextEvaluation) { this.nextEvaluation = nextEvaluation; }
} 