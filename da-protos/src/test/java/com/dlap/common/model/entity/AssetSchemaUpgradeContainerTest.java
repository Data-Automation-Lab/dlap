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

package com.dlap.common.model.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

/**
 * ADR-0046 / CAT-01 schema verification on the Postgres version the stacks run (15.1).
 *
 * <p>The container boots exactly like the dev compose: the real init scripts run from
 * {@code /docker-entrypoint-initdb.d}, which builds {@code dlap_common} from the current
 * {@code 02-create-common-schema.sql}. The test then builds a second database,
 * {@code dlap_baseline}, from the pre-ADR-0046 script (test fixture, sdg 7984d1d), puts an
 * old-shape asset row in it, and applies every upgrade delta newer than that fixture. Both
 * databases must end with the same columns, constraints, indexes, triggers and column comments,
 * the delta and the init script must be re-runnable, and Hibernate must validate {@link Asset}
 * against both.
 */
@Testcontainers
class AssetSchemaUpgradeContainerTest {

    private static final String SUPERUSER = "dlap";
    private static final String COMMON = "dlap_common";
    private static final String BASELINE = "dlap_baseline";
    private static final String BASELINE_FIXTURE = "02-create-common-schema.baseline-7984d1d.sql";
    private static final String LEGACY_ASSET_URI = "s3://legacy-bucket";

