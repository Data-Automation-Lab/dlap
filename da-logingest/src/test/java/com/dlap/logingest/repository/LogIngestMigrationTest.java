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

package com.dlap.logingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LOG-02bc acceptance: Flyway V3 and V4 apply to a fresh database and to a V2 database that holds
 * watermarks and replay rows (Testcontainers, PostgreSQL 15.1; requires Docker).
 *
 * <p>Fresh: every V3 table exists, {@code log_events} is a partitioned table with monthly
 * partitions from this month to two months ahead plus a default partition, and its key is
 * {@code (event_id, ingest_time)}. Upgrade: the V2 rows survive in the partition of their ingest
 * month, the legacy watermarks become MIGRATED bindings with cursors, and
 * {@code source_watermarks} is left as it was for the legacy sources.
 */
class LogIngestMigrationTest {

    private static final String S = LogIngestPostgres.SCHEMA;

    @Test
    void freshDatabaseMigratesThroughV5() {
        DataSource ds = LogIngestPostgres.newDatabase();
        Flyway flyway = LogIngestPostgres.flyway(ds, null);

        flyway.migrate();

        assertThat(versions(flyway)).containsExactly("1", "2", "3", "4", "5");
        assertThat(flyway.info().pending()).isEmpty();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(tables(jdbc)).contains("source_binding", "stream_cursor", "processed_object",
                "format_observation", "quarantine_record", "backfill_job", "shedlock", "log_events",
                "log_events_default", "source_watermarks", "unknown_path_observation", "fill_baseline");
        assertThat(relkind(jdbc, "log_events")).isEqualTo("p");
        YearMonth now = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC));
        assertThat(partitions(jdbc)).contains("log_events_default",
                LogEventPartitionRepository.partitionName(now),
                LogEventPartitionRepository.partitionName(now.plusMonths(1)),
                LogEventPartitionRepository.partitionName(now.plusMonths(2)));
        assertThat(primaryKey(jdbc, "log_events")).containsExactly("event_id", "ingest_time");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + ".source_binding", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + ".stream_cursor", Integer.class)).isZero();
    }

    @Test
    void freshLogEventsRowLandsInItsIngestMonthPartition() {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant ingest = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        insertEvent(jdbc, "e-now", ingest.minus(400, ChronoUnit.DAYS), ingest);

        assertThat(partitionOf(jdbc, "e-now"))
                .isEqualTo(LogEventPartitionRepository.partitionName(YearMonth.from(ingest.atZone(ZoneOffset.UTC))));
    }

    @Test
    void v2DatabaseWithWatermarksAndEventsUpgrades() {
        DataSource ds = LogIngestPostgres.newDatabase();
        LogIngestPostgres.flyway(ds, "2").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant t1 = Instant.parse("2026-05-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-02T10:00:00Z");
        Instant t3 = Instant.parse("2026-05-03T10:00:00Z");
        Instant t0 = Instant.parse("2026-04-30T10:00:00Z");
        watermark(jdbc, "AWS", "us-east-1", t1);
        watermark(jdbc, "AWS", "eu-west-1", t2);
        watermark(jdbc, "GCP", "my-project-123", t3);
        watermark(jdbc, "AZURE", "00000000-0000-0000-0000-000000000000", t3);
        // A lower-case provider spelling of the same region collides after sanitizing; the oldest wins.
        watermark(jdbc, "aws", "us-east-1", t0);
        Instant oldIngest = now.minus(70, ChronoUnit.DAYS);
        insertLegacyEvent(jdbc, "e-old", oldIngest.minus(1, ChronoUnit.DAYS), oldIngest);
        insertLegacyEvent(jdbc, "e-new", now.minus(1, ChronoUnit.HOURS), now);

        Flyway latest = LogIngestPostgres.flyway(ds, null);
        latest.migrate();

        assertThat(versions(latest)).containsExactly("1", "2", "3", "4", "5");
        assertThat(relkind(jdbc, "log_events")).isEqualTo("p");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + ".log_events", Integer.class)).isEqualTo(2);
        assertThat(partitionOf(jdbc, "e-old"))
                .isEqualTo(LogEventPartitionRepository.partitionName(YearMonth.from(oldIngest.atZone(ZoneOffset.UTC))));
        assertThat(partitionOf(jdbc, "e-new"))
                .isEqualTo(LogEventPartitionRepository.partitionName(YearMonth.from(now.atZone(ZoneOffset.UTC))));
        Map<String, Object> old = jdbc.queryForMap("SELECT operation, raw_event::text AS raw, binding_id FROM " + S
                + ".log_events WHERE event_id = 'e-old'");
        assertThat(old.get("operation")).isEqualTo("GetObject");
        assertThat((String) old.get("raw")).contains("\"eventVersion\"");
        assertThat(old.get("binding_id")).isNull();

        List<Map<String, Object>> bindings = jdbc.queryForList("SELECT binding_id, source_kind, config_origin, state, "
                + "enabled, scope::text AS scope FROM " + S + ".source_binding ORDER BY binding_id");
        assertThat(bindings).extracting(m -> m.get("binding_id"))
                .containsExactly("legacy-aws-eu-west-1", "legacy-aws-us-east-1", "legacy-gcp-my-project-123");
        assertThat(bindings).allSatisfy(m -> {
            assertThat(m.get("config_origin")).isEqualTo("MIGRATED");
            assertThat(m.get("state")).isEqualTo("MIGRATED");
            assertThat(m.get("enabled")).isEqualTo(false);
        });
        assertThat(bindings.get(1).get("source_kind")).isEqualTo("AWS_CLOUDTRAIL");
        assertThat((String) bindings.get(1).get("scope")).contains("us-east-1");
        assertThat(bindings.get(2).get("source_kind")).isEqualTo("GCP_CLOUD_AUDIT");
        assertThat((String) bindings.get(2).get("scope")).contains("my-project-123");

        List<Map<String, Object>> cursors = jdbc.queryForList("SELECT binding_id, stream_id, partition_key, "
                + "low_watermark, high_watermark FROM " + S + ".stream_cursor ORDER BY binding_id");
        assertThat(cursors).hasSize(3);
        assertThat(cursors.get(1)).containsEntry("binding_id", "legacy-aws-us-east-1")
                .containsEntry("stream_id", "lookup-events").containsEntry("partition_key", "us-east-1");
        assertThat(instant(cursors.get(1).get("low_watermark"))).isEqualTo(t0);
        assertThat(instant(cursors.get(1).get("high_watermark"))).isEqualTo(t0);
        assertThat(cursors.get(0)).containsEntry("stream_id", "lookup-events").containsEntry("partition_key", "eu-west-1");
        assertThat(instant(cursors.get(0).get("low_watermark"))).isEqualTo(t2);
        assertThat(cursors.get(2)).containsEntry("binding_id", "legacy-gcp-my-project-123")
                .containsEntry("stream_id", "logging-api").containsEntry("partition_key", "");
        assertThat(instant(cursors.get(2).get("low_watermark"))).isEqualTo(t3);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + ".source_watermarks", Integer.class))
                .as("the legacy sources keep their watermarks").isEqualTo(5);
    }

    @Test
    void v5DriftStateTablesHoldTheDocumentedColumnsAndRerunCleanly() throws Exception {
        DataSource ds = LogIngestPostgres.migratedDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        assertThat(columns(jdbc, "unknown_path_observation")).containsExactly("source_kind", "envelope",
                "schema_version", "path", "observed_count", "first_seen", "last_seen");
        assertThat(columns(jdbc, "fill_baseline")).containsExactly("source_kind", "schema_version", "field",
                "baseline", "updated_at");
        assertThat(primaryKey(jdbc, "unknown_path_observation")).containsExactly("source_kind", "envelope",
                "schema_version", "path");
        assertThat(primaryKey(jdbc, "fill_baseline")).containsExactly("source_kind", "schema_version", "field");
        // Idempotent per CLAUDE.md section 7: the script runs again on a migrated database.
        try (var connection = ds.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V5__drift_state.sql"));
        }
    }

    private static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = ? ORDER BY ordinal_position", String.class, S, table);
    }

    /** Applied versioned migrations; Flyway's own schema-creation entry has no version. */
    private static List<String> versions(Flyway flyway) {
        return java.util.Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(v -> v.getVersion())
                .toList();
    }

    private static List<String> tables(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, S);
    }

    private static String relkind(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ?", String.class, S, table);
    }

    private static List<String> partitions(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT c.relname::text FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                + "JOIN pg_class p ON p.oid = i.inhparent JOIN pg_namespace n ON n.oid = p.relnamespace "
                + "WHERE n.nspname = ? AND p.relname = 'log_events'", String.class, S);
    }

    private static List<String> primaryKey(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT a.attname::text FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum "
                + "WHERE n.nspname = ? AND c.relname = ? AND i.indisprimary ORDER BY k.ord", String.class, S, table);
    }

    private static String partitionOf(JdbcTemplate jdbc, String eventId) {
        return jdbc.queryForObject("SELECT tableoid::regclass::text FROM " + S + ".log_events WHERE event_id = ?",
                String.class, eventId).replace(S + ".", "");
    }

    private static void watermark(JdbcTemplate jdbc, String provider, String account, Instant at) {
        jdbc.update("INSERT INTO " + S + ".source_watermarks (provider, account_id, last_event_time, updated_at) "
                + "VALUES (?, ?, ?, ?)", provider, account, ts(at), ts(at));
    }

    private static void insertLegacyEvent(JdbcTemplate jdbc, String id, Instant eventTime, Instant ingestTime) {
        jdbc.update("INSERT INTO " + S + ".log_events (event_id, event_time, ingest_time, source_provider, change_type, "
                + "operation, schema_version, raw_event) VALUES (?, ?, ?, 'AWS', 'ACCESS', 'GetObject', 'v1', "
                + "'{\"eventVersion\":\"1.08\"}'::jsonb)", id, ts(eventTime), ts(ingestTime));
    }

    static void insertEvent(JdbcTemplate jdbc, String id, Instant eventTime, Instant ingestTime) {
        jdbc.update("INSERT INTO " + S + ".log_events (event_id, event_time, ingest_time, source_provider, change_type, "
                + "operation, schema_version) VALUES (?, ?, ?, 'AWS', 'ACCESS', 'GetObject', 'v1')",
                id, ts(eventTime), ts(ingestTime));
    }

    static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(Object v) {
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        throw new IllegalArgumentException("not a timestamp: " + v);
    }
}
