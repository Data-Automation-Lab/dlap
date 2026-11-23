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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
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

import com.dlap.catalog.kafka.AssetMatcher;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogEventDecoder;
import com.dlap.catalog.kafka.CatalogIngestMetrics;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.retention.RetentionAdminService.Actor;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.dlap.proto.log.v1.BindingScope;
import com.dlap.proto.log.v1.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * CAT-03 acceptance against the real schema: the platform init scripts plus the catalog's Flyway
 * migrations (V4 included) on {@code postgres:15.1}, connected as {@code da_catalog_user}, with the
 * real stores, evaluator, sweep, publisher, listeners and admin service. The clock is injected, so
 * every date in an assertion is a date a person can check. No test-managed transaction: each call
 * commits like it does in production. Requires Docker.
 *
 * <p>Every test starts from an empty asset table and empty retention tables.
 */
@DataJpaTest
@ContextConfiguration(classes = RetentionSweepContainerTest.RetentionSlice.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class RetentionSweepContainerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Actor ALICE = new Actor("alice", UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
    private static final String PII = "pii-ret";
    private static final String HOLD_LABEL = "legal-hold-ret";

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
        registry.add("dlap.catalog.retention.label-defaults[0].label", () -> PII);
        registry.add("dlap.catalog.retention.label-defaults[0].days", () -> "365");
        registry.add("dlap.catalog.retention.label-defaults[0].basis", () -> "CREATED");
        registry.add("dlap.catalog.retention.legal-hold-label", () -> HOLD_LABEL);
    }

    /** JPA, JDBC, Flyway and the CAT-03 beans; no Kafka (the template is a mock), web or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    @EnableJpaRepositories(basePackages = {"com.dlap.catalog.repository", "com.dlap.common.repository"})
    @EnableConfigurationProperties({RetentionProperties.class, CatalogIngestProperties.class})
    @Import({RetentionAssetStore.class, RetentionRuleStore.class, BindingStatusStore.class,
            BindingReadCoverageSource.class, RetentionOutbox.class, RetentionAuditStore.class, RetentionMetrics.class,
            RetentionEvaluator.class, RetentionSweep.class, RetentionAdminService.class, RetentionDuePublisher.class,
            SetRetentionActionListener.class, BindingStatusListener.class, RetentionLockConfiguration.class,
            CatalogEventDecoder.class, CatalogIngestMetrics.class, CanonicalUriIndex.class, AssetMatcher.class})
    static class RetentionSlice {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TestClock testClock() {
            return new TestClock(NOW);
        }

        /**
         * spring-kafka-test puts jackson-module-scala on the test classpath only, and Hibernate's
         * default JSON mapper registers every Jackson module it finds, so jsonb objects would read
         * back as Scala maps. The shipped service has no Scala module; this gives the test the
         * mapper production gets (same customizer as CatalogEventIngestContainerTest).
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
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionSweep sweep;

    @Autowired
    private RetentionProperties properties;

    @Autowired
    private RetentionAdminService admin;

    @Autowired
    private RetentionDuePublisher publisher;

    @Autowired
    private RetentionOutbox outbox;

    @Autowired
    private RetentionAuditStore audit;

    @Autowired
    private RetentionRuleStore rules;

    @Autowired
    private BindingStatusListener bindingListener;

    @Autowired
    private SetRetentionActionListener actionListener;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dlap_common.dlap_asset_label_assignments");
        jdbc.update("DELETE FROM dlap_common.dlap_assets");
        jdbc.update("DELETE FROM dlap_common.dlap_labels WHERE label_name IN (?, ?)", PII, HOLD_LABEL);
        for (String table : List.of("retention_rule", "log_binding_status", "retention_outbox", "retention_audit")) {
            jdbc.update("DELETE FROM dlap_catalog." + table);
        }
        jdbc.update("DELETE FROM dlap_catalog.shedlock");
        clock.set(NOW);
        properties.setPageSize(200);
        properties.setEvaluatedAtRefresh(Duration.ofDays(1));
        reset(kafka);
    }

    // ------------------------------------------------------------------------------------
    // Seeding and reading
    // ------------------------------------------------------------------------------------

    private UUID seed(String uri, String created, String lastRead, String technicalJson) {
        return seed(uri, "GCP", "BIGQUERY_TABLE", "ACTIVE", created, lastRead, technicalJson, false);
    }

    private UUID seed(String uri, String provider, String type, String status, String created, String lastRead,
                      String technicalJson, boolean legalHold) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dlap_common.dlap_assets (asset_id, asset_uri, cloud_provider, service_type, "
                        + "asset_name, status, cloud_created_at, discovered_at, last_accessed_at, technical_metadata, "
                        + "legal_hold, retention_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                id, uri, provider, type, "name-" + uri, status, ts(created), ts(created == null ? "2026-01-01T00:00:00Z"
                        : created), ts(lastRead), technicalJson == null ? "{}" : technicalJson, legalHold,
                legalHold ? "ON_HOLD" : "UNKNOWN");
        return id;
    }

    private static OffsetDateTime ts(String iso) {
        return iso == null ? null : Instant.parse(iso).atOffset(ZoneOffset.UTC);
    }

    private UUID label(String name) {
        jdbc.update("INSERT INTO dlap_common.dlap_labels (label_name, label_category) VALUES (?, 'Governance') "
                + "ON CONFLICT (label_name) DO NOTHING", name);
        return jdbc.queryForObject("SELECT label_id FROM dlap_common.dlap_labels WHERE label_name = ?", UUID.class,
                name);
    }

    private void assign(UUID asset, String labelName) {
        jdbc.update("INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, source) "
                + "VALUES (?, ?, 'USER') ON CONFLICT DO NOTHING", asset, label(labelName));
    }

    private void unassign(UUID asset, String labelName) {
        jdbc.update("DELETE FROM dlap_common.dlap_asset_label_assignments WHERE asset_id = ? AND label_id = ?",
                asset, label(labelName));
    }

    private Map<String, Object> retention(UUID id) {
        return jdbc.queryForMap("SELECT retention_status, retention_days, retention_basis, retention_source, "
                + "retention_policy_id, retention_expires_at, retention_evaluated_at, legal_hold "
                + "FROM dlap_common.dlap_assets WHERE asset_id = ?", id);
    }

    private String status(UUID id) {
        return (String) retention(id).get("retention_status");
    }

    private Instant expires(UUID id) {
        Object v = retention(id).get("retention_expires_at");
        return v == null ? null : ((java.sql.Timestamp) v).toInstant();
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.retention_outbox", Long.class);
    }

    private List<String> outboxPayloads(UUID asset) {
        return jdbc.queryForList("SELECT payload FROM dlap_catalog.retention_outbox WHERE payload::jsonb ->> 'assetId' "
                + "= ? ORDER BY outbox_id", String.class, asset.toString());
    }

    private static String bindingJson(String id, String project, String coverage, String since, String low,
                                      String reported) throws Exception {
        LogBindingStatus.Builder b = LogBindingStatus.newBuilder()
                .setBindingId(id)
                .setSourceProvider(SourceProvider.GCP)
                .setSourceKind("GCP_CLOUD_AUDIT")
                .setScope(BindingScope.newBuilder().setScopeType("GCP_PROJECT").setScopeId(project))
                .setState(BindingState.BINDING_STATE_ACTIVE)
                .setReportedTime(proto(reported))
                .setReadCoverage(com.dlap.proto.log.v1.ReadCoverage.valueOf(coverage))
                .setSchemaVersion("v1");
        if (since != null) {
            b.setReadCoverageSince(proto(since));
        }
        if (low != null) {
            b.setLowWatermark(proto(low));
        }
        return JsonFormat.printer().omittingInsignificantWhitespace().print(b);
    }

    private static Timestamp proto(String iso) {
        Instant i = Instant.parse(iso);
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    // ------------------------------------------------------------------------------------
    // Schema (Flyway V4)
    // ------------------------------------------------------------------------------------

    @Test
    void v4CreatesTheFiveTablesInTheCatalogSchemaAndTheirConstraintsHold() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'dlap_catalog' ORDER BY table_name", String.class);
        assertThat(tables).contains("shedlock", "retention_rule", "log_binding_status", "retention_outbox",
                "retention_audit");

        UUID asset = seed("bq://p/d/ruleshape", "2025-01-01T00:00:00Z", null, null);
        String insert = "INSERT INTO dlap_catalog.retention_rule (rule_id, asset_id, source, basis, days, expires_at, "
                + "policy_id, issued_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), now())";
        // days with FIXED_DATE, no days with CREATED, a POLICY rule without a policy, a MANUAL rule with one.
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), asset, "MANUAL", "FIXED_DATE", 5,
                ts("2027-01-01T00:00:00Z"), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), asset, "MANUAL", "CREATED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), asset, "POLICY", "CREATED", 5, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), asset, "MANUAL", "CREATED", 5, null,
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), asset, "LABEL_DEFAULT", "CREATED", 5, null,
                null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOnePolicyRuleExistsPerAssetAndPolicy() {
        UUID asset = seed("bq://p/d/onepolicy", "2025-01-01T00:00:00Z", null, null);
        UUID policy = UUID.randomUUID();
        assertThat(rules.upsertPolicyRule(asset, policy, RetentionBasis.CREATED, 10, null, NOW)).isTrue();
        assertThat(rules.upsertPolicyRule(asset, policy, RetentionBasis.CREATED, 20, "soft", NOW.plusSeconds(1)))
                .isTrue();

        assertThat(rules.findByAsset(asset)).hasSize(1);
        assertThat(rules.findByAsset(asset).get(0).rule().days()).isEqualTo(20);
    }

    // ------------------------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------------------------

    @Test
    void theSweepEvaluatesEveryAssetFromAllRuleSourcesAndWritesOnlyTheRetentionColumns() {
        // Label default: PII, 365 days from creation.
        UUID due = seed("bq://p/d/due", "2025-01-01T00:00:00Z", null, null);
        UUID retained = seed("bq://p/d/retained", "2026-09-01T00:00:00Z", null, null);
        UUID noRule = seed("bq://p/d/norule", "2025-01-01T00:00:00Z", null, null);
        UUID deleted = seed("bq://p/d/deleted", "GCP", "BIGQUERY_TABLE", "DELETED", "2025-01-01T00:00:00Z", null,
                null, false);
        UUID lastAccessed = seed("bq://p/d/lastaccessed", "2023-01-01T00:00:00Z", "2024-01-01T00:00:00Z", null);
        assign(due, PII);
        assign(retained, PII);
        assign(deleted, PII);
        rules.insertManual(lastAccessed, RetentionBasis.LAST_ACCESSED, 90, null, "test", "alice");
        Map<String, Object> before = jdbc.queryForMap("SELECT asset_name, technical_metadata::text AS tm, "
                + "cloud_created_at, last_accessed_at, status FROM dlap_common.dlap_assets WHERE asset_id = ?", due);

        RetentionSweep.Result r = sweep.run();

        assertThat(r.scanned()).isEqualTo(4);
        assertThat(r.errors()).isZero();
        assertThat(status(due)).isEqualTo("DUE");
        assertThat(expires(due)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(retention(due).get("retention_basis")).isEqualTo("CREATED");
        assertThat(retention(due).get("retention_source")).isEqualTo("LABEL_DEFAULT");
        assertThat(retention(due).get("retention_days")).isEqualTo(365);
        assertThat(status(retained)).isEqualTo("RETAINED");
        assertThat(expires(retained)).isEqualTo(Instant.parse("2027-09-01T00:00:00Z"));
        assertThat(status(noRule)).isEqualTo("NO_POLICY");
        assertThat(expires(noRule)).isNull();
        // No read signal: an old last read is not "not read".
        assertThat(status(lastAccessed)).isEqualTo("UNKNOWN");
        assertThat(retention(lastAccessed).get("retention_evaluated_at")).isNotNull();
        // A DELETED asset is not evaluated.
        assertThat(status(deleted)).isEqualTo("UNKNOWN");
        assertThat(retention(deleted).get("retention_evaluated_at")).isNull();
        // Nothing but retention_* changed.
        Map<String, Object> after = jdbc.queryForMap("SELECT asset_name, technical_metadata::text AS tm, "
                + "cloud_created_at, last_accessed_at, status FROM dlap_common.dlap_assets WHERE asset_id = ?", due);
        assertThat(after).isEqualTo(before);
        assertThat(retention(due).get("retention_evaluated_at")).isEqualTo(java.sql.Timestamp.from(NOW));
    }

    @Test
    void aSecondRunWithTheSameClockWritesNothingAndQueuesNoSecondEvent() {
        UUID due = seed("bq://p/d/idem-due", "2025-01-01T00:00:00Z", null, null);
        UUID noRule = seed("bq://p/d/idem-none", "2025-01-01T00:00:00Z", null, null);
        assign(due, PII);
        RetentionSweep.Result first = sweep.run();
        long events = outboxCount();
        String xminDue = jdbc.queryForObject("SELECT xmin::text FROM dlap_common.dlap_assets WHERE asset_id = ?",
                String.class, due);
        String xminNone = jdbc.queryForObject("SELECT xmin::text FROM dlap_common.dlap_assets WHERE asset_id = ?",
                String.class, noRule);

        RetentionSweep.Result second = sweep.run();

        assertThat(first.changed()).isEqualTo(2);
        assertThat(first.transitions()).isEqualTo(2);
        assertThat(events).isEqualTo(2);
        assertThat(second.scanned()).isEqualTo(2);
        assertThat(second.changed()).isZero();
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(second.transitions()).isZero();
        assertThat(outboxCount()).isEqualTo(events);
        // xmin is the id of the transaction that last wrote the row: unchanged means no UPDATE ran.
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM dlap_common.dlap_assets WHERE asset_id = ?",
                String.class, due)).isEqualTo(xminDue);
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM dlap_common.dlap_assets WHERE asset_id = ?",
                String.class, noRule)).isEqualTo(xminNone);
    }

    @Test
    void aLaterRunRefreshesAnUnchangedAssetOnlyAfterTheRefreshAgeAndNeverQueuesAnEventForIt() {
        UUID noRule = seed("bq://p/d/refresh", "2025-01-01T00:00:00Z", null, null);
        sweep.run();
        long events = outboxCount();

        clock.set(NOW.plus(Duration.ofHours(23)));
        RetentionSweep.Result within = sweep.run();
        clock.set(NOW.plus(Duration.ofHours(25)));
        RetentionSweep.Result beyond = sweep.run();

        assertThat(within.changed()).isZero();
        assertThat(beyond.changed()).isEqualTo(1);
        assertThat(beyond.transitions()).isZero();
        assertThat(outboxCount()).isEqualTo(events);
        assertThat(retention(noRule).get("retention_evaluated_at"))
                .isEqualTo(java.sql.Timestamp.from(NOW.plus(Duration.ofHours(25))));
    }

    @Test
    void advancingTheClockPastTheExpiryTurnsRetainedIntoDueWithExactlyOneEvent() {
        // Created 2026-09-01 + 365 days = 2027-09-01.
        UUID asset = seed("bq://p/d/clock", "2026-09-01T00:00:00Z", null, null);
        assign(asset, PII);
        sweep.run();
        assertThat(status(asset)).isEqualTo("RETAINED");

        clock.set(Instant.parse("2027-08-31T23:59:59Z"));
        sweep.run();
        assertThat(status(asset)).isEqualTo("RETAINED");
        clock.set(Instant.parse("2027-09-01T00:00:00Z"));
        sweep.run();
        clock.set(Instant.parse("2027-09-02T00:00:00Z"));
        sweep.run();

        assertThat(status(asset)).isEqualTo("DUE");
        assertThat(expires(asset)).isEqualTo(Instant.parse("2027-09-01T00:00:00Z"));
        List<String> payloads = outboxPayloads(asset);
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).contains("\"previousStatus\":\"UNKNOWN\"").contains("\"status\":\"RETAINED\"");
        assertThat(payloads.get(1)).contains("\"previousStatus\":\"RETAINED\"").contains("\"status\":\"DUE\"")
                .contains("\"statusReason\":\"EXPIRY_PASSED\"").contains("\"retentionDays\":365")
                .contains("\"retentionSource\":\"LABEL_DEFAULT\"").contains("\"expiresAt\":\"2027-09-01T00:00:00Z\"")
                .contains("\"evaluatedAt\":\"2027-09-01T00:00:00Z\"");
    }

    @Test
    void aSmallPageSizeVisitsEveryAssetExactlyOnce() {
        properties.setPageSize(2);
        for (int n = 0; n < 7; n++) {
            seed("bq://p/d/page-" + n, "2025-01-01T00:00:00Z", null, null);
        }

        RetentionSweep.Result r = sweep.run();

        assertThat(r.scanned()).isEqualTo(7);
        assertThat(r.pages()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_common.dlap_assets WHERE retention_status = "
                + "'NO_POLICY'", Long.class)).isEqualTo(7);
    }

    @Test
    void anAssetWithoutAnyRuleOrEvidenceIsNeverDueOrRetained() {
        UUID asset = seed("bq://p/d/unknown", null, null, null);
        jdbc.update("UPDATE dlap_common.dlap_assets SET cloud_created_at = NULL, discovered_at = NULL, "
                + "dlap_discovered_ts = NULL WHERE asset_id = ?", asset);
        rules.insertManual(asset, RetentionBasis.CREATED, 30, null, null, "alice");

        sweep.run();

        assertThat(status(asset)).isEqualTo("UNKNOWN");
        assertThat(expires(asset)).isNull();
    }

    // ------------------------------------------------------------------------------------
    // The read signal
    // ------------------------------------------------------------------------------------

    @Test
    void aLastAccessedRuleBecomesDueOnlyWhileTheBindingCapturesReads() throws Exception {
        UUID asset = seed("bq://proj-x/ds/t", "2024-01-01T00:00:00Z", "2025-03-01T00:00:00Z", null);
        rules.insertManual(asset, RetentionBasis.LAST_ACCESSED, 90, null, "idle tables", "alice");

        // No binding at all: not read since 2025-03-01 cannot be shown.
        sweep.run();
        assertThat(status(asset)).isEqualTo("UNKNOWN");

        // A binding that captures reads since 2025-01-01, complete through now: DUE, expiry 2025-05-30.
        bindingListener.apply(bindingJson("b1", "proj-x", "READ_COVERAGE_CAPTURED", "2025-01-01T00:00:00Z",
                "2026-10-20T12:00:00Z", "2026-10-20T12:00:00Z"), "b1");
        clock.set(NOW.plusSeconds(60));
        sweep.run();
        assertThat(status(asset)).isEqualTo("DUE");
        assertThat(expires(asset)).isEqualTo(Instant.parse("2025-05-30T00:00:00Z"));

        // The binding reports that reads are no longer observed: back to UNKNOWN, one more event.
        bindingListener.apply(bindingJson("b1", "proj-x", "READ_COVERAGE_NONE_OBSERVED", "2025-01-01T00:00:00Z",
                "2026-10-20T12:01:00Z", "2026-10-20T12:01:00Z"), "b1");
        clock.set(NOW.plusSeconds(120));
        sweep.run();
        assertThat(status(asset)).isEqualTo("UNKNOWN");

        // An older report arriving late changes nothing.
        bindingListener.apply(bindingJson("b1", "proj-x", "READ_COVERAGE_CAPTURED", "2025-01-01T00:00:00Z",
                "2026-10-20T12:00:00Z", "2026-10-20T12:00:00Z"), "b1");
        clock.set(NOW.plusSeconds(180));
        sweep.run();
        assertThat(status(asset)).isEqualTo("UNKNOWN");

        // UNKNOWN to UNKNOWN is not a transition, so the first sweep queued nothing.
        List<String> payloads = outboxPayloads(asset);
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).contains("\"previousStatus\":\"UNKNOWN\"").contains("\"status\":\"DUE\"");
        assertThat(payloads.get(1)).contains("\"previousStatus\":\"DUE\"").contains("\"status\":\"UNKNOWN\"")
                .contains("READ_COVERAGE_NOT_CAPTURED");
    }

    @Test
    void aTombstoneEndsABindingsCoverage() throws Exception {
        UUID asset = seed("bq://proj-y/ds/t", "2024-01-01T00:00:00Z", "2025-03-01T00:00:00Z", null);
        rules.insertManual(asset, RetentionBasis.LAST_ACCESSED, 90, null, null, "alice");
        bindingListener.apply(bindingJson("b2", "proj-y", "READ_COVERAGE_CAPTURED", "2025-01-01T00:00:00Z",
                "2026-10-20T12:00:00Z", "2026-10-20T12:00:00Z"), "b2");
        sweep.run();
        assertThat(status(asset)).isEqualTo("DUE");

        bindingListener.apply(null, "b2");
        clock.set(NOW.plusSeconds(60));
        sweep.run();

        assertThat(status(asset)).isEqualTo("UNKNOWN");
    }

    // ------------------------------------------------------------------------------------
    // Rule sources
    // ------------------------------------------------------------------------------------

    @Test
    void providerRulesCombineWithLabelDefaultsAndTheLongestExpiryDecides() {
        // Created 2025-01-01: label default +365d = 2026-01-01 (expired); provider period +3650d = 2034-12-30.
        UUID withPeriod = seed("bq://p/d/provider-period", "2025-01-01T00:00:00Z", null, "{\"retentionPeriodDays\":3650}");
        // Provider expiry instant 2028-06-30 retains until then.
        UUID withExpiry = seed("bq://p/d/provider-expiry", "2020-01-01T00:00:00Z", null,
                "{\"expirationTime\":\"2028-06-30T00:00:00Z\"}");
        // An unreadable provider setting fails closed: not DUE although the label default expired.
        UUID unreadable = seed("bq://p/d/provider-bad", "2025-01-01T00:00:00Z", null,
                "{\"expirationTime\":\"tomorrow\"}");
        assign(withPeriod, PII);
        assign(withExpiry, PII);
        assign(unreadable, PII);

        sweep.run();

        assertThat(status(withPeriod)).isEqualTo("RETAINED");
        assertThat(retention(withPeriod).get("retention_source")).isEqualTo("PROVIDER");
        assertThat(expires(withPeriod)).isEqualTo(Instant.parse("2034-12-30T00:00:00Z"));
        assertThat(status(withExpiry)).isEqualTo("RETAINED");
        assertThat(retention(withExpiry).get("retention_basis")).isEqualTo("FIXED_DATE");
        assertThat(retention(withExpiry).get("retention_days")).isNull();
        assertThat(expires(withExpiry)).isEqualTo(Instant.parse("2028-06-30T00:00:00Z"));
        assertThat(status(unreadable)).isEqualTo("UNKNOWN");
    }

    @Test
    void aSetRetentionActionIsRecordedAsAPolicyRuleReplacedByALaterOneAndIgnoredWhenEarlier() {
        UUID asset = seed("bq://p/d/policy", "2025-01-01T00:00:00Z", null, null);
        UUID policy = UUID.randomUUID();
        String first = action(policy, asset, 30, "2026-10-20T10:00:00Z");

        assertThat(actionListener.handle(first)).isEqualTo(SetRetentionActionListener.Outcome.RECORDED);
        // Created 2025-01-01 + 30d from CREATED: the action defaults to LAST_ACCESSED, which has no
        // read signal, so the asset is UNKNOWN and the rule is stored at its source.
        assertThat(status(asset)).isEqualTo("UNKNOWN");
        assertThat(rules.findByAsset(asset)).hasSize(1);

        // A repeat stores the same rule again (one row); an action fired earlier does not replace it.
        assertThat(actionListener.handle(first)).isEqualTo(SetRetentionActionListener.Outcome.RECORDED);
        assertThat(actionListener.handle(action(policy, asset, 99, "2026-10-20T09:00:00Z")))
                .isEqualTo(SetRetentionActionListener.Outcome.STALE);
        assertThat(rules.findByAsset(asset).get(0).rule().days()).isEqualTo(30);

        // A later action of the same policy replaces the rule: 5 days from creation is long past.
        String later = action(policy, asset, 5, "2026-10-20T11:00:00Z").replace("\"retentionDays\":5",
                "\"retentionDays\":5,\"basis\":\"CREATED\"");
        assertThat(actionListener.handle(later)).isEqualTo(SetRetentionActionListener.Outcome.RECORDED);

        assertThat(rules.findByAsset(asset)).hasSize(1);
        assertThat(status(asset)).isEqualTo("DUE");
        assertThat(retention(asset).get("retention_source")).isEqualTo("POLICY");
        assertThat(retention(asset).get("retention_policy_id")).isEqualTo(policy);
        assertThat(expires(asset)).isEqualTo(Instant.parse("2025-01-06T00:00:00Z"));
    }

    private static String action(UUID policy, UUID asset, int days, String firedAt) {
        return "{\"schemaVersion\":\"v1\",\"policyId\":\"retain\",\"policyDbId\":\"" + policy + "\","
                + "\"actionType\":\"setRetention\",\"assetId\":\"" + asset + "\",\"firedAt\":\"" + firedAt + "\","
                + "\"actionParameters\":{\"retentionDays\":" + days + "}}";
    }

    // ------------------------------------------------------------------------------------
    // Hold, manual rules, audit
    // ------------------------------------------------------------------------------------

    @Test
    void aLegalHoldTurnsDueIntoOnHoldAndReleaseEvaluatesAtOnce() {
        UUID asset = seed("bq://p/d/hold", "2025-01-01T00:00:00Z", null, null);
        assign(asset, PII);
        sweep.run();
        assertThat(status(asset)).isEqualTo("DUE");

        clock.set(NOW.plusSeconds(60));
        AssetRetentionView held = admin.placeLegalHold(asset, "litigation 42", ALICE);

        assertThat(held.retentionStatus()).isEqualTo("ON_HOLD");
        assertThat(held.legalHold()).isTrue();
        assertThat(retention(asset).get("legal_hold")).isEqualTo(true);
        // The rules alone still say DUE, kept for information.
        assertThat(expires(asset)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        // A sweep never takes the asset out of the hold.
        clock.set(NOW.plusSeconds(120));
        sweep.run();
        assertThat(status(asset)).isEqualTo("ON_HOLD");

        clock.set(NOW.plusSeconds(180));
        AssetRetentionView released = admin.releaseLegalHold(asset, "case closed", ALICE);

        assertThat(released.retentionStatus()).isEqualTo("DUE");
        assertThat(released.legalHold()).isFalse();
        assertThat(retention(asset).get("retention_evaluated_at"))
                .isEqualTo(java.sql.Timestamp.from(NOW.plusSeconds(180)));
        List<String> payloads = outboxPayloads(asset);
        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(1)).contains("\"previousStatus\":\"DUE\"").contains("\"status\":\"ON_HOLD\"")
                .contains("\"legalHold\":true").contains("HOLD_PLACED");
        assertThat(payloads.get(2)).contains("\"previousStatus\":\"ON_HOLD\"").contains("\"status\":\"DUE\"")
                .contains("\"legalHold\":false");
        assertThat(audit.findByAsset(asset)).extracting(RetentionAuditStore.Entry::action)
                .containsExactly("HOLD_PLACED", "HOLD_RELEASED");
        assertThat(audit.findByAsset(asset).get(0).actor()).isEqualTo("alice");
        assertThat(audit.findByAsset(asset).get(0).reason()).isEqualTo("litigation 42");
        assertThat(audit.findByAsset(asset).get(0).occurredAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void repeatingTheHoldCallsChangesNothingAndWritesNoSecondAuditRow() {
        UUID asset = seed("bq://p/d/hold-twice", "2025-01-01T00:00:00Z", null, null);

        admin.placeLegalHold(asset, "x", ALICE);
        admin.placeLegalHold(asset, "x", ALICE);
        admin.releaseLegalHold(asset, "y", ALICE);
        admin.releaseLegalHold(asset, "y", ALICE);

        assertThat(audit.findByAsset(asset)).hasSize(2);
    }

    @Test
    void aMappedHoldLabelHoldsWithoutTouchingTheLegalHoldFlagAndEndsWhenTheLabelIsRemoved() {
        UUID asset = seed("bq://p/d/hold-label", "2025-01-01T00:00:00Z", null, null);
        assign(asset, PII);
        assign(asset, HOLD_LABEL);

        sweep.run();
        assertThat(status(asset)).isEqualTo("ON_HOLD");
        assertThat(retention(asset).get("legal_hold")).isEqualTo(false);

        unassign(asset, HOLD_LABEL);
        clock.set(NOW.plusSeconds(60));
        sweep.run();

        assertThat(status(asset)).isEqualTo("DUE");
        List<String> payloads = outboxPayloads(asset);
        assertThat(payloads.get(0)).contains("HOLD_LABEL");
        assertThat(payloads.get(1)).contains("\"previousStatus\":\"ON_HOLD\"").contains("\"status\":\"DUE\"");
    }

    @Test
    void aManualRuleIsEvaluatedAtOnceAndRemovingItClearsTheResult() {
        UUID asset = seed("bq://p/d/manual", "2020-01-01T00:00:00Z", null, null);
        sweep.run();
        assertThat(status(asset)).isEqualTo("NO_POLICY");

        clock.set(NOW.plusSeconds(60));
        RetentionRuleView rule = admin.addManualRule(asset,
                new RetentionRuleRequest(RetentionBasis.CREATED, 30, null, "short lived"), ALICE);

        assertThat(status(asset)).isEqualTo("DUE");
        assertThat(retention(asset).get("retention_source")).isEqualTo("MANUAL");
        assertThat(expires(asset)).isEqualTo(Instant.parse("2020-01-31T00:00:00Z"));
        assertThat(rule.createdBy()).isEqualTo("alice");

        clock.set(NOW.plusSeconds(120));
        admin.removeManualRule(asset, rule.ruleId(), ALICE);

        assertThat(status(asset)).isEqualTo("NO_POLICY");
        assertThat(retention(asset).get("retention_source")).isNull();
        assertThat(retention(asset).get("retention_days")).isNull();
        assertThat(audit.findByAsset(asset)).extracting(RetentionAuditStore.Entry::action)
                .containsExactly("RULE_ADDED", "RULE_REMOVED");
        AssetRetentionView view = admin.view(asset);
        assertThat(view.rules()).isEmpty();
    }

    @Test
    void aMaterialisedViewListsStoredLabelAndProviderRules() {
        UUID asset = seed("bq://p/d/view", "2025-01-01T00:00:00Z", null, "{\"retentionPeriodDays\":90}");
        assign(asset, PII);
        admin.addManualRule(asset, new RetentionRuleRequest(RetentionBasis.LAST_MODIFIED, 10, null, null), ALICE);

        AssetRetentionView view = admin.view(asset);

        assertThat(view.rules()).extracting(RetentionRuleView::source)
                .containsExactly("MANUAL", "LABEL_DEFAULT", "PROVIDER");
        assertThat(view.assetUri()).isEqualTo("bq://p/d/view");
    }

    // ------------------------------------------------------------------------------------
    // Evidence is read under the row lock
    // ------------------------------------------------------------------------------------

    @Test
    void aSweepWaitingForAnEventConsumersRowLockEvaluatesTheEvidenceItCommitted() throws Exception {
        // The sweep first sees creation 2020 (DUE). While a consumer holds the row and changes the
        // creation time to 2026-10-15 (a new incarnation), the sweep must wait, re-read the row
        // under the lock and conclude RETAINED, never DUE.
        UUID asset = seed("bq://p/d/lock", "2020-01-01T00:00:00Z", null, null);
        assign(asset, PII);
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> consumer = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_id = ? FOR UPDATE", asset);
                locked.countDown();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.update("UPDATE dlap_common.dlap_assets SET cloud_created_at = ? WHERE asset_id = ?",
                        ts("2026-10-15T00:00:00Z"), asset);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<RetentionSweep.Result> running = pool.submit(() -> sweep.run());

            consumer.get(30, TimeUnit.SECONDS);
            RetentionSweep.Result result = running.get(30, TimeUnit.SECONDS);

            assertThat(result.errors()).isZero();
            assertThat(status(asset)).isEqualTo("RETAINED");
            assertThat(expires(asset)).isEqualTo(Instant.parse("2027-10-15T00:00:00Z"));
            assertThat(outboxPayloads(asset)).hasSize(1).allSatisfy(p -> assertThat(p).doesNotContain("\"DUE\""));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------
    // Publishing and locks
    // ------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, Object>> sent() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @Test
    void thePublisherSendsQueuedEventsKeyedByUriMarksThemAndSendsEachOnce() {
        UUID a = seed("bq://p/d/pub-a", "2025-01-01T00:00:00Z", null, null);
        UUID b = seed("bq://p/d/pub-b", "2025-01-01T00:00:00Z", null, null);
        assign(a, PII);
        sweep.run();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(sent());

        int first = publisher.publishPending();
        int second = publisher.publishPending();

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
        org.mockito.Mockito.verify(kafka).send(org.mockito.ArgumentMatchers.eq("dlap.catalog.asset.retention-due"),
                org.mockito.ArgumentMatchers.eq("bq://p/d/pub-a"), org.mockito.ArgumentMatchers.contains("\"DUE\""));
        org.mockito.Mockito.verify(kafka).send(org.mockito.ArgumentMatchers.eq("dlap.catalog.asset.retention-due"),
                org.mockito.ArgumentMatchers.eq("bq://p/d/pub-b"),
                org.mockito.ArgumentMatchers.contains("\"NO_POLICY\""));
        assertThat(outbox.backlog()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.retention_outbox WHERE published_at IS NULL",
                Long.class)).isZero();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void aBrokerFailureLeavesTheEventQueuedAndTheNextRunSendsIt() {
        UUID a = seed("bq://p/d/pub-fail", "2025-01-01T00:00:00Z", null, null);
        assign(a, PII);
        sweep.run();
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.publishPending()).isZero();
        assertThat(outbox.backlog()).isEqualTo(1);

        when(kafka.send(anyString(), anyString(), any())).thenReturn(sent());
        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.backlog()).isZero();
    }

    @Test
    void publishedRowsArePrunedAfterTheOutboxRetention() {
        UUID a = seed("bq://p/d/prune", "2025-01-01T00:00:00Z", null, null);
        sweep.run();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(sent());
        publisher.publishPending();
        assertThat(outboxCount()).isEqualTo(1);

        assertThat(outbox.pruneBefore(NOW.minus(Duration.ofDays(7)))).isZero();
        assertThat(outbox.pruneBefore(NOW.plus(Duration.ofDays(1)))).isEqualTo(1);
        assertThat(outboxCount()).isZero();
        assertThat(a).isNotNull();
    }

    @Test
    void theLockProviderLetsOneReplicaHoldTheSweepLockAtATime() {
        LockConfiguration lock = new LockConfiguration(Instant.now(), RetentionSweepJob.LOCK_NAME,
                Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> first = lockProvider.lock(lock);
        Optional<SimpleLock> second = lockProvider.lock(lock);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.shedlock WHERE name = ?", Long.class,
                RetentionSweepJob.LOCK_NAME)).isEqualTo(1);
        first.get().unlock();
        assertThat(lockProvider.lock(lock)).isPresent();
        // The publisher's lock is independent of the sweep's.
        assertThat(lockProvider.lock(new LockConfiguration(Instant.now(), RetentionDuePublisher.LOCK_NAME,
                Duration.ofMinutes(5), Duration.ZERO))).isPresent();
    }

    // ------------------------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------------------------

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

    /** A settable clock, so a test moves DLAP's time without touching event time. */
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
