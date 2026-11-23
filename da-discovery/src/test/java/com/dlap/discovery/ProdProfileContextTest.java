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

package com.dlap.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * DEP-01 acceptance: da-discovery starts under the {@code prod} profile with the documented
 * environment (deployment/PRODUCTION.md, "Environment under the prod profile").
 *
 * <p>Scope: a Testcontainers test, not a slice test, but unlike the other ten services it boots the
 * application in a forked JVM instead of an in-process Spring test context. The reason is the
 * module layout: {@code DADiscoveryAgent} scans the whole {@code com.dlap.discovery} package, and
 * the test sources live in that package too, so an in-process context also picks up the test
 * {@code @Configuration} classes (an H2 {@code DataSource} from {@code HibernateTimeZoneIT}, an
 * H2 entity-manager factory from {@code TestDatabaseConfiguration}, a test security chain). That
 * context is the test profile, not prod. The child process runs the real main class on the test
 * runtime classpath minus the test output directories, with real environment variables, against a
 * real PostgreSQL 15.1 built from the platform init scripts (00 to 02, without the 03 demo seed).
 * It also exercises the Spring Cloud bootstrap context, which only sees real environment
 * variables. Kafka and Keycloak are not started; the clients retry in the background. Requires Docker.
 *
 * <p>The module's JUnit default timeout is 15 seconds per test, so the tests below set their own.
 */
@Testcontainers
class ProdProfileContextTest {

    /**
     * SEC-12 (Q-176, Q-449): the password of this service's own database role. The container's init script
     * creates the role with it (DA_DISCOVERY_DB_PASSWORD) and the context gets it as the service reads it, so
     * the prod boot guard, which refuses a published default, lets the service start.
     */
    private static final String DB_PASSWORD = "t-" + java.util.UUID.randomUUID();

    /** Same list the compose files pass to the init script (ADR-0037). */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    /** Variable name prefixes that must not leak from the developer's shell into the child. */
    private static final List<String> FILTERED_PREFIXES = List.of(
            "SPRING_", "DLAP_", "KAFKA_", "KEYCLOAK_", "DB_", "JPA_", "CLOUD_", "JHIPSTER_", "SERVER_",
            "MANAGEMENT_", "LOGGING_", "JAVA_TOOL_OPTIONS", "GOOGLE_", "GCP_", "AWS_");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    /**
     * Scratch folder for the child's log and argument file. Not a JUnit {@code @TempDir}: on Windows the
     * child's log can stay locked for a moment after the process ends, and a {@code @TempDir} that cannot be
     * deleted fails the test. This one is removed on a best-effort basis.
     */
    private Path workDir;

    private Process child;

    private HttpServer oidcStub;

    @BeforeEach
    void createWorkDir() throws IOException {
        workDir = Files.createTempDirectory("dlap-discovery-prod-");
    }

