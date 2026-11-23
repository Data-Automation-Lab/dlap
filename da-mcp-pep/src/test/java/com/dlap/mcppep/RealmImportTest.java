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

package com.dlap.mcppep;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * PEP-02 guard on the shipped Keycloak realm import: the {@code dlap-mcp-gateway} client that MCP
 * gateways use to call the PEP (Q-381). The live proof is {@code scripts/keycloak-live-check.sh}; this
 * test catches the same wiring mistakes without Docker, including the one the first live run found
 * (a client description longer than the 255 characters Keycloak stores).
 */
class RealmImportTest {

    private static final Path REALM = Path.of("..", "deployment", "platform", "keycloak", "keycloak-import",
            "dlap-realm.json");

    /** The audience the PEP requires; the same string the PEP is configured with in the live check. */
    private static final String PEP_AUDIENCE = "dlap-mcp-pep";

    private static JsonNode realm;

    @BeforeAll
    static void readRealm() throws IOException {
        realm = new ObjectMapper().readTree(Files.readString(REALM));
    }

    private static JsonNode gateway() {
        for (JsonNode client : realm.get("clients")) {
            if ("dlap-mcp-gateway".equals(client.path("clientId").asText())) {
                return client;
            }
        }
        throw new AssertionError("the realm import has no dlap-mcp-gateway client");
    }

    @Test
    void theGatewayClientIsConfidentialAndClientCredentialsOnly() {
        JsonNode client = gateway();

        assertThat(client.path("publicClient").asBoolean(true)).isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(client.path("standardFlowEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("implicitFlowEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(client.path("enabled").asBoolean()).isTrue();
    }

    @Test
    void theGatewaySecretIsAnEnvironmentPlaceholder() {
        String secret = gateway().path("secret").asText();

        assertThat(secret).startsWith("${DLAP_MCP_GATEWAY_CLIENT_SECRET:").endsWith("}");
    }

    @Test
    void theGatewayTokensCarryThePepAudience() {
        List<JsonNode> audienceMappers = new ArrayList<>();
        for (JsonNode mapper : gateway().path("protocolMappers")) {
            if ("oidc-audience-mapper".equals(mapper.path("protocolMapper").asText())) {
                audienceMappers.add(mapper);
            }
        }

        assertThat(audienceMappers).hasSize(1);
        JsonNode config = audienceMappers.get(0).path("config");
        assertThat(config.path("included.custom.audience").asText()).isEqualTo(PEP_AUDIENCE);
        assertThat(config.path("access.token.claim").asText()).isEqualTo("true");
    }

    @Test
    void theGatewayGetsTheRolesClaimThatTheServicesRead() {
        assertThat(gateway().path("defaultClientScopes")).extracting(JsonNode::asText).contains("dlap");
    }

    @Test
    void theGatewayServiceAccountHoldsExactlyTheMachineRole() {
        JsonNode serviceAccount = null;
        for (JsonNode user : realm.get("users")) {
            if ("dlap-mcp-gateway".equals(user.path("serviceAccountClientId").asText())) {
                serviceAccount = user;
            }
        }

        assertThat(serviceAccount).as("service account user of dlap-mcp-gateway").isNotNull();
        assertThat(serviceAccount.path("realmRoles")).extracting(JsonNode::asText)
                .containsExactly("ROLE_SERVICE_ACCOUNT");
    }

    /** Keycloak stores the description and the name in 255-character columns; a longer value stops the import. */
    @Test
    void theGatewayTextFieldsFitKeycloaksColumns() {
        assertThat(gateway().path("description").asText().length()).isLessThanOrEqualTo(255);
        assertThat(gateway().path("name").asText().length()).isLessThanOrEqualTo(255);
    }

    /** The audience mapper is the only reason the other clients are unaffected: none of them names the PEP. */
    @Test
    void noOtherClientGetsThePepAudience() {
        for (JsonNode client : realm.get("clients")) {
            if ("dlap-mcp-gateway".equals(client.path("clientId").asText())) {
                continue;
            }
            for (JsonNode mapper : client.path("protocolMappers")) {
                assertThat(mapper.path("config").path("included.custom.audience").asText())
                        .as(client.path("clientId").asText()).isNotEqualTo(PEP_AUDIENCE);
            }
        }
    }
}
