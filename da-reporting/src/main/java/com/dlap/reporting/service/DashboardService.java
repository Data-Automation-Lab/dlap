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

package com.dlap.reporting.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientPolicyEngineClient;
import com.dlap.reporting.dto.ActionItemsDTO;
import com.dlap.reporting.dto.CostSavingsDTO;
import com.dlap.reporting.dto.GovernanceScoreDTO;

import lombok.RequiredArgsConstructor;

/**
 * Dashboard aggregation from live catalog and policy-engine metrics.
 *
 * <p>Nothing here is estimated. A value whose source did not answer, or that has no source yet
 * (benchmarks, score history, cloud-billing savings, SLA and due-date data), is {@code null} and
 * the response carries an explicit flag ({@code benchmarkAvailable}, {@code historyAvailable},
 * {@code available}) so the UI can show "not available" instead of a made-up number.
 */
@Service
@RequiredArgsConstructor
public class DashboardService implements IDashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** Weights of the overall governance score; they sum to 1.0. */
    private static final double POLICY_WEIGHT = 0.30;
    private static final double CLASSIFICATION_WEIGHT = 0.25;
    private static final double METADATA_WEIGHT = 0.25;
    private static final double LINEAGE_WEIGHT = 0.20;

    private static final String COMPONENT_POLICY = "POLICY_COMPLIANCE";
    private static final String COMPONENT_CLASSIFICATION = "DATA_CLASSIFICATION";
    private static final String COMPONENT_METADATA = "METADATA_COMPLETENESS";
    private static final String COMPONENT_LINEAGE = "LINEAGE_COVERAGE";

    private static final String COST_BASIS_UNAVAILABLE =
            "No cloud billing source is connected, so cost savings are not estimated. "
                    + "Asset counts are real catalog counts.";

    private final ResilientCatalogClient catalogServiceClient;
    private final ResilientPolicyEngineClient policyEngineServiceClient;

    /**
     * Time source for response timestamps. Defaults to the system clock; a {@link Clock} bean,
     * when the application defines one, replaces it (rule R6).
     */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * Optional {@link Clock} injection point; package-private so tests can fix the time
     * without a constructor change.
     */
    @Autowired(required = false)
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public GovernanceScoreDTO getGovernanceScore(Boolean includeHistory, Boolean includeBenchmark) {
        log.info("Calculating governance score (history requested: {}, benchmark requested: {})",
                includeHistory, includeBenchmark);

        Long totalAssets = null;
        Long classified = null;
        Long withMetadata = null;
        Long withBusinessContext = null;
        Long withLineage = null;
        Long lineageConnections = null;
        Map<String, Object> govIssues = Collections.emptyMap();
        boolean catalogAvailable = true;

        Long activePolicies = null;
        Long violations = null;
        Double complianceRate = null;
        boolean policyAvailable = true;

        List<String> notes = new ArrayList<>();

        try {
            totalAssets = catalogServiceClient.getTotalAssetCount();
            classified = catalogServiceClient.getClassifiedAssetsCount();
            withMetadata = catalogServiceClient.getAssetsWithMetadataCount();
            withBusinessContext = catalogServiceClient.getAssetsWithBusinessContextCount();
            withLineage = catalogServiceClient.getAssetsWithLineageCount();
            lineageConnections = catalogServiceClient.getTotalLineageConnectionsCount();
            govIssues = catalogServiceClient.getGovernanceIssues(null);
        } catch (Exception e) {
            catalogAvailable = false;
            totalAssets = null;
            classified = null;
            withMetadata = null;
            withBusinessContext = null;
            withLineage = null;
            lineageConnections = null;
            govIssues = Collections.emptyMap();
            log.warn("Catalog metrics unavailable for governance score: {}", e.getMessage());
            notes.add("catalog: " + e.getMessage());
        }

        try {
            activePolicies = policyEngineServiceClient.getActivePoliciesCount();
            violations = policyEngineServiceClient.getPolicyViolationsCount(null);
            complianceRate = policyEngineServiceClient.getPolicyComplianceRate();
        } catch (Exception e) {
            policyAvailable = false;
            activePolicies = null;
            violations = null;
            complianceRate = null;
            log.warn("Policy engine metrics unavailable for governance score: {}", e.getMessage());
            notes.add("policyengine: " + e.getMessage());
        }
        if (policyAvailable && complianceRate == null) {
            notes.add("policyengine: compliance rate unavailable");
        }

        Double policyScore = complianceRate != null ? Double.valueOf(round1(complianceRate)) : null;
        Double classificationScore = pct(classified, totalAssets);
        Double metadataScore = pct(withMetadata, totalAssets);
        Double lineageScore = pct(withLineage, totalAssets);

        GovernanceScoreDTO.GovernanceBreakdown breakdown = GovernanceScoreDTO.GovernanceBreakdown.builder()
                .policyComplianceScore(policyScore)
                .activePolicies(toInt(activePolicies))
                .violationsCount(toInt(violations))
                .complianceRate(policyScore)
                .dataClassificationScore(classificationScore)
                .classifiedAssets(toInt(classified))
                .totalAssets(toInt(totalAssets))
                .classificationRate(classificationScore)
                .metadataCompletenessScore(metadataScore)
                .assetsWithMetadata(toInt(withMetadata))
                .assetsWithBusinessContext(toInt(withBusinessContext))
                .metadataCompletionRate(metadataScore)
                .lineageCoverageScore(lineageScore)
                .assetsWithLineage(toInt(withLineage))
                .totalLineageConnections(toInt(lineageConnections))
                .lineageCoverageRate(lineageScore)
                .build();

        // Weighted mean over the components that could be computed. Weights are re-balanced
        // over those components, and the response names them, so a missing source never counts
        // as a zero.
        List<String> scored = new ArrayList<>();
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        if (policyScore != null) {
            weightedSum += policyScore * POLICY_WEIGHT;
            weightTotal += POLICY_WEIGHT;
            scored.add(COMPONENT_POLICY);
        }
        if (classificationScore != null) {
            weightedSum += classificationScore * CLASSIFICATION_WEIGHT;
            weightTotal += CLASSIFICATION_WEIGHT;
            scored.add(COMPONENT_CLASSIFICATION);
        }
        if (metadataScore != null) {
            weightedSum += metadataScore * METADATA_WEIGHT;
            weightTotal += METADATA_WEIGHT;
            scored.add(COMPONENT_METADATA);
        }
        if (lineageScore != null) {
            weightedSum += lineageScore * LINEAGE_WEIGHT;
            weightTotal += LINEAGE_WEIGHT;
            scored.add(COMPONENT_LINEAGE);
        }
        Double overallScore = weightTotal > 0.0 ? Double.valueOf(round1(weightedSum / weightTotal)) : null;

        List<GovernanceScoreDTO.GovernanceIssue> topIssues =
                buildTopIssues(govIssues, violations, catalogAvailable, policyAvailable);

        List<String> recommendations = new ArrayList<>();
        if (classificationScore != null && classificationScore < 80) {
            recommendations.add("Raise classification coverage toward 80% using autolabel and steward review.");
        }
        if (lineageScore != null && lineageScore < 75) {
            recommendations.add("Improve lineage capture for high-value datasets.");
        }
        if (violations != null && violations > 0) {
            recommendations.add("Resolve open policy evaluation failures tracked in the policy engine.");
        }
        if (recommendations.isEmpty() && scored.size() == 4) {
            recommendations.add("Maintain current governance posture; re-check after major catalog changes.");
        }

        return GovernanceScoreDTO.builder()
                .overallScore(overallScore)
                .scoredComponents(scored)
                .breakdown(breakdown)
                // No stored score history and no benchmark dataset exist, so both stay null and
                // are flagged instead of being synthesized from the current score.
                .historicalTrend(null)
                .historyAvailable(Boolean.FALSE)
                .topIssues(topIssues)
                .benchmark(null)
                .benchmarkAvailable(Boolean.FALSE)
                .recommendations(recommendations)
                .lastCalculated(LocalDateTime.now(clock))
                .dataFreshnessMinutes(null)
                .dataAvailable(catalogAvailable || policyAvailable)
                .lastError(notes.isEmpty() ? null : String.join("; ", notes))
                .build();
    }

    @Override
    public CostSavingsDTO getCostSavings(Boolean includeProjections, String timeframe) {
        log.info("Reading cost-savings inputs (timeframe: {}, projections requested: {})",
                timeframe, includeProjections);

        Long archived = null;
        Long deleted = null;
        Long pendingDeletion = null;
        boolean catalogAvailable = true;
        String lastError = null;
        try {
            archived = catalogServiceClient.countAssetsByStatus("ARCHIVED");
            deleted = catalogServiceClient.countAssetsByStatus("DELETED");
            pendingDeletion = catalogServiceClient.countAssetsByStatus("PENDING_DELETION");
        } catch (Exception e) {
            catalogAvailable = false;
            archived = null;
            deleted = null;
            pendingDeletion = null;
            lastError = "catalog: " + e.getMessage();
            log.warn("Catalog counts unavailable for cost savings: {}", e.getMessage());
        }

        // Dollar figures need a billing source, and there is none. The counts below are real
        // catalog counts; every money, storage and ratio field stays null.
        CostSavingsDTO.CostSavingsBreakdown breakdown = CostSavingsDTO.CostSavingsBreakdown.builder()
                .archivedAssetsCount(toInt(archived))
                .deletedAssetsCount(toInt(deleted))
                .build();

        List<String> recommendations = new ArrayList<>();
        if (pendingDeletion != null && pendingDeletion > 0) {
            recommendations.add(pendingDeletion + " assets are in PENDING_DELETION; review them in the deletion workflow.");
        }

        return CostSavingsDTO.builder()
                .available(Boolean.FALSE)
                .basis(COST_BASIS_UNAVAILABLE)
                .breakdown(breakdown)
                .recommendations(recommendations)
                .lastCalculated(LocalDateTime.now(clock))
                .dataAccuracy("UNAVAILABLE")
                .isEstimate(null)
                .dataAvailable(catalogAvailable)
                .lastError(lastError)
                .build();
    }

    @Override
    public ActionItemsDTO getActionItems(Boolean assignedToUser, String severity, String status, Integer limit) {
        log.info("Aggregating action items with filters - assignedToUser: {}, severity: {}, status: {}, limit: {}",
                assignedToUser, severity, status, limit);

        int cap = limit != null ? limit : 50;
        List<Map<String, Object>> raw = Collections.emptyList();
        boolean policyAvailable = true;
        String lastError = null;
        try {
            List<Map<String, Object>> fetched = policyEngineServiceClient.getPolicyActionItems(severity, status, cap);
            raw = fetched != null ? fetched : Collections.emptyList();
        } catch (Exception e) {
            policyAvailable = false;
            lastError = "policyengine: " + e.getMessage();
            log.warn("Policy action items unavailable: {}", e.getMessage());
        }

        List<ActionItemsDTO.ActionItem> items = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            if (row == null) {
                continue;
            }
            String targetAssetId = asText(row.get("targetAssetId"));
            LocalDateTime evaluatedAt = parseUtcTimestamp(row.get("evaluatedAt"));
            items.add(ActionItemsDTO.ActionItem.builder()
                    .id(asText(row.get("id")))
                    .title("Policy evaluation: " + (targetAssetId != null ? targetAssetId : "asset"))
                    .description("Failure recorded for policy " + asText(row.get("policyId")))
                    .category("POLICY_EVALUATION")
                    .severity(asText(row.get("severity")))
                    .status(asText(row.get("status")))
                    .sourceService("da-policyengine")
                    .sourceEntityType("ASSET")
                    .sourceEntityId(targetAssetId)
                    .sourceEntityName(targetAssetId)
                    // The evaluation time is the only timestamp the policy engine records.
                    .createdAt(evaluatedAt)
                    .lastUpdated(evaluatedAt)
                    .actionRequired("Review failing policy evaluation and remediate asset or policy.")
                    .tags(List.of("POLICY"))
                    .build());
        }

        // Only counts that the returned rows support. Due dates, assignment, SLA targets and
        // business-impact scores are not recorded anywhere, so those fields stay null.
        ActionItemsDTO.ActionItemsSummary summary = ActionItemsDTO.ActionItemsSummary.builder()
                .criticalCount(countBy(items, true, "CRITICAL"))
                .highCount(countBy(items, true, "HIGH"))
                .mediumCount(countBy(items, true, "MEDIUM"))
                .lowCount(countBy(items, true, "LOW"))
                .inProgressCount(countBy(items, false, "IN_PROGRESS"))
                .completedCount(countBy(items, false, "COMPLETED"))
                .build();

        return ActionItemsDTO.builder()
                .totalActionItems(items.size())
                .summary(summary)
                .actionItems(items)
                .slaMetrics(ActionItemsDTO.SlaMetrics.builder().build())
                .businessImpact(ActionItemsDTO.BusinessImpactSummary.builder().build())
                .lastAggregated(LocalDateTime.now(clock))
                .includedServices(List.of("da-policyengine"))
                .dataAvailable(policyAvailable)
                .lastError(lastError)
                .build();
    }

    @Override
    @CacheEvict(value = {"governanceScore", "costSavings", "actionItems"}, allEntries = true)
    public void refreshAllCaches() {
        log.info("Refreshing all dashboard caches");
        log.info("Dashboard caches refreshed successfully");
    }

    /** Percentage of {@code part} in {@code total} to one decimal, or null when it is undefined. */
    private static Double pct(Long part, Long total) {
        if (part == null || total == null || total <= 0) {
            return null;
        }
        return Math.round(1000.0 * part / total) / 10.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Integer toInt(Long value) {
        return value == null ? null : (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Parses an ISO-8601 instant (as the policy engine sends it) to a UTC date-time, or null. */
    private static LocalDateTime parseUtcTimestamp(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.warn("Ignoring unparseable action-item timestamp: {}", s);
            return null;
        }
    }

    private static int countBy(List<ActionItemsDTO.ActionItem> items, boolean bySeverity, String wanted) {
        return (int) items.stream()
                .filter(i -> wanted.equalsIgnoreCase(bySeverity ? i.getSeverity() : i.getStatus()))
                .count();
    }

    private static List<GovernanceScoreDTO.GovernanceIssue> buildTopIssues(
            Map<String, Object> gov, Long violations, boolean catalogAvailable, boolean policyAvailable) {
        List<GovernanceScoreDTO.GovernanceIssue> list = new ArrayList<>();
        if (gov != null && gov.get("unclassifiedAssetCount") instanceof Number u && u.longValue() > 0) {
            long c = u.longValue();
            list.add(GovernanceScoreDTO.GovernanceIssue.builder()
                    .category("MISSING_CLASSIFICATION")
                    .description(c + " assets lack label-based classification.")
                    .severity("HIGH")
                    .affectedAssets((int) Math.min(c, Integer.MAX_VALUE))
                    .actionRequired("Apply stewardship labels or autolabel jobs.")
                    .build());
        }
        if (violations != null && violations > 0) {
            list.add(GovernanceScoreDTO.GovernanceIssue.builder()
                    .category("POLICY_VIOLATION")
                    .description(violations + " failing policy evaluations require review.")
                    .severity("CRITICAL")
                    .affectedAssets((int) Math.min(violations, Integer.MAX_VALUE))
                    .actionRequired("Inspect policy engine action items.")
                    .build());
        }
        // "Nothing blocking" is only a finding when both sources answered; with a source down it
        // would be a claim about data we do not have.
        if (list.isEmpty() && catalogAvailable && policyAvailable) {
            list.add(GovernanceScoreDTO.GovernanceIssue.builder()
                    .category("NO_BLOCKING_ISSUES")
                    .description("No blocking catalog or policy issues detected from current metrics.")
                    .severity("INFO")
                    .affectedAssets(0)
                    .actionRequired("Continue periodic reviews.")
                    .build());
        }
        return list;
    }
}
