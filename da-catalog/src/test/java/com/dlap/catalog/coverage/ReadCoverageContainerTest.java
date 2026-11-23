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

package com.dlap.catalog.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.catalog.integration.NoopExternalCatalogIntegration;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.kafka.CatalogIngestMetrics;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.mapper.AssetMapper;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.catalog.retention.BindingReadCoverageSource;
import com.dlap.catalog.retention.BindingStatusStore;
import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;
import com.dlap.catalog.retention.ReadCoverage;
import com.dlap.catalog.retention.ReadCoverageSource;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.service.AssetService;
import com.dlap.catalog.service.IAssetService.ActivityQuery;
import com.dlap.catalog.service.LineageService;
import com.dlap.catalog.usage.AssetUsageDailyStore;
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
 * COV-01 against the real schema ({@code postgres:15.1} from the platform init scripts, the
 * catalog's Flyway migrations, connected as {@code da_catalog_user}): observations land in the
 * {@code read_coverage*} columns of the right rows, in order, combined with the stored bindings;
 * the activity report and its {@code readCoverage} filter read the column; an entity save cannot
 * overwrite it; and the retention signal needs per-asset {@code FULL}. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = ReadCoverageContainerTest.Slice.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ReadCoverageContainerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-20T00:41:00Z");
    private static final Instant T2 = Instant.parse("2026-10-20T06:41:00Z");
    private static final Instant T3 = Instant.parse("2026-10-20T08:41:00Z");
    private static final Instant T4 = Instant.parse("2026-10-20T10:41:00Z");

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

    /** JPA, JDBC, Flyway and the coverage beans; no Kafka, web or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    @EnableJpaRepositories(basePackages = {"com.dlap.catalog.repository", "com.dlap.common.repository"})
    @EnableConfigurationProperties({CatalogIngestProperties.class, ReadCoverageProperties.class})
    @Import({CatalogEventDecoder.class, CatalogIngestMetrics.class, CanonicalUriIndex.class, AssetUsageDailyStore.class,
            BindingStatusStore.class, BindingReadCoverageSource.class, ReadCoverageApplier.class,
            ReadCoverageObservationListener.class, AssetReadCoverageSource.class})
    static class Slice {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
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
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BindingStatusStore bindings;
    @Autowired
    private ReadCoverageApplier applier;
    @Autowired
    private ReadCoverageObservationListener listener;
    @Autowired
    private ReadCoverageSource retentionSignal;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AssetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() {
        service = new AssetService(assets, new AssetMapper(assignments), labels, assignments, users, lineage,
                mock(LineageService.class), mock(KafkaTemplate.class), new ObjectMapper(),
                new NoopExternalCatalogIntegration(), Clock.fixed(NOW, ZoneOffset.UTC), ingestProperties,
                canonicalUriIndex, usageStore);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        jdbc.update("DELETE FROM dlap_catalog.dlap_asset_lineage");
        jdbc.update("DELETE FROM dlap_catalog.asset_usage_daily");
        jdbc.update("DELETE FROM dlap_catalog.asset_uri_canonical");
        jdbc.update("DELETE FROM dlap_catalog.log_binding_status");
        jdbc.update("DELETE FROM dlap_common.dlap_asset_label_assignments");
        jdbc.update("DELETE FROM dlap_common.dlap_assets");

        save("gs://dlap-demo-curated", "GCP", "GCS_BUCKET");
        save("gs://dlap-demo-curated/exports/2026.csv", "GCP", "GCS_OBJECT");
        save("gs://dlap-demo-curated-old", "GCP", "GCS_BUCKET");
        save("bq://dlap-demo-data/sales", "GCP", "BIGQUERY_DATASET");
        save("bq://dlap-demo-data/sales/orders", "GCP", "BIGQUERY_TABLE");
        save("bq://dlap-demo-data2/sales", "GCP", "BIGQUERY_DATASET");
        save("s3://finance-raw", "AWS", "S3_BUCKET");
    }

    private Asset save(String uri, String provider, String kind) {
        return assets.saveAndFlush(new Asset(uri, provider, kind, uri));
    }

    private static ReadCoverageObservation obs(String root, Set<String> kinds, ReadCoverageLevel level, Instant at,
                                               String... reasons) {
        return new ReadCoverageObservation("e-" + at, "GCP", "dlap-demo-data", root, kinds, "GCP_CLOUD_AUDIT", level,
                List.of(reasons), at, "gcp-audit-config-coverage@1.0.0");
    }

    private static ReadCoverageObservation gcs(ReadCoverageLevel level, Instant at, String... reasons) {
        return obs("gs://dlap-demo-curated", Set.of("GCS_BUCKET", "GCS_OBJECT"), level, at, reasons);
    }

    private ReadCoverageApplier.Outcome apply(ReadCoverageObservation o) {
        return new TransactionTemplate(transactionManager).execute(s -> applier.apply(o));
    }

    private void binding(String id, String kind) {
        bindings.upsert(new BindingStatus(id, "GCP", kind, "GCP_PROJECT", "dlap-demo-data", "BINDING_STATE_ACTIVE",
                "READ_COVERAGE_CAPTURED", Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-10-20T11:00:00Z"),
                NOW, NOW), null);
    }

    private Map<String, Object> stored(String uri) {
        return jdbc.queryForMap("SELECT read_coverage, read_coverage_reasons::text AS reasons, read_coverage_observed_at, "
                + "read_coverage_since FROM dlap_common.dlap_assets WHERE asset_uri = ?", uri);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((java.sql.Timestamp) value).toInstant();
    }

    private List<String> uris(List<String> coverage) {
        return service.getAssetActivity(new ActivityQuery(null, null, null, null, null, null, null, null, coverage, null),
                PageRequest.of(0, 50)).getContent().stream().map(AssetActivityRowDTO::assetUri).toList();
    }

    @Test
    void anObservationAppliesToItsRootAndToDescendantsOfItsKindsOnly() {
        binding("audit", "GCP_CLOUD_AUDIT");
        assertThat(apply(gcs(ReadCoverageLevel.FULL, T1, "evidence"))).isEqualTo(ReadCoverageApplier.Outcome.APPLIED);

        assertThat(stored("gs://dlap-demo-curated").get("read_coverage")).isEqualTo("FULL");
        assertThat(stored("gs://dlap-demo-curated/exports/2026.csv").get("read_coverage")).isEqualTo("FULL");
        assertThat(stored("gs://dlap-demo-curated-old").get("read_coverage")).as("a name sharing the prefix").isNull();

        assertThat(apply(obs("bq://dlap-demo-data", Set.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"),
                ReadCoverageLevel.PARTIAL, T1, "gap"))).isEqualTo(ReadCoverageApplier.Outcome.APPLIED);
        assertThat(stored("bq://dlap-demo-data/sales").get("read_coverage")).isEqualTo("PARTIAL");
        assertThat(stored("bq://dlap-demo-data/sales/orders").get("read_coverage")).isEqualTo("PARTIAL");
        assertThat(stored("bq://dlap-demo-data2/sales").get("read_coverage")).isNull();
        assertThat(stored("s3://finance-raw").get("read_coverage")).isNull();
    }

    @Test
    void noBindingMeansNoneAndAJobsViewBindingAloneMeansJobReadsOnly() {
        apply(gcs(ReadCoverageLevel.FULL, T1, "evidence"));
        assertThat(stored("gs://dlap-demo-curated").get("read_coverage")).isEqualTo("NONE");
        assertThat(stored("gs://dlap-demo-curated").get("reasons")).isEqualTo(
                "[\"no DLAP log binding ingests GCP_CLOUD_AUDIT records for GCP scope dlap-demo-data\"]");

        binding("jobs", "GCP_BQ_JOBS_VIEW");
        apply(obs("bq://dlap-demo-data", Set.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"), ReadCoverageLevel.FULL, T1, "x"));
        assertThat(stored("bq://dlap-demo-data/sales/orders").get("read_coverage")).isEqualTo("PARTIAL");
        assertThat(stored("bq://dlap-demo-data/sales/orders").get("reasons")).isEqualTo("[\"bqjobs only: job reads only\"]");
    }

    @Test
    void observationsApplyInObservationOrderAndFullKeepsItsStart() {
        binding("audit", "GCP_CLOUD_AUDIT");
        apply(gcs(ReadCoverageLevel.FULL, T2, "evidence"));
        assertThat(instant(stored("gs://dlap-demo-curated").get("read_coverage_since"))).isEqualTo(T2);

        assertThat(apply(gcs(ReadCoverageLevel.NONE, T1, "older"))).isEqualTo(ReadCoverageApplier.Outcome.STALE);
        assertThat(stored("gs://dlap-demo-curated").get("read_coverage")).isEqualTo("FULL");

        assertThat(apply(gcs(ReadCoverageLevel.FULL, T2, "evidence"))).as("a redelivery re-applies")
                .isEqualTo(ReadCoverageApplier.Outcome.APPLIED);
        apply(gcs(ReadCoverageLevel.FULL, T3, "evidence"));
        Map<String, Object> run = stored("gs://dlap-demo-curated");
        assertThat(instant(run.get("read_coverage_since"))).as("an unbroken run keeps its start").isEqualTo(T2);
        assertThat(instant(run.get("read_coverage_observed_at"))).isEqualTo(T3);

        apply(gcs(ReadCoverageLevel.PARTIAL, T4, "public objects"));
        assertThat(stored("gs://dlap-demo-curated").get("read_coverage_since")).isNull();
        apply(gcs(ReadCoverageLevel.FULL, T4.plusSeconds(60), "evidence"));
        assertThat(instant(stored("gs://dlap-demo-curated").get("read_coverage_since"))).isEqualTo(T4.plusSeconds(60));
    }

    @Test
    void theSingleAssetReadReturnsTheCoverageTheApplierStored() {
        binding("audit", "GCP_CLOUD_AUDIT");
        UUID bucket = jdbc.queryForObject("SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = ?",
                UUID.class, "gs://dlap-demo-curated");
        assertThat(service.getAssetById(bucket).getReadCoverage()).as("before any observation").isNull();

        apply(gcs(ReadCoverageLevel.FULL, T1, "evidence"));

        AssetOutputDTO read = service.getAssetById(bucket);
        assertThat(read.getReadCoverage()).isEqualTo("FULL");
        assertThat(read.getReadCoverageReasons()).containsExactly("evidence");
    }

    @Test
    void anObservationForAssetsTheCatalogDoesNotHoldChangesNothing() {
        assertThat(apply(obs("gs://not-catalogued", Set.of("GCS_BUCKET"), ReadCoverageLevel.FULL, T1)))
                .isEqualTo(ReadCoverageApplier.Outcome.UNMATCHED);
    }

    @Test
    void theListenerAppliesTheSharedWireExampleAndIgnoresOtherEvents() throws IOException {
        bindings.upsert(new BindingStatus("ct", "AWS", "AWS_CLOUDTRAIL", "AWS_ACCOUNT", "111122223333",
                "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED", T1, T1, T1, T1), null);
        assertThat(listener.handle(ReadCoverageWireTest.example())).isEqualTo(ReadCoverageApplier.Outcome.APPLIED);
        assertThat(stored("s3://finance-raw").get("read_coverage")).isEqualTo("PARTIAL");
        assertThat(stored("s3://finance-raw").get("reasons"))
                .isEqualTo("[\"CloudTrail data events cover a prefix subset\"]");

        assertThat(listener.handle("{\"eventId\":\"x\",\"changeType\":\"ACCESS\"}")).isNull();
    }

    /**
     * RTR-01 (Q-824): a duplicate of an observation event has no second effect. Delivery on the log
     * spine is at least once (log-event.md section 5.5); the applier is monotonic on the observation
     * time and keeps the start of an unbroken FULL run, so copies of one event, in either wire form,
     * leave every {@code read_coverage*} column as the first copy wrote it. The listener acknowledges
     * each copy.
     */
    @Test
    void aDuplicateOfAnObservationEventChangesNothing() throws IOException {
        bindings.upsert(new BindingStatus("ct", "AWS", "AWS_CLOUDTRAIL", "AWS_ACCOUNT", "111122223333",
                "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED", T1, T1, T1, T1), null);
        String example = ReadCoverageWireTest.example();
        org.springframework.kafka.support.Acknowledgment ack = mock(org.springframework.kafka.support.Acknowledgment.class);

        listener.onLogEvent(example, ack);
        Map<String, Object> afterFirst = stored("s3://finance-raw");
        assertThat(afterFirst.get("read_coverage")).isEqualTo("PARTIAL");

        listener.onLogEvent(example, ack);
        listener.onLogEvent(new ObjectMapper().writeValueAsString(example), ack);
        listener.onLogEvent(example, ack);

        assertThat(stored("s3://finance-raw")).as("three more copies change no coverage column").isEqualTo(afterFirst);
        org.mockito.Mockito.verify(ack, org.mockito.Mockito.times(4)).acknowledge();
    }

    @Test
    void theActivityReportAndItsFilterReadTheColumn() {
        binding("audit", "GCP_CLOUD_AUDIT");
        apply(gcs(ReadCoverageLevel.FULL, T1, "evidence"));
        apply(obs("bq://dlap-demo-data", Set.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"), null, T1,
                "missing permission resourcemanager.projects.getIamPolicy on projects/dlap-demo-data"));

        assertThat(uris(List.of("FULL"))).containsExactlyInAnyOrder("gs://dlap-demo-curated", "gs://dlap-demo-curated/exports/2026.csv");
        assertThat(uris(List.of("UNKNOWN"))).containsExactlyInAnyOrder("bq://dlap-demo-data/sales", "bq://dlap-demo-data/sales/orders",
                "bq://dlap-demo-data2/sales", "gs://dlap-demo-curated-old", "s3://finance-raw");
        assertThat(uris(List.of("PARTIAL", "NONE"))).isEmpty();

        AssetActivityRowDTO orders = service.getAssetActivity(ActivityQuery.unfiltered(), PageRequest.of(0, 50))
                .getContent().stream().filter(r -> r.assetUri().equals("bq://dlap-demo-data/sales/orders"))
                .findFirst().orElseThrow();
        assertThat(orders.readCoverage()).isNull();
        assertThat(orders.readCoverageReasons()).containsExactly(
                "missing permission resourcemanager.projects.getIamPolicy on projects/dlap-demo-data");
    }

    @Test
    void anEntitySaveDoesNotOverwriteTheCoverage() {
        binding("audit", "GCP_CLOUD_AUDIT");
        Asset loaded = assets.findAll().stream().filter(a -> a.getAssetUri().equals("gs://dlap-demo-curated"))
                .findFirst().orElseThrow();
        apply(gcs(ReadCoverageLevel.FULL, T1, "evidence"));

        loaded.setDescription("renamed after the coverage was written");
        assets.saveAndFlush(loaded);

        assertThat(stored("gs://dlap-demo-curated").get("read_coverage")).isEqualTo("FULL");
        Asset reread = assets.findById(loaded.getAssetId()).orElseThrow();
        assertThat(reread.getReadCoverage()).isEqualTo("FULL");
        assertThat(reread.getReadCoverageReasons()).containsExactly("evidence");
        assertThat(reread.getReadCoverageSince()).isEqualTo(T1);
    }

    @Test
    void theRetentionSignalNeedsPerAssetFullCoverage() {
        assertThat(retentionSignal).isInstanceOf(AssetReadCoverageSource.class);
        binding("audit", "GCP_CLOUD_AUDIT");
        apply(gcs(ReadCoverageLevel.FULL, T1, "evidence"));
        apply(obs("bq://dlap-demo-data", Set.of("BIGQUERY_DATASET", "BIGQUERY_TABLE"), ReadCoverageLevel.PARTIAL, T1, "g"));

        ReadCoverageSource.View view = retentionSignal.snapshot();
        Optional<ReadCoverage> bucket = view.coverageFor(row("gs://dlap-demo-curated", "GCS_BUCKET"));
        assertThat(bucket).contains(new ReadCoverage(T1, T1));
        assertThat(view.coverageFor(row("bq://dlap-demo-data/sales/orders", "BIGQUERY_TABLE")))
                .as("PARTIAL is unknown for retention, though the binding captures reads").isEmpty();
    }

    private AssetRow row(String uri, String kind) {
        UUID id = jdbc.queryForObject("SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = ?", UUID.class, uri);
        return new AssetRow(id, uri, "GCP", kind, "ACTIVE", null, null, null, null, false,
                new com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys(null, null, "dlap-demo-data", null, null,
                        null), null);
    }

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
