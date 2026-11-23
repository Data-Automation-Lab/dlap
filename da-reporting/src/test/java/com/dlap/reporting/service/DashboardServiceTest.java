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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientPolicyEngineClient;
import com.dlap.reporting.dto.ActionItemsDTO;
import com.dlap.reporting.dto.CostSavingsDTO;
import com.dlap.reporting.dto.GovernanceScoreDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * UI-01: the dashboards must report live numbers or an explicit "not available". There is no
 * default compliance rate, no placeholder benchmark, no synthesized trend and no estimated savings.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Mock
    private ResilientCatalogClient catalog;
    @Mock
    private ResilientPolicyEngineClient policyEngine;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(catalog, policyEngine);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubCatalog(long total, long classified, long withMetadata, long withLineage) {
        when(catalog.getTotalAssetCount()).thenReturn(total);
        when(catalog.getClassifiedAssetsCount()).thenReturn(classified);
        when(catalog.getAssetsWithMetadataCount()).thenReturn(withMetadata);
        when(catalog.getAssetsWithBusinessContextCount()).thenReturn(withMetadata);
        when(catalog.getAssetsWithLineageCount()).thenReturn(withLineage);
        when(catalog.getTotalLineageConnectionsCount()).thenReturn(7L);
        when(catalog.getGovernanceIssues(any())).thenReturn(Map.of("unclassifiedAssetCount", total - classified));
    }

    private void stubPolicyEngine(long active, long violations, Double rate) {
        when(policyEngine.getActivePoliciesCount()).thenReturn(active);
        when(policyEngine.getPolicyViolationsCount(any())).thenReturn(violations);
        when(policyEngine.getPolicyComplianceRate()).thenReturn(rate);
    }

    // ---- governance score --------------------------------------------------------------------

    @Test
    void governanceScore_isTheWeightedMeanOfLiveComponents() {
        stubCatalog(200, 100, 160, 60);
        stubPolicyEngine(5, 0, 80.0);

        GovernanceScoreDTO score = service.getGovernanceScore(false, false);

        // 80*0.30 + 50*0.25 + 80*0.25 + 30*0.20
        assertThat(score.getOverallScore()).isEqualTo(62.5);
        assertThat(score.getScoredComponents()).containsExactly(
                "POLICY_COMPLIANCE", "DATA_CLASSIFICATION", "METADATA_COMPLETENESS", "LINEAGE_COVERAGE");
        assertThat(score.getBreakdown().getPolicyComplianceScore()).isEqualTo(80.0);
        assertThat(score.getBreakdown().getDataClassificationScore()).isEqualTo(50.0);
        assertThat(score.getBreakdown().getMetadataCompletenessScore()).isEqualTo(80.0);
        assertThat(score.getBreakdown().getLineageCoverageScore()).isEqualTo(30.0);
        assertThat(score.getBreakdown().getTotalAssets()).isEqualTo(200);
        assertThat(score.getBreakdown().getActivePolicies()).isEqualTo(5);
        assertThat(score.getBreakdown().getTotalLineageConnections()).isEqualTo(7);
        assertThat(score.getDataAvailable()).isTrue();
        assertThat(score.getLastError()).isNull();
        assertThat(score.getLastCalculated()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(score.getDataFreshnessMinutes()).isNull();
    }

    @Test
    void governanceScore_withoutAComplianceRate_hasNoDefaultAndRebalancesTheWeights() {
        stubCatalog(200, 100, 160, 60);
        stubPolicyEngine(5, 0, null);

        GovernanceScoreDTO score = service.getGovernanceScore(false, false);

        assertThat(score.getBreakdown().getPolicyComplianceScore()).isNull();
        assertThat(score.getBreakdown().getComplianceRate()).isNull();
        assertThat(score.getScoredComponents()).containsExactly(
                "DATA_CLASSIFICATION", "METADATA_COMPLETENESS", "LINEAGE_COVERAGE");
        // (50*0.25 + 80*0.25 + 30*0.20) / 0.70
        assertThat(score.getOverallScore()).isEqualTo(55.0);
        assertThat(score.getLastError()).contains("compliance rate unavailable");
        assertThat(score.getRecommendations()).doesNotContain(
                "Maintain current governance posture; re-check after major catalog changes.");
    }

    @Test
    void governanceScore_whenTheCatalogFails_scoresOnlyThePolicyComponent() {
        when(catalog.getTotalAssetCount()).thenThrow(new IllegalStateException("catalog down"));
        stubPolicyEngine(5, 0, 90.0);

        GovernanceScoreDTO score = service.getGovernanceScore(false, false);

        assertThat(score.getBreakdown().getTotalAssets()).isNull();
        assertThat(score.getBreakdown().getDataClassificationScore()).isNull();
        assertThat(score.getBreakdown().getLineageCoverageScore()).isNull();
        assertThat(score.getScoredComponents()).containsExactly("POLICY_COMPLIANCE");
        assertThat(score.getOverallScore()).isEqualTo(90.0);
        assertThat(score.getDataAvailable()).isTrue();
        assertThat(score.getLastError()).contains("catalog: catalog down");
        // With the catalog down, "no blocking issues" would be a claim about data we do not have.
        assertThat(score.getTopIssues()).isEmpty();
    }

    @Test
    void governanceScore_whenEverySourceFails_hasNoScoreAtAll() {
        when(catalog.getTotalAssetCount()).thenThrow(new IllegalStateException("catalog down"));
        when(policyEngine.getActivePoliciesCount()).thenThrow(new IllegalStateException("policy down"));

        GovernanceScoreDTO score = service.getGovernanceScore(true, true);

        assertThat(score.getOverallScore()).isNull();
        assertThat(score.getScoredComponents()).isEmpty();
        assertThat(score.getDataAvailable()).isFalse();
        assertThat(score.getTopIssues()).isEmpty();
        assertThat(score.getRecommendations()).isEmpty();
        assertThat(score.getLastError()).contains("catalog:").contains("policyengine:");
    }

    @Test
    void governanceScore_neverSynthesizesBenchmarkOrHistory() {
        stubCatalog(200, 100, 160, 60);
        stubPolicyEngine(5, 0, 80.0);

        GovernanceScoreDTO score = service.getGovernanceScore(true, true);

        assertThat(score.getBenchmark()).isNull();
        assertThat(score.getBenchmarkAvailable()).isFalse();
        assertThat(score.getHistoricalTrend()).isNull();
        assertThat(score.getHistoryAvailable()).isFalse();
    }

    @Test
    void governanceScore_withNoAssets_leavesCoverageUndefinedInsteadOfZero() {
        stubCatalog(0, 0, 0, 0);
        stubPolicyEngine(1, 0, 100.0);

        GovernanceScoreDTO score = service.getGovernanceScore(false, false);

        assertThat(score.getBreakdown().getDataClassificationScore()).isNull();
        assertThat(score.getBreakdown().getMetadataCompletenessScore()).isNull();
        assertThat(score.getBreakdown().getLineageCoverageScore()).isNull();
        assertThat(score.getScoredComponents()).containsExactly("POLICY_COMPLIANCE");
        assertThat(score.getOverallScore()).isEqualTo(100.0);
    }

    @Test
    void governanceScore_topIssuesComeFromCountsAndCarryNoInventedTeam() {
        stubCatalog(10, 4, 10, 10);
        stubPolicyEngine(2, 3, 70.0);

        GovernanceScoreDTO score = service.getGovernanceScore(false, false);

        assertThat(score.getTopIssues()).extracting(GovernanceScoreDTO.GovernanceIssue::getCategory)
                .containsExactly("MISSING_CLASSIFICATION", "POLICY_VIOLATION");
        assertThat(score.getTopIssues().get(0).getAffectedAssets()).isEqualTo(6);
        assertThat(score.getTopIssues().get(1).getAffectedAssets()).isEqualTo(3);
        assertThat(score.getTopIssues()).allSatisfy(i -> assertThat(i.getAssignedTeam()).isNull());
    }

    // ---- cost savings ------------------------------------------------------------------------

    @Test
    void costSavings_isUnavailableAndCarriesNoMoneyFigures() {
        when(catalog.countAssetsByStatus("ARCHIVED")).thenReturn(4L);
        when(catalog.countAssetsByStatus("DELETED")).thenReturn(2L);
        when(catalog.countAssetsByStatus("PENDING_DELETION")).thenReturn(3L);

        CostSavingsDTO cost = service.getCostSavings(true, "90_DAYS");

        assertThat(cost.getAvailable()).isFalse();
        assertThat(cost.getBasis()).isNotBlank();
        assertThat(cost.getTotalSavings()).isNull();
        assertThat(cost.getMonthlySavingsRate()).isNull();
        assertThat(cost.getRoiPercentage()).isNull();
        assertThat(cost.getProviderSavings()).isNull();
        assertThat(cost.getSavingsOpportunities()).isNull();
        assertThat(cost.getHistoricalTrend()).isNull();
        assertThat(cost.getIsEstimate()).isNull();
        assertThat(cost.getDataAccuracy()).isEqualTo("UNAVAILABLE");

        CostSavingsDTO.CostSavingsBreakdown breakdown = cost.getBreakdown();
        assertThat(breakdown.getArchivalSavings()).isNull();
        assertThat(breakdown.getDeletionSavings()).isNull();
        assertThat(breakdown.getTierOptimizationSavings()).isNull();
        assertThat(breakdown.getComputeSavings()).isNull();
        assertThat(breakdown.getPlatformCosts()).isNull();
        assertThat(breakdown.getNetSavings()).isNull();
        assertThat(breakdown.getArchivalStorageUsed()).isNull();
        assertThat(breakdown.getStorageReclaimed()).isNull();
        assertThat(breakdown.getOptimizedAssetsCount()).isNull();
        assertThat(breakdown.getDecommissionedResources()).isNull();
        // Counts are real catalog counts.
        assertThat(breakdown.getArchivedAssetsCount()).isEqualTo(4);
        assertThat(breakdown.getDeletedAssetsCount()).isEqualTo(2);
        assertThat(cost.getRecommendations()).containsExactly(
                "3 assets are in PENDING_DELETION; review them in the deletion workflow.");
        assertThat(cost.getDataAvailable()).isTrue();
        assertThat(cost.getLastError()).isNull();
    }

    @Test
    void costSavings_whenTheCatalogFails_hasNullCountsAndAnError() {
        when(catalog.countAssetsByStatus(anyString())).thenThrow(new IllegalStateException("catalog down"));

        CostSavingsDTO cost = service.getCostSavings(false, null);

        assertThat(cost.getAvailable()).isFalse();
        assertThat(cost.getDataAvailable()).isFalse();
        assertThat(cost.getLastError()).contains("catalog: catalog down");
        assertThat(cost.getBreakdown().getArchivedAssetsCount()).isNull();
        assertThat(cost.getBreakdown().getDeletedAssetsCount()).isNull();
        assertThat(cost.getRecommendations()).isEmpty();
    }

    // ---- wire contract with the UI -----------------------------------------------------------

    @Test
    void jsonContract_exposesTheAvailabilityFlagsAndExplicitNulls() throws Exception {
        stubCatalog(200, 100, 160, 60);
        stubPolicyEngine(5, 0, 80.0);
        when(catalog.countAssetsByStatus(anyString())).thenReturn(1L);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode governance = mapper.valueToTree(service.getGovernanceScore(true, true));
        assertThat(governance.get("benchmarkAvailable").isBoolean()).isTrue();
        assertThat(governance.get("benchmarkAvailable").asBoolean()).isFalse();
        assertThat(governance.get("historyAvailable").asBoolean()).isFalse();
        assertThat(governance.get("benchmark").isNull()).isTrue();
        assertThat(governance.get("overallScore").asDouble()).isEqualTo(62.5);

        JsonNode cost = mapper.valueToTree(service.getCostSavings(true, "30_DAYS"));
        assertThat(cost.get("available").asBoolean()).isFalse();
        assertThat(cost.get("basis").asText()).isNotBlank();
        assertThat(cost.get("totalSavings").isNull()).isTrue();
        assertThat(cost.get("monthlySavingsRate").isNull()).isTrue();
        assertThat(cost.get("breakdown").get("netSavings").isNull()).isTrue();
    }

    // ---- action items ------------------------------------------------------------------------

    private static Map<String, Object> row(String id, String asset, String severity, String evaluatedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("policyId", "policy-1");
        row.put("targetAssetId", asset);
        row.put("status", "FAIL");
        row.put("severity", severity);
        row.put("evaluatedAt", evaluatedAt);
        return row;
    }

    @Test
    void actionItems_useTheStoredEvaluationTimeAndInventNoSlaOrDueDates() {
        when(policyEngine.getPolicyActionItems(isNull(), isNull(), anyInt())).thenReturn(List.of(
                row("e1", "asset-1", "HIGH", "2026-10-17T09:30:00Z"),
                row("e2", "asset-2", "CRITICAL", "2026-10-16T08:00:00Z")));

        ActionItemsDTO result = service.getActionItems(false, null, null, 50);

        assertThat(result.getTotalActionItems()).isEqualTo(2);
        ActionItemsDTO.ActionItem first = result.getActionItems().get(0);
        assertThat(first.getId()).isEqualTo("e1");
        assertThat(first.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 17, 9, 30));
        assertThat(first.getLastUpdated()).isEqualTo(LocalDateTime.of(2026, 10, 17, 9, 30));
        assertThat(first.getSourceEntityId()).isEqualTo("asset-1");
        assertThat(first.getAssignedTo()).isNull();
        assertThat(first.getAssignedTeam()).isNull();
        assertThat(first.getDueDate()).isNull();
        assertThat(first.getBusinessImpactScore()).isNull();

        ActionItemsDTO.ActionItemsSummary summary = result.getSummary();
        assertThat(summary.getCriticalCount()).isEqualTo(1);
        assertThat(summary.getHighCount()).isEqualTo(1);
        assertThat(summary.getMediumCount()).isZero();
        assertThat(summary.getLowCount()).isZero();
        assertThat(summary.getOverdueCount()).isNull();
        assertThat(summary.getDueTodayCount()).isNull();
        assertThat(summary.getDueThisWeekCount()).isNull();
        assertThat(summary.getAssignedCount()).isNull();
        assertThat(summary.getUnassignedCount()).isNull();

        ActionItemsDTO.SlaMetrics sla = result.getSlaMetrics();
        assertThat(sla.getAverageResolutionTimeHours()).isNull();
        assertThat(sla.getSlaComplianceRate()).isNull();
        assertThat(sla.getBreachedSlaCount()).isNull();
        assertThat(sla.getNearBreachCount()).isNull();

        ActionItemsDTO.BusinessImpactSummary impact = result.getBusinessImpact();
        assertThat(impact.getAverageImpactScore()).isNull();
        assertThat(impact.getHighestImpactCategory()).isNull();
        assertThat(impact.getImpactAreas()).isNull();
        assertThat(impact.getHighImpactItems()).isNull();
    }

    @Test
    void actionItems_withMissingFieldsStayNullInsteadOfDefaults() {
        Map<String, Object> sparse = new HashMap<>();
        sparse.put("policyId", "policy-9");
        when(policyEngine.getPolicyActionItems(isNull(), isNull(), anyInt())).thenReturn(List.of(sparse));

        ActionItemsDTO.ActionItem item = service.getActionItems(false, null, null, null)
                .getActionItems().get(0);

        assertThat(item.getId()).isNull();
        assertThat(item.getSeverity()).isNull();
        assertThat(item.getStatus()).isNull();
        assertThat(item.getSourceEntityId()).isNull();
        assertThat(item.getSourceEntityName()).isNull();
        assertThat(item.getCreatedAt()).isNull();
        assertThat(item.getLastUpdated()).isNull();
        assertThat(item.getTitle()).isEqualTo("Policy evaluation: asset");
    }

    @Test
    void actionItems_ignoreAnUnparseableEvaluationTime() {
        when(policyEngine.getPolicyActionItems(isNull(), isNull(), anyInt())).thenReturn(List.of(
                row("e1", "asset-1", "HIGH", "last tuesday")));

        ActionItemsDTO.ActionItem item = service.getActionItems(false, null, null, 10)
                .getActionItems().get(0);

        assertThat(item.getCreatedAt()).isNull();
        assertThat(item.getLastUpdated()).isNull();
    }

    @Test
    void actionItems_whenThePolicyEngineFails_reportItAndReturnNoItems() {
        when(policyEngine.getPolicyActionItems(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("policy down"));

        ActionItemsDTO result = service.getActionItems(false, null, null, 10);

        assertThat(result.getDataAvailable()).isFalse();
        assertThat(result.getLastError()).contains("policyengine: policy down");
        assertThat(result.getActionItems()).isEmpty();
        assertThat(result.getTotalActionItems()).isZero();
    }
}
