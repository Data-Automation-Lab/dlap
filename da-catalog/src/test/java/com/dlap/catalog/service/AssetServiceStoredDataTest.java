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

package com.dlap.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.dto.AssetComplianceStatusDTO;
import com.dlap.catalog.dto.AssetPolicyMappingDTO;
import com.dlap.catalog.dto.AssetUsageAnalyticsDTO;
import com.dlap.catalog.dto.CatalogFiltersDTO;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.mapper.AssetMapper;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.catalog.usage.AssetUsageDailyStore.UsageDay;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.model.entity.User;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.common.repository.ILabelRepository;
import com.dlap.common.repository.IUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * UI-01: the asset usage, policy-mapping, compliance-status and catalog-filter responses must
 * carry stored data only. A value with no data source is null and the response says so with
 * {@code available=false}; nothing is random, estimated or an example.
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceStoredDataTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Mock
    private IAssetRepository assetRepository;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private ILabelRepository labelRepository;
    @Mock
    private IAssetLabelAssignmentRepository assetLabelAssignmentRepository;
    @Mock
    private IUserRepository userRepository;
    @Mock
    private AssetLineageRepository assetLineageRepository;
    @Mock
    private LineageService lineageService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private EntityManager entityManager;
    @Mock
    private CanonicalUriIndex canonicalUriIndex;
    @Mock
    private AssetUsageDailyStore usageStore;

    private AssetService assetService;

    @BeforeEach
    void setUp() {
        assetService = new AssetService(
                assetRepository,
                assetMapper,
                labelRepository,
                assetLabelAssignmentRepository,
                userRepository,
                assetLineageRepository,
                lineageService,
                kafkaTemplate,
                new ObjectMapper(),
                new com.dlap.catalog.integration.NoopExternalCatalogIntegration(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new CatalogIngestProperties(),
                canonicalUriIndex,
                usageStore);
        ReflectionTestUtils.setField(assetService, "entityManager", entityManager);
    }

    /** Daily read counts as asset_usage_daily holds them: one row per UTC day. */
    private static List<UsageDay> days(UUID assetId, long... reads) {
        List<UsageDay> out = new java.util.ArrayList<>();
        for (int i = 0; i < reads.length; i++) {
            out.add(new UsageDay(assetId, LocalDate.of(2026, 9, 1).plusDays(i), reads[i], 0, null, null));
        }
        return out;
    }

    private Asset assetWithId(UUID id) {
        Asset asset = new Asset("s3://bucket/key", "AWS", "S3_BUCKET");
        asset.setAssetId(id);
        asset.setAssetName("orders");
        when(assetRepository.findById(id)).thenReturn(Optional.of(asset));
        return asset;
    }

    // ---- usage analytics --------------------------------------------------------------------

    @Test
    void usageAnalytics_returnsOnlyTheRecordedReadsAndLastAccess() {
        UUID id = UUID.randomUUID();
        Asset asset = assetWithId(id);
        asset.setLastAccessedAt(Instant.parse("2026-10-01T10:15:30Z"));
        when(usageStore.findDays(eq(id), any(), any())).thenReturn(days(id, 3, 0, 4));

        AssetUsageAnalyticsDTO usage = assetService.getAssetUsageAnalytics(id);

        assertThat(usage.getAvailable()).isTrue();
        assertThat(usage.getUsageMetrics().getTotalAccesses()).isEqualTo(7L);
        assertThat(usage.getUsageMetrics().getLastAccessed()).isEqualTo(Instant.parse("2026-10-01T10:15:30Z"));
        // No data source for any of these: they must be null, not estimated.
        assertThat(usage.getUsageMetrics().getUniqueUsers()).isNull();
        assertThat(usage.getUsageMetrics().getAvgAccessesPerDay()).isNull();
        assertThat(usage.getUsageMetrics().getPeakUsageHour()).isNull();
        assertThat(usage.getUsageMetrics().getUsageTrend()).isNull();
        assertThat(usage.getUserAnalytics().getTopUsers()).isNull();
        assertThat(usage.getUserAnalytics().getDepartmentBreakdown()).isNull();
        assertThat(usage.getAccessPatterns().getHourlyDistribution()).isNull();
        assertThat(usage.getAccessPatterns().getWeeklyDistribution()).isNull();
        assertThat(usage.getAccessPatterns().getMonthlyTrend()).isNull();
        assertThat(usage.getPerformanceMetrics().getAvgQueryTime()).isNull();
        assertThat(usage.getPerformanceMetrics().getSlowestQuery()).isNull();
        assertThat(usage.getPerformanceMetrics().getFastestQuery()).isNull();
        assertThat(usage.getPerformanceMetrics().getTimeoutRate()).isNull();
        assertThat(usage.getPerformanceMetrics().getErrorRate()).isNull();
        assertThat(usage.getPopularQueries()).isNull();
    }

    @Test
    void usageAnalytics_isDeterministicAcrossCalls() {
        UUID id = UUID.randomUUID();
        Asset asset = assetWithId(id);
        asset.setLastAccessedAt(Instant.parse("2026-09-30T00:00:00Z"));
        when(usageStore.findDays(eq(id), any(), any())).thenReturn(days(id, 3));

        AssetUsageAnalyticsDTO first = assetService.getAssetUsageAnalytics(id);
        AssetUsageAnalyticsDTO second = assetService.getAssetUsageAnalytics(id);

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Test
    void usageAnalytics_withoutStoredSignals_isUnavailableAndEverythingNull() {
        UUID id = UUID.randomUUID();
        assetWithId(id);

        AssetUsageAnalyticsDTO usage = assetService.getAssetUsageAnalytics(id);

        assertThat(usage.getAvailable()).isFalse();
        assertThat(usage.getBasis()).isNotBlank();
        assertThat(usage.getUsageMetrics().getTotalAccesses()).isNull();
        assertThat(usage.getUsageMetrics().getLastAccessed()).isNull();
        assertThat(usage.getUsageMetrics().getUniqueUsers()).isNull();
    }

    @Test
    void usageAnalytics_doesNotReadTheLegacyUsageBag() {
        UUID id = UUID.randomUUID();
        Asset asset = assetWithId(id);
        // Before CAT-02a these held DLAP processing time and a redelivery-inflated counter; they
        // are not evidence and must not leak into the response (ADR-0046, ADR-0055 item 8).
        asset.setDlapUsageMetrics(Map.of("accessCount", 99, "lastAccessedAt", "2026-10-01T10:15:30Z"));

        AssetUsageAnalyticsDTO usage = assetService.getAssetUsageAnalytics(id);

        assertThat(usage.getAvailable()).isFalse();
        assertThat(usage.getUsageMetrics().getTotalAccesses()).isNull();
        assertThat(usage.getUsageMetrics().getLastAccessed()).isNull();
    }

    @Test
    void usageAnalytics_countsOnlyTheCurrentIncarnation() {
        UUID id = UUID.randomUUID();
        Asset asset = assetWithId(id);
        asset.setRecreatedAt(Instant.parse("2026-09-15T08:00:00Z"));
        when(usageStore.findDays(eq(id), eq(LocalDate.of(2026, 9, 15)), any())).thenReturn(days(id, 2, 5));

        AssetUsageAnalyticsDTO usage = assetService.getAssetUsageAnalytics(id);

        assertThat(usage.getUsageMetrics().getTotalAccesses()).isEqualTo(7L);
    }

    @Test
    void usageAnalytics_noRecordedReadIsUnknownNotZero() {
        UUID id = UUID.randomUUID();
        assetWithId(id);
        // Days that only counted writes: DLAP recorded no read, which does not prove none happened.
        when(usageStore.findDays(eq(id), any(), any())).thenReturn(days(id, 0, 0));

        AssetUsageAnalyticsDTO usage = assetService.getAssetUsageAnalytics(id);

        assertThat(usage.getAvailable()).isFalse();
        assertThat(usage.getUsageMetrics().getTotalAccesses()).isNull();
    }

    @Test
    void usageAnalytics_recommendsBusinessMetadataOnlyWhenItIsMissing() {
        UUID missing = UUID.randomUUID();
        assetWithId(missing);
        UUID present = UUID.randomUUID();
        Asset withMetadata = assetWithId(present);
        withMetadata.setBusinessMetadataJson(Map.of("businessName", "Orders"));

        List<AssetUsageAnalyticsDTO.RecommendationDTO> forMissing =
                assetService.getAssetUsageAnalytics(missing).getRecommendations();
        List<AssetUsageAnalyticsDTO.RecommendationDTO> forPresent =
                assetService.getAssetUsageAnalytics(present).getRecommendations();

        assertThat(forMissing).hasSize(1);
        assertThat(forMissing.get(0).getType()).isEqualTo("COMPLIANCE");
        assertThat(forPresent).isEmpty();
    }

    @Test
    void usageAnalytics_unknownAsset_isNotFound() {
        UUID id = UUID.randomUUID();
        when(assetRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.getAssetUsageAnalytics(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- policy mapping and compliance status ------------------------------------------------

    @Test
    void policyMapping_isReportedUnavailableWithoutExamplePolicies() {
        UUID id = UUID.randomUUID();
        assetWithId(id);
        when(assetRepository.existsById(id)).thenReturn(true);

        AssetPolicyMappingDTO mapping = assetService.getAssetPolicyMapping(id);

        assertThat(mapping.getAssetId()).isEqualTo(id);
        assertThat(mapping.getAvailable()).isFalse();
        assertThat(mapping.getBasis()).isNotBlank();
        assertThat(mapping.getApplicablePolicies()).isNull();
        assertThat(mapping.getInheritedPolicies()).isNull();
        assertThat(mapping.getComplianceSummary()).isNull();
        assertThat(mapping.getRiskAssessment()).isNull();
        assertThat(mapping.getRecommendations()).isNull();
        assertThat(mapping.getLastEvaluated()).isNull();
        assertThat(mapping.getEvaluationStatus()).isNull();
    }

    @Test
    void complianceStatus_isReportedUnknownWithoutAScoreOrFindings() {
        UUID id = UUID.randomUUID();
        assetWithId(id);
        when(assetRepository.existsById(id)).thenReturn(true);

        AssetComplianceStatusDTO status = assetService.getAssetComplianceStatus(id);

        assertThat(status.getAvailable()).isFalse();
        assertThat(status.getOverallComplianceStatus()).isEqualTo("UNKNOWN");
        assertThat(status.getBasis()).contains("da-autocompliance");
        assertThat(status.getOverallComplianceScore()).isNull();
        assertThat(status.getFrameworkCompliance()).isNull();
        assertThat(status.getViolations()).isNull();
        assertThat(status.getControls()).isNull();
        assertThat(status.getRiskAssessment()).isNull();
        assertThat(status.getLastEvaluated()).isNull();
        assertThat(status.getEvaluatedBy()).isNull();
        assertThat(status.getNextEvaluation()).isNull();
    }

    // ---- wire shape (contract with the UI) ---------------------------------------------------

    @Test
    void wireShape_unavailableSectionsAreOmittedAndFlagged() throws Exception {
        UUID id = UUID.randomUUID();
        Asset asset = assetWithId(id);
        asset.setLastAccessedAt(Instant.parse("2026-10-01T10:15:30Z"));
        when(usageStore.findDays(eq(id), any(), any())).thenReturn(days(id, 4));
        when(assetRepository.existsById(id)).thenReturn(true);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        JsonNode usage = json.valueToTree(assetService.getAssetUsageAnalytics(id));
        assertThat(usage.get("available").asBoolean()).isTrue();
        assertThat(usage.get("usageMetrics").get("totalAccesses").asLong()).isEqualTo(4L);
        // Fields inside a section are explicit JSON null when there is no data source.
        assertThat(usage.get("usageMetrics").get("uniqueUsers").isNull()).isTrue();
        assertThat(usage.get("usageMetrics").get("peakUsageHour").isNull()).isTrue();
        // The section objects stay present, so a consumer reading them never meets undefined.
        assertThat(usage.get("userAnalytics").isObject()).isTrue();
        assertThat(usage.get("userAnalytics").get("topUsers").isNull()).isTrue();
        assertThat(usage.get("accessPatterns").get("hourlyDistribution").isNull()).isTrue();
        assertThat(usage.get("performanceMetrics").get("timeoutRate").isNull()).isTrue();
        // A null top-level list is omitted.
        assertThat(usage.has("popularQueries")).isFalse();

        JsonNode policies = json.valueToTree(assetService.getAssetPolicyMapping(id));
        assertThat(policies.get("available").asBoolean()).isFalse();
        assertThat(policies.get("basis").asText()).isNotBlank();
        assertThat(policies.has("applicablePolicies")).isFalse();

        JsonNode compliance = json.valueToTree(assetService.getAssetComplianceStatus(id));
        assertThat(compliance.get("available").asBoolean()).isFalse();
        assertThat(compliance.get("overallComplianceStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(compliance.has("overallComplianceScore")).isFalse();
        assertThat(compliance.has("violations")).isFalse();
    }

    // ---- catalog filters ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubGroupedCounts() {
        TypedQuery<Object[]> assetTypes = org.mockito.Mockito.mock(TypedQuery.class);
        when(assetTypes.getResultList()).thenReturn(List.of(
                new Object[] {"BIGQUERY_TABLE", 3L}, new Object[] {"S3_BUCKET", 2L}));
        TypedQuery<Object[]> providers = org.mockito.Mockito.mock(TypedQuery.class);
        when(providers.getResultList()).thenReturn(List.of(
                new Object[] {"AWS", 2L}, new Object[] {"GCP", 3L}, new Object[] {"SNOWFLAKE", 1L}));
        TypedQuery<Object[]> labels = org.mockito.Mockito.mock(TypedQuery.class);
        when(labels.getResultList()).thenReturn(List.<Object[]>of(new Object[] {"PII", 1L}));

        when(entityManager.createQuery(anyString(), eq(Object[].class))).thenAnswer(invocation -> {
            String jpql = invocation.getArgument(0, String.class);
            if (jpql.contains("a.assetType")) {
                return assetTypes;
            }
            if (jpql.contains("a.cloudProvider")) {
                return providers;
            }
            return labels;
        });
    }

    private static CatalogFiltersDTO.FilterCategoryDTO category(CatalogFiltersDTO filters, String name) {
        return filters.getFilterCategories().stream()
                .filter(c -> name.equals(c.getCategoryName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void catalogFilters_countsComeFromGroupedQueries() {
        stubGroupedCounts();
        when(assetRepository.count()).thenReturn(5L);

        CatalogFiltersDTO filters = assetService.getCatalogFilters();

        assertThat(filters.getTotalAssets()).isEqualTo(5);
        assertThat(filters.getLastUpdated()).isEqualTo(NOW);

        CatalogFiltersDTO.FilterCategoryDTO types = category(filters, "assetType");
        assertThat(types.getOptions()).extracting(CatalogFiltersDTO.FilterOptionDTO::getValue)
                .containsExactly("BIGQUERY_TABLE", "S3_BUCKET");
        assertThat(types.getOptions()).extracting(CatalogFiltersDTO.FilterOptionDTO::getCount)
                .containsExactly(3, 2);

        CatalogFiltersDTO.FilterCategoryDTO providers = category(filters, "cloudProvider");
        assertThat(providers.getOptions()).extracting(CatalogFiltersDTO.FilterOptionDTO::getDisplayValue)
                .containsExactly("Amazon Web Services", "Google Cloud Platform", "SNOWFLAKE");
        assertThat(providers.getTotalOptions()).isEqualTo(3);

        CatalogFiltersDTO.FilterCategoryDTO labels = category(filters, "labels");
        assertThat(labels.getOptions()).hasSize(1);
        assertThat(labels.getOptions().get(0).getCount()).isEqualTo(1);
    }

    @Test
    void catalogFilters_doNotClaimCapabilitiesTheCatalogLacks() {
        stubGroupedCounts();
        when(assetRepository.count()).thenReturn(0L);

        CatalogFiltersDTO filters = assetService.getCatalogFilters();

        assertThat(filters.getQuickFilters()).isNull();
        assertThat(filters.getCatalogVersion()).isNull();
        assertThat(filters.getSearchOptions().getSearchableFields()).containsExactly("assetName");
        assertThat(filters.getSearchOptions().getSupportsFullTextSearch()).isFalse();
        assertThat(filters.getSearchOptions().getSupportsFuzzySearch()).isFalse();
        assertThat(filters.getSearchOptions().getSupportsWildcards()).isFalse();
        assertThat(filters.getSearchOptions().getDefaultPageSize()).isEqualTo(20);
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogFilters_emptyCatalogYieldsEmptyFacets() {
        TypedQuery<Object[]> empty = org.mockito.Mockito.mock(TypedQuery.class);
        when(empty.getResultList()).thenReturn(List.of());
        when(entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(empty);
        when(assetRepository.count()).thenReturn(0L);

        CatalogFiltersDTO filters = assetService.getCatalogFilters();

        assertThat(filters.getTotalAssets()).isZero();
        assertThat(filters.getFilterCategories()).hasSize(3);
        assertThat(filters.getFilterCategories()).allSatisfy(c -> assertThat(c.getOptions()).isEmpty());
    }

    // ---- business metadata audit and completeness ---------------------------------------------

    @Test
    void auditInfo_usesTheStoredUserNameOrNull() {
        UUID creator = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Asset asset = assetWithId(assetId);
        asset.setCreatedByUserId(creator);
        User user = User.builder().userId(creator).email("a@example.org").fullName("Ada Lovelace").build();
        when(userRepository.findById(creator)).thenReturn(Optional.of(user));

        var withName = assetService.getBusinessMetadata(assetId).getAuditInfo();
        assertThat(withName.getCreatedByName()).isEqualTo("Ada Lovelace");

        when(userRepository.findById(creator)).thenReturn(Optional.empty());
        when(userRepository.findByKeycloakSubjectId(creator.toString())).thenReturn(Optional.empty());

        var unknown = assetService.getBusinessMetadata(assetId).getAuditInfo();
        assertThat(unknown.getCreatedByName()).isNull();
        assertThat(unknown.getCreatedBy()).isEqualTo(creator);
    }

    @Test
    void auditInfo_withoutACreator_hasNoName() {
        UUID assetId = UUID.randomUUID();
        assetWithId(assetId);

        var audit = assetService.getBusinessMetadata(assetId).getAuditInfo();

        assertThat(audit.getCreatedByName()).isNull();
        assertThat(audit.getLastUpdatedByName()).isNull();
    }

    @Test
    void validationStatus_scoresOverTheFieldsActuallyChecked() {
        UUID assetId = UUID.randomUUID();
        Asset asset = assetWithId(assetId);
        asset.setBusinessMetadataJson(Map.of(
                "businessName", "Orders",
                "businessDescription", "All customer orders",
                "businessCriticality", "HIGH",
                "businessDomain", "Sales",
                "businessOwner", Map.of("name", "Owner", "email", "owner@example.org"),
                "dataSteward", Map.of("name", "Steward", "email", "steward@example.org"),
                "complianceClassification", Map.of("dataClassification", "INTERNAL")));

        var validation = assetService.getBusinessMetadata(assetId).getValidationStatus();

        assertThat(validation.getCompletenessScore()).isEqualTo(1.0);
        assertThat(validation.getOverallStatus()).isEqualTo("COMPLETE");
        assertThat(validation.getIsComplete()).isTrue();
        assertThat(validation.getMissingFields()).isEmpty();
    }

    @Test
    void validationStatus_countsMissingFields() {
        UUID assetId = UUID.randomUUID();
        Asset asset = assetWithId(assetId);
        asset.setBusinessMetadataJson(Map.of("businessName", "Orders", "businessDescription", "All orders"));

        var validation = assetService.getBusinessMetadata(assetId).getValidationStatus();

        assertThat(validation.getCompletenessScore()).isEqualTo(2.0 / 7.0);
        assertThat(validation.getOverallStatus()).isEqualTo("INCOMPLETE");
        assertThat(validation.getMissingFields())
                .containsExactlyInAnyOrder("businessCriticality", "businessDomain", "businessOwner",
                        "dataSteward", "complianceClassification");
    }

    @Test
    void usageAnalytics_neverResolvesUsersOrWritesAnything() {
        // Guards the removal of the made-up top-users list (random ids, example e-mail addresses).
        UUID id = UUID.randomUUID();
        Asset asset = assetWithId(id);
        asset.setDlapUsageMetrics(Map.of("accessCount", 100, "lastAccessedAt", "2026-10-01T00:00:00Z"));

        AssetUsageAnalyticsDTO usage = assetService.getAssetUsageAnalytics(id);

        assertThat(usage.getUserAnalytics().getTopUsers()).isNull();
        org.mockito.Mockito.verifyNoInteractions(userRepository);
        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never()).save(any());
    }
}
