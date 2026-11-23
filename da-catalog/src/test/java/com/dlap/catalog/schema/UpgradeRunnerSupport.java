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

package com.dlap.catalog.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Test support for the dlap_common upgrade runner (DB-02, ADR-0056): builds PostgreSQL 15.1
 * containers from init scripts, installs the real {@code run-upgrades.sh} and its deltas inside
 * them, runs the script the way an operator does, and reads the ledger and the schema back.
 *
 * <p>The runner runs inside the container, so the tests need Docker and nothing else: no host
 * {@code psql}, {@code bash} or {@code sha256sum}.
 */
final class UpgradeRunnerSupport {

    /** Superuser the container is created with; the runner needs an owner-level role. */
    static final String SUPERUSER = "dlap";

    /** Where the runner script is installed in the container. */
    static final String RUNNER_PATH = "/upgrades/run-upgrades.sh";

    /**
     * The database list compose passes to the init script
     * ({@code deployment/environments/dev/docker-compose.yml}, POSTGRES_MULTIPLE_DATABASES).
     * Script 01 and the grants in 02 name a user of every service, so each must exist.
     */
    static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    /** Classpath folder holding the init scripts of the previous release (sdg 7984d1d). */
    static final String BASELINE_RESOURCE_DIR = "db/init-scripts-7984d1d/";

    /** The init scripts in that folder, copied verbatim from {@code git show 7984d1d:...}. */
    static final List<String> BASELINE_SCRIPTS = List.of(
            "00-create-multiple-databases.sh",
            "01-create-schemas-and-users.sql",
            "01-create-single-database.sh",
            "01-init-extensions.sql",
            "02-create-common-schema.sql",
            "03-insert-test-data.sql");

    private static final Pattern APPLIED = Pattern.compile("applied=(\\d+)");
    private static final Pattern SKIPPED = Pattern.compile("skipped=(\\d+)");

    /**
     * One structural fingerprint line per column, constraint, index, trigger and comment in
     * schema {@code dlap_common}. Rows and privileges are not part of it.
     */
    private static final String SNAPSHOT_SQL = """
            SELECT line FROM (
              SELECT 'col ' || c.table_name || '.' || c.column_name || ' ' || c.data_type
                     || COALESCE('(' || c.character_maximum_length || ')', '')
                     || ' null=' || c.is_nullable || ' default=' || COALESCE(c.column_default, '') AS line
                FROM information_schema.columns c WHERE c.table_schema = 'dlap_common'
              UNION ALL
              SELECT 'con ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
                FROM pg_constraint WHERE connamespace = 'dlap_common'::regnamespace
              UNION ALL
              SELECT 'idx ' || indexdef FROM pg_indexes WHERE schemaname = 'dlap_common'
              UNION ALL
              SELECT 'trg ' || pg_get_triggerdef(t.oid)
                FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid
               WHERE NOT t.tgisinternal AND r.relnamespace = 'dlap_common'::regnamespace
              UNION ALL
              SELECT 'cmt ' || r.relname || ' ' || obj_description(r.oid, 'pg_class')
                FROM pg_class r WHERE r.relnamespace = 'dlap_common'::regnamespace AND r.relkind = 'r'
                 AND obj_description(r.oid, 'pg_class') IS NOT NULL
              UNION ALL
              SELECT 'cmt ' || r.relname || '.' || a.attname || ' ' || col_description(r.oid, a.attnum)
                FROM pg_class r JOIN pg_attribute a ON a.attrelid = r.oid
               WHERE r.relnamespace = 'dlap_common'::regnamespace AND r.relkind = 'r'
                 AND a.attnum > 0 AND NOT a.attisdropped AND col_description(r.oid, a.attnum) IS NOT NULL
            ) s ORDER BY line
            """;

    private UpgradeRunnerSupport() {
    }

    /** Outcome of one runner invocation. */
    record Run(int exitCode, String out, String err) {

        /** The {@code applied=N} figure of the summary line, or -1 when the run printed none. */
        int applied() {
            return figure(APPLIED);
        }

