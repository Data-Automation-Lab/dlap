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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * SEC-05: what the dev compose pair does with secrets, read from the files in the checkout.
 *
 * <p>The quickstart must boot with no setup, so it keeps working defaults. These tests pin the rules
 * around them: the client secret is never inside {@code SPRING_APPLICATION_JSON}, the database does not
 * log statements, the quickstart labels with the rules provider and not the random mock, Keycloak gets the
 * client secret, and every default of a secret-named variable is one the prod boot guard refuses.
 *
 * <p>Skipped, not failed, when the module is built without the rest of the checkout.
 */
class DevComposeSecretsContractTest {

    /** Social-login placeholders for Keycloak identity providers: inert text, no credential behind them. */
    private static final Set<String> INERT_PLACEHOLDERS = Set.of("GOOGLE_CLIENT_SECRET", "FACEBOOK_APP_SECRET");

    private static Map<String, Object> infrastructure;
    private static Map<String, Object> microservices;
    private static String microservicesText;
    private static String infrastructureText;
    private static String envExample;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        assumeTrue(dir != null, "repo root with .env.example not found; skipping compose secrets contract");
        Path dev = dir.resolve("deployment/environments/dev");
        Path infra = dev.resolve("docker-compose.yml");
        Path micro = dev.resolve("docker-compose.microservices.yml");
        assumeTrue(Files.exists(infra) && Files.exists(micro), "dev compose pair missing; skipping");
        microservicesText = Files.readString(micro, StandardCharsets.UTF_8);
        envExample = Files.readString(dir.resolve(".env.example"), StandardCharsets.UTF_8);
        infrastructureText = Files.readString(infra, StandardCharsets.UTF_8);
        infrastructure = (Map<String, Object>) new Yaml().load(infrastructureText);
        microservices = (Map<String, Object>) new Yaml().load(microservicesText);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(Map<String, Object> file, String name) {
        return (Map<String, Object>) ((Map<String, Object>) file.get("services")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> environment(Map<String, Object> file, String name) {
        return (List<String>) service(file, name).get("environment");
    }

    @SuppressWarnings("unchecked")
    private static List<String> serviceNames(Map<String, Object> file) {
        return new ArrayList<>(((Map<String, Object>) file.get("services")).keySet());
    }

    private static String valueOf(List<String> environment, String name) {
        return environment.stream().filter(e -> e.startsWith(name + "=")).map(e -> e.substring(name.length() + 1))
                .findFirst().orElse(null);
    }

    /** B11-I1: no service carries the client secret inside SPRING_APPLICATION_JSON any more. */
    @Test
    void noSpringApplicationJsonHoldsASecret() {
        for (String name : serviceNames(microservices)) {
            List<String> env = environment(microservices, name);
            if (env == null) {
                continue;
            }
            String json = valueOf(env, "SPRING_APPLICATION_JSON");
            if (json != null) {
                assertThat(json.toLowerCase()).as("SPRING_APPLICATION_JSON of %s", name)
                        .doesNotContain("secret").doesNotContain("password").doesNotContain("token\":")
                        .doesNotContain("change-me-dev-only");
            }
        }
    }

    /** Every service that turns service auth on gets the secret in its own variable, from the one source. */
    @Test
    void everyServiceWithServiceAuthGetsTheSecretInItsOwnVariable() {
        List<String> withServiceAuth = new ArrayList<>();
        for (String name : serviceNames(microservices)) {
            List<String> env = environment(microservices, name);
            String json = env == null ? null : valueOf(env, "SPRING_APPLICATION_JSON");
            if (json != null && json.contains("\"dlap.service-auth.enabled\":\"true\"")) {
                withServiceAuth.add(name);
                assertThat(valueOf(env, "DLAP_SERVICE_AUTH_CLIENT_SECRET"))
                        .as("client secret variable of %s", name)
                        .isEqualTo("${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}");
            }
        }
        assertThat(withServiceAuth).containsExactlyInAnyOrder("da-discovery", "da-policyengine", "da-reporting",
                "da-autolabel", "da-autoarchival", "da-autodelete", "da-autocompliance", "da-admin-service");
    }

    /** Q-178: Keycloak receives the secret the services use, so a value chosen in .env reaches the realm import. */
    @Test
    void keycloakReceivesTheBackendClientSecret() {
        assertThat(valueOf(environment(infrastructure, "keycloak"), "DLAP_BACKEND_CLIENT_SECRET"))
                .isEqualTo("${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}");
    }

    /** Q-458: Keycloak receives the MCP gateway secret too, so a value chosen in .env reaches the realm import. */
    @Test
    void keycloakReceivesTheMcpGatewayClientSecret() {
        assertThat(valueOf(environment(infrastructure, "keycloak"), "DLAP_MCP_GATEWAY_CLIENT_SECRET"))
                .isEqualTo("${DLAP_MCP_GATEWAY_CLIENT_SECRET:-change-me-dev-only}");
        assertThat(envExample).contains("DLAP_MCP_GATEWAY_CLIENT_SECRET=change-me-dev-only");
    }

    /** B6-I2: the database logs DDL, not every statement with its literals. */
    @Test
    @SuppressWarnings("unchecked")
    void postgresLogsDdlOnly() {
        List<String> command = (List<String>) service(infrastructure, "postgresql").get("command");

        assertThat(command).contains("log_statement=ddl").doesNotContain("log_statement=all", "log_statement=mod");
    }

    /** Q-099: the quickstart labels with the rules provider and the mock stays opt-in. */
    @Test
    void autolabelUsesTheRulesProviderAndNotTheMock() {
        String json = valueOf(environment(microservices, "da-autolabel"), "SPRING_APPLICATION_JSON");

        assertThat(json).contains("\"dlap.autolabel.llm-provider\":\"rules\"")
                .doesNotContain("mock").doesNotContain("allow-mock");
    }

    /** The admin service and Keycloak read one variable for the master admin password, with one fallback. */
    @Test
    void theKeycloakAdminPasswordHasOneSourceAndNoAdminDefault() {
        String keycloak = valueOf(environment(infrastructure, "keycloak"), "KEYCLOAK_ADMIN_PASSWORD");
        String adminService = valueOf(environment(microservices, "da-admin-service"), "KEYCLOAK_ADMIN_PASSWORD");

        assertThat(keycloak).isEqualTo("${KEYCLOAK_ADMIN_PASSWORD:-changeme}");
        assertThat(adminService).isEqualTo(keycloak);
        assertThat(valueOf(environment(infrastructure, "grafana"), "GF_SECURITY_ADMIN_PASSWORD"))
                .isEqualTo("${GRAFANA_ADMIN_PASSWORD:-changeme}");
        assertThat(envExample).contains("KEYCLOAK_ADMIN_PASSWORD=changeme").contains("GRAFANA_ADMIN_PASSWORD=changeme");
    }

    /**
     * The one list: every non-empty default the compose pair gives a secret-named variable is a default the
     * boot guard refuses under prod. A new dev secret in a compose file has to be added to the guard.
     */
    @Test
    void everyComposeSecretDefaultIsOnTheGuardsList() {
        Pattern defaulted = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):-([^}]+)}");
        Pattern secretName = Pattern.compile("(PASSWORD|_PASS$|SECRET|TOKEN|ENCRYPTION_KEY|ACCESS_KEY|API_KEY)");
        Set<String> notOnTheList = new TreeSet<>();
        Set<String> checked = new TreeSet<>();
        for (String text : List.of(microservicesText, infrastructureText)) {
            for (String line : text.split("\n")) {
                if (line.trim().startsWith("#")) {
                    continue;
                }
                Matcher m = defaulted.matcher(line);
                while (m.find()) {
                    String name = m.group(1);
                    if (secretName.matcher(name).find() && !INERT_PLACEHOLDERS.contains(name)) {
                        checked.add(name + "=" + m.group(2));
                        if (!DevSecretGuard.isPublishedDefault(name, m.group(2))) {
                            notOnTheList.add(name);
                        }
                    }
                }
            }
        }

