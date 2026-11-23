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

package com.dlap.policyengine.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A PostgreSQL booted from the platform init scripts (00 to 02), as the compose stack does, so the
 * AUD-01 audit log in {@code dlap_common} is the real one; and a runner of the independent verifier
 * {@code scripts/audit-verify.sh} against it through {@code docker exec}.
 */
public final class AuditDatabase {

    /** Same list the compose files pass to the init script (ADR-0037). */
    public static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    private AuditDatabase() {
    }

    /**
     * The result of one run of scripts/audit-verify.sh.
     *
     * @param exit   the exit code
     * @param output stdout and stderr
     */
    public record Script(int exit, String output) {
    }

    /**
     * A container with the init scripts; superuser {@code dlap}/{@code dlap}.
     *
     * @return the container, not started
     */
    public static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES);
        try (Stream<Path> scripts = Files.list(repoRoot().resolve("deployment/platform/database/init-scripts"))) {
            scripts.filter(Files::isRegularFile)
                    .filter(script -> !script.getFileName().toString().startsWith("03-"))
                    .sorted()
                    .forEach(script -> {
                        String name = script.getFileName().toString();
                        int mode = name.endsWith(".sh") ? 0755 : 0644;
                        postgres.withCopyFileToContainer(MountableFile.forHostPath(script, mode),
                                "/docker-entrypoint-initdb.d/" + name);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the platform init scripts", e);
        }
        return postgres;
    }

    /**
     * The JDBC URL of a database of the container.
     *
     * @param postgres the container
     * @param database the database
     * @return the URL
     */
    public static String url(PostgreSQLContainer<?> postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    /**
     * {@code dlap_common} as a service role of the init scripts (password {@code <name>_pass}).
     *
     * @param postgres the container
     * @param role     for example {@code da_policyengine_user}
     * @return the data source
     */
    public static DataSource commonAs(PostgreSQLContainer<?> postgres, String role) {
        return new DriverManagerDataSource(url(postgres, "dlap_common"), role, role.replaceAll("_user$", "_pass"));
    }

    /**
     * {@code dlap_common} as the superuser.
     *
     * @param postgres the container
     * @return the data source
     */
    public static DataSource commonAsSuperuser(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(url(postgres, "dlap_common"), "dlap", "dlap");
    }

    /**
     * Runs scripts/audit-verify.sh over every stream of the container, as the superuser. Skips the
     * calling test when bash or the docker CLI is missing.
     *
     * @param postgres the container
     * @return the result
     * @throws Exception when the script cannot be run
     */
    public static Script auditVerify(PostgreSQLContainer<?> postgres) throws Exception {
        String bash = bash();
        assumeThat(bash).as("bash is not available").isNotNull();
        assumeThat(succeeds("docker", "version")).as("the docker CLI is not available").isTrue();
        Path out = Files.createTempFile("aud02a-audit-verify-", ".log");
        ProcessBuilder pb = new ProcessBuilder(bash, repoRoot().resolve("scripts/audit-verify.sh").toAbsolutePath()
                .toString().replace('\\', '/')).redirectErrorStream(true).redirectOutput(out.toFile());
        pb.environment().put("DLAP_PSQL", "docker exec -i " + postgres.getContainerId() + " psql -U dlap");
        pb.environment().put("DLAP_COMMON_DB_URL", "dlap_common");
        pb.environment().put("MSYS_NO_PATHCONV", "1");
        Process p = pb.start();
        assertThat(p.waitFor(180, TimeUnit.SECONDS)).as("audit-verify.sh finished").isTrue();
        String output = Files.readString(out, StandardCharsets.UTF_8);
        Files.deleteIfExists(out);
        return new Script(p.exitValue(), output);
    }

    private static String bash() {
        String override = System.getenv("DLAP_TEST_BASH");
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (File.separatorChar == '\\') {
            for (String candidate : List.of("C:\\Program Files\\Git\\bin\\bash.exe",
                    "C:\\Program Files\\Git\\usr\\bin\\bash.exe")) {
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
            Process p = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The repository root: the first parent that holds scripts/audit-verify.sh.
     *
     * @return the root
     */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("scripts/audit-verify.sh"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("scripts/audit-verify.sh not found above " + Path.of("").toAbsolutePath());
    }
}