    /** Same database list as deployment/environments/dev/docker-compose.yml, so every service user exists. */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,"
            + "da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    /** Init scripts committed with mode 100755. */
    private static final Set<String> EXECUTABLE_INIT_SCRIPTS = Set.of("01-create-single-database.sh");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = withInitScripts(
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                    .withDatabaseName("dlap")
                    .withUsername(SUPERUSER)
                    .withPassword(SUPERUSER)
                    .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES));

    @BeforeAll
    static void buildBaselineAndUpgrade() throws Exception {
        psqlCommand("dlap", "CREATE DATABASE " + BASELINE);
        String baseline = readFixture().replace("\\c dlap_common", "\\c " + BASELINE);
        assertThat(baseline).contains("\\c " + BASELINE);
        psqlScript("dlap", "baseline-02.sql", baseline);

        // An asset written before the upgrade, with the provider and type the old checks allowed.
        try (Connection c = connect(BASELINE); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO dlap_common.dlap_assets (asset_uri, cloud_provider, service_type)"
                    + " VALUES ('" + LEGACY_ASSET_URI + "', 'AWS', 'S3_BUCKET')");
        }

        List<Path> deltas = SchemaScripts.deltasAfterBaseline();
        assertThat(deltas).extracting(p -> p.getFileName().toString()).contains(SchemaScripts.ASSET_TIME_DELTA);
        for (Path delta : deltas) {
            psqlScript(BASELINE, delta.getFileName().toString(), SchemaScripts.read(delta));
        }
    }

    @Test
    void upgradedBaselineMatchesAFreshInit() throws Exception {
        assertThat(schemaOf(BASELINE)).isEqualTo(schemaOf(COMMON));
    }

    @Test
    void deltaIsIdempotentOnFreshAndUpgradedDatabases() throws Exception {
        for (String db : List.of(COMMON, BASELINE)) {
            List<String> before = schemaOf(db);
            psqlScript(db, "delta-again.sql", SchemaScripts.assetTimeDelta());
            psqlScript(db, "delta-third.sql", SchemaScripts.assetTimeDelta());
            assertThat(schemaOf(db)).as("schema of %s after re-running the delta", db).isEqualTo(before);
        }
    }

    @Test
    void initScriptCanBeRerunWithoutChangingTheSchema() throws Exception {
        List<String> before = schemaOf(COMMON);
        psqlScript("dlap", "02-again.sql", SchemaScripts.commonSchema());
        assertThat(schemaOf(COMMON)).isEqualTo(before);
    }

    @Test
    void existingRowsReadAsUnknownAndNotHeld() throws Exception {
        try (Connection c = connect(BASELINE);
             PreparedStatement ps = c.prepareStatement("SELECT retention_status, legal_hold, cloud_created_at,"
                     + " last_accessed_at, size_bytes, retention_expires_at FROM dlap_common.dlap_assets"
                     + " WHERE asset_uri = ?")) {
            ps.setString(1, LEGACY_ASSET_URI);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("retention_status")).isEqualTo("UNKNOWN");
                assertThat(rs.getBoolean("legal_hold")).isFalse();
                assertThat(rs.getObject("cloud_created_at")).isNull();
                assertThat(rs.getObject("last_accessed_at")).isNull();
                assertThat(rs.getObject("size_bytes")).isNull();
                assertThat(rs.getObject("retention_expires_at")).isNull();
            }
        }
    }

    @Test
    void hibernateValidatesAssetAgainstBothDatabases() {
        for (String db : List.of(COMMON, BASELINE)) {
            try (SessionFactory sf = sessionFactory(db, "validate")) {
                assertThat(sf.isOpen()).as("validated against %s", db).isTrue();
            }
        }
    }

    @Test
    void assetRoundTripsEveryNewField() {
        Instant t = Instant.parse("2026-10-01T12:00:00Z");
        UUID policyId = UUID.randomUUID();
        Asset saved = new Asset("uc://ms-1/main/sales/volumes/raw", "UNITY", "UNITY_VOLUME", "raw");
        saved.setCloudCreatedAt(t.minus(400, ChronoUnit.DAYS));
        saved.setCloudCreatedSource("CRAWLER");
        saved.setSizeBytes(0L);
        saved.setSizeObservedAt(t);
        saved.setLastAccessedAt(t.minus(3, ChronoUnit.DAYS));
        saved.setLastAccessSource("AUDIT_LOG:DATABRICKS_AUDIT");
        saved.setLastModifiedAt(t.minus(9, ChronoUnit.DAYS));
        saved.setLastPermissionChangeAt(t.minus(30, ChronoUnit.DAYS));
        saved.setLifecycleEventTime(t.minus(400, ChronoUnit.DAYS));
        saved.setRecreatedAt(t.minus(400, ChronoUnit.DAYS));
        saved.setDiscoverySource("CRAWLER");
        saved.setRetentionDays(365);
        saved.setRetentionBasis(RetentionBasis.CREATED);
        saved.setRetentionSource("POLICY");
        saved.setRetentionPolicyId(policyId);
        saved.setRetentionExpiresAt(t.minus(35, ChronoUnit.DAYS));
        saved.setRetentionStatus(RetentionStatus.DUE);
        saved.setRetentionEvaluatedAt(t);

        try (SessionFactory sf = sessionFactory(COMMON, "validate")) {
            sf.inTransaction(session -> session.persist(saved));
            Asset loaded = sf.fromTransaction(session -> session.get(Asset.class, saved.getAssetId()));

            assertThat(loaded.getCloudProvider()).isEqualTo("UNITY");
            assertThat(loaded.getServiceType()).isEqualTo("UNITY_VOLUME");
            assertThat(loaded.getCloudCreatedAt()).isEqualTo(saved.getCloudCreatedAt());
            assertThat(loaded.getCloudCreatedSource()).isEqualTo("CRAWLER");
            assertThat(loaded.getSizeBytes()).isZero();
            assertThat(loaded.getSizeObservedAt()).isEqualTo(t);
            assertThat(loaded.getLastAccessedAt()).isEqualTo(saved.getLastAccessedAt());
            assertThat(loaded.getLastAccessSource()).isEqualTo("AUDIT_LOG:DATABRICKS_AUDIT");
            assertThat(loaded.getLastModifiedAt()).isEqualTo(saved.getLastModifiedAt());
            assertThat(loaded.getLastPermissionChangeAt()).isEqualTo(saved.getLastPermissionChangeAt());
            assertThat(loaded.getLifecycleEventTime()).isEqualTo(saved.getLifecycleEventTime());
            assertThat(loaded.getRecreatedAt()).isEqualTo(saved.getRecreatedAt());
            assertThat(loaded.getDiscoverySource()).isEqualTo("CRAWLER");
            assertThat(loaded.getRetentionDays()).isEqualTo(365);
            assertThat(loaded.getRetentionBasis()).isEqualTo(RetentionBasis.CREATED);
            assertThat(loaded.getRetentionSource()).isEqualTo("POLICY");
            assertThat(loaded.getRetentionPolicyId()).isEqualTo(policyId);
            assertThat(loaded.getRetentionExpiresAt()).isEqualTo(saved.getRetentionExpiresAt());
            assertThat(loaded.getRetentionStatus()).isEqualTo(RetentionStatus.DUE);
            assertThat(loaded.getRetentionEvaluatedAt()).isEqualTo(t);
            assertThat(loaded.isLegalHold()).isFalse();
            assertThat(loaded.retentionPermitsRetire()).isTrue();

            sf.inTransaction(session -> session.get(Asset.class, saved.getAssetId()).placeLegalHold());
            Asset held = sf.fromTransaction(session -> session.get(Asset.class, saved.getAssetId()));
            assertThat(held.isLegalHold()).isTrue();
            assertThat(held.getRetentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
            assertThat(held.retentionPermitsRetire()).isFalse();
        }
    }

    @Test
    void newProvidersAndFrozenKindsAreAccepted() throws Exception {
        String[][] rows = {
                {"DATABRICKS", "DATABRICKS_JOB"}, {"SNOWFLAKE", "SNOWFLAKE_TABLE"},
                {"AWS", "S3_OBJECT"}, {"AWS", "GLUE_DATABASE"}, {"AWS", "GLUE_TABLE"},
                {"GCP", "GCS_OBJECT"}, {"AZURE", "BLOB_CONTAINER"}, {"AZURE", "BLOB_OBJECT"},
                {"UNITY", "UNITY_VOLUME"}};
        for (String db : List.of(COMMON, BASELINE)) {
            try (Connection c = connect(db);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO dlap_common.dlap_assets"
                         + " (asset_uri, cloud_provider, service_type) VALUES (?, ?, ?)")) {
                for (String[] row : rows) {
                    ps.setString(1, "test://kinds/" + row[1] + "/" + UUID.randomUUID());
                    ps.setString(2, row[0]);
                    ps.setString(3, row[1]);
                    assertThat(ps.executeUpdate()).as("%s %s in %s", row[0], row[1], db).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void invalidValuesAreRejectedByTheDatabase() throws Exception {
        for (String db : List.of(COMMON, BASELINE)) {
            String uri = "test://invalid/" + UUID.randomUUID();
            execute(db, "INSERT INTO dlap_common.dlap_assets (asset_uri, cloud_provider, service_type)"
                    + " VALUES ('" + uri + "', 'GCP', 'GCS_BUCKET')");
            String where = " WHERE asset_uri = '" + uri + "'";

            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET size_bytes = -1" + where,
                    "ck_asset_size_bytes_nonneg");
            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET retention_days = -1" + where,
                    "ck_asset_retention_days_nonneg");
            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET retention_status = 'EXPIRED'" + where,
                    "ck_asset_retention_status_valid");
            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET retention_basis = 'DISCOVERED'" + where,
                    "ck_asset_retention_basis_valid");
            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET legal_hold = true" + where,
                    "ck_asset_legal_hold_status");
            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET legal_hold = true,"
                    + " retention_status = 'DUE'" + where, "ck_asset_legal_hold_status");
            assertCheckViolation(db, "UPDATE dlap_common.dlap_assets SET cloud_provider = 'ORACLE'" + where,
                    "ck_asset_cloud_provider_valid");
            assertThatThrownBy(() -> execute(db, "UPDATE dlap_common.dlap_assets SET retention_status = NULL" + where))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("retention_status");

            // A hold with the matching status is accepted.
            execute(db, "UPDATE dlap_common.dlap_assets SET legal_hold = true, retention_status = 'ON_HOLD'" + where);
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private static PostgreSQLContainer<?> withInitScripts(PostgreSQLContainer<?> container) {
        try (Stream<Path> files = Files.list(SchemaScripts.initScripts())) {
            for (Path script : files.sorted().toList()) {
                String name = script.getFileName().toString();
                // Mirror the git file modes of a Linux checkout: the entrypoint runs executable
                // .sh files and sources the rest, and 01-create-single-database.sh relies on
                // being run (it reads $#).
                int mode = EXECUTABLE_INIT_SCRIPTS.contains(name) ? 0755 : 0644;
                container.withCopyFileToContainer(MountableFile.forHostPath(script, mode),
                        "/docker-entrypoint-initdb.d/" + name);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list init scripts", e);
        }
        return container;
    }

    private static String readFixture() throws IOException {
        try (InputStream in = AssetSchemaUpgradeContainerTest.class.getResourceAsStream(BASELINE_FIXTURE)) {
            assertThat(in).as("fixture %s", BASELINE_FIXTURE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static void psqlCommand(String db, String sql) throws Exception {
        ExecResult r = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", SUPERUSER, "-d", db, "-c", sql);
        assertThat(r.getExitCode()).as("psql -c %s: %s", sql, r.getStderr()).isZero();
    }

    private static void psqlScript(String db, String name, String sql) throws Exception {
        String target = "/tmp/cat01-" + name;
        POSTGRES.copyFileToContainer(Transferable.of(sql), target);
        ExecResult r = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", SUPERUSER, "-d", db, "-f", target);
        assertThat(r.getExitCode()).as("psql -f %s on %s: %s", name, db, r.getStderr()).isZero();
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), SUPERUSER, SUPERUSER);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + db;
    }

    private static void execute(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void assertCheckViolation(String db, String sql, String constraint) {
        assertThatThrownBy(() -> execute(db, sql))
                .as("%s on %s", sql, db)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
    }

    private static SessionFactory sessionFactory(String db, String hbm2ddl) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.connection.url", jdbcUrl(db));
        cfg.setProperty("hibernate.connection.username", SUPERUSER);
        cfg.setProperty("hibernate.connection.password", SUPERUSER);
        cfg.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        cfg.setProperty("hibernate.hbm2ddl.auto", hbm2ddl);
        cfg.addAnnotatedClass(Asset.class);
        return cfg.buildSessionFactory();
    }

    /**
     * A comparable description of the dlap_common schema: columns, constraints, indexes, triggers
     * and column comments, each as one line, sorted.
     */
    private static List<String> schemaOf(String db) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(db)) {
            collect(c, out, "column", """
                    SELECT table_name, column_name, data_type, character_maximum_length, numeric_precision,
                           is_nullable, column_default
                    FROM information_schema.columns WHERE table_schema = 'dlap_common'""");
            collect(c, out, "constraint", """
                    SELECT rel.relname, con.conname, con.contype, pg_get_constraintdef(con.oid)
                    FROM pg_constraint con
                    JOIN pg_class rel ON rel.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = rel.relnamespace
                    WHERE n.nspname = 'dlap_common'""");
            collect(c, out, "index", """
                    SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = 'dlap_common'""");
            collect(c, out, "trigger", """
                    SELECT event_object_table, trigger_name, action_timing, event_manipulation, action_statement
                    FROM information_schema.triggers WHERE trigger_schema = 'dlap_common'""");
            collect(c, out, "comment", """
                    SELECT rel.relname, a.attname, col_description(rel.oid, a.attnum)
                    FROM pg_class rel
                    JOIN pg_namespace n ON n.oid = rel.relnamespace
                    JOIN pg_attribute a ON a.attrelid = rel.oid
                    WHERE n.nspname = 'dlap_common' AND rel.relkind = 'r' AND a.attnum > 0
                      AND NOT a.attisdropped AND col_description(rel.oid, a.attnum) IS NOT NULL""");
        }
        // The upgrade ledger (ADR-0056) comes from 02 on a fresh install and from run-upgrades.sh on an
        // upgrade. This test applies bare deltas, so it compares everything except the ledger;
        // da-catalog CommonSchemaUpgradeTest covers the ledger and the runner.
        out.removeIf(line -> line.contains("schema_upgrades"));
        out.sort(String::compareTo);
        return out;
    }

    private static void collect(Connection c, List<String> out, String kind, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder line = new StringBuilder(kind);
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    line.append(" | ").append(rs.getString(i));
                }
                out.add(line.toString());
            }
        }
    }
}