        assertThat(checked).as("secret defaults found in the compose pair").isNotEmpty();
        assertThat(notOnTheList).as("compose secret defaults the boot guard would accept").isEmpty();
    }

    /** The database roles of the init script and the variable that holds each one's password (SEC-12). */
    private static final Map<String, String> SERVICE_ROLES = Map.of(
            "da-discovery", "DA_DISCOVERY", "da-catalog", "DA_CATALOG", "da-policyengine", "DA_POLICYENGINE",
            "da-reporting", "DA_REPORTING", "da-autolabel", "DA_AUTOLABEL", "da-autoarchival", "DA_AUTOARCHIVAL",
            "da-autodelete", "DA_AUTODELETE", "da-logingest", "DA_LOGINGEST", "da-autocompliance",
            "DA_AUTOCOMPLIANCE", "da-admin-service", "DA_ADMIN");

    /**
     * SEC-12 (Q-176, Q-449): each service gets the password of its own database role, from the variable the init
     * script reads for that role, with the published development value as the fallback. The default is one the
     * prod boot guard refuses, so a deployment that forgets the variable does not start.
     */
    @Test
    void everyServiceReadsThePasswordOfItsOwnDatabaseRole() {
        for (Map.Entry<String, String> role : SERVICE_ROLES.entrySet()) {
            String variable = role.getValue() + "_DB_PASSWORD";
            String fallback = role.getValue().toLowerCase() + "_pass";
            List<String> env = environment(microservices, role.getKey());

            assertThat(valueOf(env, "SPRING_DATASOURCE_PASSWORD")).as("datasource password of %s", role.getKey())
                    .isEqualTo("${" + variable + ":-" + fallback + "}");
            assertThat(valueOf(env, "DB_PASS")).as("DB_PASS of %s", role.getKey())
                    .isEqualTo("${" + variable + ":-" + fallback + "}");
            assertThat(DevSecretGuard.isPublishedDefault("SPRING_DATASOURCE_PASSWORD", fallback))
                    .as("the guard refuses %s", fallback).isTrue();
        }
    }

    /** The PostgreSQL service receives every variable, empty when unset, so the init script's fallback applies. */
    @Test
    void postgresReceivesEveryRolePasswordVariableAndTheFallbackIsTheInitScripts() {
        List<String> env = environment(infrastructure, "postgresql");
        List<String> variables = new ArrayList<>(
                SERVICE_ROLES.values().stream().map(v -> v + "_DB_PASSWORD").sorted().toList());
        variables.addAll(List.of("DLAP_COMMON_DB_PASSWORD", "DLAP_MONITOR_DB_PASSWORD", "DLAP_BACKUP_DB_PASSWORD"));

        for (String variable : variables) {
            assertThat(valueOf(env, variable)).as(variable).isEqualTo("${" + variable + ":-}");
        }
        assertThat(valueOf(env, "POSTGRES_PASSWORD")).as("the superuser keeps its dev fallback in the dev pair")
                .isEqualTo("${POSTGRES_PASSWORD:-dlap}");
    }

    /** SEC-12 (Q-247, Q-455): the exporter connects as the monitor role, not as the superuser. */
    @Test
    void thePostgresExporterConnectsAsTheMonitorRole() {
        List<String> env = environment(infrastructure, "postgres-exporter");

        assertThat(valueOf(env, "DATA_SOURCE_USER")).isEqualTo("dlap_monitor");
        assertThat(valueOf(env, "DATA_SOURCE_PASS")).isEqualTo("${DLAP_MONITOR_DB_PASSWORD:-dlap_monitor_pass}");
        assertThat(env).noneMatch(e -> e.contains("POSTGRES_PASSWORD"));
    }

    /** Q-246: the issuer and audience variables are passed to every resource server and documented. */
    @Test
    void theJwtValidationVariablesAreHandedToEveryResourceServer() {
        List<String> servers = List.of("da-discovery", "da-catalog", "da-policyengine", "da-reporting",
                "da-autolabel", "da-autoarchival", "da-autodelete", "da-logingest", "da-autocompliance",
                "da-admin-service");
        for (String name : servers) {
            List<String> env = environment(microservices, name);
            assertThat(valueOf(env, "DLAP_SECURITY_JWT_VALIDATE_ISSUER")).as(name)
                    .isEqualTo("${DLAP_SECURITY_JWT_VALIDATE_ISSUER:-false}");
            assertThat(valueOf(env, "DLAP_SECURITY_JWT_EXPECTED_ISSUER")).as(name)
                    .isEqualTo("${DLAP_SECURITY_JWT_EXPECTED_ISSUER:-}");
            assertThat(valueOf(env, "DLAP_SECURITY_JWT_VALIDATE_AUDIENCE")).as(name)
                    .isEqualTo("${DLAP_SECURITY_JWT_VALIDATE_AUDIENCE:-false}");
            assertThat(valueOf(env, "DLAP_SECURITY_JWT_EXPECTED_AUDIENCE")).as(name)
                    .isEqualTo("${DLAP_SECURITY_JWT_EXPECTED_AUDIENCE:-}");
        }
        assertThat(envExample).contains("DLAP_SECURITY_JWT_VALIDATE_ISSUER=false")
                .contains("DLAP_SECURITY_JWT_EXPECTED_ISSUER=")
                .contains("DLAP_SECURITY_JWT_VALIDATE_AUDIENCE=false")
                .contains("DLAP_SECURITY_JWT_EXPECTED_AUDIENCE=");
    }
}
