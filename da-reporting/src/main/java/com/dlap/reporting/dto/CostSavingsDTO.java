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

package com.dlap.reporting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for Cost Savings Dashboard response
 * Provides comprehensive cost analysis and savings opportunities.
 *
 * <p>Dollar figures exist only when {@link #available} is true, which requires a real
 * cloud-billing source. No billing source is connected yet, so today {@code available} is
 * false and every dollar, storage and ratio field is {@code null}. A consumer must show
 * "not available" for a null figure and never read it as zero. Asset counts are real
 * catalog counts and are filled whenever the catalog answers.
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostSavingsDTO {

    /**
     * True only when the dollar figures in this response come from a real billing source.
     * False means those figures are null.
     */
    private Boolean available;

    /**
     * Why the figures are available or not, and what they are based on. Human readable.
     */
    private String basis;

    /**
     * Total cost savings achieved (USD)
     */
    private BigDecimal totalSavings;

    /**
     * Monthly savings rate (USD per month)
     */
    private BigDecimal monthlySavingsRate;

    /**
     * ROI percentage including platform costs
     */
    private Double roiPercentage;

    /**
     * Cost savings breakdown by category
     */
    private CostSavingsBreakdown breakdown;

    /**
     * Savings by cloud provider
     */
    private List<ProviderSavings> providerSavings;

    /**
     * Projected savings pipeline
     */
    private List<SavingsOpportunity> savingsOpportunities;

    /**
     * Historical savings trend (last 12 months)
     */
    private List<SavingsTrendPoint> historicalTrend;

    /**
     * Cost optimization recommendations
     */
    private List<String> recommendations;

    /**
     * Last calculation timestamp
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastCalculated;

    /**
     * Data accuracy indicator
     */
    private String dataAccuracy; // "REAL_TIME", "DAILY", "UNAVAILABLE"

    /**
     * Retained for API compatibility. The service no longer produces estimated figures, so this
     * is null; use {@link #available} to tell whether dollar figures exist.
     */
    private Boolean isEstimate;

    /**
     * True when the catalog upstream responded successfully. False means the
     * catalog call failed and the asset counts in this response are null.
     * That differs from the case where the catalog responded with zero counts.
     */
    private Boolean dataAvailable;

    /**
     * Non-null error summary when the catalog call failed. Purely diagnostic.
     */
    private String lastError;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostSavingsBreakdown {
        
        /**
         * Savings from archival operations
         */
        private BigDecimal archivalSavings;
        private Integer archivedAssetsCount;
        private String archivalStorageUsed; // e.g., "2.5 TB"

        /**
         * Savings from deletion operations
         */
        private BigDecimal deletionSavings;
        private Integer deletedAssetsCount;
        private String storageReclaimed; // e.g., "5.2 TB"

        /**
         * Savings from storage tier optimization
         */
        private BigDecimal tierOptimizationSavings;
        private Integer optimizedAssetsCount;

        /**
         * Compute cost reductions
         */
        private BigDecimal computeSavings;
        private Integer decommissionedResources;

        /**
         * Platform operational costs
         */
        private BigDecimal platformCosts;
        private BigDecimal netSavings; // totalSavings - platformCosts
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderSavings {
        private String providerName; // "AWS", "GCP", "Azure", "OCI"
        private BigDecimal savings;
        private BigDecimal monthlyRate;
        private String primarySavingsSource; // "ARCHIVAL", "DELETION", "OPTIMIZATION"
        private Double contributionPercentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SavingsOpportunity {
        private String opportunityType; // "ARCHIVAL_CANDIDATE", "DELETION_CANDIDATE", "TIER_OPTIMIZATION"
        private String description;
        private BigDecimal potentialSavings;
        private BigDecimal monthlyImpact;
        private String timeframe; // "IMMEDIATE", "30_DAYS", "90_DAYS"
        private String effortLevel; // "LOW", "MEDIUM", "HIGH"
        private Integer affectedAssets;
        private String providerName;
        private String actionRequired;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SavingsTrendPoint {
        @JsonFormat(pattern = "yyyy-MM")
        private LocalDateTime month;
        private BigDecimal cumulativeSavings;
        private BigDecimal monthlySavings;
        private BigDecimal platformCosts;
        private BigDecimal netSavings;
        private Integer assetsProcessed;
    }
} 