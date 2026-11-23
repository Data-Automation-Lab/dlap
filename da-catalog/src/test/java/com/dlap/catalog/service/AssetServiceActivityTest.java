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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.integration.NoopExternalCatalogIntegration;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.mapper.AssetMapper;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.catalog.service.IAssetService.ActivityQuery;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.common.repository.ILabelRepository;
import com.dlap.common.repository.IUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * CAT-02b service tests with mocked collaborators: the activity report's validation, paging,
 * sorting, report instant and labels, and {@code resolveAssetIdByUri} normalizing its input
 * through {@code AssetIdNormalizer}. How the filters and the order translate into SQL, and how
 * the real index and schema behave, is proved against PostgreSQL in
 * {@code AssetActivityContainerTest}.
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceActivityTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private IAssetRepository assetRepository;
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
                new AssetMapper(assetLabelAssignmentRepository),
                labelRepository,
                assetLabelAssignmentRepository,
                userRepository,
                assetLineageRepository,
                lineageService,
                kafkaTemplate,
                new ObjectMapper(),
                new NoopExternalCatalogIntegration(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new CatalogIngestProperties(),
                canonicalUriIndex,
                usageStore);
        ReflectionTestUtils.setField(assetService, "entityManager", entityManager);
    }

    private static Asset asset(String uri, Instant lastAccessedAt, Instant cloudCreatedAt) {
        Asset a = new Asset();
        a.setAssetId(UUID.randomUUID());
        a.setAssetUri(uri);
        a.setCloudProvider("GCP");
        a.setServiceType("GCS_BUCKET");
        a.setStatus("ACTIVE");
        a.setLastAccessedAt(lastAccessedAt);
        a.setCloudCreatedAt(cloudCreatedAt);
        return a;
    }

    private void repositoryReturns(List<Asset> assets) {
        when(assetRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(assets, inv.getArgument(1), assets.size()));
    }

    @SuppressWarnings("unchecked")
    private void labelsQueryReturns(List<Object[]> rows) {
        TypedQuery<Object[]> query = org.mockito.Mockito.mock(TypedQuery.class);
        when(entityManager.createQuery(AssetService.ACTIVITY_LABELS_JPQL, Object[].class)).thenReturn(query);
        when(query.setParameter(eq("ids"), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    private Pageable pageSentToTheRepository() {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepository).findAll(any(Specification.class), page.capture());
        return page.getValue();
    }

    // ---- report instant ---------------------------------------------------------------------

    @Test
    void asOfDefaultsToTheInjectedClockNotTheWallClock() {
        repositoryReturns(List.of(asset("gs://a", Instant.parse("2026-10-09T12:00:00Z"), null)));
        labelsQueryReturns(List.of());

        Page<AssetActivityRowDTO> page = assetService.getAssetActivity(ActivityQuery.unfiltered(),
                PageRequest.of(0, 10));

        AssetActivityRowDTO row = page.getContent().get(0);
        assertThat(row.asOf()).isEqualTo(NOW);
        assertThat(row.idleDays()).isEqualTo(10L);
    }

    @Test
    void theCallersAsOfDrivesTheDerivedDays() {
        repositoryReturns(List.of(asset("gs://a", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"))));
        labelsQueryReturns(List.of());
        ActivityQuery query = new ActivityQuery(null, null, null, null, null, null, null, null, null,
                Instant.parse("2026-10-11T00:00:00Z"));

        AssetActivityRowDTO row = assetService.getAssetActivity(query, PageRequest.of(0, 10)).getContent().get(0);

        assertThat(row.asOf()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(row.idleDays()).isEqualTo(10L);
        assertThat(row.ageDays()).isEqualTo(71L);
    }

    @Test
    void aNullQueryMeansNoFilter() {
        repositoryReturns(List.of());

        Page<AssetActivityRowDTO> page = assetService.getAssetActivity(null, null);

        assertThat(page.getContent()).isEmpty();
    }

    // ---- unknown stays unknown --------------------------------------------------------------

    @Test
    void anUnknownLastReadAndCreationGiveNoDerivedDaysAndNoCoverage() {
        repositoryReturns(List.of(asset("gs://never-seen", null, null)));
        labelsQueryReturns(List.of());

        AssetActivityRowDTO row = assetService.getAssetActivity(ActivityQuery.unfiltered(), PageRequest.of(0, 10))
                .getContent().get(0);

        assertThat(row.lastAccessedAt()).isNull();
        assertThat(row.idleDays()).isNull();
        assertThat(row.cloudCreatedAt()).isNull();
        assertThat(row.ageDays()).isNull();
        assertThat(row.sizeBytes()).isNull();
        assertThat(row.readCoverage()).isNull();
    }

    // ---- labels -----------------------------------------------------------------------------

    @Test
    void labelsOfThePageComeFromOneQueryAndAreSortedAndDistinct() {
        Asset a = asset("gs://a", null, null);
        Asset b = asset("gs://b", null, null);
        repositoryReturns(List.of(a, b));
        labelsQueryReturns(List.of(
                new Object[] {a.getAssetId(), "PII"},
                new Object[] {a.getAssetId(), "Finance"},
                new Object[] {a.getAssetId(), "PII"}));

        List<AssetActivityRowDTO> rows = assetService.getAssetActivity(ActivityQuery.unfiltered(),
                PageRequest.of(0, 10)).getContent();

        assertThat(rows.get(0).labels()).containsExactly("Finance", "PII");
        assertThat(rows.get(1).labels()).isEmpty();
        verify(entityManager).createQuery(anyString(), eq(Object[].class));
    }

    @Test
    void anEmptyPageAsksForNoLabels() {
        repositoryReturns(List.of());

        assetService.getAssetActivity(ActivityQuery.unfiltered(), PageRequest.of(0, 10));

        verifyNoInteractions(entityManager);
    }

    // ---- paging and sorting -----------------------------------------------------------------

    @Test
    void thePageSizeIsCappedAtTheConfiguredMaximum() {
        repositoryReturns(List.of());

        assetService.getAssetActivity(ActivityQuery.unfiltered(), PageRequest.of(3, 5000));

        Pageable sent = pageSentToTheRepository();
        assertThat(sent.getPageNumber()).isEqualTo(3);
        assertThat(sent.getPageSize()).isEqualTo(200);
    }

    @Test
    void anUnpagedRequestGetsTheDefaultPage() {
        repositoryReturns(List.of());

        assetService.getAssetActivity(ActivityQuery.unfiltered(), Pageable.unpaged());

        Pageable sent = pageSentToTheRepository();
        assertThat(sent.getPageNumber()).isZero();
        assertThat(sent.getPageSize()).isEqualTo(50);
    }

    @Test
    void theOrderIsAppliedInsideTheQueryNotByThePageable() {
        repositoryReturns(List.of());

        assetService.getAssetActivity(ActivityQuery.unfiltered(),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("sizeBytes"))));

        // Null placement and the assetUri tie-break are part of the specification, so the
        // page window itself carries no sort that would re-order unknowns first.
        assertThat(pageSentToTheRepository().getSort().isSorted()).isFalse();
    }

    @Test
    void everySortablePropertyIsAccepted() {
        for (String property : AssetService.ACTIVITY_SORTABLE) {
            org.mockito.Mockito.clearInvocations(assetRepository);
            repositoryReturns(List.of());
            assetService.getAssetActivity(ActivityQuery.unfiltered(),
                    PageRequest.of(0, 10, Sort.by(Sort.Order.asc(property))));
        }
        assertThat(AssetService.ACTIVITY_SORTABLE).contains("cloudCreatedAt", "sizeBytes", "lastAccessedAt",
                "lastModifiedAt", "retentionStatus", "retentionExpiresAt", "assetUri");
    }

    @Test
    void sortingByAnUnsupportedPropertyIsRejectedBeforeTheQuery() {
        assertThatThrownBy(() -> assetService.getAssetActivity(ActivityQuery.unfiltered(),
                PageRequest.of(0, 10, Sort.by("dlapUsageMetrics"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot sort the activity report by 'dlapUsageMetrics'");
        verifyNoInteractions(assetRepository);
    }

    // ---- filter validation ------------------------------------------------------------------

    private void assertRejected(ActivityQuery query, String message) {
        assertThatThrownBy(() -> assetService.getAssetActivity(query, PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
        verify(assetRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    private static ActivityQuery with(List<String> providers, List<String> kinds, List<String> statuses,
                                      List<String> retention, List<String> coverage) {
        return new ActivityQuery(providers, kinds, statuses, null, null, null, retention, null, coverage, null);
    }

    @Test
    void anUnknownProviderIsRejected() {
        assertRejected(with(List.of("MARS"), null, null, null, null), "Unknown provider 'MARS'");
    }

    @Test
    void aMalformedKindIsRejected() {
        assertRejected(with(null, List.of("gcs bucket; drop table"), null, null, null), "Malformed kind");
    }

    @Test
    void anUnknownStatusIsRejected() {
        assertRejected(with(null, null, List.of("GONE"), null, null), "Unknown status 'GONE'");
    }

    @Test
    void anUnknownRetentionStatusIsRejected() {
        assertRejected(with(null, null, null, List.of("EXPIRED"), null), "Unknown retentionStatus 'EXPIRED'");
    }

    @Test
    void anUnknownReadCoverageIsRejected() {
        assertRejected(with(null, null, null, null, List.of("MOST")), "Unknown readCoverage 'MOST'");
    }

    @Test
    void tooManyValuesForOneFilterAreRejected() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add("KIND_" + i);
        }
        assertRejected(with(null, many, null, null, null), "At most 20 values are accepted for kind");
    }

    @Test
    void aCallersValueInAnErrorIsBoundedAndHasNoControlCharacters() {
        String hostile = "bad\nforged log line " + "x".repeat(200);
        assertThatThrownBy(() -> assetService.getAssetActivity(with(List.of(hostile), null, null, null, null),
                PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("\n");
                    assertThat(e.getMessage().length()).isLessThan(120);
                });
    }

    @Test
    void validValuesAreAcceptedInAnyCaseAndBlanksAndRepeatsAreDropped() {
        repositoryReturns(List.of());
        ActivityQuery query = with(List.of("gcp", " ", "GCP", "aws"), List.of(" gcs_bucket "), List.of("active"),
                List.of("due", "ON_HOLD"), List.of("full", "unknown"));

        Page<AssetActivityRowDTO> page = assetService.getAssetActivity(query, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        verify(assetRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aLegalHoldAndAKnownLastReadFilterNeedNoVocabulary() {
        repositoryReturns(List.of());
        ActivityQuery query = new ActivityQuery(null, null, null, Instant.parse("2026-07-19T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), Boolean.TRUE, null, Boolean.TRUE, null, null);

        assetService.getAssetActivity(query, PageRequest.of(0, 10));

        verify(assetRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ---- resolve: normalization through AssetIdNormalizer -----------------------------------

    @Test
    void resolveFindsABucketByItsGcsAuditResourceName() {
        UUID id = UUID.randomUUID();
        Asset a = asset("gs://dlap-demo", null, null);
        a.setAssetId(id);
        when(assetRepository.findByAssetUri("gs://dlap-demo")).thenReturn(Optional.of(a));

        assertThat(assetService.resolveAssetIdByUri("projects/_/buckets/dlap-demo")).contains(id);
    }

    @Test
    void resolveFindsABucketByItsAwsArn() {
        UUID id = UUID.randomUUID();
        Asset a = asset("s3://dlap-data", null, null);
        a.setAssetId(id);
        when(assetRepository.findByAssetUri("s3://dlap-data")).thenReturn(Optional.of(a));

        assertThat(assetService.resolveAssetIdByUri("arn:aws:s3:::dlap-data")).contains(id);
    }

    @Test
    void resolveTrimsAndLowerCasesTheSchemeAndHost() {
        UUID id = UUID.randomUUID();
        Asset a = asset("gs://dlap-demo", null, null);
        a.setAssetId(id);
        when(assetRepository.findByAssetUri("gs://dlap-demo")).thenReturn(Optional.of(a));

        assertThat(assetService.resolveAssetIdByUri("  GS://DLAP-DEMO  ")).contains(id);
    }

    @Test
    void resolveFindsARowStoredInAnOlderFormThroughTheCanonicalIndex() {
        UUID id = UUID.randomUUID();
        when(assetRepository.findByAssetUri("gs://dlap-demo")).thenReturn(Optional.empty());
        when(canonicalUriIndex.candidates("gs://dlap-demo")).thenReturn(List.of(id));

        assertThat(assetService.resolveAssetIdByUri("gs://dlap-demo")).contains(id);
    }

    @Test
    void resolveTakesTheOldestRowWhenSeveralNormalizeToTheSameUri() {
        UUID oldest = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        when(assetRepository.findByAssetUri("gs://dlap-demo")).thenReturn(Optional.empty());
        when(canonicalUriIndex.candidates("gs://dlap-demo")).thenReturn(List.of(oldest, newer));

        assertThat(assetService.resolveAssetIdByUri("gs://dlap-demo")).contains(oldest);
    }

    @Test
    void resolveBuildsOrCatchesUpTheIndexOnAMissAndLooksAgain() {
        UUID id = UUID.randomUUID();
        when(assetRepository.findByAssetUri("gs://late")).thenReturn(Optional.empty());
        when(canonicalUriIndex.candidates("gs://late")).thenReturn(List.of()).thenReturn(List.of(id));
        when(canonicalUriIndex.ensureBuilt()).thenReturn(false);
        when(canonicalUriIndex.catchUpIfDue()).thenReturn(true);

        assertThat(assetService.resolveAssetIdByUri("gs://late")).contains(id);
    }

    @Test
    void resolveReturnsEmptyWhenNothingMatchesAnywhere() {
        when(assetRepository.findByAssetUri("gs://nobody")).thenReturn(Optional.empty());
        when(canonicalUriIndex.candidates("gs://nobody")).thenReturn(List.of());

        assertThat(assetService.resolveAssetIdByUri("gs://nobody")).isEmpty();
    }

    @Test
    void resolveFallsBackToTheCallersSpellingForARowWrittenThatWay() {
        // The index can lag a row written as the caller spelled it; the alias normalizes to
        // gs://dlap-demo, which has no row, so the text as written is tried last.
        UUID id = UUID.randomUUID();
        Asset a = asset("projects/_/buckets/dlap-demo", null, null);
        a.setAssetId(id);
        when(assetRepository.findByAssetUri("gs://dlap-demo")).thenReturn(Optional.empty());
        when(canonicalUriIndex.candidates("gs://dlap-demo")).thenReturn(List.of());
        when(assetRepository.findByAssetUri("projects/_/buckets/dlap-demo")).thenReturn(Optional.of(a));

        assertThat(assetService.resolveAssetIdByUri("projects/_/buckets/dlap-demo")).contains(id);
    }

    @Test
    void resolveLooksUpTextTheNormalizerDoesNotKnowAsWritten() {
        UUID id = UUID.randomUUID();
        Asset a = asset("legacy-asset-name", null, null);
        a.setAssetId(id);
        when(assetRepository.findByAssetUri("legacy-asset-name")).thenReturn(Optional.of(a));

        assertThat(assetService.resolveAssetIdByUri("legacy-asset-name")).contains(id);
        verifyNoInteractions(canonicalUriIndex);
    }

    @Test
    void resolveNeverQueriesForBlankOrOverlongInput() {
        assertThat(assetService.resolveAssetIdByUri(null)).isEmpty();
        assertThat(assetService.resolveAssetIdByUri("   ")).isEmpty();
        assertThat(assetService.resolveAssetIdByUri("x".repeat(3000))).isEmpty();

        verifyNoInteractions(assetRepository);
        verifyNoInteractions(canonicalUriIndex);
    }

    // ---- keyset paging (CAT-04) -------------------------------------------------------------

    @Test
    void theKeysetPathValidatesTheSameFiltersBeforeItTouchesTheRepository() {
        ActivityQuery badProvider = new ActivityQuery(List.of("NOWHERE"), null, null, null, null, null, null, null,
                null, null);
        ActivityQuery badStatus = new ActivityQuery(null, null, List.of("GONE"), null, null, null, null, null,
                null, null);

        assertThatThrownBy(() -> assetService.getAssetActivityKeyset(badProvider, null, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown provider");
        assertThatThrownBy(() -> assetService.getAssetActivityKeyset(badStatus, "gs://a", 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown status");

        verifyNoInteractions(assetRepository);
    }

    @Test
    void aCursorLongerThanAnAssetUriIsRefused() {
        assertThatThrownBy(() -> assetService.getAssetActivityKeyset(ActivityQuery.unfiltered(),
                "x".repeat(2049), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after");

        verifyNoInteractions(assetRepository);
    }

    @Test
    void theOffsetPathStillAsksTheRepositoryForOnePageByPageable() {
        repositoryReturns(List.of(asset("gs://a", null, null), asset("gs://b", null, null)));
        labelsQueryReturns(List.of());

        Page<AssetActivityRowDTO> page = assetService.getAssetActivity(ActivityQuery.unfiltered(),
                PageRequest.of(2, 2));

        assertThat(page.getContent()).extracting(AssetActivityRowDTO::assetUri).containsExactly("gs://a", "gs://b");
        Pageable sent = pageSentToTheRepository();
        assertThat(sent.getPageNumber()).isEqualTo(2);
        assertThat(sent.getPageSize()).isEqualTo(2);
        verify(assetRepository, never()).count(any(Specification.class));
    }
}
