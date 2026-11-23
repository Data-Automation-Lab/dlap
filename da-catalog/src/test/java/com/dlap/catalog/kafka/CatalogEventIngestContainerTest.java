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

package com.dlap.catalog.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.catalog.idempotency.ProcessedEventPruner;
import com.dlap.catalog.idempotency.ProcessedEventStore;
import com.dlap.catalog.lineage.DefaultLineageExtractor;
import com.dlap.catalog.lineage.MetadataHarvester;
import com.dlap.catalog.service.AssetService;
import com.dlap.catalog.service.DiscoveryAssetUpsertService;
import com.dlap.catalog.service.LineageService;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.catalog.usage.AssetUsageDailyStore.UsageDay;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventDecodeException;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Timestamp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * CAT-02a acceptance against the real schema: the platform init scripts plus the catalog's
 * Flyway migrations (V3 included) on {@code postgres:15.1}, connected as
 * {@code da_catalog_user}, with the real consumers, applier, matcher, stores and enricher.
 * Records are handed to the listener methods directly (no broker), so each test controls
 * delivery order and redelivery.
 *
 * <p>No test-managed transaction: each listener call commits like it does in production.
 * Every test uses its own asset names, so tests are independent of order. Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = CatalogEventIngestContainerTest.IngestSlice.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class CatalogEventIngestContainerTest {

    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant BASE = Instant.parse("2026-10-19T09:00:00Z");
    private static final String GOVERNED_BUCKET = "cat02a-governed";

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
        registry.add("dlap.catalog.ingest.governed-prefixes", () -> "gs://" + GOVERNED_BUCKET + "/pii/");
        registry.add("dlap.catalog.ingest.match.index-on-startup", () -> "false");
        registry.add("dlap.catalog.ingest.match.catch-up-interval", () -> "PT0S");
    }

    /** The consumer slice: JPA, JDBC, Flyway and the CAT-02a beans; no Kafka, web or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    @EnableJpaRepositories(basePackages = {"com.dlap.catalog.repository", "com.dlap.common.repository"})
    @EnableConfigurationProperties(CatalogIngestProperties.class)
    @Import({CatalogEventDecoder.class, CatalogIngestMetrics.class, CanonicalUriIndex.class, AssetMatcher.class,
            AssetEventApplier.class, LogEventEnricher.class, AssetLifecycleEventListener.class,
            DiscoveryAssetDiscoveredListener.class, LogEventEnrichmentListener.class, ProcessedEventStore.class,
            AssetUsageDailyStore.class, DiscoveryAssetUpsertService.class, LineageService.class,
            DefaultLineageExtractor.class, MetadataHarvester.class})
    static class IngestSlice {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TestClock testClock() {
            return new TestClock(BASE);
        }

        /**
         * spring-kafka-test puts jackson-module-scala on the test classpath only, and
         * Hibernate's default JSON mapper registers every Jackson module it finds, so nested
         * jsonb objects would read back as Scala maps. The shipped service has no Scala module
         * on its runtime classpath; this gives the test the mapper production gets.
         *
         * @return the customizer
         */
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

    @MockitoSpyBean
    private MetadataHarvester metadataHarvester;

    @Autowired
    private DiscoveryAssetDiscoveredListener discovered;

    @Autowired
    private AssetLifecycleEventListener lifecycle;

    @Autowired
    private LogEventEnrichmentListener enrichment;

    @Autowired
    private IAssetRepository assets;

    @Autowired
    private AssetUsageDailyStore usage;

    @Autowired
    private ProcessedEventStore processedEvents;

    @Autowired
    private CatalogIngestProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TestClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetClock() {
        clock.set(BASE);
    }

    // ------------------------------------------------------------------------------------
    // Creation: canonical identity, provider and type from the ID, no default
    // ------------------------------------------------------------------------------------

    @Test
    void aCreateEventCreatesTheRowUnderItsCanonicalUri() {
        Acknowledgment ack = mock(Acknowledgment.class);
        Instant t = at("2026-09-01T10:00:00Z");

        discovered.onMessage(event("c1-create", ChangeType.CREATE, ResultStatus.SUCCESS, t, SourceProvider.GCP,
                "projects/_/buckets/cat02a-create-one", null), ack);

        Asset row = row("gs://cat02a-create-one");
        assertThat(row.getCloudProvider()).isEqualTo("GCP");
        assertThat(row.getServiceType()).isEqualTo("GCS_BUCKET");
        assertThat(row.getNativeAssetId()).isEqualTo("projects/_/buckets/cat02a-create-one");
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getLifecycleEventTime()).isEqualTo(t);
        assertThat(row.getCloudCreatedAt()).as("a log CREATE's event time is a creation time").isEqualTo(t);
        assertThat(row.getCloudCreatedSource()).isEqualTo("AUDIT_LOG");
        assertThat(row.getDiscoverySource()).isEqualTo("AUDIT_LOG");
        assertThat(row.getLastScannedAt()).as("operational time from the injected clock").isEqualTo(BASE);
        assertThat(processedAt("c1-create", row.getAssetId())).isEqualTo(BASE);
        assertThat(jdbc.queryForObject("SELECT canonical_uri FROM dlap_catalog.asset_uri_canonical WHERE asset_id = ?",
                String.class, row.getAssetId())).isEqualTo("gs://cat02a-create-one");
        assertThat(org.mockito.Mockito.mockingDetails(assetService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("publishAssetChangeEvent"))
                .map(i -> i.getArgument(1)))
                .as("ASSET_CREATED is published after the commit").containsExactly("ASSET_CREATED");
        verify(ack).acknowledge();
    }

    @Test
    void anIdentifierThatDoesNotNormalizeCreatesNoRowAndHasNoProviderDefault() {
        Acknowledgment ack = mock(Acknowledgment.class);
        double unmatched = count(CatalogIngestMetrics.UNMATCHED_TARGET, "kind", "UNRESOLVED");

        discovered.onMessage(event("c2-mystery", ChangeType.CREATE, ResultStatus.SUCCESS, at("2026-09-01T10:00:00Z"),
                SourceProvider.SOURCE_PROVIDER_UNSPECIFIED, "cat02a-mystery", null), ack);
        discovered.onMessage(event("c2-denied", ChangeType.CREATE, ResultStatus.FAILURE, at("2026-09-01T10:00:00Z"),
                SourceProvider.GCP, "gs://cat02a-denied", null), ack);

        assertThat(assets.findByAssetUri("cat02a-mystery")).isEmpty();
        assertThat(assets.findByAssetUri("gs://cat02a-mystery")).isEmpty();
        assertThat(assets.findByAssetUri("gs://cat02a-denied")).as("a failed CREATE creates nothing").isEmpty();
        assertThat(count(CatalogIngestMetrics.UNMATCHED_TARGET, "kind", "UNRESOLVED")).isEqualTo(unmatched + 1);
        assertThat(count(CatalogIngestMetrics.UNRESOLVED_TARGET, "reason", "UNRECOGNIZED")).isPositive();
        verify(ack, times(2)).acknowledge();
    }

    // ------------------------------------------------------------------------------------
    // last_accessed_at, usage and idempotency
    // ------------------------------------------------------------------------------------

    @Test
    void lastAccessedIsTheMaximumEventTimeOfSuccessfulReadsNeverProcessingTime() {
        String uri = createBucket("cat02a-reads", "2026-09-01T00:00:00Z");
        clock.set(Instant.parse("2027-01-01T00:00:00Z"));
        Instant newer = at("2026-09-03T12:00:00Z");
        Instant older = at("2026-09-02T12:00:00Z");

        lifecycle.onAccessed(read("r-newer", newer, uri), mock(Acknowledgment.class));
        lifecycle.onAccessed(doubleEncoded(read("r-older", older, uri)), mock(Acknowledgment.class));
        lifecycle.onAccessed(event("r-denied", ChangeType.ACCESS, ResultStatus.FAILURE, at("2026-09-04T00:00:00Z"),
                SourceProvider.GCP, uri, null), mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(row.getLastAccessedAt()).isEqualTo(newer);
        assertThat(row.getLastAccessSource()).isEqualTo("AUDIT_LOG");
        assertThat(readsOn(row, newer)).isEqualTo(1);
        assertThat(readsOn(row, older)).isEqualTo(1);
        assertThat(readsOn(row, at("2026-09-04T00:00:00Z"))).as("failures never count").isZero();
        assertThat(row.getDlapUsageMetrics()).as("legacy mirror holds event time and the deduped count")
                .containsEntry("lastAccessedAt", newer.toString())
                .containsEntry("accessCount", 2);
    }

    @Test
    void anObservedReadMovesLastAccessButIsNotUsage() {
        String uri = createBucket("cat02a-observed", "2026-09-01T00:00:00Z");
        Instant observed = at("2026-09-10T00:00:00Z");

        lifecycle.onAccessed(event("o-1", ChangeType.ACCESS, ResultStatus.SUCCESS, observed, SourceProvider.GCP, uri,
                b -> b.setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED)
                        .setSourceFormat("GCP_STORAGE_INSIGHTS/csv")), mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(row.getLastAccessedAt()).isEqualTo(observed);
        assertThat(row.getLastAccessSource()).isEqualTo("PROVIDER_METADATA:GCP_STORAGE_INSIGHTS");
        assertThat(readsOn(row, observed)).isZero();
    }

    @Test
    void redeliveringAReadThreeTimesLeavesEveryCountUnchanged() {
        String uri = createBucket("cat02a-redeliver", "2026-09-01T00:00:00Z");
        Instant t = at("2026-09-05T08:00:00Z");
        String payload = read("rd-1", t, uri);
        Acknowledgment ack = mock(Acknowledgment.class);

        lifecycle.onAccessed(payload, ack);
        lifecycle.onAccessed(payload, ack);
        lifecycle.onAccessed(doubleEncoded(payload), ack);
        lifecycle.onAccessed(payload, ack);

        Asset row = row(uri);
        assertThat(readsOn(row, t)).isEqualTo(1);
        assertThat(row.getDlapUsageMetrics()).containsEntry("accessCount", 1);
        assertThat(claims(ProcessedEventStore.CONSUMER_ASSET_STATE, "rd-1", row.getAssetId())).isEqualTo(1);
        assertThat(claims(ProcessedEventStore.CONSUMER_USAGE, "rd-1", row.getAssetId())).isEqualTo(1);
        verify(ack, times(4)).acknowledge();
    }

    /**
     * Q-783: a crash of da-logingest can leave two messages for one event_id on the spine (40001
     * messages for 40000 stored events in the shake-down run). Whatever the kind of event, the second
     * message, in either wire form, must leave the catalog exactly as the first one did: the asset row,
     * the usage counters, the claims and the lineage edges.
     */
    @Test
    void aSecondMessageOfOneEventIdChangesNothingForEveryKindOfEvent() throws Exception {
        String bucket = "q783-dup";
        String uri = createBucket(bucket, "2026-09-01T00:00:00Z");
        Asset upstream = assets.save(new Asset("gs://q783-dup-up", "GCP", "GCS_BUCKET", "up"));
        Acknowledgment ack = mock(Acknowledgment.class);
        String crawl = crawler("q783-crawl", ChangeType.UPDATE, at("2026-09-02T00:00:00Z"), uri,
                at("2026-09-01T00:00:00Z"), 4096L);
        String modify = event("q783-modify", ChangeType.UPDATE, ResultStatus.SUCCESS, at("2026-09-03T00:00:00Z"),
                SourceProvider.GCP, uri, null);
        String access = read("q783-read", at("2026-09-04T08:00:00Z"), uri);
        String write = event("q783-write", ChangeType.UPDATE, ResultStatus.SUCCESS, at("2026-09-04T09:00:00Z"),
                SourceProvider.GCP, uri, b -> b.setEventCategory(EventCategory.EVENT_CATEGORY_DATA_WRITE));
        String permission = event("q783-perm", ChangeType.PERMISSION, ResultStatus.SUCCESS,
                at("2026-09-05T00:00:00Z"), SourceProvider.GCP, uri, null);
        String lineage = lineagePayload("q783-lineage", at("2026-09-06T00:00:00Z"), "carol@data-eng.co",
                "gs://q783-dup-up", uri);
        String delete = event("q783-delete", ChangeType.DELETE, ResultStatus.SUCCESS, at("2026-09-07T00:00:00Z"),
                SourceProvider.GCP, uri, null);

        List<Map.Entry<String, Consumer<String>>> deliveries = List.of(
                Map.entry("crawler update", p -> lifecycle.onModified(p, ack)),
                Map.entry("management update", p -> lifecycle.onModified(p, ack)),
                Map.entry("read", p -> lifecycle.onAccessed(p, ack)),
                Map.entry("write", p -> lifecycle.onModified(p, ack)),
                Map.entry("permission change", p -> lifecycle.onPermissionChanged(p, ack)),
                Map.entry("lineage and harvest", p -> enrichment.onLogEvent(p, ack)),
                Map.entry("delete", p -> lifecycle.onDeleted(p, ack)));
        List<String> payloads = List.of(crawl, modify, access, write, permission, lineage, delete);

        for (int i = 0; i < payloads.size(); i++) {
            String kind = deliveries.get(i).getKey();
            Consumer<String> deliver = deliveries.get(i).getValue();
            String payload = payloads.get(i);

            deliver.accept(payload);
            Map<String, Object> afterOne = snapshot(uri, upstream);
            deliver.accept(payload);
            deliver.accept(doubleEncoded(payload));
            deliver.accept(payload);

            assertThat(snapshot(uri, upstream)).as("three more messages of the %s event change nothing", kind)
                    .isEqualTo(afterOne);
        }
        assertThat(claims(ProcessedEventStore.CONSUMER_ASSET_STATE, "q783-read", row(uri).getAssetId()))
                .isEqualTo(1);
        assertThat(readsOn(row(uri), at("2026-09-04T08:00:00Z"))).isEqualTo(1);
    }

    /**
     * RTR-01 (Q-824): the two catalog listeners that LOG-07 left without a duplicate test of their own.
     * A second message of one CREATE event_id on the discovered topic, and of one ARCHIVE event_id on
     * the archived topic, in either wire form, leaves the row, the usage, the claims and the edges as
     * the first message did, and publishes the "asset created" change event once.
     */
    @Test
    void aSecondMessageOfOneEventIdChangesNothingForTheDiscoveredAndArchivedListeners() {
        Asset upstream = assets.save(new Asset("gs://q824-dup-up", "GCP", "GCS_BUCKET", "up"));
        Acknowledgment ack = mock(Acknowledgment.class);
        String create = event("q824-create", ChangeType.CREATE, ResultStatus.SUCCESS, at("2026-09-01T00:00:00Z"),
                SourceProvider.GCP, "projects/_/buckets/q824-dup", null);
        String uri = "gs://q824-dup";

        discovered.onMessage(create, ack);
        Map<String, Object> afterCreate = snapshot(uri, upstream);
        discovered.onMessage(create, ack);
        discovered.onMessage(doubleEncoded(create), ack);
        discovered.onMessage(create, ack);

        assertThat(snapshot(uri, upstream)).as("three more messages of the CREATE event change nothing")
                .isEqualTo(afterCreate);
        assertThat(claims(ProcessedEventStore.CONSUMER_ASSET_STATE, "q824-create", row(uri).getAssetId())).isEqualTo(1);
        assertThat(org.mockito.Mockito.mockingDetails(assetService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("publishAssetChangeEvent"))
                .map(i -> i.getArgument(1)).filter("ASSET_CREATED"::equals).count())
                .as("one ASSET_CREATED for four messages").isEqualTo(1);

        String archive = event("q824-archive", ChangeType.ARCHIVE, ResultStatus.SUCCESS, at("2026-09-10T00:00:00Z"),
                SourceProvider.GCP, uri, null);
        lifecycle.onArchived(archive, ack);
        Map<String, Object> afterArchive = snapshot(uri, upstream);
        assertThat(row(uri).getStatus()).isEqualTo("ARCHIVED");
        lifecycle.onArchived(archive, ack);
        lifecycle.onArchived(doubleEncoded(archive), ack);
        lifecycle.onArchived(archive, ack);

        assertThat(snapshot(uri, upstream)).as("three more messages of the ARCHIVE event change nothing")
                .isEqualTo(afterArchive);
        assertThat(claims(ProcessedEventStore.CONSUMER_ASSET_STATE, "q824-archive", row(uri).getAssetId())).isEqualTo(1);
    }

    /**
     * The same, for the case Q-363 creates on purpose: a pass-through event and the full event the
     * replay makes of it share one event_id. The pass-through changes no state, so it must not claim the
     * id and the full event must still apply once (and only once).
     */
    @Test
    void thePassThroughOfAnEventIdDoesNotHideTheFullEventOfTheReplay() {
        String uri = createBucket("q783-partial", "2026-09-01T00:00:00Z");
        Acknowledgment ack = mock(Acknowledgment.class);
        Instant t = at("2026-09-08T08:00:00Z");
        String passThrough = event("q783-both", ChangeType.CHANGE_TYPE_UNSPECIFIED, ResultStatus.RESULT_STATUS_UNSPECIFIED,
                t, SourceProvider.GCP, uri, b -> b.setParseStatus(com.dlap.proto.log.v1.ParseStatus.PARSE_STATUS_PARTIAL));
        String full = read("q783-both", t, uri);

        lifecycle.onAccessed(passThrough, ack);
        assertThat(readsOn(row(uri), t)).as("a pass-through counts no read").isZero();
        lifecycle.onAccessed(full, ack);
        lifecycle.onAccessed(full, ack);
        lifecycle.onAccessed(passThrough, ack);

        assertThat(readsOn(row(uri), t)).as("the replayed full event counts once").isEqualTo(1);
        assertThat(claims(ProcessedEventStore.CONSUMER_USAGE, "q783-both", row(uri).getAssetId())).isEqualTo(1);
    }

    /** Everything the consumers write for one asset, as plain values, for a before and after comparison. */
    private Map<String, Object> snapshot(String uri, Asset upstream) {
        Asset row = row(uri);
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("row", jdbc.queryForMap("SELECT * FROM dlap_common.dlap_assets WHERE asset_id = ?",
                row.getAssetId()));
        snapshot.put("usage", jdbc.queryForList("SELECT * FROM dlap_catalog.asset_usage_daily WHERE asset_id = ? "
                + "ORDER BY day", row.getAssetId()));
        snapshot.put("claims", jdbc.queryForList("SELECT consumer, event_id FROM dlap_catalog.processed_event "
                + "WHERE asset_id = ? ORDER BY consumer, event_id", row.getAssetId()));
        snapshot.put("edges", edges(upstream.getAssetId(), row.getAssetId()));
        return snapshot;
    }

    @Test
    void copiesOfOneCrossAccountReadCountOnce() {
        String uri = createAwsBucket("cat02a-shared");
        Instant t = at("2026-09-06T08:00:00Z");
        Consumer<DlapLogEvent.Builder> shared = b -> b.setSharedEventId("shared-0001");

        lifecycle.onAccessed(event("sh-requester", ChangeType.ACCESS, ResultStatus.SUCCESS, t, SourceProvider.AWS,
                "s3://cat02a-shared/reports/q3.csv", shared), mock(Acknowledgment.class));
        lifecycle.onAccessed(event("sh-owner", ChangeType.ACCESS, ResultStatus.SUCCESS, t, SourceProvider.AWS,
                "s3://cat02a-shared/reports/q3.csv", shared), mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(readsOn(row, t)).isEqualTo(1);
        assertThat(row.getLastAccessedAt()).isEqualTo(t);
    }

    // ------------------------------------------------------------------------------------
    // Roll-up, governed prefixes, unknown targets
    // ------------------------------------------------------------------------------------

    @Test
    void objectEventsRollUpToTheirContainerAndCreateNoObjectRow() {
        String uri = createBucket("cat02a-rollup", "2026-09-01T00:00:00Z");
        Instant readAt = at("2026-09-07T10:00:00Z");
        Instant writeAt = at("2026-09-07T11:00:00Z");

        lifecycle.onAccessed(read("ru-read", readAt, "projects/_/buckets/cat02a-rollup/objects/data.csv"),
                mock(Acknowledgment.class));
        discovered.onMessage(event("ru-write", ChangeType.CREATE, ResultStatus.SUCCESS, writeAt, SourceProvider.GCP,
                "gs://cat02a-rollup/new.csv", null), mock(Acknowledgment.class));

        double unmatched = count(CatalogIngestMetrics.UNMATCHED_TARGET, "kind", "GCS_BUCKET");
        discovered.onMessage(crawler("ru-crawl", ChangeType.CREATE, at("2026-09-07T12:00:00Z"),
                "gs://cat02a-rollup/crawled.csv", null, 5L), mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(row.getLastAccessedAt()).isEqualTo(readAt);
        assertThat(row.getLastModifiedAt()).as("an object CREATE writes the container").isEqualTo(writeAt);
        assertThat(row.getSizeBytes()).as("size never rolls up from objects").isNull();
        assertThat(assets.findByAssetUri("gs://cat02a-rollup/crawled.csv")).as("a crawl of an object is no row")
                .isEmpty();
        assertThat(count(CatalogIngestMetrics.UNMATCHED_TARGET, "kind", "GCS_BUCKET")).isEqualTo(unmatched);
        assertThat(row.getLifecycleEventTime()).as("and is not a lifecycle event of the container")
                .isEqualTo(at("2026-09-01T00:00:00Z"));
        assertThat(readsOn(row, readAt)).isEqualTo(1);
        assertThat(writesOn(row, writeAt)).isEqualTo(1);
        assertThat(assets.findByAssetUri("gs://cat02a-rollup/new.csv")).isEmpty();
        assertThat(assets.findByAssetUri("gs://cat02a-rollup/data.csv")).isEmpty();
    }

    @Test
    void anObjectUnderAGovernedPrefixGetsItsOwnRowAndStillRollsUp() {
        assertThat(properties.getGovernedPrefixes()).containsExactly("gs://" + GOVERNED_BUCKET + "/pii/");
        String bucket = createBucket(GOVERNED_BUCKET, "2026-09-01T00:00:00Z");
        Instant created = at("2026-09-08T10:00:00Z");
        Instant readAt = at("2026-09-08T11:00:00Z");

        discovered.onMessage(event("gv-create", ChangeType.CREATE, ResultStatus.SUCCESS, created, SourceProvider.GCP,
                "gs://" + GOVERNED_BUCKET + "/pii/people.csv", null), mock(Acknowledgment.class));
        lifecycle.onAccessed(read("gv-read", readAt, "gs://" + GOVERNED_BUCKET + "/pii/people.csv"),
                mock(Acknowledgment.class));
        lifecycle.onAccessed(read("gv-other", readAt, "gs://" + GOVERNED_BUCKET + "/public/readme.txt"),
                mock(Acknowledgment.class));

        Asset object = row("gs://" + GOVERNED_BUCKET + "/pii/people.csv");
        assertThat(object.getServiceType()).isEqualTo("GCS_OBJECT");
        assertThat(object.getLastAccessedAt()).isEqualTo(readAt);
        assertThat(readsOn(object, readAt)).isEqualTo(1);
        Asset container = row(bucket);
        assertThat(container.getLastModifiedAt()).isEqualTo(created);
        assertThat(container.getLastAccessedAt()).isEqualTo(readAt);
        assertThat(readsOn(container, readAt)).isEqualTo(2);
        assertThat(assets.findByAssetUri("gs://" + GOVERNED_BUCKET + "/public/readme.txt")).isEmpty();
    }

    @Test
    void anUnknownTargetIsCountedChangesNothingAndAReplayAppliesItOnceTheRowExists() {
        double before = count(CatalogIngestMetrics.UNMATCHED_TARGET, "kind", "GCS_BUCKET");
        Instant t = at("2026-09-09T09:00:00Z");
        String access = read("un-1", t, "gs://cat02a-later");

        lifecycle.onAccessed(access, mock(Acknowledgment.class));

        assertThat(count(CatalogIngestMetrics.UNMATCHED_TARGET, "kind", "GCS_BUCKET")).isEqualTo(before + 1);
        assertThat(assets.findByAssetUri("gs://cat02a-later")).as("activity never creates rows").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.processed_event WHERE event_id = 'un-1'",
                Integer.class)).as("an unmatched event is not recorded, so a replay can apply it").isZero();

        String uri = createBucket("cat02a-later", "2026-09-01T00:00:00Z");
        lifecycle.onAccessed(access, mock(Acknowledgment.class));

        assertThat(row(uri).getLastAccessedAt()).isEqualTo(t);
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle ordering, incarnations, creation time, size
    // ------------------------------------------------------------------------------------

    @Test
    void statusFollowsEventTimeAndACreateAfterADeleteStartsANewIncarnation() {
        String uri = createBucket("cat02a-reuse", "2026-09-10T00:00:00Z");
        Acknowledgment ack = mock(Acknowledgment.class);

        lifecycle.onDeleted(event("lc-del", ChangeType.DELETE, ResultStatus.SUCCESS, at("2026-09-20T00:00:00Z"),
                SourceProvider.GCP, uri, null), ack);
        discovered.onMessage(event("lc-late-create", ChangeType.CREATE, ResultStatus.SUCCESS,
                at("2026-09-15T00:00:00Z"), SourceProvider.GCP, uri, null), ack);
        assertThat(row(uri).getStatus()).as("an older CREATE does not undo a newer DELETE").isEqualTo("DELETED");

        lifecycle.onAccessed(read("lc-gap-read", at("2026-09-25T00:00:00Z"), uri), ack);
        discovered.onMessage(event("lc-recreate", ChangeType.CREATE, ResultStatus.SUCCESS,
                at("2026-09-30T00:00:00Z"), SourceProvider.GCP, uri, null), ack);
        lifecycle.onDeleted(event("lc-late-del", ChangeType.DELETE, ResultStatus.SUCCESS, at("2026-09-28T00:00:00Z"),
                SourceProvider.GCP, uri, null), ack);
        lifecycle.onAccessed(read("lc-old-read", at("2026-09-12T00:00:00Z"), uri), ack);

        Asset row = row(uri);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getRecreatedAt()).isEqualTo(at("2026-09-30T00:00:00Z"));
        assertThat(row.getCloudCreatedAt()).isEqualTo(at("2026-09-30T00:00:00Z"));
        assertThat(row.getLifecycleEventTime()).isEqualTo(at("2026-09-30T00:00:00Z"));
        assertThat(row.getLastAccessedAt()).as("evidence before the new incarnation is gone").isNull();

        lifecycle.onAccessed(read("lc-new-read", at("2026-10-02T00:00:00Z"), uri), ack);
        assertThat(row(uri).getLastAccessedAt()).isEqualTo(at("2026-10-02T00:00:00Z"));
        assertThat(row(uri).getDlapUsageMetrics()).as("the legacy count is per incarnation: the late old read is out")
                .containsEntry("accessCount", 1);
        assertThat(readsOn(row(uri), at("2026-09-12T00:00:00Z"))).as("the daily history keeps it on its day")
                .isEqualTo(1);
    }

    @Test
    void aCrawlOfADeletedRowStartsTheIncarnationAtTheCrawlAndClearsEarlierDeliveredEvidence() {
        String uri = createAwsBucket("cat02a-recrawled");
        Acknowledgment ack = mock(Acknowledgment.class);

        lifecycle.onDeleted(event("rc-del", ChangeType.DELETE, ResultStatus.SUCCESS, at("2026-09-10T00:00:00Z"),
                SourceProvider.AWS, uri, null), ack);
        // A read of the recreated bucket is delivered before the crawl that sees it again.
        lifecycle.onAccessed(event("rc-early-read", ChangeType.ACCESS, ResultStatus.SUCCESS,
                at("2026-09-20T00:00:00Z"), SourceProvider.AWS, uri, null), ack);
        assertThat(row(uri).getLastAccessedAt()).isEqualTo(at("2026-09-20T00:00:00Z"));
        discovered.onMessage(crawler("rc-crawl", ChangeType.CREATE, at("2026-09-25T00:00:00Z"), uri,
                at("2026-09-15T00:00:00Z"), 30L), ack);

        Asset row = row(uri);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getRecreatedAt()).as("the CREATE's event time (ADR-0046 section 4), not created_time")
                .isEqualTo(at("2026-09-25T00:00:00Z"));
        assertThat(row.getCloudCreatedAt()).isEqualTo(at("2026-09-25T00:00:00Z"));
        assertThat(row.getCloudCreatedSource()).isEqualTo("CRAWLER");
        assertThat(row.getLifecycleEventTime()).isEqualTo(at("2026-09-25T00:00:00Z"));
        assertThat(row.getLastAccessedAt()).as("cleared by the new incarnation, as the contract says").isNull();
        assertThat(row.getSizeBytes()).as("the CREATE's own size belongs to the new incarnation").isEqualTo(30L);
        assertThat(row.getSizeObservedAt()).isEqualTo(at("2026-09-25T00:00:00Z"));
        assertThat(row.getDlapUsageMetrics()).containsEntry("accessCount", 0)
                .doesNotContainKey("lastAccessedAt");
        assertThat(readsOn(row, at("2026-09-20T00:00:00Z"))).as("the daily history keeps the read on its day")
                .isEqualTo(1);

        // Redelivering the early read changes nothing: it is older than the incarnation and was claimed.
        lifecycle.onAccessed(event("rc-early-read", ChangeType.ACCESS, ResultStatus.SUCCESS,
                at("2026-09-20T00:00:00Z"), SourceProvider.AWS, uri, null), ack);
        assertThat(row(uri).getLastAccessedAt()).isNull();
        assertThat(readsOn(row(uri), at("2026-09-20T00:00:00Z"))).isEqualTo(1);
    }

    @Test
    void aCrawlAfterAnArchiveLeavesTheRowArchived() {
        String uri = createAwsBucket("cat02a-archived-then-crawled");
        Instant archivedAt = at("2026-09-20T00:00:00Z");

        lifecycle.onArchived(event("ac-archive", ChangeType.ARCHIVE, ResultStatus.SUCCESS, archivedAt,
                SourceProvider.AWS, uri, null), mock(Acknowledgment.class));
        discovered.onMessage(crawler("ac-crawl", ChangeType.CREATE, at("2026-09-21T00:00:00Z"), uri,
                at("2026-09-01T00:00:00Z"), 10L), mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(row.getStatus()).as("a crawl is no transition").isEqualTo("ARCHIVED");
        assertThat(row.getLifecycleEventTime()).isEqualTo(archivedAt);
        assertThat(row.getSizeBytes()).as("the crawl's size still applies").isEqualTo(10L);
        assertThat(row.getSizeObservedAt()).isEqualTo(at("2026-09-21T00:00:00Z"));
    }

    @Test
    void anArchiveDeliveredAfterALaterCrawlStillArchivesTheRow() {
        String uri = createAwsBucket("cat02a-crawled-then-archived");
        Instant archivedAt = at("2026-09-20T00:00:00Z");

        discovered.onMessage(crawler("ca-crawl", ChangeType.CREATE, at("2026-09-21T00:00:00Z"), uri,
                at("2026-09-01T00:00:00Z"), null), mock(Acknowledgment.class));
        assertThat(row(uri).getLifecycleEventTime()).as("the crawl does not move the lifecycle time")
                .isEqualTo(at("2026-09-01T00:00:00Z"));
        lifecycle.onArchived(event("ca-archive", ChangeType.ARCHIVE, ResultStatus.SUCCESS, archivedAt,
                SourceProvider.AWS, uri, null), mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(row.getStatus()).as("the delayed archive is not stale").isEqualTo("ARCHIVED");
        assertThat(row.getLifecycleEventTime()).isEqualTo(archivedAt);
    }

    @Test
    void anArchiveFromBeforeTheCrawlThatCreatedTheRowStillApplies() {
        String uri = "s3://cat02a-first-seen-archived";

        discovered.onMessage(crawler("fs-crawl", ChangeType.CREATE, at("2026-09-21T00:00:00Z"), uri,
                at("2026-09-01T00:00:00Z"), null), mock(Acknowledgment.class));
        assertThat(row(uri).getLifecycleEventTime()).as("the reported creation, not the crawl")
                .isEqualTo(at("2026-09-01T00:00:00Z"));
        lifecycle.onArchived(event("fs-archive", ChangeType.ARCHIVE, ResultStatus.SUCCESS, at("2026-09-20T00:00:00Z"),
                SourceProvider.AWS, uri, null), mock(Acknowledgment.class));

        assertThat(row(uri).getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    void aCrawlLeavesAPendingDeletionRowPendingDeletion() {
        String uri = createAwsBucket("cat02a-pending-deletion");
        Asset pending = row(uri);
        pending.setStatus("PENDING_DELETION");
        assets.save(pending);

        discovered.onMessage(crawler("pd-crawl", ChangeType.CREATE, at("2026-09-21T00:00:00Z"), uri, null, null),
                mock(Acknowledgment.class));

        Asset row = row(uri);
        assertThat(row.getStatus()).isEqualTo("PENDING_DELETION");
        assertThat(row.getLifecycleEventTime()).isEqualTo(at("2026-09-01T00:00:00Z"));
    }

    @Test
    void cloudCreatedKeepsTheLeastKnownValueAndSizeKeepsTheNewestObservation() {
        String id = "s3://cat02a-created";
        discovered.onMessage(crawler("cr-1", ChangeType.CREATE, at("2026-09-10T00:00:00Z"), id,
                at("2026-03-01T00:00:00Z"), 100L), mock(Acknowledgment.class));
        lifecycle.onModified(crawler("cr-2", ChangeType.UPDATE, at("2026-09-11T00:00:00Z"), id,
                at("2026-02-01T00:00:00Z"), 120L), mock(Acknowledgment.class));
        lifecycle.onModified(crawler("cr-3", ChangeType.UPDATE, at("2026-09-09T00:00:00Z"), id,
                at("2026-04-01T00:00:00Z"), 50L), mock(Acknowledgment.class));
        discovered.onMessage(event("cr-log", ChangeType.CREATE, ResultStatus.SUCCESS, at("2026-05-01T00:00:00Z"),
                SourceProvider.AWS, id, null), mock(Acknowledgment.class));

        Asset row = row(id);
        assertThat(row.getCloudCreatedAt()).isEqualTo(at("2026-02-01T00:00:00Z"));
        assertThat(row.getCloudCreatedSource()).isEqualTo("CRAWLER");
        assertThat(row.getDiscoverySource()).isEqualTo("CRAWLER");
        assertThat(row.getSizeBytes()).isEqualTo(120L);
        assertThat(row.getSizeObservedAt()).isEqualTo(at("2026-09-11T00:00:00Z"));
        assertThat(row.getServiceType()).isEqualTo("S3_BUCKET");
        assertThat(row.getCloudProvider()).isEqualTo("AWS");
    }

    // ------------------------------------------------------------------------------------
    // normalize(existing asset_uri): rows stored before canonical IDs
    // ------------------------------------------------------------------------------------

    @Test
    void rowsStoredBeforeCanonicalIdsAreMatchedNotDuplicated() {
        Asset legacyBucket = assets.save(new Asset("cat02a-legacy-bkt", "GCP", "GCS_BUCKET", "cat02a-legacy-bkt"));
        Asset legacyTable = assets.save(new Asset("proj-legacy:sales.orders", "GCP", "BIGQUERY_TABLE", "orders"));
        double normalizedBefore = count(CatalogIngestMetrics.ASSET_MATCH, "method", "normalized");

        discovered.onMessage(crawler("lg-crawl", ChangeType.CREATE, at("2026-09-12T00:00:00Z"),
                "gs://cat02a-legacy-bkt", null, null), mock(Acknowledgment.class));
        lifecycle.onAccessed(read("lg-read-bkt", at("2026-09-12T01:00:00Z"), "projects/_/buckets/cat02a-legacy-bkt"),
                mock(Acknowledgment.class));
        lifecycle.onAccessed(read("lg-read-tbl", at("2026-09-12T02:00:00Z"),
                "projects/proj-legacy/datasets/sales/tables/orders"), mock(Acknowledgment.class));

        assertThat(assets.findByAssetUri("gs://cat02a-legacy-bkt")).as("no duplicate row").isEmpty();
        assertThat(assets.findById(legacyBucket.getAssetId()).orElseThrow().getLastAccessedAt())
                .isEqualTo(at("2026-09-12T01:00:00Z"));
        assertThat(assets.findById(legacyBucket.getAssetId()).orElseThrow().getLifecycleEventTime())
                .as("a crawl of an existing row is no transition").isNull();
        assertThat(assets.findById(legacyTable.getAssetId()).orElseThrow().getLastAccessedAt())
                .isEqualTo(at("2026-09-12T02:00:00Z"));
        assertThat(assets.findByAssetUri("bq://proj-legacy/sales/orders")).isEmpty();
        assertThat(count(CatalogIngestMetrics.ASSET_MATCH, "method", "normalized")).isGreaterThan(normalizedBefore);
    }

    // ------------------------------------------------------------------------------------
    // Enrichment: rollback to the error handler, monotonic harvest, lineage via normalization
    // ------------------------------------------------------------------------------------

    @Test
    void anEnrichmentFailureRollsEverythingBackAndAReplayAppliesItOnce() throws Exception {
        Asset up = assets.save(new Asset("gs://cat02a-lin-up", "GCP", "GCS_BUCKET", "up"));
        Asset down = assets.save(new Asset("bq://proj-lineage/ds/down", "GCP", "BIGQUERY_TABLE", "down"));
        String payload = lineagePayload("en-1", at("2026-09-13T00:00:00Z"), "alice@data-eng.co",
                "gs://cat02a-lin-up", "projects/proj-lineage/datasets/ds/tables/down");
        Acknowledgment ack = mock(Acknowledgment.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("harvest failed"))
                .doCallRealMethod().when(metadataHarvester).harvest(any());

        assertThatThrownBy(() -> enrichment.onLogEvent(payload, ack)).isInstanceOf(IllegalStateException.class);

        verify(ack, never()).acknowledge();
        assertThat(edges(up.getAssetId(), down.getAssetId())).as("the lineage write rolled back").isZero();
        assertThat(claims(ProcessedEventStore.CONSUMER_LINEAGE, "en-1", down.getAssetId())).isZero();
        assertThat(assets.findById(down.getAssetId()).orElseThrow().getOwnerInfo()).isNull();

        enrichment.onLogEvent(payload, ack);
        enrichment.onLogEvent(payload, ack);

        verify(ack, times(2)).acknowledge();
        assertThat(edges(up.getAssetId(), down.getAssetId())).isEqualTo(1);
        Asset enriched = assets.findById(down.getAssetId()).orElseThrow();
        assertThat(enriched.getOwnerInfo()).isEqualTo("alice@data-eng.co");
        assertThat(harvested(enriched)).containsEntry("harvestedOwner", "alice@data-eng.co")
                .containsEntry("harvestedEventTime", "2026-09-13T00:00:00Z");
        assertThat(claims(ProcessedEventStore.CONSUMER_ENRICHMENT, "en-1", down.getAssetId())).isEqualTo(1);
    }

    @Test
    void harvestKeepsTheLatestEventByEventTimeAndIgnoresFailures() throws Exception {
        Asset target = assets.save(new Asset("gs://cat02a-harvest", "GCP", "GCS_BUCKET", "harvest"));
        Acknowledgment ack = mock(Acknowledgment.class);

        enrichment.onLogEvent(lineagePayload("hv-new", at("2026-09-14T12:00:00Z"), "alice@data-eng.co",
                null, "gs://cat02a-harvest"), ack);
        enrichment.onLogEvent(lineagePayload("hv-old", at("2026-09-14T08:00:00Z"), "bob@ops.co",
                null, "gs://cat02a-harvest"), ack);
        enrichment.onLogEvent(CODEC.encode(DlapLogEvent.newBuilder().setEventId("hv-fail")
                .setEventTime(ts(at("2026-09-15T00:00:00Z"))).setSourceProvider(SourceProvider.GCP)
                .setChangeType(ChangeType.ACCESS).setResultStatus(ResultStatus.FAILURE)
                .setPrincipal(Principal.newBuilder().setId("mallory@evil.co").setType(PrincipalType.USER))
                .setTargetResource(TargetResource.newBuilder().setId("gs://cat02a-harvest")).build()), ack);

        Map<String, Object> harvested = harvested(assets.findById(target.getAssetId()).orElseThrow());
        assertThat(harvested).containsEntry("harvestedOwner", "alice@data-eng.co")
                .containsEntry("harvestedEventTime", "2026-09-14T12:00:00Z");
    }

    @Test
    void aPayloadThatIsNotALogEventGoesToTheErrorHandler() {
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThatThrownBy(() -> lifecycle.onAccessed("{not json", ack)).isInstanceOf(LogEventDecodeException.class);
        assertThatThrownBy(() -> enrichment.onLogEvent("[]", ack)).isInstanceOf(LogEventDecodeException.class);

        verify(ack, never()).acknowledge();
    }

    // ------------------------------------------------------------------------------------
    // processed_event pruning
    // ------------------------------------------------------------------------------------

    @Test
    void pruningRemovesClaimsOlderThanTheRetention() {
        UUID asset = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> processedEvents.claim("prune-test", "old", asset, null));
        clock.set(BASE.plus(Duration.ofDays(101)));
        tx.executeWithoutResult(s -> processedEvents.claim("prune-test", "new", asset, null));

        int deleted = new ProcessedEventPruner(processedEvents, properties, clock).pruneOnce();

        assertThat(deleted).isPositive();
        assertThat(processedEvents.isProcessed("prune-test", "old", asset)).isFalse();
        assertThat(processedEvents.isProcessed("prune-test", "new", asset)).isTrue();
        Boolean again = tx.execute(s -> processedEvents.claim("prune-test", "new", asset, null));
        assertThat(again).as("a remembered pair is still a duplicate").isFalse();
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private String createBucket(String bucket, String createdAt) {
        discovered.onMessage(event("mk-" + bucket, ChangeType.CREATE, ResultStatus.SUCCESS, at(createdAt),
                SourceProvider.GCP, "gs://" + bucket, null), mock(Acknowledgment.class));
        return "gs://" + bucket;
    }

    private String createAwsBucket(String bucket) {
        discovered.onMessage(event("mk-" + bucket, ChangeType.CREATE, ResultStatus.SUCCESS, at("2026-09-01T00:00:00Z"),
                SourceProvider.AWS, "s3://" + bucket, null), mock(Acknowledgment.class));
        return "s3://" + bucket;
    }

    private static String read(String id, Instant time, String target) {
        return event(id, ChangeType.ACCESS, ResultStatus.SUCCESS, time, SourceProvider.GCP, target, null);
    }

    private static String event(String id, ChangeType type, ResultStatus status, Instant time,
                                SourceProvider provider, String target, Consumer<DlapLogEvent.Builder> extra) {
        DlapLogEvent.Builder b = DlapLogEvent.newBuilder()
                .setEventId(id)
                .setEventTime(ts(time))
                .setSourceProvider(provider)
                .setChangeType(type)
                .setResultStatus(status)
                .setTargetResource(TargetResource.newBuilder().setId(target));
        if (extra != null) {
            extra.accept(b);
        }
        return CODEC.encode(b.build());
    }

    /** A crawler observation (origin CRAWLER, no result status, like the legacy bridge). */
    private static String crawler(String id, ChangeType type, Instant crawlTime, String target, Instant createdTime,
                                  Long size) {
        TargetResource.Builder t = TargetResource.newBuilder().setId(target);
        if (createdTime != null) {
            t.setCreatedTime(ts(createdTime));
        }
        if (size != null) {
            t.setSizeBytes(size);
        }
        return CODEC.encode(DlapLogEvent.newBuilder().setEventId(id).setEventTime(ts(crawlTime))
                .setSourceProvider(SourceProvider.AWS).setChangeType(type).setOrigin(Origin.ORIGIN_CRAWLER)
                .setTargetResource(t).build());
    }

    /** A normalized event with a principal and, optionally, the legacy lineage keys outside the contract. */
    private static String lineagePayload(String id, Instant time, String principal, String source, String target)
            throws Exception {
        ObjectNode node = (ObjectNode) JSON.readTree(CODEC.encode(DlapLogEvent.newBuilder()
                .setEventId(id).setEventTime(ts(time)).setSourceProvider(SourceProvider.GCP)
                .setChangeType(ChangeType.UPDATE).setResultStatus(ResultStatus.SUCCESS).setOperation("jobservice.insert")
                .setPrincipal(Principal.newBuilder().setId(principal).setDisplayName(principal)
                        .setType(PrincipalType.USER))
                .setTargetResource(TargetResource.newBuilder().setId(target)).build()));
        if (source != null) {
            node.putArray("sourceResources").add(source);
            node.putArray("targetResources").add(target);
        }
        // The legacy producers double-encode the normalized topic; the consumer reads both.
        return JSON.writeValueAsString(JSON.writeValueAsString(node));
    }

    private static String doubleEncoded(String json) {
        try {
            return JSON.writeValueAsString(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Asset row(String uri) {
        return assets.findByAssetUri(uri).orElseThrow(() -> new AssertionError("no row for " + uri));
    }

    private long readsOn(Asset asset, Instant time) {
        LocalDate day = AssetUsageDailyStore.dayOf(time);
        return usage.findDays(asset.getAssetId(), day, day).stream().mapToLong(UsageDay::reads).sum();
    }

    private long writesOn(Asset asset, Instant time) {
        LocalDate day = AssetUsageDailyStore.dayOf(time);
        return usage.findDays(asset.getAssetId(), day, day).stream().mapToLong(UsageDay::writes).sum();
    }

    private int claims(String consumer, String eventId, UUID assetId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.processed_event "
                + "WHERE consumer = ? AND event_id = ? AND asset_id = ?", Integer.class, consumer, eventId, assetId);
        return n == null ? 0 : n;
    }

    private Instant processedAt(String eventId, UUID assetId) {
        return jdbc.queryForObject("SELECT processed_at FROM dlap_catalog.processed_event "
                        + "WHERE consumer = ? AND event_id = ? AND asset_id = ?", OffsetDateTime.class,
                ProcessedEventStore.CONSUMER_ASSET_STATE, eventId, assetId).toInstant();
    }

    private int edges(UUID upstream, UUID downstream) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.dlap_asset_lineage "
                + "WHERE upstream_asset_id = ? AND downstream_asset_id = ?", Integer.class, upstream, downstream);
        return n == null ? 0 : n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> harvested(Asset asset) {
        Object bucket = asset.getBusinessMetadataJson() == null ? null
                : asset.getBusinessMetadataJson().get(LogEventEnricher.HARVESTED_KEY);
        return bucket instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private double count(String meter, String tag, String value) {
        return Search.in(meters).name(meter).tag(tag, value).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static Timestamp ts(Instant i) {
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

    /** A settable clock, so a test can move DLAP's operational time without touching event time. */
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
