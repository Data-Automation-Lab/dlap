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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * OBS-01 acceptance for da-autocompliance: the whole service, with its own {@code application.properties}
 * and the real security chain, serves the actuator on a separate management port
 * ({@code MANAGEMENT_SERVER_PORT}) and keeps the rest closed.
 *
 * <p>Scope: a Testcontainers test, not a slice test. It boots the same context as
 * {@code ProdProfileContextTest} (prod profile, real PostgreSQL 15.1 built from the platform init scripts,
 * the documented environment) with {@code management.server.port=0}, then calls both ports over HTTP
 * with no token. Requires Docker. The security rules alone, without a database, are in
 * {@code ActuatorExposureTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0"
})
@ActiveProfiles("prod")
@AutoConfigureMetrics
@Testcontainers
class ManagementPortContextTest {

    /**
     * SEC-12 (Q-176, Q-449): the password of this service's own database role. The container's init script
     * creates the role with it (DA_AUTOCOMPLIANCE_DB_PASSWORD) and the context gets it as the service reads it, so
     * the prod boot guard, which refuses a published default, lets the service start.
     */
    private static final String DB_PASSWORD = "t-" + java.util.UUID.randomUUID();

    /** Same list the compose files pass to the init script (ADR-0037). */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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
    private Environment environment;

    /** The aggregate health, liveness and readiness answer without a token on the management port. */
    @Test
    void healthIsPublicOnTheManagementPort() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(managementPort(), path);

            assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"")
                    .doesNotContain("\"components\"").doesNotContain("\"details\"");
        }
    }

    /** The scrape is open on the management port and carries the series the platform alerts read. */
    @Test
    void prometheusIsOpenOnTheManagementPort() throws Exception {
        get(applicationPort(), "/api/v1/obs01/anything");

        HttpResponse<String> response = get(managementPort(), "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes{")
                .contains("process_start_time_seconds")
                .contains("http_server_requests_seconds_count{")
                .contains("hikaricp_connections_max{")
                .contains("hikaricp_connections_active{");
    }

    /** The application port does not serve the scrape, and does not serve it for free. */
    @Test
    void prometheusIsNotOpenOnTheApplicationPort() throws Exception {
        HttpResponse<String> response = get(applicationPort(), "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
    }

    /** Every other actuator endpoint needs a token, on both ports. */
    @Test
    void everythingElseNeedsATokenOnBothPorts() throws Exception {
        for (int port : new int[] {managementPort(), applicationPort()}) {
            assertThat(get(port, "/actuator/metrics").statusCode()).as("metrics on " + port).isEqualTo(401);
            assertThat(get(port, "/actuator/info").statusCode()).as("info on " + port).isEqualTo(401);
        }
    }

    private int applicationPort() {
        return Integer.parseInt(environment.getRequiredProperty("local.server.port"));
    }

    private int managementPort() {
        return Integer.parseInt(environment.getRequiredProperty("local.management.port"));
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
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
