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

package com.dlap.adminservice.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.adminservice.audit.CheckpointService.CheckpointRun;
import com.dlap.adminservice.audit.CheckpointService.StreamResult;
import com.dlap.adminservice.audit.CheckpointService.StreamStatus;
import com.dlap.adminservice.audit.export.CheckpointExportException;
import com.dlap.adminservice.audit.export.CheckpointSink;
import com.dlap.adminservice.audit.export.FileSystemCheckpointSink;
import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditTarget;
import com.dlap.common.audit.ChainVerification.Problem;

/**
 * AUD-01 end to end on PostgreSQL 15.1 booted from the platform init scripts, with the admin
 * service's Flyway V1 and V2 applied as da_admin_user: entries are appended by their writer role,
 * checkpoints are signed and exported, and three verifiers agree: {@link CheckpointService} before
 * it signs, {@link AuditVerificationService} behind the API, and {@code scripts/audit-verify.sh}
 * (run through {@code docker exec} and OpenSSL) on the exported copies. Each test tampers with a
 * stream of its own. Requires Docker; the script cases also need bash and OpenSSL 3.
 */
@Testcontainers
class AuditCheckpointContainerTest {

    private static final String SUPERUSER = "dlap";
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,"
            + "da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";
    private static final Set<String> EXECUTABLE_INIT_SCRIPTS = Set.of("01-create-single-database.sh");
    private static final String WRITER = "da_admin_user";
    /** The database audit-verify.sh reads, named to the script and to the precondition check alike (Q-788). */
    private static final String COMMON_DB = "dlap_common";
    /** Streams this class registered over JDBC; the script is only run on them after psql has seen them. */
    private static final Set<String> REGISTERED_STREAMS = ConcurrentHashMap.newKeySet();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = withInitScripts(
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                    .withDatabaseName("dlap")
                    .withUsername(SUPERUSER)
                    .withPassword(SUPERUSER)
                    .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES));

    @TempDir
    Path tmp;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
    private final KeyPair pair = AuditTestKeys.newPair();
    private Path exportDir;
    private Path publicKey;
    private AuditLogReader reader;
    private CheckpointStore store;
    private CheckpointKeys keys;

    @BeforeAll
    static void migrateAdminSchema() {
        Flyway.configure().dataSource(as(WRITER, "da_admin")).schemas("dlap_admin")
                .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
    }

    @BeforeEach
    void wire() throws IOException {
        exportDir = Files.createDirectories(tmp.resolve("export"));
        AuditProperties.Signing signing = AuditTestKeys.write(pair, tmp, "checkpoint");
        publicKey = Path.of(signing.getPublicKeyFile());
        reader = new AuditLogReader(as(WRITER, "dlap_common"));
        store = new CheckpointStore(new JdbcTemplate(as(WRITER, "da_admin")));
        keys = CheckpointKeys.load(signing);
    }

    @AfterEach
    void makeExportsDeletable() throws IOException {
        try (Stream<Path> files = Files.walk(tmp)) {
            files.map(Path::toFile).forEach(f -> f.setWritable(true));
        }
    }

    private CheckpointService service(CheckpointSink sink) {
        return new CheckpointService(reader, as(WRITER, "dlap_common"), store, keys, sink, new AuditChainVerifier(), clock);
    }

    private CheckpointService service() {
        return service(new FileSystemCheckpointSink(exportDir));
    }

    private AuditVerificationService verification() {
        return new AuditVerificationService(reader, store, keys, new AuditChainVerifier(), clock);
    }

    @Test
    void checkpointsAreSignedExportedAndVerifiedByTheServiceAndTheScript() throws Exception {
        String stream = register("test.flow");
        append(stream, 1, 3);
        CheckpointRun first = service().run();
        assertThat(result(first, stream)).isEqualTo(new StreamResult(stream, StreamStatus.SIGNED, 3L, null));
        assertThat(first.exportPending()).isZero();
        assertThat(exportDir.resolve(stream + "/00000000000000000003.json")).exists();
        assertThat(exportDir.resolve(stream + "/00000000000000000003.sig")).exists();
        assertThat(result(service().run(), stream).status()).isEqualTo(StreamStatus.UP_TO_DATE);

        append(stream, 4, 5);
        assertThat(result(service().run(), stream)).isEqualTo(new StreamResult(stream, StreamStatus.SIGNED, 5L, null));
        AuditVerificationService.Report report = verification().verify(stream).orElseThrow();
        assertThat(report.status()).isEqualTo(AuditVerificationService.Status.VALID);
        assertThat(report.headSeq()).isEqualTo(5);
        assertThat(report.checkpointsTotal()).isEqualTo(2);
        assertThat(report.checkpointsVerified()).isEqualTo(2);
        assertThat(report.latestVerifiedCheckpoint()).isEqualTo(5L);
        assertThat(report.latestCheckpointExported()).isTrue();
        assertThat(report.unanchoredEntries()).isZero();
        assertThat(store.latest(stream).orElseThrow().exportLocation()).startsWith("file:");

        Script ok = script("--stream", stream, "--checkpoints", bashPath(exportDir), "--public-key", bashPath(publicKey),
                "--require-checkpoints");
        assertThat(ok.exit()).as(ok.output()).isZero();
        assertThat(ok.output()).contains("OK stream=" + stream + " entries=5 head=5")
                .contains("checkpoints=2 latest_checkpoint=5 unanchored=0");

        append(stream, 6, 6);
        assertThat(verification().verify(stream).orElseThrow().unanchoredEntries()).isEqualTo(1);
        assertThat(script("--stream", stream, "--checkpoints", bashPath(exportDir), "--public-key", bashPath(publicKey))
                .output()).contains("unanchored=1");
    }

    @Test
    void aTamperedRowIsDetected() throws Exception {
        String stream = register("test.tamper");
        append(stream, 1, 4);
        assertThat(result(service().run(), stream).status()).isEqualTo(StreamStatus.SIGNED);

        superuser("ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " UPDATE dlap_common.audit_log SET record = replace(record, '\"outcome\":\"SUCCEEDED\"', '\"outcome\":\"DENIED\"')"
                + " WHERE stream_id = 'test.tamper' AND seq = 2;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");

        AuditVerificationService.Report report = verification().verify(stream).orElseThrow();
        assertThat(report.status()).isEqualTo(AuditVerificationService.Status.BROKEN);
        assertThat(report.chainProblem()).isEqualTo(Problem.ENTRY_HASH_MISMATCH);
        assertThat(report.firstBadSeq()).isEqualTo(2L);
        assertThat(report.headSeq()).isEqualTo(1);

        Script broken = script("--stream", stream, "--checkpoints", bashPath(exportDir), "--public-key", bashPath(publicKey));
        assertThat(broken.exit()).as(broken.output()).isEqualTo(1);
        assertThat(broken.output()).contains("BROKEN stream=" + stream + " problem=ENTRY_HASH_MISMATCH seq=2");

        // A row after the newest checkpoint that does not verify stops the signer.
        append(stream, 5, 5);
        superuser("ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " UPDATE dlap_common.audit_log SET record = replace(record, '\"n\":5', '\"n\":50')"
                + " WHERE stream_id = 'test.tamper' AND seq = 5;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        StreamResult refused = result(service().run(), stream);
        assertThat(refused.status()).isEqualTo(StreamStatus.BROKEN);
        assertThat(refused.seq()).isEqualTo(5L);
        assertThat(refused.detail()).startsWith("ENTRY_HASH_MISMATCH");
        assertThat(store.latest(stream).orElseThrow().seq()).as("nothing new was signed").isEqualTo(4);
    }

    @Test
    void aConsistentRewriteAndATruncationAreCaughtOnlyByTheSignedCheckpoints() throws Exception {
        String rewrite = register("test.rewrite");
        append(rewrite, 1, 5);
        assertThat(result(service().run(), rewrite).status()).isEqualTo(StreamStatus.SIGNED);
        superuser("ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " DO $$ DECLARE r RECORD; prev TEXT; BEGIN"
                + "   SELECT entry_hash INTO prev FROM dlap_common.audit_log WHERE stream_id = 'test.rewrite' AND seq = 2;"
                + "   FOR r IN SELECT seq, record FROM dlap_common.audit_log WHERE stream_id = 'test.rewrite' AND seq >= 3 ORDER BY seq LOOP"
                + "     UPDATE dlap_common.audit_log SET record = replace(r.record, 'SUCCEEDED', 'FAILED'), prev_hash = prev,"
                + "       entry_hash = dlap_common.audit_entry_hash('test.rewrite', r.seq, prev, replace(r.record, 'SUCCEEDED', 'FAILED'))"
                + "       WHERE stream_id = 'test.rewrite' AND seq = r.seq RETURNING entry_hash INTO prev;"
                + "   END LOOP; END $$;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        assertThat(new AuditChainVerifier().verify(rewrite, reader, List.of()).isValid())
                .as("the rewrite is internally consistent").isTrue();
        AuditVerificationService.Report report = verification().verify(rewrite).orElseThrow();
        assertThat(report.status()).isEqualTo(AuditVerificationService.Status.BROKEN);
        assertThat(report.chainProblem()).isEqualTo(Problem.ANCHOR_MISMATCH);
        assertThat(report.firstBadSeq()).isEqualTo(5L);
        Script withCheckpoints = script("--stream", rewrite, "--checkpoints", bashPath(exportDir), "--public-key",
                bashPath(publicKey));
        assertThat(withCheckpoints.exit()).as(withCheckpoints.output()).isEqualTo(1);
        assertThat(withCheckpoints.output()).contains("BROKEN stream=" + rewrite + " problem=ANCHOR_MISMATCH seq=5");
        assertThat(script("--stream", rewrite).exit()).as("without checkpoints the rewrite passes").isZero();
        assertThat(result(service().run(), rewrite).status()).as("the signer refuses too").isEqualTo(StreamStatus.BROKEN);

        String truncate = register("test.truncate");
        append(truncate, 1, 4);
        assertThat(result(service().run(), truncate).status()).isEqualTo(StreamStatus.SIGNED);
        superuser("ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " DELETE FROM dlap_common.audit_log WHERE stream_id = 'test.truncate' AND seq >= 3;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        assertThat(verification().verify(truncate).orElseThrow().chainProblem()).isEqualTo(Problem.ANCHOR_BEYOND_HEAD);
        Script cut = script("--stream", truncate, "--checkpoints", bashPath(exportDir), "--public-key", bashPath(publicKey));
        assertThat(cut.exit()).as(cut.output()).isEqualTo(1);
        assertThat(cut.output()).contains("BROKEN stream=" + truncate + " problem=ANCHOR_BEYOND_HEAD seq=4");
    }

    @Test
    void aDeletedOrReorderedRowIsDetectedByTheScriptAndTheApi() throws Exception {
        // No checkpoints: the chain alone must expose a row removed from the middle.
        String gap = register("test.gap");
        append(gap, 1, 5);
        superuser("ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " DELETE FROM dlap_common.audit_log WHERE stream_id = 'test.gap' AND seq = 3;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        AuditVerificationService.Report gapReport = verification().verify(gap).orElseThrow();
        assertThat(gapReport.status()).isEqualTo(AuditVerificationService.Status.BROKEN);
        assertThat(gapReport.chainProblem()).isEqualTo(Problem.SEQ_GAP);
        assertThat(gapReport.firstBadSeq()).isEqualTo(4L);
        Script gapScript = script("--stream", gap);
        assertThat(gapScript.exit()).as(gapScript.output()).isEqualTo(1);
        assertThat(gapScript.output()).contains("BROKEN stream=" + gap + " problem=SEQ_GAP seq=4");

        // Rows 2 and 3 swap their seq values through a free temporary seq.
        String swap = register("test.swap");
        append(swap, 1, 5);
        superuser("ALTER TABLE dlap_common.audit_log DISABLE TRIGGER tr_audit_log_append_only;"
                + " UPDATE dlap_common.audit_log SET seq = 1000 WHERE stream_id = 'test.swap' AND seq = 2;"
                + " UPDATE dlap_common.audit_log SET seq = 2 WHERE stream_id = 'test.swap' AND seq = 3;"
                + " UPDATE dlap_common.audit_log SET seq = 3 WHERE stream_id = 'test.swap' AND seq = 1000;"
                + " ALTER TABLE dlap_common.audit_log ENABLE ALWAYS TRIGGER tr_audit_log_append_only");
        AuditVerificationService.Report swapReport = verification().verify(swap).orElseThrow();
        assertThat(swapReport.status()).isEqualTo(AuditVerificationService.Status.BROKEN);
        assertThat(swapReport.chainProblem()).isEqualTo(Problem.PREV_HASH_MISMATCH);
        assertThat(swapReport.firstBadSeq()).isEqualTo(2L);
        Script swapScript = script("--stream", swap);
        assertThat(swapScript.exit()).as(swapScript.output()).isEqualTo(1);
        assertThat(swapScript.output()).contains("BROKEN stream=" + swap + " problem=PREV_HASH_MISMATCH seq=2");
    }

    @Test
    void checkpointRowsAreImmutableAndAForgedRowBreaksVerification() throws Exception {
        String stream = register("test.forge");
        append(stream, 1, 2);
        assertThat(result(service().run(), stream).status()).isEqualTo(StreamStatus.SIGNED);
        assertThatThrownBy(() -> admin("UPDATE dlap_admin.audit_checkpoints SET payload = 'x' WHERE stream_id = 'test.forge'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("never changes");
        assertThatThrownBy(() -> admin("DELETE FROM dlap_admin.audit_checkpoints WHERE stream_id = 'test.forge'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("never deleted");

        SignedCheckpoint forged = AuditTestKeys.signed(AuditTestKeys.newPair(), stream, 1);
        store.insert(new StoredCheckpoint(stream, 1, forged.checkpoint().entryHash(), forged.checkpoint().keyId(),
                forged.checkpoint().signedAt(), forged.checkpoint().payload(), forged.signature(), null, null, null, 0, null));
        AuditVerificationService.Report report = verification().verify(stream).orElseThrow();
        assertThat(report.status()).isEqualTo(AuditVerificationService.Status.BROKEN);
        assertThat(report.chainProblem()).as("the chain itself is fine").isNull();
        assertThat(report.checkpointProblems()).singleElement()
                .satisfies(p -> assertThat(p.reason()).contains("which is not trusted"));

        // An exported copy with a signature from another key fails the script.
        Path forgedDir = Files.createDirectories(tmp.resolve("forged/" + stream));
        Files.write(forgedDir.resolve("00000000000000000002.json"),
                AuditTestKeys.signed(pair, stream, 2).payloadBytes());
        Files.write(forgedDir.resolve("00000000000000000002.sig"), forged.signatureBytes());
        Script bad = script("--stream", stream, "--checkpoints", bashPath(tmp.resolve("forged")), "--public-key", bashPath(publicKey));
        assertThat(bad.exit()).as(bad.output()).isEqualTo(1);
        assertThat(bad.output()).contains("problem=BAD_SIGNATURE seq=2");
    }

    @Test
    void aRunWhileAnotherHoldsTheLockIsSkippedAndFailedExportsAreRetried() throws Exception {
        String stream = register("test.retry");
        append(stream, 1, 1);
        try (Connection holder = DriverManager.getConnection(jdbcUrl("dlap_common"), WRITER, "da_admin_pass")) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_try_advisory_xact_lock("
                    + "hashtextextended('dlap_admin.audit_checkpoints:run', 0))")) {
                assertThat(rs.next() && rs.getBoolean(1)).isTrue();
            }
            assertThat(service().run().skipped()).contains("another instance");
            holder.rollback();
        }

        CheckpointSink down = new CheckpointSink() {
            @Override
            public String name() {
                return "down";
            }

            @Override
            public void preflight() {
                throw new CheckpointExportException("bucket has no Object Lock");
            }

            @Override
            public String export(SignedCheckpoint checkpoint) {
                throw new AssertionError("export after a failed preflight");
            }
        };
        CheckpointRun failed = service(down).run();
        assertThat(result(failed, stream).status()).isEqualTo(StreamStatus.SIGNED);
        assertThat(failed.exportError()).contains("no Object Lock");
        assertThat(failed.exportPending()).isPositive();
        StoredCheckpoint pending = store.latest(stream).orElseThrow();
        assertThat(pending.isExported()).isFalse();
        assertThat(pending.lastExportError()).isEqualTo("preflight: bucket has no Object Lock");
        assertThat(pending.exportAttempts()).isEqualTo(1);

        CheckpointRun retried = service().run();
        assertThat(retried.exportPending()).isZero();
        StoredCheckpoint done = store.latest(stream).orElseThrow();
        assertThat(done.isExported()).isTrue();
        assertThat(done.lastExportError()).isNull();
        assertThat(done.exportAttempts()).isEqualTo(2);
        assertThat(done.exportSink()).isEqualTo("filesystem");
    }

    @Test
    void theScriptFailsClosedWhenItCannotQueryOrIsAskedForAnUnknownStream() throws Exception {
        Script unknown = script("--stream", "test.never_registered");
        assertThat(unknown.exit()).as(unknown.output()).isEqualTo(1);
        assertThat(unknown.output()).contains("BROKEN stream=test.never_registered problem=UNKNOWN_STREAM");

        // A role that may connect but not read the audit tables: the first audit query fails, and the
        // script stops with exit 2 instead of reporting streams it could not read.
        superuser("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aud01_noread') THEN"
                + " CREATE ROLE aud01_noread LOGIN; END IF; END $$; GRANT CONNECT ON DATABASE dlap_common TO aud01_noread");
        Script denied = scriptAs("aud01_noread", "--stream", "admin.access");
        assertThat(denied.exit()).as(denied.output()).isEqualTo(2);
        assertThat(denied.output()).contains("a verification query failed").doesNotContain("OK stream=");

        assertThat(script("--checkpoints", bashPath(exportDir)).exit()).as("checkpoints without a key").isEqualTo(2);
        assertThat(script("--stream", "Bad").exit()).isEqualTo(2);
    }

    @Test
    void theApiServicesListAndRejectUnknownStreams() {
        assertThat(verification().verify("test.never_registered")).isEmpty();
        assertThatThrownBy(() -> verification().verify("BAD")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------------

    private static StreamResult result(CheckpointRun run, String stream) {
        return run.streams().stream().filter(r -> r.streamId().equals(stream)).findFirst().orElseThrow();
    }

    private void append(String stream, int from, int to) {
        AuditLogWriter writer = new AuditLogWriter(as(WRITER, "dlap_common"), clock);
        for (int n = from; n <= to; n++) {
            writer.append(stream, AuditEvent.builder("admin.role.granted")
                    .occurredAt(Instant.parse("2026-10-21T00:00:00Z").plusSeconds(n))
                    .actor(AuditActor.user("subject-" + n))
                    .target(AuditTarget.of("ROLE", "ROLE_AUDITOR"))
                    .outcome(AuditOutcome.SUCCEEDED)
                    .detail("n", n)
                    .build());
        }
    }

    private static String register(String stream) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(COMMON_DB), SUPERUSER, SUPERUSER);
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO dlap_common.audit_streams (stream_id, writer_role, description) VALUES (?, ?, 'test')")) {
            ps.setString(1, stream);
            ps.setString(2, WRITER);
            ps.executeUpdate();
        }
        REGISTERED_STREAMS.add(stream);
        return stream;
    }

    private static void superuser(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(COMMON_DB), SUPERUSER, SUPERUSER);
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void admin(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl("da_admin"), WRITER, "da_admin_pass");
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static DataSource as(String role, String db) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl(db));
        ds.setUser(role);
        ds.setPassword(role.replaceAll("_user$", "_pass"));
        return ds;
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + db;
    }

    /** The result of one run of scripts/audit-verify.sh. */
    record Script(int exit, String output) {
    }

    /** Runs scripts/audit-verify.sh against the container through docker exec, as the superuser. */
    private Script script(String... args) throws Exception {
        return scriptAs(SUPERUSER, args);
    }

    /** Runs scripts/audit-verify.sh as a database role (local socket inside the container). */
    private Script scriptAs(String role, String... args) throws Exception {
        String bash = bash();
        assumeTrue(bash != null, "bash is not available");
        assumeTrue(succeeds("docker", "version"), "the docker CLI is not available");
        assumeTrue(succeeds("openssl", "version"), "openssl is not available");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--stream".equals(args[i]) && REGISTERED_STREAMS.contains(args[i + 1])) {
                assertPsqlSeesStream(args[i + 1]);
            }
        }
        List<String> command = new ArrayList<>(List.of(bash, bashPath(repoRoot().resolve("scripts/audit-verify.sh"))));
        command.addAll(List.of(args));
        Path out = tmp.resolve("script-" + System.nanoTime() + ".log");
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile());
        pb.environment().put("DLAP_PSQL", "docker exec -i " + POSTGRES.getContainerId() + " psql -U " + role);
        // The database is named to the script, never left to a default: DLAP_COMMON_DB_URL is what it passes to psql -d.
        pb.environment().put("DLAP_COMMON_DB_URL", COMMON_DB);
        pb.environment().put("PGDATABASE", COMMON_DB);
        // The script must cope with this variable, which some Git Bash setups export (SEC-06 note).
        pb.environment().put("MSYS_NO_PATHCONV", "1");
        Process p = pb.start();
        assertThat(p.waitFor(180, TimeUnit.SECONDS)).as("audit-verify.sh finished").isTrue();
        return new Script(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
    }

    /**
     * Q-788: fails with a clear message, before the script runs, when psql inside the container does not see a
     * stream this test registered over JDBC. CI once saw the script report UNKNOWN_STREAM for a stream the test
     * had registered a few lines earlier; this check tells a registration that psql cannot see (the database,
     * the container or the commit) from a fault of the script. It does not retry: a registration that is not
     * visible at once is a failure to explain, not to wait out.
     */
    private static void assertPsqlSeesStream(String stream) throws Exception {
        List<String> command = List.of("docker", "exec", "-i", POSTGRES.getContainerId(), "psql", "-U", SUPERUSER,
                "-d", COMMON_DB, "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c",
                "SELECT stream_id FROM dlap_common.audit_streams ORDER BY stream_id");
        Path out = Files.createTempFile("psql-streams", ".log");
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("psql in the container finished").isTrue();
            String text = Files.readString(out, StandardCharsets.UTF_8).replace("\r", "").strip();
            assertThat(p.exitValue()).as("psql in container " + POSTGRES.getContainerId() + " against database "
                    + COMMON_DB + " ran: " + text).isZero();
            List<String> seen = text.isEmpty() ? List.of() : List.of(text.split("\n"));
            assertThat(seen).as("psql in container " + POSTGRES.getContainerId() + " does not see the stream '"
                    + stream + "' that this test registered over JDBC in database " + COMMON_DB
                    + " (audit-verify.sh would report UNKNOWN_STREAM); registered streams seen by psql").contains(stream);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static String bash() {
        String override = System.getenv("DLAP_TEST_BASH");
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (File.separatorChar == '\\') {
            for (String candidate : List.of("C:\\Program Files\\Git\\bin\\bash.exe", "C:\\Program Files\\Git\\usr\\bin\\bash.exe")) {
                if (new File(candidate).isFile()) {
                    return candidate;
                }
            }
            return null;
        }
        return succeeds("bash", "-c", "true") ? "bash" : null;
    }

    private static boolean succeeds(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A path as bash on this platform reads it: forward slashes. */
    private static String bashPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("scripts/audit-verify.sh"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("scripts/audit-verify.sh not found");
    }

    private static PostgreSQLContainer<?> withInitScripts(PostgreSQLContainer<?> container) {
        Path scripts = repoRoot().resolve("deployment/platform/database/init-scripts");
        try (Stream<Path> files = Files.list(scripts)) {
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