    /** Always stop the child, so a failed assertion cannot leave a JVM running. */
    @AfterEach
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void stopChild() throws InterruptedException {
        if (child != null && child.isAlive()) {
            child.destroy();
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(15, TimeUnit.SECONDS);
            }
        }
        if (oidcStub != null) {
            oidcStub.stop(0);
        }
        deleteQuietly(workDir);
    }

    private static void deleteQuietly(Path dir) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && dir != null && Files.exists(dir); attempt++) {
            try (Stream<Path> tree = Files.walk(dir)) {
                tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException | UncheckedIOException e) {
                // a file may still be locked for a moment; try again
            }
            if (Files.exists(dir)) {
                Thread.sleep(300);
            }
        }
    }

    /**
     * Stands in for the Keycloak realm. da-discovery registers an OAuth2 client at startup, and Spring
     * fetches the issuer's {@code .well-known/openid-configuration} while the context is built, so a
     * reachable issuer is part of the documented environment (the compose files wait for Keycloak).
     * Nothing else of the identity provider is exercised.
     *
     * @return the issuer URI to hand to the service
     */
    private String startOidcStub() throws IOException {
        oidcStub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String issuer = "http://localhost:" + oidcStub.getAddress().getPort() + "/realms/dlap";
        String configuration = "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/protocol/openid-connect/auth\","
                + "\"token_endpoint\":\"" + issuer + "/protocol/openid-connect/token\","
                + "\"userinfo_endpoint\":\"" + issuer + "/protocol/openid-connect/userinfo\","
                + "\"jwks_uri\":\"" + issuer + "/protocol/openid-connect/certs\","
                + "\"response_types_supported\":[\"code\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
        oidcStub.createContext("/realms/dlap/.well-known/openid-configuration", exchange -> respond(exchange, configuration));
        oidcStub.createContext("/realms/dlap/protocol/openid-connect/certs", exchange -> respond(exchange, "{\"keys\":[]}"));
        oidcStub.start();
        return issuer;
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * The service starts, reports UP, ran its migrations on the fresh database and runs under prod
     * only (not dev or test).
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void startsUnderProdWithTheDocumentedEnvironment() throws Exception {
        int port = freePort();
        Path log = workDir.resolve("da-discovery.log");
        child = launch(documentedEnvironment(port), log);

        String health = awaitHealth(port, log);

        assertThat(health).contains("\"status\":\"UP\"");
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(output).contains("The following 1 profile is active: \"prod\"");
        List<Integer> versions = flywayVersions();
        assertThat(versions).as("applied Flyway versions").isNotEmpty();
        assertThat(versions).as("versions are 1..N with no gaps")
                .isEqualTo(IntStream.rangeClosed(1, versions.size()).boxed().toList());
    }

    /** No database URL means no start, and the message names the variable to set. */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void refusesToStartWithoutTheDatabaseUrl() throws Exception {
        Map<String, String> env = documentedEnvironment(freePort());
        env.remove("SPRING_DATASOURCE_URL");
        Path log = workDir.resolve("da-discovery-no-url.log");
        child = launch(env, log);

        boolean exited = child.waitFor(240, TimeUnit.SECONDS);

        assertThat(exited).as("the process exits instead of serving without a database").isTrue();
        assertThat(child.exitValue()).isNotZero();
        assertThat(Files.readString(log, StandardCharsets.UTF_8)).contains("SPRING_DATASOURCE_URL");
    }

    /**
     * Q-334: no bootstrap servers means no start, and the message names the variable to set. Before
     * FU-3B the only reader of the prod {@code kafka.bootstrap-servers} line was removed with the old
     * Kafka configuration, so a deploy without the variable quietly targeted the quickstart address
     * {@code kafka:29092}.
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void refusesToStartWithoutTheKafkaBootstrapServers() throws Exception {
        Map<String, String> env = documentedEnvironment(freePort());
        env.remove("SPRING_KAFKA_BOOTSTRAP_SERVERS");
        Path log = workDir.resolve("da-discovery-no-kafka.log");
        child = launch(env, log);

        boolean exited = child.waitFor(240, TimeUnit.SECONDS);

        assertThat(exited).as("the process exits instead of serving against a default broker").isTrue();
        assertThat(child.exitValue()).isNotZero();
        assertThat(Files.readString(log, StandardCharsets.UTF_8)).contains("SPRING_KAFKA_BOOTSTRAP_SERVERS");
    }

    /**
     * The shipped profile validates the schema (CLAUDE.md section 6) and selects an executor that
     * exists in every image. SPARK was the value before DEP-01 and it never started.
     */
    @Test
    @SuppressWarnings("unchecked")
    void shippedProfileValidatesTheSchemaAndSelectsTheInProcessExecutor() throws IOException {
        try (InputStream in = ProdProfileContextTest.class.getResourceAsStream("/config/application-prod.yml")) {
            assertThat(in).as("config/application-prod.yml on the classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(in);

            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
            Map<String, Object> hibernate = (Map<String, Object>) jpa.get("hibernate");
            assertThat(hibernate.get("ddl-auto")).isEqualTo("validate");

            Map<String, Object> discovery = (Map<String, Object>) root.get("discovery");
            Map<String, Object> job = (Map<String, Object>) discovery.get("job");
            Map<String, Object> executor = (Map<String, Object>) job.get("executor");
            assertThat(String.valueOf(executor.get("mode"))).contains(":DEFAULT}").doesNotContain("SPARK:");
        }
    }

    /** The variables of PRODUCTION.md for da-discovery, set as real environment variables. */
    private Map<String, String> documentedEnvironment(int port) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("SPRING_DATASOURCE_URL", jdbcUrl());
        env.put("SPRING_DATASOURCE_PASSWORD", DB_PASSWORD);
        env.put("DLAP_SECURITY_JWT_VALIDATE_ISSUER", "true");
        env.put("DLAP_SECURITY_JWT_VALIDATE_AUDIENCE", "true");
        env.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", "localhost:1");
        env.put("JHIPSTER_REGISTRY_PASSWORD", "unused");
        env.put("SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI", startOidcStub());
        env.put("CLOUD_PROVIDER_GCP_ENABLED", "true");
        env.put("GCP_PROJECT_ID", "dlap-demo-project");
        env.put("GOOGLE_CLOUD_PROJECT", "dlap-demo-project");
        env.put("DLAP_AWS_ENABLED", "false");
        env.put("SPRING_APPLICATION_JSON", "{\"dlap.catalog-service.url\":\"http://localhost:1\","
                + "\"dlap.service-auth.enabled\":\"true\","
                + "\"dlap.service-auth.token-uri\":\"http://localhost:1/realms/dlap/protocol/openid-connect/token\","
                + "\"dlap.service-auth.client-id\":\"dlap-backend\","
                + "\"dlap.service-auth.client-secret\":\"" + UUID.randomUUID() + "\"}");
        return env;
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_discovery";
    }

    /** Starts the real main class with the given environment and the output going to {@code log}. */
    private Process launch(Map<String, String> documentedEnv, Path log) throws IOException {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Path argFile = workDir.resolve("java.args");
        // A classpath of hundreds of jars exceeds the Windows command-line limit, so it goes in an @argfile.
        // Backslashes are escape characters inside an argfile; forward slashes work on every platform.
        Files.writeString(argFile, "-cp\n\"" + appClasspath() + "\"\n", StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(javaBin.toString(), "-Xmx768m", "@" + argFile,
                "com.dlap.discovery.application.DADiscoveryAgent");
        builder.directory(workDir.toFile());
        builder.environment().keySet().removeIf(ProdProfileContextTest::isFiltered);
        builder.environment().putAll(documentedEnv);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static boolean isFiltered(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return FILTERED_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    /**
     * The test runtime classpath without the test output directories, so no test configuration is
     * scanned. Gradle on Windows hands the worker a classpath with doubled backslashes, so every entry
     * is normalized to single forward slashes first.
     */
    private static String appClasspath() {
        return Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> entry.replace('\\', '/').replaceAll("/{2,}", "/"))
                .filter(entry -> !isTestOutput(entry))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static boolean isTestOutput(String entry) {
        return entry.endsWith("/classes/java/test") || entry.endsWith("/resources/test")
                || entry.endsWith("/classes/kotlin/test");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Polls the public health endpoint until it answers 200, failing early if the process dies. */
    private String awaitHealth(int port, Path log) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(240).toNanos();
        while (System.nanoTime() < deadline) {
            if (!child.isAlive()) {
                throw new AssertionError("da-discovery exited with " + child.exitValue() + " before it was healthy:\n"
                        + excerpt(log));
            }
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
            } catch (IOException notUpYet) {
                // connection refused while the application starts
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("da-discovery was not healthy within 240 seconds:\n" + excerpt(log));
    }

    /** The first and last lines of the child's output: the profile and datasource lines, then the failure. */
    private static String excerpt(Path log) {
        try (Stream<String> all = Files.lines(log, StandardCharsets.UTF_8)) {
            List<String> lines = all.collect(Collectors.toCollection(ArrayList::new));
            if (lines.size() <= 200) {
                return String.join("\n", lines);
            }
            return String.join("\n", lines.subList(0, 20)) + "\n...\n"
                    + String.join("\n", lines.subList(lines.size() - 160, lines.size()));
        } catch (IOException e) {
            return "(log unreadable: " + e.getMessage() + ")";
        }
    }

    /** The applied versions of the service's Flyway history, read as the service user. */
    private static List<Integer> flywayVersions() {
        List<Integer> versions = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), "da_discovery_user", DB_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT version FROM dlap_discovery.flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank")) {
            while (rows.next()) {
                versions.add(Integer.valueOf(rows.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the Flyway history", e);
        }
        return versions;
    }

    /** PostgreSQL with the platform init scripts, minus the 03 demo seed (PRODUCTION.md section 2). */
    private static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES)
                .withEnv("DA_DISCOVERY_DB_PASSWORD", DB_PASSWORD);
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