        /** The {@code skipped=N} figure of the summary line, or -1 when the run printed none. */
        int skipped() {
            return figure(SKIPPED);
        }

        private int figure(Pattern pattern) {
            Matcher m = pattern.matcher(out);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        /** Both streams, for assertion messages. */
        String transcript() {
            return "exit " + exitCode + "\n--- stdout\n" + out + "\n--- stderr\n" + err;
        }
    }

    // ---- containers -------------------------------------------------------------------------

    /** A container built from the previous release's init scripts (the fixtures in test resources). */
    static PostgreSQLContainer<?> baselinePostgres() {
        PostgreSQLContainer<?> postgres = newPostgres();
        for (String name : BASELINE_SCRIPTS) {
            postgres.withCopyFileToContainer(
                    MountableFile.forClasspathResource(BASELINE_RESOURCE_DIR + name, modeOf(name)),
                    "/docker-entrypoint-initdb.d/" + name);
        }
        return postgres;
    }

    /** A container built from the init scripts in the working tree, the way compose builds it. */
    static PostgreSQLContainer<?> currentPostgres() {
        PostgreSQLContainer<?> postgres = newPostgres();
        try (Stream<Path> scripts = Files.list(initScriptsDir())) {
            scripts.filter(Files::isRegularFile).sorted().forEach(script -> {
                String name = script.getFileName().toString();
                postgres.withCopyFileToContainer(
                        MountableFile.forHostPath(script, modeOf(name)), "/docker-entrypoint-initdb.d/" + name);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the platform init scripts", e);
        }
        return postgres;
    }

    /** A bare container with no init scripts, for the runner's own behaviour tests. */
    static PostgreSQLContainer<?> barePostgres() {
        return newPostgres();
    }

    private static PostgreSQLContainer<?> newPostgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername(SUPERUSER)
                .withPassword(SUPERUSER)
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES);
    }

    private static int modeOf(String scriptName) {
        return scriptName.endsWith(".sh") ? 0755 : 0644;
    }

    // ---- repository paths -------------------------------------------------------------------

    /** The repository root, found by walking up from the working directory. */
    static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("deployment/platform/database/upgrades"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "deployment/platform/database/upgrades not found above " + System.getProperty("user.dir"));
    }

    /** The shipped init scripts. */
    static Path initScriptsDir() {
        return repoRoot().resolve("deployment/platform/database/init-scripts");
    }

    /** The shipped deltas and the runner. */
    static Path upgradesDir() {
        return repoRoot().resolve("deployment/platform/database/upgrades");
    }

    /** The shipped delta files, in the order the runner applies them. */
    static List<Path> shippedDeltas() {
        try (Stream<Path> files = Files.list(upgradesDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the shipped deltas", e);
        }
    }

    // ---- installing and running the script --------------------------------------------------

    /** Copies the shipped {@code run-upgrades.sh} to {@link #RUNNER_PATH}. */
    static void installRunner(PostgreSQLContainer<?> container) {
        container.copyFileToContainer(
                MountableFile.forHostPath(upgradesDir().resolve("run-upgrades.sh"), 0755), RUNNER_PATH);
    }

    /** Copies the shipped deltas into {@code containerDir}. */
    static void installShippedDeltas(PostgreSQLContainer<?> container, String containerDir) {
        exec(container, "mkdir", "-p", containerDir);
        for (Path delta : shippedDeltas()) {
            container.copyFileToContainer(
                    MountableFile.forHostPath(delta, 0644), containerDir + "/" + delta.getFileName());
        }
    }

    /** Writes one file into {@code containerDir}. */
    static void putFile(PostgreSQLContainer<?> container, String containerDir, String name, String content) {
        container.copyFileToContainer(
                Transferable.of(content.getBytes(StandardCharsets.UTF_8)), containerDir + "/" + name);
    }

    /**
     * Runs the installed script as the superuser against {@code database}, reading deltas from
     * {@code containerDir}.
     */
    static Run run(PostgreSQLContainer<?> container, String database, String containerDir, String... args) {
        return runWithEnv(container, List.of("PGUSER=" + SUPERUSER, "PGDATABASE=" + database), containerDir, args);
    }

    /** Like {@link #run} with an explicit environment, for connection-variable tests. */
    static Run runWithEnv(PostgreSQLContainer<?> container, List<String> env, String containerDir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("env");
        cmd.addAll(env);
        cmd.addAll(List.of("bash", RUNNER_PATH, "--dir", containerDir));
        cmd.addAll(List.of(args));
        ExecResult result = execRaw(container, cmd.toArray(String[]::new));
        return new Run(result.getExitCode(), result.getStdout(), result.getStderr());
    }

    // ---- SQL and ledger access --------------------------------------------------------------

    /** Runs {@code sql} as the superuser and returns trimmed unaligned output; fails on any SQL error. */
    static String sql(PostgreSQLContainer<?> container, String database, String sql) {
        ExecResult r = execRaw(container, "psql", "-U", SUPERUSER, "-d", database,
                "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed (" + r.getExitCode() + "): " + r.getStderr()
                    + "\nSQL: " + sql);
        }
        return r.getStdout().trim();
    }

