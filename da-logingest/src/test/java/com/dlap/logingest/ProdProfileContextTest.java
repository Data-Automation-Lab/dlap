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

package com.dlap.logingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * DEP-01 acceptance: the full da-logingest context starts under the {@code prod} profile with the
 * documented environment (deployment/PRODUCTION.md, "Environment under the prod profile").
 *
 * <p>Scope: this is a Testcontainers test, not a slice test. It starts the whole application
 * context (web server, security, Kafka wiring, JPA, Flyway) against a real PostgreSQL 15.1
 * that was bootstrapped with the platform init scripts (00 to 02, without the 03 demo seed, as
 * PRODUCTION.md requires). It connects as the per-service database user, so Flyway and
 * {@code ddl-auto=validate} run exactly as they do in production. Kafka and Keycloak are not
 * started: producers and listeners retry in the background and the JWT decoder fetches its
 * keys lazily, so neither blocks startup. Requires Docker.
 *
 * <p>Nothing here sets {@code spring.jpa.hibernate.ddl-auto}: the shipped value has to be
 * {@code validate}.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Testcontainers
class ProdProfileContextTest {

    /**
     * SEC-12 (Q-176, Q-449): the password of this service's own database role. The container's init script
     * creates the role with it (DA_LOGINGEST_DB_PASSWORD) and the context gets it as the service reads it, so
     * the prod boot guard, which refuses a published default, lets the service start.
     */
    private static final String DB_PASSWORD = "t-" + java.util.UUID.randomUUID();

    /** Same list the compose files pass to the init script (ADR-0037). */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    /** The documented environment of da-logingest under prod. */
    @DynamicPropertySource
    static void documentedEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_logingest");
        registry.add("DB_PASS", () -> DB_PASSWORD);
        registry.add("dlap.security.jwt.validate-issuer", () -> "true");
        registry.add("dlap.security.jwt.validate-audience", () -> "true");
        registry.add("KAFKA_BOOTSTRAP_SERVERS", () -> "localhost:1");
        registry.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
        registry.add("logging.level.org.apache.kafka", () -> "ERROR");
        registry.add("logging.level.org.springframework.kafka", () -> "ERROR");
        registry.add("dlap.service-auth.enabled", () -> "true");
        registry.add("dlap.service-auth.token-uri",
                () -> "http://localhost:1/realms/dlap/protocol/openid-connect/token");
        registry.add("dlap.service-auth.client-id", () -> "dlap-backend");
        registry.add("dlap.service-auth.client-secret", () -> java.util.UUID.randomUUID().toString());
    }

    @Autowired
    private Environment environment;

    @Autowired
    private RequestMappingHandlerAdapter requestMappingHandlerAdapter;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * BOOT-01 (ADR-0066): Spring MVC writes JSON with the Jackson 2 ObjectMapper that Spring Boot
     * configures (spring-boot-jackson2 and spring.http.converters.preferred-json-mapper=jackson2), not
     * with Jackson 3, so response bodies keep the form they had on Spring Boot 3.
     *
     * @throws Exception if the date cannot be written
     */
    @Test
    @SuppressWarnings("removal")
    void httpJsonStaysOnJackson2() throws Exception {
        assertThat(requestMappingHandlerAdapter.getMessageConverters())
                .anyMatch(converter -> converter instanceof MappingJackson2HttpMessageConverter jackson2
                        && jackson2.getObjectMapper() == objectMapper)
                .noneMatch(converter -> converter.getClass().getName()
                        .equals("org.springframework.http.converter.json.JacksonJsonHttpMessageConverter"));
        assertThat(objectMapper.writeValueAsString(Instant.parse("2026-10-19T12:00:00Z")))
                .as("dates stay ISO-8601 text, as on Spring Boot 3")
                .isEqualTo("\"2026-10-19T12:00:00Z\"");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private TestRestTemplate restTemplate;

    /** The context is up, and prod is the profile that is running, not dev or test. */
    @Test
    void contextStartsUnderProd() {
        assertThat(environment.getActiveProfiles()).contains("prod").doesNotContain("dev", "test");
    }

    /** CLAUDE.md section 6: validate in production, never update or create. */
    @Test
    void ddlAutoIsValidate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    /** Flyway ran the service migrations on the fresh database, in sequence, with none pending. */
    @Test
    void flywayMigrationsApplied() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).as("applied migrations").isNotEmpty();
        assertThat(flyway.info().pending()).as("pending migrations").isEmpty();
        for (int i = 0; i < applied.length; i++) {
            assertThat(applied[i].getVersion().getVersion())
                    .as("migration %d is sequential", i + 1).isEqualTo(String.valueOf(i + 1));
        }
    }

    /** The aggregate health is UP: the database answers and nothing else reports DOWN. */
    @Test
    void healthIsUp() {
        HealthDescriptor health = healthEndpoint.health();

        assertThat(health.getStatus()).as("health, not UP: %s", notUp(health)).isEqualTo(Status.UP);
    }

    /** Names the components that are DOWN or out of service, so a failure points at its cause. */
    private static String notUp(HealthDescriptor health) {
        if (health instanceof CompositeHealthDescriptor composite) {
            return composite.getComponents().entrySet().stream()
                    .filter(entry -> !Status.UP.equals(entry.getValue().getStatus())
                            && !Status.UNKNOWN.equals(entry.getValue().getStatus()))
                    .map(entry -> entry.getKey() + "=" + notUp(entry.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return String.valueOf(health.getStatus());
    }

    /** The public health endpoint answers 200 and UP on the application port. */
    @Test
    void healthEndpointIsPublic() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }


    /** PostgreSQL with the platform init scripts, minus the 03 demo seed (PRODUCTION.md section 2). */
    private static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES)
                .withEnv("DA_LOGINGEST_DB_PASSWORD", DB_PASSWORD);
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
