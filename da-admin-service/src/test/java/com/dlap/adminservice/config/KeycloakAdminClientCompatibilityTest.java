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

import com.dlap.adminservice.dto.RoleDTO;
import com.dlap.adminservice.dto.UserDTO;
import com.dlap.adminservice.mapper.RoleMapperImpl;
import com.dlap.adminservice.mapper.UserMapperImpl;
import com.dlap.adminservice.service.impl.RoleServiceImpl;
import com.dlap.adminservice.service.impl.UserServiceImpl;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * VULN-01: the Keycloak admin client moved from 24.0.4 to the independently released 26.0.x line
 * (CVE-2024-10039 in keycloak-core 24.0.4, which admin client 26 no longer depends on). This test runs
 * the admin-service's own client configuration and its user and role services against the Keycloak
 * server release the dev compose stack, the demo and the pilot ship (quay.io/keycloak/keycloak:26.8.1,
 * Q-508), so a wire or representation incompatibility between the client and that server fails the build.
 * The production realm and the pilot sign-in are covered by {@link KeycloakProductionRealmCompatibilityTest}.
 * {@code test-realm-templates.py} fails when this tag and the one in the compose files differ.
 */
@Testcontainers
class KeycloakAdminClientCompatibilityTest {

    /** The server release in deployment/environments/dev/docker-compose.yml (the tag; Testcontainers takes no digest after it). */
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.1";

    private static final String REALM = "vuln01-compat";

    @Container
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    private static Keycloak client;

    @BeforeAll
    static void connect() {
        KeycloakAdminClientConfig config = new KeycloakAdminClientConfig();
        ReflectionTestUtils.setField(config, "serverUrl",
                "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080));
        ReflectionTestUtils.setField(config, "authRealm", "master");
        ReflectionTestUtils.setField(config, "clientId", "admin-cli");
        ReflectionTestUtils.setField(config, "adminUsername", "admin");
        ReflectionTestUtils.setField(config, "adminPassword", "admin");
        client = config.keycloakAdminClient();

        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(REALM);
        realm.setEnabled(true);
        client.realms().create(realm);
        for (String role : List.of("DATA_STEWARD", "DATA_ANALYST")) {
            RoleRepresentation representation = new RoleRepresentation();
            representation.setName(role);
            client.realm(REALM).roles().create(representation);
        }
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    private static UserServiceImpl userService() {
        UserServiceImpl service = new UserServiceImpl(client, new UserMapperImpl());
        ReflectionTestUtils.setField(service, "realmName", REALM);
        return service;
    }

    @Test
    void roleServiceListsTheRealmRolesThroughAdminClient26() {
        RoleServiceImpl service = new RoleServiceImpl(client, new RoleMapperImpl());
        ReflectionTestUtils.setField(service, "realmName", REALM);

        List<String> names = service.getAllRealmRoles().stream().map(RoleDTO::getName).toList();

        assertThat(names).contains("DATA_STEWARD", "DATA_ANALYST");
    }

    @Test
    void userServiceCreatesReadsAssignsAndDeletesAUser() {
        UserServiceImpl service = userService();
        UserDTO request = new UserDTO();
        request.setUsername("compat-user");
        request.setEmail("compat-user@example.invalid");
        request.setFirstName("Compat");
        request.setLastName("User");
        request.setPassword("Compat-Passw0rd!");
        request.setRoles(List.of("DATA_STEWARD"));

        UserDTO created = service.createUser(request);

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getUsername()).isEqualTo("compat-user");
        assertThat(service.getUserRealmRoles(created.getId())).extracting(RoleRepresentation::getName)
                .contains("DATA_STEWARD");

        service.assignRealmRolesToUser(created.getId(), List.of("DATA_ANALYST"));
        assertThat(service.getUserRealmRoles(created.getId())).extracting(RoleRepresentation::getName)
                .contains("DATA_STEWARD", "DATA_ANALYST");

        Optional<UserDTO> byName = service.getUserByUsername("compat-user");
        assertThat(byName).map(UserDTO::getId).contains(created.getId());
        assertThat(service.getAllUsers(0, 10)).extracting(UserDTO::getUsername).contains("compat-user");

        service.deleteUser(created.getId());
        assertThat(service.getUserById(created.getId())).isEmpty();
    }
}