    /** Runs {@code sql} as {@code user} and returns the raw result, so a test can expect a failure. */
    static ExecResult sqlAs(PostgreSQLContainer<?> container, String user, String database, String sql) {
        return execRaw(container, "psql", "-U", user, "-d", database, "-X", "-q", "-v", "ON_ERROR_STOP=1",
                "-At", "-c", sql);
    }

    /** Creates an empty database owned by the superuser. */
    static void createDatabase(PostgreSQLContainer<?> container, String name) {
        sql(container, "dlap", "CREATE DATABASE " + name);
    }

    /** Ledger rows as {@code file_name|checksum_sha256}, in file-name order; empty when there is no ledger. */
    static List<String> ledger(PostgreSQLContainer<?> container, String database) {
        if (!"t".equals(sql(container, database, "SELECT to_regclass('dlap_common.schema_upgrades') IS NOT NULL"))) {
            return List.of();
        }
        String rows = sql(container, database, "SELECT file_name || '|' || checksum_sha256 "
                + "FROM dlap_common.schema_upgrades ORDER BY file_name");
        return rows.isEmpty() ? List.of() : List.of(rows.split("\n"));
    }

    /** Ledger rows with their timestamps, to prove a later run did not rewrite them. */
    static String ledgerWithTimes(PostgreSQLContainer<?> container, String database) {
        return sql(container, database, "SELECT file_name || '|' || checksum_sha256 || '|' || applied_at "
                + "FROM dlap_common.schema_upgrades ORDER BY file_name");
    }

    /** The structural fingerprint of schema {@code dlap_common} in {@code database}. */
    static List<String> schemaSnapshot(PostgreSQLContainer<?> container, String database) {
        return List.of(sql(container, database, SNAPSHOT_SQL).split("\n"));
    }

    /** True when {@code table} exists in schema {@code dlap_common}. */
    static boolean tableExists(PostgreSQLContainer<?> container, String database, String table) {
        return "t".equals(sql(container, database, "SELECT to_regclass('dlap_common." + table + "') IS NOT NULL"));
    }

    // ---- checksums --------------------------------------------------------------------------

    /** SHA-256 hex of the file with carriage returns removed, the value the runner records. */
    static String checksum(Path file) {
        try {
            return checksum(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** SHA-256 hex of the text with carriage returns removed. */
    static String checksum(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.replace("\r", "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- exec -------------------------------------------------------------------------------

    private static void exec(PostgreSQLContainer<?> container, String... command) {
        ExecResult r = execRaw(container, command);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + r.getStderr());
        }
    }

    private static ExecResult execRaw(PostgreSQLContainer<?> container, String... command) {
        try {
            return container.execInContainer(command);
        } catch (IOException e) {
            throw new UncheckedIOException("exec failed: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + String.join(" ", command), e);
        }
    }
}
