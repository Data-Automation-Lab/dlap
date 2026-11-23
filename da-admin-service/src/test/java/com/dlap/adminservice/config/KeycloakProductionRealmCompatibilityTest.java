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

package com.dlap.adminservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dlap.adminservice.dto.RoleDTO;
import com.dlap.adminservice.dto.UserDTO;
import com.dlap.adminservice.mapper.RoleMapperImpl;
import com.dlap.adminservice.mapper.UserMapperImpl;
import com.dlap.adminservice.service.impl.RoleServiceImpl;
import com.dlap.adminservice.service.impl.UserServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * IAM-02: da-admin-service against the Keycloak release the quickstart, the pilot and the demo now run
 * (26.8.1, pinned by digest) with the production realm template imported. It signs in the way the pilot
 * configures it (the bootstrap admin of the master realm, created from {@code KC_BOOTSTRAP_ADMIN_*}) and
 * runs its role and user services on realm {@code dlap}: the template's roles are listed, and a user is
 * created under the template's password policy, given roles, read and deleted. A wire or representation
 * incompatibility between admin client 26 and this server, or a template the admin API cannot work with,
 * fails the build.
 *
 * <p>Needs Docker. Skipped, not failed, when the module is built without the rest of the checkout.
 */
class KeycloakProductionRealmCompatibilityTest {

    /** The image of deployment/environments/dev/docker-compose.yml and deployment/platform/keycloak/Dockerfile. */
    static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.1"
            + "@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244";

    private static final String REALM = "dlap";

    private static final String ADMIN_PASSWORD = "iam02-compat-admin-password";

    private static GenericContainer<?> keycloak;

    private static Keycloak client;

    @BeforeAll
    static void start() {
        Path template = repositoryRoot().resolve("deployment/platform/keycloak/production/dlap-realm.json");
        assumeTrue(Files.isRegularFile(template), "production realm template not found; skipping");
        keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("DLAP_BACKEND_CLIENT_SECRET", "iam02-compat-backend-secret")
                .withEnv("DLAP_MCP_GATEWAY_CLIENT_SECRET", "iam02-compat-gateway-secret")
                .withEnv("DLAP_APP_URL", "http://localhost:8081")
                .withCopyFileToContainer(MountableFile.forHostPath(template), "/opt/keycloak/data/import/dlap-realm.json")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(4)));
        keycloak.start();

        KeycloakAdminClientConfig config = new KeycloakAdminClientConfig();
        ReflectionTestUtils.setField(config, "serverUrl",
                "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080));
        ReflectionTestUtils.setField(config, "authRealm", "master");
        ReflectionTestUtils.setField(config, "clientId", "admin-cli");
        ReflectionTestUtils.setField(config, "adminUsername", "admin");
        ReflectionTestUtils.setField(config, "adminPassword", ADMIN_PASSWORD);
        client = config.keycloakAdminClient();
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    /** Walks up from the module directory to the checkout root (the folder that holds .env.example). */
    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("") : dir;
    }

    @Test
    void roleServiceListsTheTemplateRoles() {
        RoleServiceImpl service = new RoleServiceImpl(client, new RoleMapperImpl());
        ReflectionTestUtils.setField(service, "realmName", REALM);

        List<String> names = service.getAllRealmRoles().stream().map(RoleDTO::getName).toList();

        assertThat(names).contains("ROLE_ADMIN", "ROLE_CDO", "ROLE_DIRECTOR", "ROLE_POLICY_MANAGER",
                "ROLE_DATA_ENGINEER", "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST", "ROLE_AUDITOR", "ROLE_VIEWER",
                "ROLE_SERVICE_ACCOUNT", "dlap-mfa-required");
    }

    @Test
    void userServiceManagesAPersonInTheTemplateRealm() {
        UserServiceImpl service = new UserServiceImpl(client, new UserMapperImpl());
        ReflectionTestUtils.setField(service, "realmName", REALM);
        UserDTO request = new UserDTO();
        request.setUsername("iam02-viewer");
        request.setEmail("iam02-viewer@example.invalid");
        request.setFirstName("Compat");
        request.setLastName("Viewer");
        request.setPassword("Compat-Passw0rd-2026");
        request.setRoles(List.of("ROLE_VIEWER"));

        UserDTO created = service.createUser(request);

        assertThat(created.getId()).isNotBlank();
        assertThat(service.getUserRealmRoles(created.getId())).extracting(RoleRepresentation::getName)
                .contains("ROLE_VIEWER");
        service.assignRealmRolesToUser(created.getId(), List.of("ROLE_POLICY_MANAGER"));
        assertThat(service.getUserRealmRoles(created.getId())).extracting(RoleRepresentation::getName)
                .contains("ROLE_VIEWER", "ROLE_POLICY_MANAGER");
        assertThat(service.getUserByUsername("iam02-viewer")).map(UserDTO::getId).contains(created.getId());
        service.deleteUser(created.getId());
        assertThat(service.getUserById(created.getId())).isEmpty();
    }

    @Test
    void theTemplateSeedsNoPeople() {
        List<String> people = client.realm(REALM).users().list(0, 100).stream()
                .map(user -> user.getUsername())
                .filter(name -> !name.startsWith("service-account-"))
                .filter(name -> !name.equals("iam02-viewer"))
                .toList();

        assertThat(people).isEmpty();
    }
}
