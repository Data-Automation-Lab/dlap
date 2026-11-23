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

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for requesting policy impact analysis preview.
 * This allows users to see what assets would be affected by policy changes before implementation.
 */
public class PolicyImpactRequestDTO {

    /**
     * The policy rules content to analyze for impact (JSON format).
     * Required field containing the policy rules that need impact analysis.
     */
    @NotBlank(message = "Policy rules content is required")
    private String rulesContent;

    /**
     * Type of analysis to perform: FULL, QUICK, or TARGETED.
     * - FULL: Comprehensive analysis across all assets
     * - QUICK: Fast analysis with sampling
     * - TARGETED: Analysis for specific asset types/groups
     */
    @NotBlank(message = "Analysis type is required")
    private String analysisType;

    /**
     * Optional scope limitations for targeted analysis.
     * Can include asset types, data sources, or specific asset IDs.
     */
    private PolicyImpactScopeDTO scope;

    /**
     * Include estimated performance impact in the analysis.
     * When true, includes estimates for processing time and resource usage.
     */
    private Boolean includePerformanceEstimate = false;

    /**
     * Include cost impact analysis in the preview.
     * When true, estimates potential costs of policy enforcement.
     */
    private Boolean includeCostImpact = false;

    /**
     * Include compliance impact analysis.
     * When true, shows how this policy affects compliance with other policies.
     */
    private Boolean includeComplianceImpact = false;

    /**
     * Additional context parameters for policy evaluation.
     * Flexible map for custom analysis parameters.
     */
    private Map<String, Object> contextParameters;

    // Constructors
    public PolicyImpactRequestDTO() {}

    public PolicyImpactRequestDTO(String rulesContent, String analysisType) {
        this.rulesContent = rulesContent;
        this.analysisType = analysisType;
    }

    // Getters and Setters
    public String getRulesContent() {
        return rulesContent;
    }

    public void setRulesContent(String rulesContent) {
        this.rulesContent = rulesContent;
    }

    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public PolicyImpactScopeDTO getScope() {
        return scope;
    }

    public void setScope(PolicyImpactScopeDTO scope) {
        this.scope = scope;
    }

    public Boolean getIncludePerformanceEstimate() {
        return includePerformanceEstimate;
    }

    public void setIncludePerformanceEstimate(Boolean includePerformanceEstimate) {
        this.includePerformanceEstimate = includePerformanceEstimate;
    }

    public Boolean getIncludeCostImpact() {
        return includeCostImpact;
    }

    public void setIncludeCostImpact(Boolean includeCostImpact) {
        this.includeCostImpact = includeCostImpact;
    }

    public Boolean getIncludeComplianceImpact() {
        return includeComplianceImpact;
    }

    public void setIncludeComplianceImpact(Boolean includeComplianceImpact) {
        this.includeComplianceImpact = includeComplianceImpact;
    }

    public Map<String, Object> getContextParameters() {
        return contextParameters;
    }

    public void setContextParameters(Map<String, Object> contextParameters) {
        this.contextParameters = contextParameters;
    }

    /**
     * Nested DTO for defining analysis scope limitations.
     */
    public static class PolicyImpactScopeDTO {
        /**
         * Specific asset types to include in analysis (e.g., "database", "file", "api").
         */
        private List<String> assetTypes;

        /**
         * Specific data sources to include (e.g., connection IDs or source names).
         */
        private List<String> dataSources;

        /**
         * Specific asset IDs to analyze (for targeted analysis).
         */
        private List<String> assetIds;

        /**
         * Include only assets with specific labels.
         */
        private List<String> requiredLabels;

        /**
         * Exclude assets with specific labels.
         */
        private List<String> excludedLabels;

        /**
         * Maximum number of assets to analyze (for performance control).
         */
        private Integer maxAssets;

        // Constructors
        public PolicyImpactScopeDTO() {}

        // Getters and Setters
        public List<String> getAssetTypes() {
            return assetTypes;
        }

        public void setAssetTypes(List<String> assetTypes) {
            this.assetTypes = assetTypes;
        }

        public List<String> getDataSources() {
            return dataSources;
        }

        public void setDataSources(List<String> dataSources) {
            this.dataSources = dataSources;
        }

        public List<String> getAssetIds() {
            return assetIds;
        }

        public void setAssetIds(List<String> assetIds) {
            this.assetIds = assetIds;
        }

        public List<String> getRequiredLabels() {
            return requiredLabels;
        }

        public void setRequiredLabels(List<String> requiredLabels) {
            this.requiredLabels = requiredLabels;
        }

        public List<String> getExcludedLabels() {
            return excludedLabels;
        }

        public void setExcludedLabels(List<String> excludedLabels) {
            this.excludedLabels = excludedLabels;
        }

        public Integer getMaxAssets() {
            return maxAssets;
        }

        public void setMaxAssets(Integer maxAssets) {
            this.maxAssets = maxAssets;
        }
    }
} 