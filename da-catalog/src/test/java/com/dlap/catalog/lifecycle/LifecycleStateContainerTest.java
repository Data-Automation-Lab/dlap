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

package com.dlap.catalog.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.catalog.coverage.AssetReadCoverageSource;
import com.dlap.catalog.coverage.ReadCoverageApplier;
import com.dlap.catalog.coverage.ReadCoverageLevel;
import com.dlap.catalog.coverage.ReadCoverageObservation;
import com.dlap.catalog.coverage.ReadCoverageProperties;
import com.dlap.catalog.idempotency.ProcessedEventStore;
import com.dlap.catalog.kafka.AssetEventApplier;
import com.dlap.catalog.kafka.AssetLifecycleEventListener;
import com.dlap.catalog.kafka.AssetMatcher;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.kafka.CatalogIngestMetrics;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.kafka.DiscoveryAssetDiscoveredListener;
import com.dlap.catalog.lifecycle.okf.OkfExporter;
import com.dlap.catalog.lifecycle.okf.OkfFrontmatterCheck;
import com.dlap.catalog.lineage.DefaultLineageExtractor;
import com.dlap.catalog.lineage.MetadataHarvester;
import com.dlap.catalog.retention.BindingReadCoverageSource;
import com.dlap.catalog.retention.BindingStatusStore;
import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;
import com.dlap.catalog.retention.RetentionAssetStore;
import com.dlap.catalog.retention.RetentionEvaluator;
import com.dlap.catalog.retention.RetentionMetrics;
import com.dlap.catalog.retention.RetentionOutbox;
import com.dlap.catalog.retention.RetentionProperties;
import com.dlap.catalog.retention.RetentionRuleStore;
import com.dlap.catalog.service.AssetService;
import com.dlap.catalog.service.DiscoveryAssetUpsertService;
import com.dlap.catalog.service.LineageService;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * LSE-01 acceptance against the real schema: the platform init scripts plus the catalog's Flyway
 * migrations (V6 included) on {@code postgres:15.1}, connected as {@code da_catalog_user}, with the
 * real event consumers, applier, coverage applier, retention evaluator, evidence recorder, emitter
 * and controller. Log Events are handed to the listener methods directly (no broker); each test
 * uses its own asset names. Every emitted object is validated with the networknt validator against
 * {@code docs/specs/schemas/lifecycle-state-0.1.json}. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = LifecycleStateContainerTest.Slice.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class LifecycleStateContainerTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final String PROJECT = "lse01-proj";
    private static final String BINDING = "gcp-audit-lse01";
    private static final String ENGINE = "dlap-executor@lse01-proj.iam.gserviceaccount.com";
    private static final String AUDIT_FORMAT = "GCP_CLOUD_AUDIT/audit-log";

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
        registry.add("dlap.catalog.lifecycle-state.engine-principals[0]", () -> ENGINE);
    }

    /** JPA, JDBC, Flyway, the ingest, coverage, retention and lifecycle beans; no Kafka, web or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    @EnableJpaRepositories(basePackages = {"com.dlap.catalog.repository", "com.dlap.common.repository"})
    @EnableConfigurationProperties({CatalogIngestProperties.class, ReadCoverageProperties.class,
            RetentionProperties.class, LifecycleStateProperties.class})
    @Import({CatalogEventDecoder.class, CatalogIngestMetrics.class, CanonicalUriIndex.class, AssetMatcher.class,
            AssetEventApplier.class, AssetLifecycleEventListener.class, DiscoveryAssetDiscoveredListener.class,
            ProcessedEventStore.class, AssetUsageDailyStore.class, DiscoveryAssetUpsertService.class,
            LineageService.class, DefaultLineageExtractor.class, MetadataHarvester.class,
            BindingStatusStore.class, BindingReadCoverageSource.class, AssetReadCoverageSource.class,
            ReadCoverageApplier.class, RetentionAssetStore.class, RetentionRuleStore.class, RetentionOutbox.class,
            RetentionMetrics.class, RetentionEvaluator.class,
            LifecycleEvidenceStore.class, LifecycleEvidenceRecorder.class, LifecycleStateEmitter.class,
            LifecycleStateController.class})
    static class Slice {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TestClock testClock() {
            return new TestClock(NOW);
        }

        /** The mapper production gets, without the Scala module spring-kafka-test adds to the test classpath. */
        @Bean
        HibernatePropertiesCustomizer productionJsonFormatMapper() {
            List<com.fasterxml.jackson.databind.Module> modules = ObjectMapper.findModules().stream()
                    .filter(m -> !m.getClass().getName().startsWith("com.fasterxml.jackson.module.scala"))
                    .toList();
            return props -> props.put(AvailableSettings.JSON_FORMAT_MAPPER,
                    new JacksonJsonFormatMapper(new ObjectMapper().registerModules(modules)));
        }
    }

    @MockitoBean
    private AssetService assetService;

    @Autowired
    private DiscoveryAssetDiscoveredListener discovered;

    @Autowired
    private AssetLifecycleEventListener lifecycle;

    @Autowired
    private ReadCoverageApplier coverageApplier;

    @Autowired
    private BindingStatusStore bindings;

    @Autowired
    private LifecycleStateEmitter emitter;

    @Autowired
    private LifecycleStateController controller;

    @Autowired
    private IAssetRepository assets;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TestClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        clock.set(NOW);
        bindings.upsert(new BindingStatus(BINDING, "GCP", "GCP_CLOUD_AUDIT", "GCP_PROJECT", PROJECT,
                "BINDING_STATE_ACTIVE", "READ_COVERAGE_CAPTURED", Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2026-10-31T11:00:00Z"), NOW, NOW), null);
    }

    // ------------------------------------------------------------------------------------
    // Real objects from real events: schema-valid, provenance from the stored ids
    // ------------------------------------------------------------------------------------

    @Test
    void anObjectBuiltFromAppliedEventsIsValidAndNamesExactlyThoseEvents() {
        String uri = "gs://lse01-full";
        Instant created = Instant.parse("2026-01-05T08:00:00Z");
        Instant read1 = Instant.parse("2026-10-01T09:00:00Z");
        Instant read2 = Instant.parse("2026-10-02T09:00:00.250Z");
        Instant write = Instant.parse("2026-10-03T07:30:00Z");
        Instant objectRead = Instant.parse("2026-10-04T10:00:00Z");
        Instant crawl = Instant.parse("2026-10-30T00:00:00Z");
        Instant observed = Instant.parse("2026-10-30T06:41:00Z");

        discovered.onMessage(audit("full-create", ChangeType.CREATE, EventCategory.EVENT_CATEGORY_MANAGEMENT, created,
                uri, null), ack());
        lifecycle.onAccessed(audit("full-read-1", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ, read1, uri,
                null), ack());
        lifecycle.onAccessed(audit("full-read-2a", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ, read2, uri,
                null), ack());
        lifecycle.onAccessed(audit("full-read-2b", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ, read2, uri,
                null), ack());
        lifecycle.onModified(audit("full-write", ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_DATA_WRITE, write, uri,
                null), ack());
        lifecycle.onAccessed(audit("full-object-read", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                objectRead, uri + "/exports/2026.csv", null), ack());
        discovered.onMessage(crawler("full-crawl", ChangeType.CREATE, crawl, uri, null, 52_428_800L), ack());
        applyCoverage(observation("full-cov", uri, ReadCoverageLevel.FULL, observed, "Data Access logs are on"));
        manualRule(uri, "CREATED", 30);

        JsonNode state = valid(emitOne(uri));

        assertThat(state.get("asset").asText()).isEqualTo(uri);
        assertThat(state.get("platform").get("provider").asText()).isEqualTo("GCP");
        assertThat(state.get("platform").get("kind").asText()).isEqualTo("GCS_BUCKET");
        assertThat(state.get("asOf").asText()).as("asOf is the injected clock").isEqualTo(NOW.toString());
        assertThat(state.get("createdAt").asText()).isEqualTo(created.toString());
        assertThat(state.get("lastReadAt").asText()).as("an object read rolls up to the bucket")
                .isEqualTo(objectRead.toString());
        assertThat(state.get("lastWrittenAt").asText()).isEqualTo(write.toString());
        assertThat(state.get("readCoverage").asText()).isEqualTo("FULL");
        assertThat(state.get("readCoverageSince").asText()).isEqualTo(observed.toString());
        assertThat(state.get("readCoverageReasons").get(0).asText()).isEqualTo("Data Access logs are on");
        assertThat(state.get("sizeBytes").asLong()).isEqualTo(52_428_800L);
        assertThat(state.get("sizeObservedAt").asText()).isEqualTo(crawl.toString());
        assertThat(state.get("retention").get("status").asText()).isEqualTo("DUE");
        assertThat(state.get("retention").get("basis").asText()).isEqualTo("CREATED");
        assertThat(state.get("retention").get("days").asInt()).isEqualTo(30);
        assertThat(state.get("retention").get("source").asText()).isEqualTo("MANUAL");
        assertThat(state.get("retention").get("expiresAt").asText()).isEqualTo(created.plusSeconds(30L * 86400).toString());
        assertThat(state.get("legalHold").asBoolean()).isFalse();
        assertThat(state.get("disposition").isNull()).isTrue();

        assertThat(provenanceFields(state)).containsExactly("createdAt", "lastReadAt", "lastWrittenAt", "readCoverage",
                "sizeBytes");
        assertThat(ids(state, "createdAt")).containsExactly(id("full-create"));
        assertThat(ids(state, "lastReadAt")).containsExactly(id("full-object-read"));
        assertThat(ids(state, "lastWrittenAt")).containsExactly(id("full-write"));
        assertThat(ids(state, "readCoverage")).containsExactly(id("full-cov"));
        assertThat(ids(state, "sizeBytes")).containsExactly(id("full-crawl"));
        JsonNode created0 = entry(state, "createdAt");
        assertThat(created0.get("channel").asText()).isEqualTo("AUDIT_LOG:GCP_CLOUD_AUDIT");
        assertThat(created0.get("bindingIds").get(0).asText()).isEqualTo(BINDING);
        assertThat(entry(state, "sizeBytes").get("channel").asText()).isEqualTo("CRAWLER");
        assertThat(entry(state, "sizeBytes").get("bindingIds")).isEmpty();
        assertThat(entry(state, "readCoverage").get("channel").asText()).isEqualTo("CRAWLER");
    }

    @Test
    void everyEventAtTheMaximumIsNamedInAscendingOrder() {
        String uri = "gs://lse01-tie";
        Instant tie = Instant.parse("2026-10-02T09:00:00.250Z");
        createBucket(uri, "2026-01-01T00:00:00Z");
        lifecycle.onAccessed(audit("tie-b", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ, tie, uri, null),
                ack());
        lifecycle.onAccessed(audit("tie-a", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ, tie, uri, null),
                ack());
        lifecycle.onAccessed(audit("tie-old", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                tie.minusSeconds(5), uri, null), ack());

        JsonNode state = valid(emitOne(uri));

        List<String> expected = new ArrayList<>(List.of(id("tie-a"), id("tie-b")));
        expected.sort(null);
        assertThat(ids(state, "lastReadAt")).as("both events at the maximum, ascending, the older one dropped")
                .isEqualTo(expected);
    }

    @Test
    void anAssetWithoutEvidenceIsAllUnknownNeverADefault() {
        assets.saveAndFlush(new Asset("gs://lse01-bare", "GCP", "GCS_BUCKET", "lse01-bare"));

        JsonNode state = valid(emitOne("gs://lse01-bare"));

        for (String member : List.of("createdAt", "lastReadAt", "lastWrittenAt", "readCoverage", "readCoverageSince",
                "sizeBytes", "sizeObservedAt", "disposition")) {
            assertThat(state.get(member).isNull()).as("%s stays null", member).isTrue();
        }
        assertThat(state.get("readCoverageReasons")).isEmpty();
        assertThat(state.get("provenance")).isEmpty();
        assertThat(state.get("retention").get("status").asText()).isEqualTo("NO_POLICY");
    }

    @Test
    void aStoredValueThatNoStoredEventProvesIsWithheldAsUnknown() {
        String uri = createBucket("gs://lse01-unproven", "2026-01-01T00:00:00Z");
        jdbc.update("UPDATE dlap_common.dlap_assets SET last_accessed_at = ?, size_bytes = 7, size_observed_at = ?, "
                        + "read_coverage = 'PARTIAL', read_coverage_reasons = '[\"a gap\"]'::jsonb, "
                        + "read_coverage_observed_at = ? WHERE asset_uri = ?",
                ts(Instant.parse("2026-10-01T00:00:00Z")), ts(Instant.parse("2026-10-01T00:00:00Z")),
                ts(Instant.parse("2026-10-01T00:00:00Z")), uri);
        double before = withheld("lastReadAt");

        JsonNode state = valid(emitOne(uri));

        assertThat(state.get("lastReadAt").isNull()).as("no stored id names this read").isTrue();
        assertThat(state.get("sizeBytes").isNull()).isTrue();
        assertThat(state.get("sizeObservedAt").isNull()).isTrue();
        assertThat(state.get("readCoverage").isNull()).isTrue();
        assertThat(state.get("readCoverageReasons")).isEmpty();
        assertThat(state.get("createdAt").asText()).as("the CREATE event still proves the creation time")
                .isEqualTo("2026-01-01T00:00:00Z");
        assertThat(withheld("lastReadAt")).isEqualTo(before + 1);
    }

    @Test
    void aReplayOfAnAppliedEventBackfillsItsEvidence() {
        String uri = createBucket("gs://lse01-replay", "2026-01-01T00:00:00Z");
        String read = audit("replay-read", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                Instant.parse("2026-10-10T00:00:00Z"), uri, null);
        lifecycle.onAccessed(read, ack());
        jdbc.update("DELETE FROM dlap_catalog.lifecycle_evidence WHERE field = 'lastReadAt' AND asset_id = ?",
                assetId(uri));
        assertThat(valid(emitOne(uri)).get("lastReadAt").isNull()).as("evidence lost, value unknown").isTrue();

        lifecycle.onAccessed(read, ack());

        JsonNode state = valid(emitOne(uri));
        assertThat(state.get("lastReadAt").asText()).isEqualTo("2026-10-10T00:00:00Z");
        assertThat(ids(state, "lastReadAt")).containsExactly(id("replay-read"));
    }

    @Test
    void aNewerReadReplacesTheEvidenceOfTheOlderMaximum() {
        String uri = createBucket("gs://lse01-move", "2026-01-01T00:00:00Z");
        lifecycle.onAccessed(audit("move-1", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                Instant.parse("2026-10-01T00:00:00Z"), uri, null), ack());
        lifecycle.onAccessed(audit("move-2", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                Instant.parse("2026-10-02T00:00:00Z"), uri, null), ack());

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.lifecycle_evidence "
                + "WHERE asset_id = ? AND field = 'lastReadAt'", Integer.class, assetId(uri));
        assertThat(rows).as("only the current value keeps evidence").isEqualTo(1);
        assertThat(ids(valid(emitOne(uri)), "lastReadAt")).containsExactly(id("move-2"));
    }

    @Test
    void aProviderExpiryReadByACrawlIsAFixedDateRuleWhoseProvenanceIsTheLatestCrawl() {
        String uri = createBucket("gs://lse01-provider", "2026-01-01T00:00:00Z");
        String expiry = "2026-09-30T00:00:00Z";
        discovered.onMessage(crawlerWith("provider-crawl-2", Instant.parse("2026-10-02T00:00:00Z"), uri,
                "expirationTime", expiry), ack());
        discovered.onMessage(crawlerWith("provider-crawl-1", Instant.parse("2026-10-01T00:00:00Z"), uri,
                "expirationTime", expiry), ack());

        JsonNode state = valid(emitOne(uri));

        JsonNode retention = state.get("retention");
        assertThat(retention.get("status").asText()).isEqualTo("DUE");
        assertThat(retention.get("basis").asText()).isEqualTo("FIXED_DATE");
        assertThat(retention.get("days").isNull()).isTrue();
        assertThat(retention.get("fixedDate").asText()).isEqualTo(expiry);
        assertThat(retention.get("expiresAt").asText()).isEqualTo(expiry);
        assertThat(retention.get("source").asText()).isEqualTo("PROVIDER");
        assertThat(ids(state, "retention")).as("the latest crawl that carried the setting, not the older one")
                .containsExactly(id("provider-crawl-2"));
        assertThat(entry(state, "retention").get("channel").asText()).isEqualTo("CRAWLER");
    }

    // ------------------------------------------------------------------------------------
    // Sub-microsecond event times: keyed and compared at the stored (microsecond) precision
    // ------------------------------------------------------------------------------------

    @Test
    void anAzureStyleSevenDigitReadTimeIsKeptAtTheStoredPrecision() {
        String uri = createBucket("gs://lse01-ns-read", "2025-01-01T00:00:00Z");
        Instant read = Instant.parse("2025-04-15T10:16:32.9873441Z");
        Instant observed = Instant.parse("2025-04-15T11:00:00.123456789Z");
        lifecycle.onAccessed(audit("ns-read", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ, read, uri,
                null), ack());
        applyCoverage(observation("ns-cov", uri, ReadCoverageLevel.PARTIAL, observed, "a prefix subset"));

        JsonNode state = valid(emitOne(uri));

        Instant stored = stored(uri, "last_accessed_at");
        assertThat(stored).as("the column keeps six digits, rounded down here")
                .isEqualTo(Instant.parse("2025-04-15T10:16:32.987344Z"));
        assertThat(state.get("lastReadAt").asText()).as("the known read time is emitted, not withheld")
                .isEqualTo(stored.toString());
        assertThat(ids(state, "lastReadAt")).containsExactly(id("ns-read"));
        assertThat(state.get("readCoverage").asText()).as("a nine-digit coverage observation still proves the level")
                .isEqualTo("PARTIAL");
        assertThat(ids(state, "readCoverage")).containsExactly(id("ns-cov"));
    }

    @Test
    void aNineDigitWriteTimeThatRoundsUpIsKeptAtTheStoredPrecision() {
        String uri = createBucket("gs://lse01-ns-write", "2026-01-01T00:00:00Z");
        Instant write = Instant.parse("2026-10-03T07:30:00.123456789Z");
        lifecycle.onModified(audit("ns-write", ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_DATA_WRITE, write, uri,
                null), ack());

        JsonNode state = valid(emitOne(uri));

        Instant stored = stored(uri, "last_modified_at");
        assertThat(stored).as("789 ns rounds up to the next microsecond")
                .isEqualTo(Instant.parse("2026-10-03T07:30:00.123457Z"));
        assertThat(state.get("lastWrittenAt").asText()).isEqualTo(stored.toString());
        assertThat(ids(state, "lastWrittenAt")).containsExactly(id("ns-write"));
    }

    @Test
    void aNineDigitCrawlerCreatedTimeAndSizeAreKeptAtTheStoredPrecision() {
        String uri = "gs://lse01-ns-crawl";
        Instant created = Instant.parse("2026-02-01T08:00:00.000000500Z");
        Instant crawl = Instant.parse("2026-10-30T00:00:00.999999499Z");
        discovered.onMessage(crawler("ns-crawl", ChangeType.CREATE, crawl, uri, created, 4_096L), ack());

        JsonNode state = valid(emitOne(uri));

        Instant storedCreated = stored(uri, "cloud_created_at");
        Instant storedObserved = stored(uri, "size_observed_at");
        assertThat(storedCreated).as("500 ns rounds up").isEqualTo(Instant.parse("2026-02-01T08:00:00.000001Z"));
        assertThat(storedObserved).as("499 ns rounds down").isEqualTo(Instant.parse("2026-10-30T00:00:00.999999Z"));
        assertThat(state.get("createdAt").asText()).isEqualTo(storedCreated.toString());
        assertThat(ids(state, "createdAt")).containsExactly(id("ns-crawl"));
        assertThat(state.get("sizeBytes").asLong()).isEqualTo(4_096L);
        assertThat(state.get("sizeObservedAt").asText()).isEqualTo(storedObserved.toString());
        assertThat(ids(state, "sizeBytes")).containsExactly(id("ns-crawl"));
    }

    @Test
    void aNineDigitDispositionIsKeptAtTheStoredPrecision() {
        String uri = createBucket("gs://lse01-ns-delete", "2026-01-01T00:00:00Z");
        Instant deleted = Instant.parse("2026-09-30T00:03:51.208000501Z");
        lifecycle.onDeleted(audit("ns-delete", ChangeType.DELETE, EventCategory.EVENT_CATEGORY_MANAGEMENT, deleted,
                uri, b -> b.setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM))
                        .setRequestMetadata(GCS_LIFECYCLE)), ack());

        JsonNode state = valid(emitOne(uri));

        Instant stored = stored(uri, "lifecycle_event_time");
        assertThat(stored).isEqualTo(Instant.parse("2026-09-30T00:03:51.208001Z"));
        JsonNode d = state.get("disposition");
        assertThat(d.get("action").asText()).isEqualTo("delete");
        assertThat(d.get("executedAt").asText()).isEqualTo(stored.toString());
        assertThat(d.get("executedBy").asText()).isEqualTo("native-rule");
        assertThat(ids(state, "disposition")).containsExactly(id("ns-delete"));
    }

    @Test
    void aReplayOfASubMicrosecondEventBackfillsItsEvidence() {
        String uri = createBucket("gs://lse01-ns-replay", "2025-01-01T00:00:00Z");
        String read = audit("ns-replay-read", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                Instant.parse("2025-04-15T10:16:32.9873446Z"), uri, null);
        lifecycle.onAccessed(read, ack());
        jdbc.update("DELETE FROM dlap_catalog.lifecycle_evidence WHERE field = 'lastReadAt' AND asset_id = ?",
                assetId(uri));
        assertThat(valid(emitOne(uri)).get("lastReadAt").isNull()).as("evidence lost, value unknown").isTrue();

        lifecycle.onAccessed(read, ack());

        JsonNode state = valid(emitOne(uri));
        Instant stored = stored(uri, "last_accessed_at");
        assertThat(stored).as("the reloaded row holds the rounded-up value")
                .isEqualTo(Instant.parse("2025-04-15T10:16:32.987345Z"));
        assertThat(state.get("lastReadAt").asText()).isEqualTo(stored.toString());
        assertThat(ids(state, "lastReadAt")).containsExactly(id("ns-replay-read"));
    }

    // ------------------------------------------------------------------------------------
    // Disposition and hold
    // ------------------------------------------------------------------------------------

    @Test
    void aProviderDeleteIsANativeRuleDispositionWithItsEvidence() {
        String uri = createBucket("gs://lse01-expired", "2026-01-01T00:00:00Z");
        Instant deleted = Instant.parse("2026-09-30T00:03:51.208Z");
        lifecycle.onDeleted(audit("expired-delete", ChangeType.DELETE, EventCategory.EVENT_CATEGORY_MANAGEMENT, deleted,
                uri, b -> b.setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM))
                        .setRequestMetadata(GCS_LIFECYCLE)), ack());

        JsonNode state = valid(emitOne(uri));

        JsonNode d = state.get("disposition");
        assertThat(d.get("action").asText()).isEqualTo("delete");
        assertThat(d.get("executedAt").asText()).isEqualTo(deleted.toString());
        assertThat(d.get("executedBy").asText()).isEqualTo("native-rule");
        assertThat(d.get("evidence").get(0).asText()).isEqualTo(id("expired-delete"));
        assertThat(ids(state, "disposition")).containsExactly(id("expired-delete"));
    }

    @Test
    void anArchiveByTheEnginesPrincipalIsAnEngineDisposition() {
        String uri = createBucket("gs://lse01-archived", "2026-01-01T00:00:00Z");
        lifecycle.onArchived(audit("engine-archive", ChangeType.ARCHIVE, EventCategory.EVENT_CATEGORY_MANAGEMENT,
                Instant.parse("2026-10-20T00:00:00Z"), uri, b -> b.setPrincipal(Principal.newBuilder()
                        .setType(PrincipalType.SERVICE_ACCOUNT).setId(ENGINE))), ack());

        JsonNode d = valid(emitOne(uri)).get("disposition");
        assertThat(d.get("action").asText()).isEqualTo("archive");
        assertThat(d.get("executedBy").asText()).isEqualTo("engine");
    }

    @Test
    void aDeleteByAPersonHasNoExecutorValueSoItsDispositionIsNull() {
        String uri = createBucket("gs://lse01-person", "2026-01-01T00:00:00Z");
        lifecycle.onDeleted(audit("person-delete", ChangeType.DELETE, EventCategory.EVENT_CATEGORY_MANAGEMENT,
                Instant.parse("2026-10-20T00:00:00Z"), uri, b -> b.setPrincipal(Principal.newBuilder()
                        .setType(PrincipalType.USER).setId("alice@example.com"))), ack());

        assertThat(assets.findByAssetUri(uri).orElseThrow().getStatus()).isEqualTo("DELETED");
        JsonNode state = valid(emitOne(uri));
        assertThat(state.get("disposition").isNull()).as("spec section 3.7: another actor has no value in 0.1").isTrue();
        assertThat(provenanceFields(state)).doesNotContain("disposition");
    }

    @Test
    void aHeldAssetIsOnHoldAndAHeldDeleteIsNeverEmitted() {
        String uri = createBucket("gs://lse01-held", "2026-01-01T00:00:00Z");
        lifecycle.onDeleted(audit("held-delete", ChangeType.DELETE, EventCategory.EVENT_CATEGORY_MANAGEMENT,
                Instant.parse("2026-10-20T00:00:00Z"), uri,
                b -> b.setPrincipal(Principal.newBuilder().setType(PrincipalType.SYSTEM))
                        .setRequestMetadata(GCS_LIFECYCLE)), ack());
        jdbc.update("UPDATE dlap_common.dlap_assets SET legal_hold = true, retention_status = 'ON_HOLD' "
                + "WHERE asset_uri = ?", uri);

        JsonNode state = valid(emitOne(uri));

        assertThat(state.get("legalHold").asBoolean()).isTrue();
        assertThat(state.get("retention").get("status").asText()).isEqualTo("ON_HOLD");
        assertThat(state.get("disposition").isNull()).as("R5: a held asset carries no delete").isTrue();
    }

    // ------------------------------------------------------------------------------------
    // R6, paging, the endpoint and the OKF export
    // ------------------------------------------------------------------------------------

    @Test
    void twoCallsAtTheSameAsOfGiveByteIdenticalJsonAndAsOfFollowsTheClock() {
        String uri = createBucket("gs://lse01-r6", "2026-01-01T00:00:00Z");
        lifecycle.onAccessed(audit("r6-read", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                Instant.parse("2026-10-10T00:00:00Z"), uri, null), ack());
        UUID id = assetId(uri);

        ResponseEntity<String> first = controller.getLifecycleState(id);
        ResponseEntity<String> second = controller.getLifecycleState(id);
        ResponseEntity<String> page1 = controller.listLifecycleStates(0, 500);
        ResponseEntity<String> page2 = controller.listLifecycleStates(0, 500);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isEqualTo(second.getBody());
        assertThat(page1.getBody()).isEqualTo(page2.getBody());
        valid(first.getBody());

        clock.set(NOW.plusSeconds(3600));
        String later = controller.getLifecycleState(id).getBody();
        assertThat(LifecycleStateSchema.read(later).get("asOf").asText()).isEqualTo("2026-10-31T13:00:00Z");
        assertThat(later.replace("2026-10-31T13:00:00Z", NOW.toString())).as("only asOf moved")
                .isEqualTo(first.getBody());
    }

    @Test
    void thePagedListReturnsValidObjectsAndNamesUnrepresentableAssets() {
        createBucket("gs://lse01-page", "2026-01-01T00:00:00Z");
        Asset legacy = assets.saveAndFlush(new Asset("lse01 Not A Bucket Name", "GCP", "GCS_BUCKET", "legacy"));

        JsonNode page = LifecycleStateSchema.read(controller.listLifecycleStates(0, 500).getBody());

        assertThat(page.get("asOf").asText()).isEqualTo(NOW.toString());
        assertThat(page.get("totalElements").asLong()).isEqualTo(assets.count());
        assertThat(page.get("items").size()).isPositive();
        for (JsonNode item : page.get("items")) {
            LifecycleStateSchema.assertValid(item.toString());
            assertThat(item.get("asOf").asText()).isEqualTo(NOW.toString());
        }
        assertThat(page.get("unrepresentable").toString()).contains(legacy.getAssetId().toString())
                .contains(LifecycleStateEmitter.ASSET_URI_NOT_CANONICAL);
        assertThat(controller.getLifecycleState(legacy.getAssetId()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(controller.getLifecycleState(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.listLifecycleStates(-1, 10).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.listLifecycleStates(0, 501).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theOkfExportOfRealObjectsPassesTheFrontmatterCheck() throws IOException {
        String uri = createBucket("gs://lse01-okf", "2026-01-01T00:00:00Z");
        lifecycle.onAccessed(audit("okf-read", ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ,
                Instant.parse("2026-10-10T00:00:00Z"), uri, null), ack());
        applyCoverage(observation("okf-cov", uri, ReadCoverageLevel.PARTIAL, Instant.parse("2026-10-30T06:41:00Z"),
                "data access logs cover a | prefix subset"));

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        controller.export(NOW, zip);
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        controller.export(NOW, again);

        assertThat(zip.toByteArray()).as("the same export twice is the same archive").isEqualTo(again.toByteArray());
        Map<String, String> files = OkfFrontmatterCheck.unzip(zip.toByteArray());
        long representable = LifecycleStateSchema.read(controller.listLifecycleStates(0, 500).getBody())
                .get("items").size();
        assertThat(files).hasSize((int) representable);
        files.forEach(OkfFrontmatterCheck::check);
        String okf = files.get("lifecycle-state/" + LogEventIds.sha256Hex(uri) + ".md");
        assertThat(okf).contains("tags:\n  - lifecycle-state\n  - gcp\n  - gcs-bucket\n  - read-coverage-partial\n")
                .contains("| Read coverage | PARTIAL: data access logs cover a \\| prefix subset |");
        JsonNode embedded = OkfFrontmatterCheck.check("lifecycle-state/" + LogEventIds.sha256Hex(uri) + ".md", okf);
        assertThat(LifecycleStateJson.compact(embedded)).as("the json block is the object the API returns")
                .isEqualTo(controller.getLifecycleState(assetId(uri)).getBody());
        assertThat(OkfExporter.path(LifecycleStateJson.parse(embedded)))
                .isEqualTo("lifecycle-state/" + LogEventIds.sha256Hex(uri) + ".md");
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private String emitOne(String uri) {
        LifecycleStateEmitter.Outcome outcome = emitter.emit(assetId(uri)).orElseThrow();
        assertThat(outcome.unrepresentable()).isNull();
        return LifecycleStateJson.compact(outcome.state());
    }

    private static JsonNode valid(String json) {
        LifecycleStateSchema.assertValid(json);
        return LifecycleStateSchema.read(json);
    }

    private Instant stored(String uri, String column) {
        java.sql.Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM dlap_common.dlap_assets "
                + "WHERE asset_uri = ?", java.sql.Timestamp.class, uri);
        assertThat(value).as("%s of %s", column, uri).isNotNull();
        return value.toInstant();
    }

    private UUID assetId(String uri) {
        return assets.findByAssetUri(uri).orElseThrow(() -> new AssertionError("no row for " + uri)).getAssetId();
    }

    private String createBucket(String uri, String createdAt) {
        String name = uri.substring("gs://".length());
        discovered.onMessage(audit("mk-" + name, ChangeType.CREATE, EventCategory.EVENT_CATEGORY_MANAGEMENT,
                Instant.parse(createdAt), uri, null), ack());
        return uri;
    }

    private void manualRule(String uri, String basis, int days) {
        jdbc.update("INSERT INTO dlap_catalog.retention_rule (rule_id, asset_id, source, basis, days, issued_at, "
                        + "created_at, updated_at) VALUES (?, ?, 'MANUAL', ?, ?, ?, ?, ?)", UUID.randomUUID(),
                assetId(uri), basis, days, ts(NOW), ts(NOW), ts(NOW));
    }

    private void applyCoverage(ReadCoverageObservation observation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> coverageApplier.apply(observation));
    }

    private static ReadCoverageObservation observation(String name, String root, ReadCoverageLevel level, Instant at,
                                                       String... reasons) {
        return new ReadCoverageObservation(id(name), "GCP", PROJECT, root, Set.of("GCS_BUCKET", "GCS_OBJECT"),
                "GCP_CLOUD_AUDIT", level, List.of(reasons), at, "gcp-audit-config-coverage@1.0.0");
    }

    /** The user agent of a GCS lifecycle action: the lifecycle signature a native-rule disposition needs (Q-654). */
    private static final RequestMetadata GCS_LIFECYCLE = RequestMetadata.newBuilder()
            .setUserAgent("GCS Lifecycle Management").build();

    private static String audit(String name, ChangeType type, EventCategory category, Instant time, String target,
                                Consumer<DlapLogEvent.Builder> extra) {
        DlapLogEvent.Builder b = DlapLogEvent.newBuilder()
                .setEventId(id(name))
                .setEventTime(tsProto(time))
                .setSourceProvider(SourceProvider.GCP)
                .setChangeType(type)
                .setEventCategory(category)
                .setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setSourceFormat(AUDIT_FORMAT)
                .setBindingId(BINDING)
                .setTargetResource(TargetResource.newBuilder().setId(target));
        if (extra != null) {
            extra.accept(b);
        }
        return CODEC.encode(b.build());
    }

    private static String crawler(String name, ChangeType type, Instant time, String target, Instant createdTime,
                                  Long size) {
        TargetResource.Builder t = TargetResource.newBuilder().setId(target);
        if (createdTime != null) {
            t.setCreatedTime(tsProto(createdTime));
        }
        if (size != null) {
            t.setSizeBytes(size);
        }
        return CODEC.encode(DlapLogEvent.newBuilder().setEventId(id(name)).setEventTime(tsProto(time))
                .setSourceProvider(SourceProvider.GCP).setChangeType(type).setOrigin(Origin.ORIGIN_CRAWLER)
                .setTargetResource(t).build());
    }

    private static String crawlerWith(String name, Instant time, String target, String key, String value) {
        return CODEC.encode(DlapLogEvent.newBuilder().setEventId(id(name)).setEventTime(tsProto(time))
                .setSourceProvider(SourceProvider.GCP).setChangeType(ChangeType.UPDATE).setOrigin(Origin.ORIGIN_CRAWLER)
                .setTargetResource(TargetResource.newBuilder().setId(target).putTechnicalMetadata(key, value)).build());
    }

    private static String id(String name) {
        return LogEventIds.sha256Hex("lse01-" + name);
    }

    private static List<String> provenanceFields(JsonNode state) {
        List<String> fields = new ArrayList<>();
        state.get("provenance").forEach(e -> fields.add(e.get("field").asText()));
        return fields;
    }

    private static JsonNode entry(JsonNode state, String field) {
        for (JsonNode e : state.get("provenance")) {
            if (e.get("field").asText().equals(field)) {
                return e;
            }
        }
        throw new AssertionError("no provenance entry for " + field);
    }

    private static List<String> ids(JsonNode state, String field) {
        List<String> ids = new ArrayList<>();
        entry(state, field).get("eventIds").forEach(i -> ids.add(i.asText()));
        return ids;
    }

    private double withheld(String field) {
        return Search.in(meters).name(LifecycleStateEmitter.METER_WITHHELD).tag("field", field).counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    private static Acknowledgment ack() {
        return mock(Acknowledgment.class);
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    private static Timestamp tsProto(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
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
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the platform init scripts", e);
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

    /** A settable clock: the source of {@code asOf}. */
    static final class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
