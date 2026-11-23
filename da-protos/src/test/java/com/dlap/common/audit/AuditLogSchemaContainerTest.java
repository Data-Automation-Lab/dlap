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

package com.dlap.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.common.audit.ChainVerification.Problem;

/**
 * AUD-01 schema and trigger tests on the PostgreSQL version the stacks run (15.1), booted from the
 * real init scripts exactly like the dev compose. Each test works on a stream of its own, which the
 * superuser registers, so the tests do not depend on each other's rows. Requires Docker.
 */
@Testcontainers
class AuditLogSchemaContainerTest {

    private static final String SUPERUSER = "dlap";
    private static final String COMMON = "dlap_common";
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,"
            + "da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";
    private static final Set<String> EXECUTABLE_INIT_SCRIPTS = Set.of("01-create-single-database.sh");
    private static final List<String> SERVICE_ROLES = List.of("da_catalog_user", "da_discovery_user",
            "da_policyengine_user", "da_reporting_user", "da_autolabel_user", "da_autoarchival_user",
            "da_autodelete_user", "da_autocompliance_user", "da_admin_user", "da_logingest_user");
    private static final String DELTA = "2026-10-21_audit_log.sql";
    private static final String BLOCK_START = "-- Tamper-evident audit log (AUD-01;";
    private static final String BLOCK_END = "-- End of the audit log block (AUD-01).";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = withInitScripts(
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                    .withDatabaseName("dlap")
                    .withUsername(SUPERUSER)
                    .withPassword(SUPERUSER)
                    .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void theSixPlannedStreamsAreSeededWithTheirWriters() {
        List<AuditStream> streams = new AuditLogReader(as(SUPERUSER)).streams();
        assertThat(streams).extracting(AuditStream::streamId, AuditStream::writerRole).contains(
                org.assertj.core.groups.Tuple.tuple(AuditStreams.RETIRE_DELETE, "da_autodelete_user"),
                org.assertj.core.groups.Tuple.tuple(AuditStreams.RETIRE_ARCHIVE, "da_autoarchival_user"),
                org.assertj.core.groups.Tuple.tuple(AuditStreams.GOVERN_APPROVAL, "da_policyengine_user"),
                org.assertj.core.groups.Tuple.tuple(AuditStreams.GOVERN_POLICY, "da_policyengine_user"),
                org.assertj.core.groups.Tuple.tuple(AuditStreams.ADMIN_ACCESS, "da_admin_user"),
                org.assertj.core.groups.Tuple.tuple(AuditStreams.REPORT_EXPORT, "da_reporting_user"));
    }

    @Test
    void theDatabaseHashFunctionMatchesTheSpecVectors() throws Exception {
        try (Connection c = connect("da_reporting_user"); PreparedStatement ps = c.prepareStatement(
                "SELECT dlap_common.audit_entry_hash('retire.delete', ?, ?, ?)")) {
            ps.setLong(1, 1);
            ps.setString(2, AuditChain.GENESIS_HASH);
            ps.setString(3, AuditChainTest.EXAMPLE_RECORD);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(AuditChainTest.EXAMPLE_HASH_1);
            }
            ps.setLong(1, 2);
            ps.setString(2, AuditChainTest.EXAMPLE_HASH_1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(AuditChainTest.EXAMPLE_HASH_2);
            }
        }
    }

