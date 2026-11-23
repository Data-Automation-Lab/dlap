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
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.catalog.dto.AssetActivityPageDTO;
import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.integration.NoopExternalCatalogIntegration;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.kafka.CatalogIngestMetrics;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.mapper.AssetMapper;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.catalog.service.IAssetService.ActivityQuery;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.common.repository.ILabelRepository;
import com.dlap.common.repository.IUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;

/**
 * CAT-02b acceptance against the real schema: the activity report's filters, order, paging and
 * count, the read-coverage match over the {@code read_coverage} column (COV-01), and
 * {@code resolveAssetIdByUri} with the real canonical-URI index, on {@code postgres:15.1} built
 * from the platform init scripts and the catalog's Flyway migrations, connected as
 * {@code da_catalog_user}. The service is the production {@link AssetService} with real
 * repositories; only Kafka and the lineage service are mocks, since the report touches neither.
 *
 * <p>Each test clears the assets first, so tests are independent of order and of the seed data.
 * No test-managed transaction: reads commit as they do in production. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = AssetActivityContainerTest.ActivitySlice.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AssetActivityContainerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/dlap_common");
        registry.add("spring.datasource.username", () -> "da_catalog_user");
        registry.add("spring.datasource.password", () -> "da_catalog_pass");
        registry.add("dlap.catalog.ingest.match.index-on-startup", () -> "false");
        registry.add("dlap.catalog.ingest.match.catch-up-interval", () -> "PT0S");
    }

    /** JPA, JDBC, Flyway and the beans the real service needs; no Kafka, web or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    @EnableJpaRepositories(basePackages = {"com.dlap.catalog.repository", "com.dlap.common.repository"})
    @EnableConfigurationProperties(CatalogIngestProperties.class)
    @Import({CatalogEventDecoder.class, CatalogIngestMetrics.class, CanonicalUriIndex.class,
            AssetUsageDailyStore.class})
    static class ActivitySlice {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        /** The mapper production gets: no Scala module (spring-kafka-test adds one to tests only). */
        @Bean
        HibernatePropertiesCustomizer productionJsonFormatMapper() {
            List<com.fasterxml.jackson.databind.Module> modules = ObjectMapper.findModules().stream()
                    .filter(m -> !m.getClass().getName().startsWith("com.fasterxml.jackson.module.scala"))
                    .toList();
            return props -> props.put(AvailableSettings.JSON_FORMAT_MAPPER,
                    new JacksonJsonFormatMapper(new ObjectMapper().registerModules(modules)));
        }
    }

    @Autowired
    private IAssetRepository assets;
    @Autowired
    private ILabelRepository labels;
    @Autowired
    private IAssetLabelAssignmentRepository assignments;
    @Autowired
    private IUserRepository users;
    @Autowired
    private AssetLineageRepository lineage;
    @Autowired
    private CanonicalUriIndex canonicalUriIndex;
    @Autowired
    private AssetUsageDailyStore usageStore;
    @Autowired
    private CatalogIngestProperties ingestProperties;
    @Autowired
    private Clock clock;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private AssetService service;

    private Asset a;
    private Asset b;
    private Asset c;
    private Asset d;
    private Asset e;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() {
        service = new AssetService(assets, new AssetMapper(assignments), labels, assignments, users, lineage,
                mock(LineageService.class), mock(KafkaTemplate.class), new ObjectMapper(),
                new NoopExternalCatalogIntegration(), clock, ingestProperties, canonicalUriIndex, usageStore);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        jdbc.update("DELETE FROM dlap_catalog.dlap_asset_lineage");
        jdbc.update("DELETE FROM dlap_catalog.asset_usage_daily");
        jdbc.update("DELETE FROM dlap_catalog.asset_uri_canonical");
        jdbc.update("DELETE FROM dlap_common.dlap_asset_label_assignments");
        jdbc.update("DELETE FROM dlap_common.dlap_assets");

        // A: read recently, a size, retained, full read coverage, two labels.
        a = save("gs://cat02b-a", "GCP", "GCS_BUCKET", x -> {
            x.setCloudCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            x.setCloudCreatedSource("CRAWLER");
            x.setLastAccessedAt(Instant.parse("2026-10-10T12:00:00Z"));
            x.setLastAccessSource("AUDIT_LOG:GCP_CLOUD_AUDIT");
            x.setLastModifiedAt(Instant.parse("2026-10-11T00:00:00Z"));
            x.setSizeBytes(1000L);
            x.setRetentionStatus(RetentionStatus.RETAINED);
            x.setRetentionDays(365);
            x.setRetentionBasis(RetentionBasis.CREATED);
            x.setRetentionSource("POLICY");
            x.setRetentionExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        });
        coverage(a, "FULL", "[\"storage.googleapis.com DATA_READ is recorded with no exemptions\"]");
        // B: read long ago, a real size of zero, due, partial read coverage.
        b = save("gs://cat02b-b", "GCP", "GCS_BUCKET", x -> {
            x.setLastAccessedAt(Instant.parse("2026-05-01T00:00:00Z"));
            x.setSizeBytes(0L);
            x.setRetentionStatus(RetentionStatus.DUE);
        });
        coverage(b, "PARTIAL", "[\"public object reads are not logged\"]");
        // C: nothing known at all.
        c = save("s3://cat02b-c", "AWS", "S3_BUCKET", x -> { });
        // D: a table on legal hold, a size beyond int, no read coverage at all.
        d = save("bq://cat02b-p/ds/t", "GCP", "BIGQUERY_TABLE", x -> {
            x.setLastAccessedAt(Instant.parse("2026-09-01T00:00:00Z"));
            x.setSizeBytes(5_000_000_000L);
            x.placeLegalHold();
        });
        coverage(d, "NONE", "[\"no DLAP log binding ingests GCP_CLOUD_AUDIT records\"]");
        // E: archived; unknown coverage with a reason (a missing permission), still unknown.
        e = save("az://cat02b-e", "AZURE", "BLOB_STORAGE", x -> {
            x.setStatus("ARCHIVED");
            // The pre-COV-01 metadata key is ignored by the report and the filter.
            x.setTechnicalMetadata(Map.of("readCoverage", "FULL"));
        });
        coverage(e, null, "[\"missing permission Microsoft.Insights/diagnosticSettings/read\"]");

        label(a, "PII");
        label(a, "FINANCIAL");
        label(d, "CONFIDENTIAL");
    }

    private Asset save(String uri, String provider, String kind, Consumer<Asset> customize) {
        Asset asset = new Asset(uri, provider, kind, uri);
        customize.accept(asset);
        return assets.saveAndFlush(asset);
    }

    /** Stores coverage the way the COV-01 applier does: by SQL, since the entity maps it read-only. */
    private void coverage(Asset asset, String level, String reasonsJson) {
        jdbc.update("UPDATE dlap_common.dlap_assets SET read_coverage = CAST(? AS varchar), "
                + "read_coverage_reasons = CAST(? AS jsonb), read_coverage_observed_at = CAST(? AS timestamptz), "
                + "read_coverage_since = CASE WHEN CAST(? AS varchar) = 'FULL' THEN CAST(? AS timestamptz) END "
                + "WHERE asset_id = ?", level, reasonsJson, java.sql.Timestamp.from(NOW), String.valueOf(level),
                java.sql.Timestamp.from(NOW), asset.getAssetId());
    }

    private void label(Asset asset, String labelName) {
        jdbc.update("INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, source) "
                + "SELECT ?, label_id, 'USER' FROM dlap_common.dlap_labels WHERE label_name = ?",
                asset.getAssetId(), labelName);
    }

    private static ActivityQuery query(List<String> providers, List<String> kinds, List<String> statuses,
                                       Instant before, Instant after, Boolean known, List<String> retention,
                                       Boolean hold, List<String> coverage, Instant asOf) {
        return new ActivityQuery(providers, kinds, statuses, before, after, known, retention, hold, coverage, asOf);
    }

    private List<String> uris(ActivityQuery query) {
        return service.getAssetActivity(query, PageRequest.of(0, 50)).getContent().stream()
                .map(AssetActivityRowDTO::assetUri).toList();
    }

    private List<String> urisSorted(String property, Sort.Direction direction) {
        return service.getAssetActivity(ActivityQuery.unfiltered(),
                PageRequest.of(0, 50, Sort.by(direction, property))).getContent().stream()
                .map(AssetActivityRowDTO::assetUri).toList();
    }

    // ---- paging and count -------------------------------------------------------------------

    @Test
    void pagesEveryRowExactlyOnceInAssetUriOrderWithAStableTotal() {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Page<AssetActivityRowDTO> result = service.getAssetActivity(ActivityQuery.unfiltered(),
                    PageRequest.of(page, 2));
            assertThat(result.getTotalElements()).isEqualTo(5);
            assertThat(result.getTotalPages()).isEqualTo(3);
            result.getContent().forEach(r -> seen.add(r.assetUri()));
        }
        assertThat(seen).containsExactly("az://cat02b-e", "bq://cat02b-p/ds/t", "gs://cat02b-a", "gs://cat02b-b",
                "s3://cat02b-c");
    }

    @Test
    void theTotalCountsOnlyTheRowsThatMatchTheFilters() {
        Page<AssetActivityRowDTO> result = service.getAssetActivity(
                query(List.of("GCP"), null, null, null, null, null, null, null, null, null), PageRequest.of(0, 1));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    // ---- keyset paging (CAT-04, Q-445) --------------------------------------------------------

    private static final ActivityQuery WALK = query(null, List.of("DYNAMODB_TABLE"), null, null, null, null, null,
            null, null, null);

    private String walkUri(int n) {
        return String.format("ddb://walk-%03d", n);
    }

    private void walkAsset(int n) {
        save(walkUri(n), "AWS", "DYNAMODB_TABLE", x -> { });
    }

    @Test
    void concurrentInsertsDuringAKeysetWalkNeitherSkipNorRepeatAnAsset() {
        for (int n = 2; n <= 40; n += 2) {
            walkAsset(n); // walk-002 .. walk-040: 20 assets
        }
        List<String> seen = new ArrayList<>();
        List<String> insertedBehind = new ArrayList<>();
        List<String> insertedAhead = new ArrayList<>();
        String after = null;
        int pageCount = 0;
        while (true) {
            AssetActivityPageDTO page = service.getAssetActivityKeyset(WALK, after, 4);
            page.getContent().forEach(r -> seen.add(r.assetUri()));
            pageCount++;
            if (page.getNextAfter() == null) {
                break;
            }
            assertThat(page.hasNext()).isTrue();
            assertThat(page.getContent()).hasSize(4);
            after = page.getNextAfter();
            // Between pages another writer inserts one asset behind the cursor (it sorts before the
            // last row seen) and one ahead of it. Offset paging would now repeat a row.
            int cursor = Integer.parseInt(after.substring(after.length() - 3));
            String behind = walkUri(cursor - 1);
            String ahead = walkUri(cursor + 1);
            if (!insertedBehind.contains(behind) && !seen.contains(behind) && !insertedAhead.contains(behind)) {
                walkAsset(cursor - 1);
                insertedBehind.add(behind);
            }
            if (!insertedAhead.contains(ahead) && !seen.contains(ahead) && !insertedBehind.contains(ahead)) {
                walkAsset(cursor + 1);
                insertedAhead.add(ahead);
            }
        }

        // Nothing repeats, and the walk is in strictly ascending assetUri order.
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).isSorted();
        // Every asset that was there when the walk began is visited exactly once.
        for (int n = 2; n <= 40; n += 2) {
            assertThat(seen).as("original asset %s", walkUri(n)).containsOnlyOnce(walkUri(n));
        }
        // An asset written ahead of the cursor is visited; one written behind it is not.
        assertThat(insertedAhead).isNotEmpty();
        assertThat(insertedBehind).isNotEmpty();
        assertThat(seen).containsAll(insertedAhead);
        assertThat(seen).doesNotContainAnyElementsOf(insertedBehind);
        assertThat(pageCount).isGreaterThan(5);
    }

    @Test
    void offsetPagingStillShiftsWhenARowIsInsertedBehindThePageWhichIsWhatKeysetPagingFixes() {
        for (int n = 2; n <= 8; n += 2) {
            walkAsset(n);
        }
        Page<AssetActivityRowDTO> first = service.getAssetActivity(WALK, PageRequest.of(0, 2));
        walkAsset(1);
        Page<AssetActivityRowDTO> second = service.getAssetActivity(WALK, PageRequest.of(1, 2));

        // Offset 2 now starts at walk-004, the row page 0 ended with: the repeat that keyset paging removes.
        assertThat(first.getContent().get(1).assetUri()).isEqualTo(walkUri(4));
        assertThat(second.getContent().get(0).assetUri()).isEqualTo(walkUri(4));
    }

    @Test
    void aKeysetPageHoldsTheRowsAfterTheCursorInAssetUriOrderWithTheExactTotal() {
        for (int n = 1; n <= 7; n++) {
            walkAsset(n);
        }
        AssetActivityPageDTO first = service.getAssetActivityKeyset(WALK, null, 3);
        assertThat(first.getContent()).extracting(AssetActivityRowDTO::assetUri)
                .containsExactly(walkUri(1), walkUri(2), walkUri(3));
        assertThat(first.getNextAfter()).isEqualTo(walkUri(3));
        assertThat(first.isFirst()).isTrue();
        assertThat(first.isLast()).isFalse();

        AssetActivityPageDTO second = service.getAssetActivityKeyset(WALK, first.getNextAfter(), 3);
        assertThat(second.getContent()).extracting(AssetActivityRowDTO::assetUri)
                .containsExactly(walkUri(4), walkUri(5), walkUri(6));
        assertThat(second.getNextAfter()).isEqualTo(walkUri(6));
        assertThat(second.isFirst()).isFalse();

        AssetActivityPageDTO last = service.getAssetActivityKeyset(WALK, second.getNextAfter(), 3);
        assertThat(last.getContent()).extracting(AssetActivityRowDTO::assetUri).containsExactly(walkUri(7));
        assertThat(last.getNextAfter()).isNull();
        assertThat(last.isLast()).isTrue();
        // The total is the filtered result, not the rows left, even on a page shorter than its size.
        assertThat(last.getTotalElements()).isEqualTo(7);
        assertThat(last.getTotalPages()).isEqualTo(3);
    }

    @Test
    void aPageThatEndsExactlyOnTheLastRowHasNoNextAfter() {
        for (int n = 1; n <= 4; n++) {
            walkAsset(n);
        }
        AssetActivityPageDTO first = service.getAssetActivityKeyset(WALK, null, 2);
        AssetActivityPageDTO second = service.getAssetActivityKeyset(WALK, first.getNextAfter(), 2);

        assertThat(second.getContent()).hasSize(2);
        assertThat(second.getNextAfter()).isNull();
        assertThat(service.getAssetActivityKeyset(WALK, second.getContent().get(1).assetUri(), 2).getContent())
                .isEmpty();
    }

    @Test
    void aKeysetPageKeepsTheFiltersTheLabelsAndTheRowShape() {
        AssetActivityPageDTO page = service.getAssetActivityKeyset(
                query(List.of("GCP"), null, null, null, null, null, null, null, null, null), null, 50);

        assertThat(page.getContent()).extracting(AssetActivityRowDTO::assetUri)
                .containsExactly("bq://cat02b-p/ds/t", "gs://cat02b-a", "gs://cat02b-b");
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getNextAfter()).isNull();
        AssetActivityRowDTO rowA = page.getContent().get(1);
        assertThat(rowA.labels()).containsExactly("FINANCIAL", "PII");
        assertThat(rowA.readCoverage()).isEqualTo("FULL");
        assertThat(rowA.asOf()).isEqualTo(NOW);
    }

    @Test
    void anOffsetPageReportsTheSameNextAfterSoAWalkCanStartWithAnOrdinaryFirstPage() {
        for (int n = 1; n <= 5; n++) {
            walkAsset(n);
        }
        Page<AssetActivityRowDTO> offset = service.getAssetActivity(WALK, PageRequest.of(0, 2));
        AssetActivityPageDTO wrapped = AssetActivityPageDTO.ofOffsetPage(offset, PageRequest.of(0, 2));

        assertThat(wrapped.getNextAfter()).isEqualTo(walkUri(2));
        AssetActivityPageDTO second = service.getAssetActivityKeyset(WALK, wrapped.getNextAfter(), 2);
        assertThat(second.getContent()).extracting(AssetActivityRowDTO::assetUri)
                .containsExactly(walkUri(3), walkUri(4));
    }

    // ---- filters ----------------------------------------------------------------------------

    @Test
    void filtersByProviderAndKindAndStatus() {
        assertThat(uris(query(List.of("GCP"), null, null, null, null, null, null, null, null, null)))
                .containsExactly("bq://cat02b-p/ds/t", "gs://cat02b-a", "gs://cat02b-b");
        assertThat(uris(query(List.of("aws", "azure"), null, null, null, null, null, null, null, null, null)))
                .containsExactly("az://cat02b-e", "s3://cat02b-c");
        assertThat(uris(query(null, List.of("GCS_BUCKET"), null, null, null, null, null, null, null, null)))
                .containsExactly("gs://cat02b-a", "gs://cat02b-b");
        assertThat(uris(query(null, null, List.of("ARCHIVED"), null, null, null, null, null, null, null)))
                .containsExactly("az://cat02b-e");
    }

    @Test
    void theLastReadFiltersNeverMatchAnUnknownLastRead() {
        // C and E have no recorded read: neither before nor after anything.
        assertThat(uris(query(null, null, null, Instant.parse("2026-07-01T00:00:00Z"), null, null, null, null,
                null, null))).containsExactly("gs://cat02b-b");
        assertThat(uris(query(null, null, null, null, Instant.parse("2026-09-15T00:00:00Z"), null, null, null,
                null, null))).containsExactly("gs://cat02b-a");
        assertThat(uris(query(null, null, null, Instant.parse("2026-09-15T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"), null, null, null, null, null)))
                .containsExactly("bq://cat02b-p/ds/t", "gs://cat02b-b");
        // Strictly: an instant equal to the last read matches neither side.
        assertThat(uris(query(null, null, null, Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, null, null))).isEmpty();
    }

    @Test
    void anUnknownLastReadIsAskedForExplicitly() {
        assertThat(uris(query(null, null, null, null, null, Boolean.FALSE, null, null, null, null)))
                .containsExactly("az://cat02b-e", "s3://cat02b-c");
        assertThat(uris(query(null, null, null, null, null, Boolean.TRUE, null, null, null, null)))
                .containsExactly("bq://cat02b-p/ds/t", "gs://cat02b-a", "gs://cat02b-b");
    }

    @Test
    void filtersByStoredRetentionStatusAndLegalHold() {
        assertThat(uris(query(null, null, null, null, null, null, List.of("DUE"), null, null, null)))
                .containsExactly("gs://cat02b-b");
        assertThat(uris(query(null, null, null, null, null, null, List.of("due", "retained"), null, null, null)))
                .containsExactly("gs://cat02b-a", "gs://cat02b-b");
        assertThat(uris(query(null, null, null, null, null, null, List.of("ON_HOLD"), null, null, null)))
                .containsExactly("bq://cat02b-p/ds/t");
        assertThat(uris(query(null, null, null, null, null, null, null, Boolean.TRUE, null, null)))
                .containsExactly("bq://cat02b-p/ds/t");
        assertThat(uris(query(null, null, null, null, null, null, null, Boolean.FALSE, null, null)))
                .containsExactly("az://cat02b-e", "gs://cat02b-a", "gs://cat02b-b", "s3://cat02b-c");
    }

    @Test
    void aHeldAssetNeverReadsAsDueThroughTheReport() {
        // ck_asset_legal_hold_status: a held row is ON_HOLD, so a DUE filter cannot return it.
        assertThat(uris(query(null, null, null, null, null, null, List.of("DUE"), Boolean.TRUE, null, null)))
                .isEmpty();
    }

    @Test
    void filtersByReadCoverageWhenPresentAndUnknownMeansNoneRecorded() {
        assertThat(uris(query(null, null, null, null, null, null, null, null, List.of("FULL"), null)))
                .containsExactly("gs://cat02b-a");
        assertThat(uris(query(null, null, null, null, null, null, null, null, List.of("partial"), null)))
                .containsExactly("gs://cat02b-b");
        // NONE is a coverage, not "unknown".
        assertThat(uris(query(null, null, null, null, null, null, null, null, List.of("NONE"), null)))
                .containsExactly("bq://cat02b-p/ds/t");
        // A null column is unknown, with (E) or without (C) reasons; the old metadata key on E is ignored.
        assertThat(uris(query(null, null, null, null, null, null, null, null, List.of("UNKNOWN"), null)))
                .containsExactly("az://cat02b-e", "s3://cat02b-c");
        assertThat(uris(query(null, null, null, null, null, null, null, null, List.of("FULL", "UNKNOWN"), null)))
                .containsExactly("az://cat02b-e", "gs://cat02b-a", "s3://cat02b-c");
    }

    @Test
    void filtersCombineWithAnd() {
        assertThat(uris(query(List.of("GCP"), null, null, null, null, Boolean.TRUE, null, Boolean.FALSE, null,
                null))).containsExactly("gs://cat02b-a", "gs://cat02b-b");
        assertThat(uris(query(List.of("GCP"), List.of("GCS_BUCKET"), List.of("ACTIVE"), null,
                Instant.parse("2026-09-15T00:00:00Z"), null, List.of("RETAINED"), null, List.of("FULL"), null)))
                .containsExactly("gs://cat02b-a");
        assertThat(uris(query(List.of("AWS"), List.of("GCS_BUCKET"), null, null, null, null, null, null, null,
                null))).isEmpty();
    }

    // ---- sort -------------------------------------------------------------------------------

    @Test
    void anUnknownSortValueSortsLastInEitherDirection() {
        // lastAccessedAt: B 05-01, D 09-01, A 10-10; C and E unknown, kept in assetUri order.
        assertThat(urisSorted("lastAccessedAt", Sort.Direction.ASC)).containsExactly(
                "gs://cat02b-b", "bq://cat02b-p/ds/t", "gs://cat02b-a", "az://cat02b-e", "s3://cat02b-c");
        assertThat(urisSorted("lastAccessedAt", Sort.Direction.DESC)).containsExactly(
                "gs://cat02b-a", "bq://cat02b-p/ds/t", "gs://cat02b-b", "az://cat02b-e", "s3://cat02b-c");
    }

    @Test
    void sizeZeroIsASizeAndSortsBeforeEveryOtherSizeAndUnknownLast() {
        assertThat(urisSorted("sizeBytes", Sort.Direction.ASC)).containsExactly(
                "gs://cat02b-b", "gs://cat02b-a", "bq://cat02b-p/ds/t", "az://cat02b-e", "s3://cat02b-c");
        assertThat(urisSorted("sizeBytes", Sort.Direction.DESC)).containsExactly(
                "bq://cat02b-p/ds/t", "gs://cat02b-a", "gs://cat02b-b", "az://cat02b-e", "s3://cat02b-c");
    }

    @Test
    void pagingThroughASortWithUnknownsRepeatsAndDropsNothing() {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            service.getAssetActivity(ActivityQuery.unfiltered(),
                    PageRequest.of(page, 2, Sort.by(Sort.Direction.DESC, "lastAccessedAt")))
                    .getContent().forEach(r -> seen.add(r.assetUri()));
        }
        assertThat(seen).containsExactly("gs://cat02b-a", "bq://cat02b-p/ds/t", "gs://cat02b-b", "az://cat02b-e",
                "s3://cat02b-c");
    }

    @Test
    void everySortablePropertyRunsOnPostgresInBothDirections() {
        for (String property : AssetService.ACTIVITY_SORTABLE) {
            for (Sort.Direction direction : Sort.Direction.values()) {
                Page<AssetActivityRowDTO> page = service.getAssetActivity(ActivityQuery.unfiltered(),
                        PageRequest.of(0, 50, Sort.by(direction, property)));
                assertThat(page.getContent()).as("%s %s", property, direction).hasSize(5);
                assertThat(page.getTotalElements()).isEqualTo(5);
            }
        }
    }

    // ---- row content ------------------------------------------------------------------------

    private AssetActivityRowDTO row(String uri) {
        return service.getAssetActivity(ActivityQuery.unfiltered(), PageRequest.of(0, 50)).getContent().stream()
                .filter(r -> r.assetUri().equals(uri)).findFirst().orElseThrow();
    }

    @Test
    void aRowCarriesTheStoredTimesSizeAndRetentionResult() {
        AssetActivityRowDTO r = row("gs://cat02b-a");

        assertThat(r.assetId()).isEqualTo(a.getAssetId());
        assertThat(r.cloudProvider()).isEqualTo("GCP");
        assertThat(r.serviceType()).isEqualTo("GCS_BUCKET");
        assertThat(r.cloudCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(r.cloudCreatedSource()).isEqualTo("CRAWLER");
        assertThat(r.ageDays()).isEqualTo(291L);
        assertThat(r.lastAccessedAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        assertThat(r.lastAccessSource()).isEqualTo("AUDIT_LOG:GCP_CLOUD_AUDIT");
        assertThat(r.idleDays()).isEqualTo(9L);
        assertThat(r.lastModifiedAt()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(r.sizeBytes()).isEqualTo(1000L);
        assertThat(r.readCoverage()).isEqualTo("FULL");
        assertThat(r.readCoverageReasons()).containsExactly("storage.googleapis.com DATA_READ is recorded with no exemptions");
        assertThat(r.readCoverageObservedAt()).as("read_coverage_observed_at, Q-475").isEqualTo(NOW);
        assertThat(r.readCoverageSince()).as("read_coverage_since, Q-434").isEqualTo(NOW);
        assertThat(r.retentionStatus()).isEqualTo(RetentionStatus.RETAINED);
        assertThat(r.retentionDays()).isEqualTo(365);
        assertThat(r.retentionBasis()).isEqualTo(RetentionBasis.CREATED);
        assertThat(r.retentionSource()).isEqualTo("POLICY");
        assertThat(r.retentionExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(r.legalHold()).isFalse();
        assertThat(r.asOf()).isEqualTo(NOW);
    }

    @Test
    void whatTheCatalogDoesNotKnowIsNullAndAZeroSizeIsKept() {
        AssetActivityRowDTO unknown = row("s3://cat02b-c");
        assertThat(unknown.cloudCreatedAt()).isNull();
        assertThat(unknown.ageDays()).isNull();
        assertThat(unknown.lastAccessedAt()).isNull();
        assertThat(unknown.idleDays()).isNull();
        assertThat(unknown.lastModifiedAt()).isNull();
        assertThat(unknown.sizeBytes()).isNull();
        assertThat(unknown.readCoverage()).isNull();
        assertThat(unknown.readCoverageReasons()).isNull();
        assertThat(unknown.readCoverageObservedAt()).isNull();
        assertThat(unknown.readCoverageSince()).isNull();
        AssetActivityRowDTO denied = row("az://cat02b-e");
        assertThat(denied.readCoverage()).as("unknown, not NONE").isNull();
        assertThat(denied.readCoverageReasons())
                .containsExactly("missing permission Microsoft.Insights/diagnosticSettings/read");
        assertThat(unknown.retentionStatus()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(unknown.retentionExpiresAt()).isNull();

        assertThat(row("gs://cat02b-b").sizeBytes()).isZero();
        assertThat(row("bq://cat02b-p/ds/t").sizeBytes()).isEqualTo(5_000_000_000L);
    }

    @Test
    void aHeldAssetShowsTheHoldAndOnHold() {
        AssetActivityRowDTO r = row("bq://cat02b-p/ds/t");

        assertThat(r.legalHold()).isTrue();
        assertThat(r.retentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(r.readCoverage()).isEqualTo("NONE");
        assertThat(r.readCoverageObservedAt()).isEqualTo(NOW);
        assertThat(r.readCoverageSince()).as("only a FULL run has a start").isNull();
    }

    @Test
    void labelsComeFromTheAssignmentsSortedAndEmptyWhenNone() {
        assertThat(row("gs://cat02b-a").labels()).containsExactly("FINANCIAL", "PII");
        assertThat(row("bq://cat02b-p/ds/t").labels()).containsExactly("CONFIDENTIAL");
        assertThat(row("s3://cat02b-c").labels()).isEmpty();
    }

    @Test
    void theReportInstantIsTheInjectedClockUnlessTheCallerSetsOne() {
        Instant asOf = Instant.parse("2026-10-20T12:00:00Z");

        AssetActivityRowDTO later = service.getAssetActivity(
                query(List.of("GCP"), List.of("GCS_BUCKET"), null, null, Instant.parse("2026-10-01T00:00:00Z"),
                        null, null, null, null, asOf), PageRequest.of(0, 5)).getContent().get(0);

        assertThat(later.asOf()).isEqualTo(asOf);
        assertThat(later.idleDays()).isEqualTo(10L);
        assertThat(row("gs://cat02b-a").asOf()).isEqualTo(NOW);
    }

    // ---- resolve ----------------------------------------------------------------------------

    @Test
    void resolveFindsARowByEveryAliasOfItsCanonicalUri() {
        assertThat(service.resolveAssetIdByUri("gs://cat02b-a")).contains(a.getAssetId());
        assertThat(service.resolveAssetIdByUri("projects/_/buckets/cat02b-a")).contains(a.getAssetId());
        assertThat(service.resolveAssetIdByUri("//storage.googleapis.com/projects/_/buckets/cat02b-a"))
                .contains(a.getAssetId());
        assertThat(service.resolveAssetIdByUri("  GS://cat02b-a  ")).contains(a.getAssetId());
        assertThat(service.resolveAssetIdByUri("arn:aws:s3:::cat02b-c")).contains(c.getAssetId());
        assertThat(service.resolveAssetIdByUri("s3a://cat02b-c")).contains(c.getAssetId());
    }

    @Test
    void resolveFindsARowStoredInAnOlderFormThroughTheCanonicalIndex() {
        // Written before canonical IDs: the stored text is the GCS audit resource name.
        Asset old = save("projects/_/buckets/cat02b-old", "GCP", "GCS_BUCKET", x -> { });

        assertThat(service.resolveAssetIdByUri("gs://cat02b-old")).contains(old.getAssetId());
        assertThat(service.resolveAssetIdByUri("projects/_/buckets/cat02b-old")).contains(old.getAssetId());
    }

    @Test
    void resolveTakesTheOldestRowWhenTwoStoredFormsNormalizeToTheSameUri() {
        Asset first = save("projects/_/buckets/cat02b-dup", "GCP", "GCS_BUCKET", x -> { });
        save("//storage.googleapis.com/projects/_/buckets/cat02b-dup", "GCP", "GCS_BUCKET", x -> { });

        assertThat(service.resolveAssetIdByUri("gs://cat02b-dup")).contains(first.getAssetId());
    }

    @Test
    void resolveDoesNotInventARowForAnUnknownOrAnObjectUri() {
        assertThat(service.resolveAssetIdByUri("gs://cat02b-nobody")).isEmpty();
        assertThat(service.resolveAssetIdByUri("projects/_/buckets/cat02b-nobody")).isEmpty();
        // An object has no row of its own outside a governed prefix; it never resolves to its container.
        assertThat(service.resolveAssetIdByUri("gs://cat02b-a/some/object.csv")).isEmpty();
        assertThat(service.resolveAssetIdByUri("not an identifier")).isEmpty();
    }

    // ---- environment ------------------------------------------------------------------------

    private static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES);
        try (Stream<Path> scripts = Files.list(initScriptsDir())) {
            scripts.filter(Files::isRegularFile).sorted().forEach(script -> {
                String name = script.getFileName().toString();
                int mode = name.endsWith(".sh") ? 0755 : 0644;
                postgres.withCopyFileToContainer(
                        MountableFile.forHostPath(script, mode), "/docker-entrypoint-initdb.d/" + name);
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read the platform init scripts", ex);
        }
        return postgres;
    }

    private static Path initScriptsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deployment/platform/database/init-scripts not found");
    }

}
