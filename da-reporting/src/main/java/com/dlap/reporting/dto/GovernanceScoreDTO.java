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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for Governance Score Dashboard response
 * Provides comprehensive governance metrics and breakdown.
 *
 * <p>Every number is computed from live catalog and policy-engine data. A component whose
 * source did not answer is {@code null} and is left out of {@link #overallScore}, whose weights
 * are re-balanced over {@link #scoredComponents}. {@link #benchmarkAvailable} and
 * {@link #historyAvailable} are false until a benchmark dataset and a score history exist; the
 * matching {@code benchmark} and {@code historicalTrend} fields are then null.
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceScoreDTO {

    /**
     * Overall governance score (0-100)
     * Weighted calculation: Policy Compliance 30%, Data Classification 25%, 
     * Metadata Completeness 25%, Lineage Coverage 20%. Null when no component could be computed.
     */
    private Double overallScore;

    /**
     * The components that went into {@link #overallScore}: any of {@code POLICY_COMPLIANCE},
     * {@code DATA_CLASSIFICATION}, {@code METADATA_COMPLETENESS}, {@code LINEAGE_COVERAGE}.
     */
    private List<String> scoredComponents;

    /**
     * Governance score breakdown by category
     */
    private GovernanceBreakdown breakdown;

    /**
     * Historical trend data (last 30 days). Null: no score history is stored yet.
     */
    private List<GovernanceTrendPoint> historicalTrend;

    /**
     * False until a stored score history exists. While false, {@code historicalTrend} is null.
     */
    private Boolean historyAvailable;

    /**
     * Top governance issues requiring attention
     */
    private List<GovernanceIssue> topIssues;

    /**
     * Benchmark comparison against industry standards. Null: no benchmark dataset is connected.
     */
    private BenchmarkComparison benchmark;

    /**
     * False until a real benchmark dataset is connected. While false, {@code benchmark} is null.
     */
    private Boolean benchmarkAvailable;

    /**
     * Score improvement recommendations
     */
    private List<String> recommendations;

    /**
     * Last calculation timestamp
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastCalculated;

    /**
     * Data freshness indicator (minutes since last update)
     */
    private Integer dataFreshnessMinutes;

    /**
     * True when at least one upstream service responded successfully. False means
     * all upstream calls failed (catalog + policy engine) and the response is a
     * best-effort shell. That differs from the "no data" case where services
     * responded but returned zero records.
     */
    private Boolean dataAvailable;

    /**
     * Non-null error summary when upstream calls failed. Purely diagnostic.
     * Clients should still render the response and show a warning banner.
     */
    private String lastError;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceBreakdown {
        
        /**
         * Policy compliance score (30% weight)
         */
        private Double policyComplianceScore;
        private Integer activePolicies;
        private Integer violationsCount;
        private Double complianceRate;

        /**
         * Data classification score (25% weight)
         */
        private Double dataClassificationScore;
        private Integer classifiedAssets;
        private Integer totalAssets;
        private Double classificationRate;

        /**
         * Metadata completeness score (25% weight)
         */
        private Double metadataCompletenessScore;
        private Integer assetsWithMetadata;
        private Integer assetsWithBusinessContext;
        private Double metadataCompletionRate;

        /**
         * Lineage coverage score (20% weight)
         */
        private Double lineageCoverageScore;
        private Integer assetsWithLineage;
        private Integer totalLineageConnections;
        private Double lineageCoverageRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceTrendPoint {
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDateTime date;
        private Double score;
        private String period; // "daily", "weekly"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceIssue {
        private String category; // "POLICY_VIOLATION", "MISSING_CLASSIFICATION", "INCOMPLETE_METADATA", "MISSING_LINEAGE"
        private String description;
        private String severity; // "HIGH", "MEDIUM", "LOW"
        private Integer affectedAssets;
        private String actionRequired;
        private String assignedTeam;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BenchmarkComparison {
        private Double industryAverage;
        private Double peerAverage;
        private String ranking; // "EXCELLENT", "GOOD", "AVERAGE", "BELOW_AVERAGE", "POOR"
        private Integer percentile; // 0-100
        private String comparisonNote;
        /**
         * True if any of the benchmark values are estimates rather than values sourced from a
         * real benchmarking dataset. The service no longer produces benchmark values.
         */
        private Boolean isEstimate;
    }
} 