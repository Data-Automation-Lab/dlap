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

package com.dlap.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

import com.dlap.common.security.DevSecretGuard.Finding;
import com.dlap.common.security.DevSecretGuard.Kind;

/**
 * SEC-05: the guard against the {@code application.properties} files the services really ship.
 *
 * <p>The unit tests in {@link DevSecretGuardTest} use invented settings. These tests load the files of
 * the eleven services that have one, resolve their placeholders the way Spring does, and pin what the
 * guard says about each: which shipped defaults are fatal under {@code prod}, which are advisory, and
 * that real secrets leave nothing. When a service adds a new dev default for a secret, or renames one,
 * these tests fail.
 *
 * <p>Skipped, not failed, when the module is built without the rest of the checkout.
 */
class DevSecretGuardShippedDefaultsTest {

    /** Real-looking values for every setting the guard looks at, as an operator's environment would supply. */
    private static final Map<String, String> PRODUCTION_ENV = productionEnvironment();

    private static String random() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, String> productionEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_PASS", random());
        env.put("SPRING_DATASOURCE_PASSWORD", random());
        env.put("KEYCLOAK_ADMIN_PASSWORD", random());
        env.put("DLAP_ENCRYPTION_KEY", (random() + random()).replace("-", "").substring(0, 64));
        env.put("DLAP_PDP_TOKEN", random());
        env.put("DLAP_SERVICE_CLIENT_SECRET", random());
        env.put("dlap.service-auth.enabled", "true");
        env.put("dlap.service-auth.client-secret", random());
        env.put("dlap.security.jwt.validate-issuer", "true");
        env.put("dlap.security.jwt.validate-audience", "true");
        return Map.copyOf(env);
    }

    private static Path root;

    @BeforeAll
    static void locateRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        assumeTrue(dir != null, "repo root with .env.example not found; skipping shipped-defaults contract");
        root = dir;
    }

    /** Each service with an application.properties, and the fatal findings it ships under prod. */
    static Stream<Object[]> services() {
        return Stream.of(
                new Object[] {"da-admin-service", List.of("app.encryption.key", "keycloak.admin.password")},
                new Object[] {"da-autoarchival", List.of()},
                new Object[] {"da-autocompliance", List.of()},
                new Object[] {"da-autodelete", List.of()},
                new Object[] {"da-autolabel", List.of()},
                new Object[] {"da-catalog", List.of()},
                new Object[] {"da-discovery", List.of()},
                new Object[] {"da-logingest", List.of()},
                new Object[] {"da-mcp-pep", List.of("dlap.pdp.token")},
                new Object[] {"da-policyengine", List.of()},
                new Object[] {"da-reporting", List.of()});
    }

    private static Properties load(String service) throws IOException {
        Path file = root.resolve(service).resolve("src/main/resources/application.properties");
        assumeTrue(Files.exists(file), file + " not found");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static MockEnvironment environmentOf(String service, Map<String, String> overrides) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources()
                .addLast(new PropertiesPropertySource("application.properties of " + service, load(service)));
        overrides.forEach(environment::setProperty);
        environment.setActiveProfiles("prod");
        return environment;
    }

    /** The files as shipped: exactly the documented fatal findings, and nothing else is fatal. */
    @ParameterizedTest(name = "{0} as shipped")
    @MethodSource("services")
    void theShippedFileHasExactlyTheDocumentedFatalFindings(String service, List<String> fatal) throws IOException {
        List<Finding> findings = DevSecretGuard.inspect(environmentOf(service, Map.of()));

        assertThat(findings.stream().filter(f -> !f.kind().advisory()).map(Finding::name).toList())
                .as("fatal findings of %s as shipped", service)
                .containsExactlyInAnyOrderElementsOf(fatal);
    }

    /** Every shipped database password is seen as the published default (advisory), so strict mode has teeth. */
    @ParameterizedTest(name = "{0} database default")
    @MethodSource("services")
    void everyShippedDatabasePasswordIsSeen(String service, List<String> fatal) throws IOException {
        Properties properties = load(service);
        List<String> databasePasswords = properties.stringPropertyNames().stream()
                .filter(n -> n.equals("spring.datasource.password") || n.equals("dlap.common-db.password")
                        || n.equals("dlap.common.datasource.password"))
                .sorted().toList();
        assumeTrue(!databasePasswords.isEmpty(), service + " has no database password in application.properties");

        List<Finding> findings = DevSecretGuard.inspect(environmentOf(service, Map.of()));

        assertThat(findings).filteredOn(f -> f.kind() == Kind.DATABASE_DEFAULT).extracting(Finding::name)
                .as("database defaults of %s", service)
                .containsExactlyInAnyOrderElementsOf(databasePasswords.stream()
                        .filter(n -> properties.getProperty(n).contains("_pass}")).toList());
    }

    /** Real secrets for every guarded setting clear every finding, advisory ones included. */
    @ParameterizedTest(name = "{0} with real secrets")
    @MethodSource("services")
    void realSecretsLeaveNothing(String service, List<String> fatal) throws IOException {
        MockEnvironment environment = environmentOf(service, new LinkedHashMap<>(PRODUCTION_ENV));

        List<Finding> leftovers = DevSecretGuard.inspect(environment);

        assertThat(leftovers.stream().map(Finding::describe).toList())
                .as("findings of %s with real secrets", service).isEmpty();
    }

    /**
     * SEC-12 (Q-449): since the guard is strict by default under prod, no service starts on its shipped
     * file. The refusal names the database default (or, for da-mcp-pep, the PDP token) and the JWT flags.
     */
    @ParameterizedTest(name = "{0} is refused under prod as shipped")
    @MethodSource("services")
    void noServiceStartsOnItsShippedFileUnderProd(String service, List<String> fatal) throws IOException {
        assertThatThrownBy(() -> DevSecretGuard.verify(environmentOf(service, Map.of())))
                .isInstanceOf(DevSecretGuard.RefusedStartupException.class);
    }

    /** With real secrets, including the database password, the strict guard lets every service start. */
    @ParameterizedTest(name = "{0} starts under prod with real secrets")
    @MethodSource("services")
    void everyServiceStartsUnderProdWithRealSecrets(String service, List<String> fatal) throws IOException {
        MockEnvironment environment = environmentOf(service, new LinkedHashMap<>(PRODUCTION_ENV));

        assertThatCode(() -> DevSecretGuard.verify(environment)).doesNotThrowAnyException();
    }

    /**
     * Seven services also carry a {@code dlap.common-db.password} property that no Java code reads
     * (Q-NEW-SEC-12-1). It defaults to {@code dlap_common_pass}, so a deployment that sets only
     * {@code SPRING_DATASOURCE_PASSWORD} is still refused: it has to set {@code DB_PASS} as well, which
     * resolves every database password of the file. This test pins that, until the dead property is removed.
     */
    @Test
    void onlySettingTheDatasourcePasswordLeavesTheUnusedCommonDbDefault() throws IOException {
        Map<String, String> onlyTheDatasource = new LinkedHashMap<>(PRODUCTION_ENV);
        onlyTheDatasource.remove("DB_PASS");
        onlyTheDatasource.put("spring.datasource.password", random());

        List<Finding> findings = DevSecretGuard.inspect(environmentOf("da-reporting", onlyTheDatasource));

        assertThat(findings).extracting(Finding::name).containsExactly("dlap.common-db.password");
    }

    /** The service list above is the list of modules that ship an application.properties. */
    @Test
    void theServiceListCoversEveryModuleWithAnApplicationProperties() throws IOException {
        try (Stream<Path> dirs = Files.list(root)) {
            List<String> modules = dirs
                    .filter(d -> Files.exists(d.resolve("src/main/resources/application.properties")))
                    .map(d -> d.getFileName().toString())
                    .filter(n -> n.startsWith("da-") && !n.equals("da-protos") && !n.equals("da-llm-client"))
                    .sorted().toList();

            assertThat(modules).containsExactlyInAnyOrderElementsOf(
                    services().map(a -> (String) a[0]).toList());
        }
    }
}
