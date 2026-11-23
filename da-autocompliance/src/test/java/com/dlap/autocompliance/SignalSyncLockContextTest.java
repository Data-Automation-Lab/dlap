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

package com.dlap.autocompliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autocompliance.signal.ComplianceSignalSyncService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * HA-01 acceptance, whole-service scope: with {@code dlap.compliance.signal-sync.enabled=true} the real
 * application (prod profile, its own {@code application.properties}, component scanning, the platform
 * init scripts and the service's own Flyway run as the service database user) wires the lock provider
 * and a ShedLock-proxied sync, and one scheduled tick takes the lock row in {@code dlap_autocompliance}.
 *
 * <p>Complements {@code ComplianceSignalSyncLockTest}, which uses hand-built contexts. Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dlap.compliance.signal-sync.enabled=true",
        // The first run is driven by the test, not by the timer.
        "dlap.compliance.signal-sync.initial-delay=PT1H"
})
@ActiveProfiles("prod")
@Testcontainers
class SignalSyncLockContextTest {

    /**
     * SEC-12 (Q-176, Q-449): the password of this service's own database role. The container's init script
     * creates the role with it (DA_AUTOCOMPLIANCE_DB_PASSWORD) and the context gets it as the service reads it, so
     * the prod boot guard, which refuses a published default, lets the service start.
     */
    private static final String DB_PASSWORD = "t-" + java.util.UUID.randomUUID();

    /** Same list the compose files pass to the init script (ADR-0037). */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    /** The documented environment of da-autocompliance under prod. */
    @DynamicPropertySource
    static void documentedEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_autocompliance");
        registry.add("DB_PASS", () -> DB_PASSWORD);
        registry.add("dlap.security.jwt.validate-issuer", () -> "true");
        registry.add("dlap.security.jwt.validate-audience", () -> "true");
        registry.add("KAFKA_BOOTSTRAP_SERVERS", () -> "localhost:1");
        registry.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
        registry.add("logging.level.org.apache.kafka", () -> "ERROR");
        registry.add("logging.level.org.springframework.kafka", () -> "ERROR");
        registry.add("DA_CATALOG_URL", () -> "http://localhost:1");
        registry.add("dlap.service-auth.enabled", () -> "true");
        registry.add("dlap.service-auth.token-uri",
                () -> "http://localhost:1/realms/dlap/protocol/openid-connect/token");
        registry.add("dlap.service-auth.client-id", () -> "dlap-backend");
        registry.add("dlap.service-auth.client-secret", () -> java.util.UUID.randomUUID().toString());
    }

    @Autowired
    private ComplianceSignalSyncService sync;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private JdbcTemplate jdbc;

    /** One scheduled tick of the real bean writes its named row into the service schema. */
    @Test
    void aScheduledTickTakesTheLockRowInTheServiceSchema() {
        assertThat(lockProvider).isNotNull();
        assertThat(AopUtils.isAopProxy(sync)).as("the sync bean is advised by ShedLock").isTrue();

        sync.scheduledSync();

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM dlap_autocompliance.shedlock WHERE name = ?", Integer.class,
                ComplianceSignalSyncService.LOCK_NAME);
        assertThat(rows).isEqualTo(1);
    }

    /** PostgreSQL with the platform init scripts, minus the 03 demo seed (PRODUCTION.md section 2). */
    private static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES)
                .withEnv("DA_AUTOCOMPLIANCE_DB_PASSWORD", DB_PASSWORD);
        try (Stream<Path> scripts = Files.list(initScriptsDir())) {
            scripts.filter(Files::isRegularFile)
                    .filter(script -> !script.getFileName().toString().startsWith("03-"))
                    .sorted()
                    .forEach(script -> {
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

    /** Walks up from the working directory to the repository's shared init-scripts folder. */
    private static Path initScriptsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "deployment/platform/database/init-scripts not found above " + System.getProperty("user.dir"));
    }
}