    @Test
    void theWriterRoleAppendsAChainThatVerifiesAndTheTriggerAgreesOnEveryHash() throws Exception {
        String stream = register("test.append", "da_autodelete_user");
        AuditLogWriter writer = new AuditLogWriter(as("da_autodelete_user"), CLOCK);
        List<AuditEntry> written = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            written.add(writer.append(stream, event("retire.delete.executed", i, null)));
        }
        assertThat(written).extracting(AuditEntry::seq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(written.get(0).prevHash()).isEqualTo(AuditChain.GENESIS_HASH);
        assertThat(written.get(3).entryHash()).isEqualTo(written.get(4).prevHash());

        AuditLogReader reader = new AuditLogReader(as("da_reporting_user"));
        assertThat(reader.page(stream, 0, 100)).containsExactlyElementsOf(written);
        assertThat(reader.head(stream)).contains(written.get(4));
        assertThat(reader.entry(stream, 3)).contains(written.get(2));
        assertThat(new AuditChainVerifier(2).verify(stream, reader, List.of()).isValid()).isTrue();
        try (Connection c = connect(SUPERUSER); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT event_type, target_id, correlation_id FROM dlap_common.audit_log"
                        + " WHERE stream_id = 'test.append' AND seq = 2")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("retire.delete.executed");
            assertThat(rs.getString(2)).isEqualTo("s3://finance-reports");
            assertThat(rs.getString(3)).isEqualTo("corr-2");
        }
    }

    @Test
    void concurrentWritersProduceOneContiguousChain() throws Exception {
        String stream = register("test.concurrent", "da_autodelete_user");
        AuditLogWriter writer = new AuditLogWriter(as("da_autodelete_user"), CLOCK);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AuditEntry>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                for (int i = 0; i < 25; i++) {
                    int n = thread * 100 + i;
                    futures.add(pool.submit(() -> writer.append(stream, event("retire.delete.requested", n, null))));
                }
            }
            List<Long> seqs = new ArrayList<>();
            for (Future<AuditEntry> f : futures) {
                seqs.add(f.get(120, TimeUnit.SECONDS).seq());
            }
            Collections.sort(seqs);
            assertThat(seqs).hasSize(200).first().isEqualTo(1L);
            assertThat(seqs.get(199)).isEqualTo(200L);
            assertThat(Set.copyOf(seqs)).hasSize(200);
        } finally {
            pool.shutdownNow();
        }
        ChainVerification v = new AuditChainVerifier(64).verify(stream, new AuditLogReader(as(SUPERUSER)), List.of());
        assertThat(v.isValid()).isTrue();
        assertThat(v.entriesChecked()).isEqualTo(200);
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheFirstEntryAndAChangedEventIsRefused() throws Exception {
        String stream = register("test.idempotent", "da_autodelete_user");
        AuditLogWriter first = new AuditLogWriter(as("da_autodelete_user"), CLOCK);
        AuditLogWriter later = new AuditLogWriter(as("da_autodelete_user"),
                Clock.offset(CLOCK, java.time.Duration.ofMinutes(5)));
        AuditEntry a = first.append(stream, event("retire.delete.executed", 1, "task-1:executed"));
        AuditEntry again = later.append(stream, event("retire.delete.executed", 1, "task-1:executed"));
        assertThat(again).isEqualTo(a);
        assertThat(new AuditLogReader(as(SUPERUSER)).page(stream, 0, 10)).hasSize(1);
        assertThatThrownBy(() -> later.append(stream, event("retire.delete.executed", 2, "task-1:executed")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different event");
        assertThat(later.append(stream, event("retire.delete.executed", 2, "task-2:executed")).seq()).isEqualTo(2);
    }

    @Test
    void onlyTheWriterRoleMayAppendAndOnlyToARegisteredStream() throws Exception {
        String stream = register("test.writer", "da_autodelete_user");
        assertThatThrownBy(() -> new AuditLogWriter(as("da_reporting_user"), CLOCK).append(stream, event("a.b", 1, null)))
                .isInstanceOf(AuditException.class).hasMessageContaining("may not write audit stream");
        assertThatThrownBy(() -> new AuditLogWriter(as("da_autodelete_user"), CLOCK).append("test.unregistered",
                event("a.b", 1, null))).isInstanceOf(AuditException.class).hasMessageContaining("is not registered");
        // A stream whose writer role does not exist accepts nobody, not even a superuser.
        String orphan = register("test.orphan", "no_such_role");
        assertThatThrownBy(() -> new AuditLogWriter(as(SUPERUSER), CLOCK).append(orphan, event("a.b", 1, null)))
                .isInstanceOf(AuditException.class).hasMessageContaining("may not write audit stream");
        // Service roles cannot register streams.
        assertThatThrownBy(() -> execute("da_autodelete_user",
                "INSERT INTO dlap_common.audit_streams VALUES ('test.self', 'da_autodelete_user', 'x')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    }

    @Test
    void updateDeleteAndTruncateAreRejectedForServiceRolesAndForTheOwner() throws Exception {
        String stream = register("test.immutable", "da_autodelete_user");
        new AuditLogWriter(as("da_autodelete_user"), CLOCK).append(stream, event("a.b", 1, null));
        String update = "UPDATE dlap_common.audit_log SET record = '{}' WHERE stream_id = 'test.immutable'";
        String delete = "DELETE FROM dlap_common.audit_log WHERE stream_id = 'test.immutable'";
        for (String role : SERVICE_ROLES) {
            for (String sql : List.of(update, delete, "TRUNCATE dlap_common.audit_log",
                    "UPDATE dlap_common.audit_streams SET writer_role = 'da_admin_user'",
                    "DELETE FROM dlap_common.audit_streams")) {
                assertThatThrownBy(() -> execute(role, sql)).as("%s as %s", sql, role)
                        .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            }
        }
        for (String sql : List.of(update, delete, "TRUNCATE dlap_common.audit_log",
                "UPDATE dlap_common.audit_streams SET description = 'x' WHERE stream_id = 'test.immutable'",
                "DELETE FROM dlap_common.audit_streams WHERE stream_id = 'test.immutable'",
                "TRUNCATE dlap_common.audit_streams CASCADE")) {
            assertThatThrownBy(() -> execute(SUPERUSER, sql)).as("%s as the owner", sql)
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> execute(SUPERUSER, "SET session_replication_role = replica; " + sql))
                    .as("%s as the owner in replica mode", sql)
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
        assertThat(new AuditLogReader(as(SUPERUSER)).page(stream, 0, 10)).hasSize(1);
    }

    @Test
    void theInsertTriggerRejectsAForkAWrongHashAndANonAsciiRecord() throws Exception {
        String stream = register("test.fork", "da_autodelete_user");
        AuditEntry first = new AuditLogWriter(as("da_autodelete_user"), CLOCK).append(stream, event("a.b", 1, null));
        String record = AuditChain.record(event("a.b", 2, null), CLOCK.instant());
        String good = AuditChain.entryHash(stream, 2, first.entryHash(), record);
        // Same seq again (a fork), a gap, a wrong link, a wrong hash.
        assertInsertRefused(stream, 1, AuditChain.GENESIS_HASH, record, "does not extend the head");
        assertInsertRefused(stream, 3, first.entryHash(), record, "does not extend the head");
        assertInsertRefused(stream, 2, AuditChain.GENESIS_HASH, record, "does not extend the head");
        assertThatThrownBy(() -> insert(stream, 2, first.entryHash(), "0".repeat(64), record))
                .isInstanceOf(SQLException.class).hasMessageContaining("wrong entry_hash");
        String nonAscii = record.replace("test", "t\u00e9st");
        assertThatThrownBy(() -> insert(stream, 2, first.entryHash(),
                AuditChain.entryHash(stream, 2, first.entryHash(), nonAscii), nonAscii))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_audit_log_record_ascii");
        insert(stream, 2, first.entryHash(), good, record);
        assertThat(new AuditChainVerifier().verify(stream, new AuditLogReader(as(SUPERUSER)), List.of()).isValid())
                .isTrue();
    }

    @Test
    void aTamperedRowIsDetected() throws Exception {
        String stream = register("test.tamper", "da_autodelete_user");
        AuditLogWriter writer = new AuditLogWriter(as("da_autodelete_user"), CLOCK);
        List<AuditEntry> written = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            written.add(writer.append(stream, event("retire.delete.executed", i, null)));
        }
        AuditLogReader reader = new AuditLogReader(as(SUPERUSER));
        List<ChainAnchor> anchors = List.of(new ChainAnchor(6, written.get(5).entryHash()));
        assertThat(new AuditChainVerifier().verify(stream, reader, anchors).isValid()).isTrue();

        // The owner turns the guard off, edits seq 3 and turns it back on: only a superuser can.
        execute(SUPERUSER, "ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " UPDATE dlap_common.audit_log SET record = replace(record, '\"outcome\":\"SUCCEEDED\"',"
                + " '\"outcome\":\"FAILED\"') WHERE stream_id = 'test.tamper' AND seq = 3;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        ChainVerification v = new AuditChainVerifier().verify(stream, reader, anchors);
        assertThat(v.isValid()).isFalse();
        assertThat(v.problem()).isEqualTo(Problem.ENTRY_HASH_MISMATCH);
        assertThat(v.firstBadSeq()).isEqualTo(3L);

        // Recomputing that hash only moves the break to the next link.
        execute(SUPERUSER, "ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " UPDATE dlap_common.audit_log SET entry_hash = dlap_common.audit_entry_hash(stream_id, seq, prev_hash,"
                + " record) WHERE stream_id = 'test.tamper' AND seq = 3;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        v = new AuditChainVerifier().verify(stream, reader, anchors);
        assertThat(v.problem()).isEqualTo(Problem.PREV_HASH_MISMATCH);
        assertThat(v.firstBadSeq()).isEqualTo(4L);
    }

    @Test
    void privilegesAreSelectAndInsertOnlyAndNoServiceRoleOwnsTheTables() throws Exception {
        assertNarrowPrivileges();
    }

    @Test
    void theDeltaEqualsTheInitScriptBlockAndBothCanBeRerun() throws Exception {
        String init = read(scripts().resolve("init-scripts/02-create-common-schema.sql"));
        String delta = read(scripts().resolve("upgrades/" + DELTA));
        assertThat(block(delta)).isEqualTo(block(init));

        List<String> before = auditSchema();
        psqlScript("delta-1.sql", delta);
        psqlScript("delta-2.sql", delta);
        assertThat(auditSchema()).isEqualTo(before);
        psqlScript("02-again.sql", init);
        assertThat(auditSchema()).isEqualTo(before);
        assertNarrowPrivileges();
        assertThat(new AuditLogReader(as(SUPERUSER)).streams()).extracting(AuditStream::streamId)
                .contains(AuditStreams.RETIRE_DELETE);
    }

    @Test
    void theDeltaRefusesToRunAsAServiceRole() throws Exception {
        String delta = read(scripts().resolve("upgrades/" + DELTA));
        POSTGRES.copyFileToContainer(Transferable.of(delta), "/tmp/aud01-delta-svc.sql");
        ExecResult r = POSTGRES.execInContainer("env", "PGPASSWORD=da_catalog_pass", "psql", "-h", "localhost",
                "-v", "ON_ERROR_STOP=1", "-U", "da_catalog_user", "-d", COMMON, "-f", "/tmp/aud01-delta-svc.sql");
        assertThat(r.getExitCode()).isNotZero();
        assertThat(r.getStderr()).contains("must run as a superuser");
    }

    // ---------------------------------------------------------------------------------------------

    private static void assertNarrowPrivileges() throws SQLException {
        try (Connection c = connect(SUPERUSER); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT c.relname, pg_get_userbyid(c.relowner), r.rolsuper,
                       coalesce(string_agg(CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE a.grantee::regrole::text END
                                || ':' || a.privilege_type, ',' ORDER BY 1)
                                FILTER (WHERE a.grantee <> c.relowner), '')
                FROM pg_class c
                JOIN pg_roles r ON r.oid = c.relowner
                LEFT JOIN LATERAL aclexplode(c.relacl) a ON true
                WHERE c.oid IN ('dlap_common.audit_log'::regclass, 'dlap_common.audit_streams'::regclass)
                GROUP BY c.relname, c.relowner, r.rolsuper""")) {
            int tables = 0;
            while (rs.next()) {
                tables++;
                String table = rs.getString(1);
                assertThat(rs.getBoolean(3)).as("%s is owned by a superuser", table).isTrue();
                assertThat(SERVICE_ROLES).doesNotContain(rs.getString(2));
                List<String> grants = List.of(rs.getString(4).split(","));
                Set<String> allowed = "audit_log".equals(table) ? Set.of("SELECT", "INSERT") : Set.of("SELECT");
                for (String grant : grants) {
                    String role = grant.substring(0, grant.indexOf(':'));
                    String privilege = grant.substring(grant.indexOf(':') + 1);
                    assertThat(role).as("grantee on %s", table).isIn(SERVICE_ROLES);
                    assertThat(privilege).as("%s on %s", role, table).isIn(allowed);
                }
                for (String role : SERVICE_ROLES) {
                    for (String privilege : allowed) {
                        assertThat(grants).as("%s on %s", role, table).contains(role + ":" + privilege);
                    }
                }
            }
            assertThat(tables).isEqualTo(2);
        }
    }

    private List<String> auditSchema() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(SUPERUSER); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT 'column ' || table_name || ' ' || column_name || ' ' || data_type || ' ' || is_nullable
                       || ' ' || coalesce(column_default, '') || ' ' || coalesce(generation_expression, '')
                FROM information_schema.columns WHERE table_schema = 'dlap_common' AND table_name LIKE 'audit%'
                UNION ALL
                SELECT 'constraint ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
                FROM pg_constraint WHERE conrelid IN ('dlap_common.audit_log'::regclass, 'dlap_common.audit_streams'::regclass)
                UNION ALL
                SELECT 'index ' || indexname || ' ' || indexdef FROM pg_indexes
                WHERE schemaname = 'dlap_common' AND tablename LIKE 'audit%'
                UNION ALL
                SELECT 'trigger ' || tgrelid::regclass || ' ' || tgname || ' ' || tgenabled::text || ' ' || pg_get_triggerdef(oid)
                FROM pg_trigger WHERE tgrelid IN ('dlap_common.audit_log'::regclass, 'dlap_common.audit_streams'::regclass)
                UNION ALL
                SELECT 'function ' || p.proname || ' ' || md5(pg_get_functiondef(p.oid))
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'dlap_common' AND p.proname LIKE 'audit%'
                UNION ALL
                SELECT 'acl ' || relname || ' ' || coalesce(relacl::text, '') FROM pg_class
                WHERE oid IN ('dlap_common.audit_log'::regclass, 'dlap_common.audit_streams'::regclass)
                UNION ALL
                SELECT 'stream ' || stream_id || ' ' || writer_role FROM dlap_common.audit_streams
                WHERE stream_id NOT LIKE 'test.%'""")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        out.sort(String::compareTo);
        assertThat(out).anyMatch(l -> l.startsWith("trigger dlap_common.audit_log tr_audit_log_append_only A "));
        return out;
    }

    private static String block(String sql) {
        int start = sql.indexOf(BLOCK_START);
        int end = sql.indexOf(BLOCK_END);
        assertThat(start).as("block start").isPositive();
        assertThat(end).as("block end").isGreaterThan(start);
        // The block begins at the "====" rule line above the title.
        int ruleStart = sql.lastIndexOf("\n-- ====", start) + 1;
        return sql.substring(ruleStart, end + BLOCK_END.length());
    }

    private static AuditEvent event(String type, int n, String idempotencyKey) {
        return AuditEvent.builder(type)
                .occurredAt(Instant.parse("2026-10-21T00:00:00Z").plusSeconds(n))
                .actor(AuditActor.user("subject-" + n))
                .target(AuditTarget.asset("arn:aws:s3:::finance-reports"))
                .outcome(AuditOutcome.SUCCEEDED)
                .correlationId("corr-" + n)
                .idempotencyKey(idempotencyKey)
                .detail("n", n)
                .detail("label", "test")
                .build();
    }

    private static String register(String stream, String writer) throws SQLException {
        try (Connection c = connect(SUPERUSER); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dlap_common.audit_streams (stream_id, writer_role, description) VALUES (?, ?, 'test')")) {
            ps.setString(1, stream);
            ps.setString(2, writer);
            ps.executeUpdate();
        }
        return stream;
    }

    private static void insert(String stream, long seq, String prev, String hash, String record) throws SQLException {
        try (Connection c = connect("da_autodelete_user"); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dlap_common.audit_log (stream_id, seq, prev_hash, entry_hash, record) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, stream);
            ps.setLong(2, seq);
            ps.setString(3, prev);
            ps.setString(4, hash);
            ps.setString(5, record);
            ps.executeUpdate();
        }
    }

    private static void assertInsertRefused(String stream, long seq, String prev, String record, String message) {
        assertThatThrownBy(() -> insert(stream, seq, prev, AuditChain.entryHash(stream, seq, prev, record), record))
                .as("insert seq %d", seq).isInstanceOf(SQLException.class).hasMessageContaining(message);
    }

    static DataSource as(String role) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl());
        ds.setUser(role);
        ds.setPassword(password(role));
        return ds;
    }

    private static String password(String role) {
        return SUPERUSER.equals(role) ? SUPERUSER : role.replaceAll("_user$", "_pass");
    }

    private static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, password(role));
    }

    private static void execute(String role, String sql) throws SQLException {
        try (Connection c = connect(role); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + COMMON;
    }

    private static void psqlScript(String name, String sql) throws Exception {
        String target = "/tmp/aud01-" + name;
        POSTGRES.copyFileToContainer(Transferable.of(sql), target);
        ExecResult r = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", SUPERUSER, "-d", COMMON, "-f", target);
        assertThat(r.getExitCode()).as("psql -f %s: %s", name, r.getStderr()).isZero();
    }

    /** {@code deployment/platform/database}, found by walking up from the working directory. */
    static Path scripts() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database");
            if (Files.isRegularFile(candidate.resolve("init-scripts/02-create-common-schema.sql"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deployment/platform/database not found");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static PostgreSQLContainer<?> withInitScripts(PostgreSQLContainer<?> container) {
        try (Stream<Path> files = Files.list(scripts().resolve("init-scripts"))) {
            for (Path script : files.sorted().toList()) {
                String name = script.getFileName().toString();
                int mode = EXECUTABLE_INIT_SCRIPTS.contains(name) ? 0755 : 0644;
                container.withCopyFileToContainer(MountableFile.forHostPath(script, mode),
                        "/docker-entrypoint-initdb.d/" + name);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list init scripts", e);
        }
        return container;
    }
}
