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

package com.dlap.catalog.rekey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import com.dlap.catalog.rekey.RekeyOutcome.Status;
import com.dlap.common.assetid.AssetIdNormalizer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * ID-03 acceptance against the real schema: the platform init scripts (with their seed rows) plus
 * the catalog's Flyway migrations, V5 included, on {@code postgres:15.1}, connected as
 * {@code da_catalog_user}. One scenario in order, because a re-key plans over every row of the
 * catalog: blockers, dry run, apply, a second apply, revert, re-apply and the revert refusals.
 * Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = AssetUriRekeyContainerTest.RekeySlice.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AssetUriRekeyContainerTest {

    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    /** The tables whose content a revert must restore exactly ({@code asset_uri_canonical} is derived). */
    private static final List<String> SNAPSHOT_TABLES = List.of("dlap_common.dlap_assets",
            "dlap_common.dlap_asset_label_assignments", "dlap_common.dlap_cloud_resources",
            "dlap_catalog.dlap_asset_lineage", "dlap_catalog.asset_usage_daily", "dlap_catalog.processed_event",
            "dlap_catalog.retention_rule");

    private static final UUID A1 = UUID.fromString("00000000-0000-0000-0003-000000000001");
    private static final UUID A2 = UUID.fromString("00000000-0000-0000-0003-000000000002");
    private static final UUID A3 = UUID.fromString("00000000-0000-0000-0003-000000000003");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0003-000000000010");
    private static final UUID BQ = UUID.fromString("00000000-0000-0000-0003-000000000020");
    private static final UUID MISTYPED = UUID.fromString("00000000-0000-0000-0003-000000000030");
    private static final UUID BAD = UUID.fromString("00000000-0000-0000-0003-000000000040");
    private static final UUID POLICY = UUID.fromString("00000000-0000-0000-0003-0000000000a1");

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
        registry.add("dlap.catalog.rekey.operator", () -> "id03-test");
    }

    /** The re-key slice: JPA, JDBC, Flyway and the re-key beans; no Kafka, web or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    @EnableJpaRepositories(basePackages = {"com.dlap.catalog.repository", "com.dlap.common.repository"})
    @EnableConfigurationProperties({CatalogIngestProperties.class, RekeyProperties.class})
    @Import({CatalogEventDecoder.class, CatalogIngestMetrics.class, CanonicalUriIndex.class, AssetMatcher.class,
            AssetUriRekeyPlanner.class, AssetUriRekeyExecutor.class, AssetUriRekeyReverter.class,
            AssetUriRekeyService.class})
    static class RekeySlice {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private AssetUriRekeyService rekey;

    @Autowired
    private AssetMatcher matcher;

    @Autowired
    private AssetUriRekeyExecutor executor;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dryRunApplySecondApplyRevertAndReapply() {
        // Batches of two, so the in-place re-keys span several batches and undo statements.
        executor.setBatchSize(2);
        seed();
        Map<String, String> before = snapshot();
        assertThat(AssetUriRekeyReverter.unknownAssetColumns(new java.util.HashSet<>(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'dlap_common' "
                        + "AND table_name = 'dlap_assets'", String.class))))
                .as("the revert restores every column of the live asset table").isEmpty();

        // 1. An unpublished retention-due event that names an old URI blocks both commands.
        jdbc.update("INSERT INTO dlap_catalog.retention_outbox (event_id, asset_uri, payload, created_at) "
                + "VALUES ('id03-outbox', 'id03proj:ds.tbl', '{}', now())");
        RekeyOutcome blockedDry = rekey.dryRun();
        assertThat(blockedDry.status()).isEqualTo(Status.REFUSED);
        assertThat(blockedDry.exitCode()).isEqualTo(2);
        assertThat(blockers(blockedDry)).extracting(b -> b.get("kind"))
                .containsExactly(AssetUriRekeyService.BLOCKER_OUTBOX);
        RekeyOutcome blockedApply = rekey.apply();
        assertThat(blockedApply.status()).isEqualTo(Status.REFUSED);
        assertThat(snapshot()).as("a refused apply changes nothing").isEqualTo(before);
        assertThat(runCount()).isZero();
        jdbc.update("UPDATE dlap_catalog.retention_outbox SET published_at = now() WHERE event_id = 'id03-outbox'");

        // 2. The dry run reports the collision, the in-place re-keys and the row that does not normalize.
        RekeyOutcome dry = rekey.dryRun();
        assertThat(dry.status()).isEqualTo(Status.DONE);
        assertThat(dry.runId()).isNull();
        assertThat(snapshot()).as("a dry run changes nothing").isEqualTo(before);
        Map<String, Object> group = collision(dry, "gs://id03-bucket");
        assertThat(cast(group.get("survivor")).get("assetId")).isEqualTo(A1.toString());
        assertThat(merged(group)).containsExactly(A2.toString(), A3.toString());
        assertThat(cast(group.get("references"))).containsEntry("labels", 2).containsEntry("labelsAlreadyOnSurvivor", 1)
                .containsEntry("lineageEdges", 3).containsEntry("usageDays", 2).containsEntry("usageDaysAlsoOnSurvivor", 1)
                .containsEntry("processedEvents", 2).containsEntry("retentionRules", 2).containsEntry("cloudResources", 1);
        assertThat(rekeyOf(dry, BQ)).containsEntry("from", "id03proj:ds.tbl").containsEntry("to", "bq://id03proj/ds/tbl");
        assertThat(rekeyOf(dry, MISTYPED)).containsEntry("serviceTypeFrom", "GCS_BUCKET")
                .containsEntry("serviceTypeTo", "S3_BUCKET").containsEntry("cloudProviderTo", "AWS");
        assertThat(rejected(dry)).contains(BAD.toString());
        assertThat(dry.report()).containsEntry("specVersion", AssetIdNormalizer.SPEC_VERSION);

        // 3. Apply: the oldest row survives and absorbs the others.
        RekeyOutcome applied = rekey.apply();
        assertThat(applied.status()).isEqualTo(Status.DONE);
        UUID run = applied.runId();
        assertThat(run).isNotNull();
        assertSurvivor();
        assertReferencesMoved();
        assertThat(uriOf(BQ)).isEqualTo("bq://id03proj/ds/tbl");
        assertThat(jdbc.queryForObject("SELECT native_asset_id FROM dlap_common.dlap_assets WHERE asset_id = ?",
                String.class, BQ)).isEqualTo("id03proj:ds.tbl");
        assertThat(jdbc.queryForMap("SELECT service_type, cloud_provider FROM dlap_common.dlap_assets WHERE asset_id = ?",
                MISTYPED)).containsEntry("service_type", "S3_BUCKET").containsEntry("cloud_provider", "AWS");
        assertThat(uriOf(BAD)).as("a row that does not normalize is left alone").isEqualTo("id03 no scheme here");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_common.dlap_assets a WHERE a.asset_uri <> "
                + "COALESCE((SELECT canonical_uri FROM dlap_catalog.asset_uri_canonical i WHERE i.asset_id = a.asset_id), "
                + "a.asset_uri)", Integer.class)).as("the index agrees with every re-keyed row").isZero();
        assertThat(mapAction(run, A1)).isEqualTo("SURVIVOR");
        assertThat(mapAction(run, A2)).isEqualTo("MERGED");
        assertThat(mapAction(run, BQ)).isEqualTo("REKEYED");
        assertThat(jdbc.queryForObject("SELECT survivor_asset_id FROM dlap_catalog.asset_uri_rekey_map "
                + "WHERE run_id = ? AND old_asset_uri = 'projects/_/buckets/id03-bucket'", UUID.class, run)).isEqualTo(A1);
        assertThat(jdbc.queryForMap("SELECT status, operator, applied_by, rows_merged, merge_groups FROM "
                + "dlap_catalog.asset_uri_rekey_run WHERE run_id = ?", run)).containsEntry("status", "APPLIED")
                .containsEntry("operator", "id03-test").containsEntry("applied_by", "da_catalog_user")
                .containsEntry("rows_merged", 2);
        assertThat(cast(applied.report().get("applied"))).containsEntry("labelsMoved", 1).containsEntry("labelsDropped", 1)
                .containsEntry("lineageMoved", 1).containsEntry("lineageDropped", 2)
                .containsEntry("usageDaysSummed", 1).containsEntry("usageDaysMoved", 1)
                .containsEntry("processedEventsMoved", 1).containsEntry("processedEventsDropped", 1)
                .containsEntry("retentionRulesMoved", 2).containsEntry("retentionRulesDropped", 1)
                .containsEntry("cloudResourcesMoved", 1).containsEntry("legalHoldsMerged", 1);
        assertThat(matcher.find(AssetIdNormalizer.normalize("projects/_/buckets/id03-bucket").orElseThrow()))
                .as("an event naming the bucket in any form now matches the survivor exactly")
                .contains(new AssetMatcher.Match(A1, AssetMatcher.Method.EXACT));
        Map<String, String> afterApply = snapshot();

        // 4. A second apply finds nothing to do and records nothing.
        RekeyOutcome second = rekey.apply();
        assertThat(second.status()).isEqualTo(Status.NOTHING_TO_DO);
        assertThat(second.exitCode()).isZero();
        assertThat(snapshot()).isEqualTo(afterApply);
        assertThat(runCount()).isEqualTo(1);
        assertThat(rekey.dryRun().report().get("summary")).satisfies(s -> assertThat(cast(s))
                .containsEntry("rekeyed", 0).containsEntry("mergeGroups", 0));

        // 5. Revert: only the latest applied run, and it restores every row exactly.
        assertThat(rekey.revert(UUID.randomUUID()).status()).isEqualTo(Status.REFUSED);
        RekeyOutcome reverted = rekey.revert(null);
        assertThat(reverted.status()).isEqualTo(Status.DONE);
        assertThat(reverted.runId()).isEqualTo(run);
        assertThat(snapshot()).as("the revert restores the state before the apply").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM dlap_catalog.asset_uri_rekey_run WHERE run_id = ?",
                String.class, run)).isEqualTo("REVERTED");
        assertThat(jdbc.queryForObject("SELECT canonical_uri FROM dlap_catalog.asset_uri_canonical WHERE asset_id = ?",
                String.class, A2)).as("restored rows are indexed again").isEqualTo("gs://id03-bucket");
        assertThat(rekey.revert(null).status()).as("nothing left to revert").isEqualTo(Status.REFUSED);

        // 6. Re-apply after the revert gives the same result as the first apply.
        RekeyOutcome again = rekey.apply();
        assertThat(again.status()).isEqualTo(Status.DONE);
        assertThat(snapshot()).isEqualTo(afterApply);

        // 7. A row whose identity changed since the run blocks the revert.
        jdbc.update("UPDATE dlap_common.dlap_assets SET asset_uri = 'gs://id03-bucket-renamed' WHERE asset_id = ?", A1);
        RekeyOutcome drift = rekey.revert(null);
        assertThat(drift.status()).isEqualTo(Status.REFUSED);
        assertThat(drift.message()).contains("changed identity since the run");
        assertThat(uriOf(A1)).isEqualTo("gs://id03-bucket-renamed");
    }

    private void assertSurvivor() {
        Map<String, Object> s = jdbc.queryForMap("SELECT asset_uri, service_type, cloud_provider, native_asset_id, "
                + "asset_name, technical_metadata::text AS tm, last_accessed_at, last_access_source, cloud_created_at, "
                + "cloud_created_source, status, lifecycle_event_time, legal_hold, retention_status, read_coverage, "
                + "created_ts FROM dlap_common.dlap_assets WHERE asset_id = ?", A1);
        assertThat(s).containsEntry("asset_uri", "gs://id03-bucket").containsEntry("service_type", "GCS_BUCKET")
                .containsEntry("cloud_provider", "GCP").containsEntry("native_asset_id", "id03-bucket")
                .containsEntry("asset_name", "id03-bucket").containsEntry("status", "ACTIVE")
                .containsEntry("legal_hold", true).containsEntry("retention_status", "ON_HOLD")
                .containsEntry("read_coverage", "FULL").containsEntry("last_access_source", "AUDIT_LOG:A2")
                .containsEntry("cloud_created_source", "CRAWLER:A2");
        assertThat(instant(s.get("last_accessed_at"))).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        assertThat(instant(s.get("cloud_created_at"))).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(instant(s.get("lifecycle_event_time"))).isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
        assertThat(instant(s.get("created_ts"))).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat((String) s.get("tm")).contains("\"a\": 1").contains("\"b\": 2").contains("\"shared\": \"old\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_common.dlap_assets WHERE asset_id IN (?, ?)",
                Integer.class, A2, A3)).isZero();
    }

    private void assertReferencesMoved() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_common.dlap_asset_label_assignments WHERE asset_id = ?",
                Integer.class, A1)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT source FROM dlap_common.dlap_asset_label_assignments x JOIN "
                + "dlap_common.dlap_labels l ON l.label_id = x.label_id WHERE x.asset_id = ? ORDER BY l.label_name "
                + "LIMIT 1", String.class, A1)).isNotNull();
        assertThat(jdbc.queryForList("SELECT relationship_type FROM dlap_catalog.dlap_asset_lineage "
                + "WHERE upstream_asset_id = ? ORDER BY relationship_type", String.class, A1))
                .containsExactly("READS", "WRITES");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.dlap_asset_lineage "
                + "WHERE upstream_asset_id = downstream_asset_id", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT reads, writes FROM dlap_catalog.asset_usage_daily WHERE asset_id = ? "
                + "AND day = DATE '2026-10-05'", A1)).containsEntry("reads", 5L).containsEntry("writes", 1L);
        assertThat(jdbc.queryForObject("SELECT reads FROM dlap_catalog.asset_usage_daily WHERE asset_id = ? "
                + "AND day = DATE '2026-10-06'", Long.class, A1)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT sum(reads) FROM dlap_catalog.asset_usage_daily WHERE asset_id IN (?, ?, ?)",
                Long.class, A1, A2, A3)).as("usage is conserved").isEqualTo(9L);
        assertThat(jdbc.queryForList("SELECT event_id FROM dlap_catalog.processed_event WHERE asset_id = ? "
                + "ORDER BY event_id", String.class, A1)).containsExactly("id03-e1", "id03-e2");
        assertThat(jdbc.queryForList("SELECT source || ':' || to_char(issued_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') "
                + "FROM dlap_catalog.retention_rule WHERE asset_id = ? ORDER BY 1", String.class, A1))
                .as("the later rule of the policy stays; the MANUAL rule moves")
                .containsExactly("MANUAL:2026-10-01", "POLICY:2026-10-02");
        assertThat(jdbc.queryForObject("SELECT asset_id FROM dlap_common.dlap_cloud_resources WHERE native_resource_id = "
                + "'id03-cr'", UUID.class)).isEqualTo(A1);
    }

    private void seed() {
        String insert = "INSERT INTO dlap_common.dlap_assets (asset_id, asset_uri, cloud_provider, service_type, "
                + "asset_name, technical_metadata, created_ts, last_accessed_at, last_access_source, cloud_created_at, "
                + "cloud_created_source, status, lifecycle_event_time, legal_hold, retention_status, read_coverage, "
                + "read_coverage_observed_at, read_coverage_since) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?)";
        jdbc.update(insert, A1, "id03-bucket", "GCP", "GCS_BUCKET", null, "{\"a\": 1, \"shared\": \"old\"}",
                ts("2026-09-01"), ts("2026-10-05"), "AUDIT_LOG:A1", ts("2026-10-02"), "CRAWLER:A1", "ARCHIVED",
                ts("2026-10-03"), false, "UNKNOWN", null, null, null);
        jdbc.update(insert, A2, "gs://id03-bucket", "GCP", "GCS_BUCKET", "id03-bucket", "{\"b\": 2, \"shared\": \"new\"}",
                ts("2026-09-02"), ts("2026-10-07"), "AUDIT_LOG:A2", ts("2026-10-01"), "CRAWLER:A2", "ACTIVE",
                ts("2026-10-04"), true, "ON_HOLD", "FULL", ts("2026-10-06"), ts("2026-10-06"));
        jdbc.update(insert, A3, "projects/_/buckets/id03-bucket", "GCP", "GCS_BUCKET", null, "{}", ts("2026-09-03"),
                null, null, null, null, "ACTIVE", null, false, "UNKNOWN", null, null, null);
        jdbc.update(insert, OTHER, "gs://id03-other", "GCP", "GCS_BUCKET", "id03-other", "{}", ts("2026-09-04"), null,
                null, null, null, "ACTIVE", null, false, "UNKNOWN", null, null, null);
        jdbc.update(insert, BQ, "id03proj:ds.tbl", "GCP", "BIGQUERY_TABLE", "tbl", "{}", ts("2026-09-05"), null, null,
                null, null, "ACTIVE", null, false, "UNKNOWN", null, null, null);
        jdbc.update(insert, MISTYPED, "s3://id03-mistyped", "GCP", "GCS_BUCKET", "id03-mistyped", "{}", ts("2026-09-06"),
                null, null, null, null, "ACTIVE", null, false, "UNKNOWN", null, null, null);
        jdbc.update(insert, BAD, "id03 no scheme here", "AZURE", "DATA_LAKE", "bad", "{}", ts("2026-09-07"), null, null,
                null, null, "ACTIVE", null, false, "UNKNOWN", null, null, null);

        List<UUID> labels = jdbc.queryForList("SELECT label_id FROM dlap_common.dlap_labels ORDER BY label_name LIMIT 2",
                UUID.class);
        String label = "INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, confidence_score, "
                + "source) VALUES (?, ?, ?, ?)";
        jdbc.update(label, A1, labels.get(0), 0.5, "SYSTEM");
        jdbc.update(label, A2, labels.get(0), 0.9, "USER");
        jdbc.update(label, A2, labels.get(1), 0.8, "SYSTEM");

        String edge = "INSERT INTO dlap_catalog.dlap_asset_lineage (lineage_id, created_at, relationship_type, "
                + "upstream_asset_id, downstream_asset_id) VALUES (?, now(), ?, ?, ?)";
        jdbc.update(edge, UUID.randomUUID(), "READS", A1, OTHER);
        jdbc.update(edge, UUID.randomUUID(), "READS", A2, OTHER);
        jdbc.update(edge, UUID.randomUUID(), "WRITES", A3, OTHER);
        jdbc.update(edge, UUID.randomUUID(), "COPIES", A2, A1);

        String usage = "INSERT INTO dlap_catalog.asset_usage_daily (asset_id, day, reads, writes, first_event_at, "
                + "last_event_at) VALUES (?, ?::date, ?, ?, ?, ?)";
        jdbc.update(usage, A1, "2026-10-05", 2, 0, ts("2026-10-05"), ts("2026-10-05"));
        jdbc.update(usage, A2, "2026-10-05", 3, 1, ts("2026-10-05"), ts("2026-10-05"));
        jdbc.update(usage, A2, "2026-10-06", 4, 0, ts("2026-10-06"), ts("2026-10-06"));

        String processed = "INSERT INTO dlap_catalog.processed_event (consumer, event_id, asset_id, event_time, "
                + "processed_at) VALUES ('id03-consumer', ?, ?, now(), now())";
        jdbc.update(processed, "id03-e1", A1);
        jdbc.update(processed, "id03-e1", A2);
        jdbc.update(processed, "id03-e2", A2);

        String rule = "INSERT INTO dlap_catalog.retention_rule (rule_id, asset_id, source, basis, days, policy_id, "
                + "issued_at, created_at, updated_at) VALUES (?, ?, ?, 'CREATED', 30, ?, ?, now(), now())";
        jdbc.update(rule, UUID.randomUUID(), A1, "POLICY", POLICY, ts("2026-10-01"));
        jdbc.update(rule, UUID.randomUUID(), A2, "POLICY", POLICY, ts("2026-10-02"));
        jdbc.update(rule, UUID.randomUUID(), A3, "MANUAL", null, ts("2026-10-01"));

        jdbc.update("INSERT INTO dlap_common.dlap_cloud_resources (asset_id, cloud_provider, native_resource_id) "
                + "VALUES (?, 'GCP', 'id03-cr')", A2);
    }

    /**
     * md5 of every row of each table, in a stable order, so two states compare exactly. The one
     * column left out is {@code last_modified_ts}: the asset table's trigger sets it to the time of
     * every update, so it records when the apply or the revert touched the row.
     */
    private Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            out.put(table, jdbc.queryForObject("SELECT md5(COALESCE(string_agg(r::text, '|' ORDER BY r::text), '')) "
                    + "FROM (SELECT to_jsonb(t) - 'last_modified_ts' AS r FROM " + table + " t) s", String.class));
        }
        return out;
    }

    private int runCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.asset_uri_rekey_run", Integer.class);
        return n == null ? 0 : n;
    }

    private String uriOf(UUID id) {
        return jdbc.queryForObject("SELECT asset_uri FROM dlap_common.dlap_assets WHERE asset_id = ?", String.class, id);
    }

    private String mapAction(UUID run, UUID asset) {
        return jdbc.queryForObject("SELECT action FROM dlap_catalog.asset_uri_rekey_map WHERE run_id = ? AND asset_id = ?",
                String.class, run, asset);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blockers(RekeyOutcome o) {
        return (List<Map<String, Object>>) o.report().get("blockers");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> collision(RekeyOutcome o, String uri) {
        return ((List<Map<String, Object>>) o.report().get("collisions")).stream()
                .filter(c -> uri.equals(c.get("canonicalUri"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> merged(Map<String, Object> group) {
        return ((List<Map<String, Object>>) group.get("merged")).stream().map(m -> m.get("assetId")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rekeyOf(RekeyOutcome o, UUID id) {
        return ((List<Map<String, Object>>) o.report().get("rekeys")).stream()
                .filter(r -> id.toString().equals(r.get("assetId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rejected(RekeyOutcome o) {
        return ((List<Map<String, Object>>) o.report().get("rejected")).stream().map(r -> r.get("assetId")).toList();
    }

    private static OffsetDateTime ts(String day) {
        return OffsetDateTime.parse(day + "T00:00:00Z");
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        throw new IllegalArgumentException("not a timestamp: " + value);
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
}
