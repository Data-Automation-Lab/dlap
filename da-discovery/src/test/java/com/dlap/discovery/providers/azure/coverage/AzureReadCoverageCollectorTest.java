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

package com.dlap.discovery.providers.azure.coverage;

import static com.dlap.discovery.coverage.CoverageFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.dlap.discovery.coverage.CoverageFixtures;
import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.CoverageLevel;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.coverage.LocalJsonServer;
import com.dlap.discovery.coverage.ReadCoverageObservation;

import reactor.core.publisher.Mono;

/**
 * COV-01, Azure: the diagnostic-settings rules over responses written from the Azure Monitor and
 * Storage REST references ({@code src/test/resources/coverage/azure/meta.yaml}), and the collector
 * over the real REST client and a local server.
 */
class AzureReadCoverageCollectorTest {

    private static final String SUB = "00000000-1111-2222-3333-444444444444";
    private static final String LAKE = "/subscriptions/" + SUB
            + "/resourceGroups/rg-data/providers/Microsoft.Storage/storageAccounts/dlapdemolake";
    private static final String ARCHIVE = "/subscriptions/" + SUB
            + "/resourceGroups/rg-data/providers/Microsoft.Storage/storageAccounts/dlapdemoarchive";
    private static final String DIAG = "/blobServices/default/providers/Microsoft.Insights/diagnosticSettings"
            + "?api-version=2021-05-01-preview";

    private static final TokenCredential TOKEN = request -> Mono.just(
            new AccessToken("test-token", OffsetDateTime.parse("2030-01-01T00:00:00Z")));

    @Test
    void storageReadToAWorkspaceIsFull() {
        CoverageVerdict v = AzureDiagnosticSettingsEvaluator.evaluate(json("azure/diagnostic-settings-storage-read.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.FULL);
        assertThat(v.reasons()).containsExactly("diagnostic setting to-law sends StorageRead to a Log Analytics workspace");
    }

    @Test
    void theAllLogsGroupHoldsStorageRead() {
        CoverageVerdict v = AzureDiagnosticSettingsEvaluator.evaluate(json("azure/diagnostic-settings-all-logs.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.FULL);
        assertThat(v.reasons().get(0)).endsWith("to a storage account");
    }

    @Test
    void aSettingWithoutStorageReadIsPartialWithTheSpecWording() {
        CoverageVerdict v = AzureDiagnosticSettingsEvaluator.evaluate(
                json("azure/diagnostic-settings-without-storage-read.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons()).containsExactly("diagnostic setting without StorageRead");
    }

    @Test
    void theAuditGroupIsNotProvenToHoldStorageRead() {
        CoverageVerdict v = AzureDiagnosticSettingsEvaluator.evaluate(json("azure/diagnostic-settings-audit-group.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons().get(0)).contains("audit category group");
    }

    @Test
    void noSettingIsNone() {
        CoverageVerdict v = AzureDiagnosticSettingsEvaluator.evaluate(json("azure/diagnostic-settings-none.json"));
        assertThat(v.level()).isEqualTo(CoverageLevel.NONE);
        assertThat(v.reasons()).containsExactly("no diagnostic setting on the blob service");
    }

    @Test
    void aSettingWithStorageReadButNoDestinationIsNotFull() {
        CoverageVerdict v = AzureDiagnosticSettingsEvaluator.evaluate(CoverageFixtures.parse(
                "{\"value\":[{\"name\":\"nowhere\",\"properties\":{\"logs\":[{\"category\":\"StorageRead\",\"enabled\":true}]}}]}"));
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons()).containsExactly("diagnostic setting nowhere enables StorageRead but has no destination");
    }

    private static List<ReadCoverageObservation> collect(LocalJsonServer server) {
        AzureCoverageRestClient client = new AzureCoverageRestClient(CoverageFixtures.http(),
                () -> TOKEN, server.base());
        return new AzureReadCoverageCollector(client, List.of(SUB)).collect();
    }

    @Test
    void oneObservationPerStorageAccountAndARefusalIsUnknown() throws IOException {
        try (LocalJsonServer server = new LocalJsonServer()
                .answer("GET /subscriptions/" + SUB + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-05-01",
                        200, json("azure/storage-accounts.json").toString())
                .answer("GET " + LAKE + DIAG, 200, json("azure/diagnostic-settings-storage-read.json").toString())
                .answer("GET " + ARCHIVE + DIAG, 403, "{\"error\":{\"code\":\"AuthorizationFailed\"}}")) {
            List<ReadCoverageObservation> out = collect(server);

            assertThat(out).extracting(ReadCoverageObservation::rootUri)
                    .containsExactly("az://dlapdemolake", "az://dlapdemoarchive");
            ReadCoverageObservation lake = out.get(0);
            assertThat(lake.provider()).isEqualTo("AZURE");
            assertThat(lake.scopeId()).isEqualTo(SUB);
            assertThat(lake.channel()).isEqualTo("AZURE_STORAGE_RESOURCE_LOG");
            assertThat(lake.appliesTo()).containsExactly("BLOB_STORAGE", "BLOB_CONTAINER", "BLOB_OBJECT");
            assertThat(lake.verdict().level()).isEqualTo(CoverageLevel.FULL);

            assertThat(out.get(1).verdict().known()).isFalse();
            assertThat(out.get(1).verdict().reasons()).containsExactly("missing permission "
                    + "Microsoft.Insights/diagnosticSettings/read on " + ARCHIVE + "/blobServices/default");
            assertThat(server.requests()).allMatch(r -> "Bearer test-token".equals(r.authorization()));
        }
    }

    private static final String LIST = "GET /subscriptions/" + SUB
            + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-05-01";
    private static final String TAIL = "/subscriptions/" + SUB
            + "/resourceGroups/rg-data/providers/Microsoft.Storage/storageAccounts/dlapdemotail";

    /** The two fixture accounts and a third, so a failing account has one before and one after it. */
    private static String threeAccounts() {
        return "{\"value\":[{\"id\":\"" + LAKE + "\",\"name\":\"dlapdemolake\"},{\"id\":\"" + ARCHIVE
                + "\",\"name\":\"dlapdemoarchive\"},{\"id\":\"" + TAIL + "\",\"name\":\"dlapdemotail\"}]}";
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500, 503})
    void aDiagnosticSettingsErrorOnOneAccountMakesOnlyThatAccountUnknownAndTheRunContinues(int status)
            throws IOException {
        try (LocalJsonServer server = new LocalJsonServer()
                .answer(LIST, 200, threeAccounts())
                .answer("GET " + LAKE + DIAG, 200, json("azure/diagnostic-settings-storage-read.json").toString())
                .answer("GET " + ARCHIVE + DIAG, status, "{\"error\":{\"code\":\"" + status + "\"}}")
                .answer("GET " + TAIL + DIAG, 200, json("azure/diagnostic-settings-none.json").toString())) {
            List<ReadCoverageObservation> out = collect(server);

            assertThat(out).extracting(ReadCoverageObservation::rootUri)
                    .containsExactly("az://dlapdemolake", "az://dlapdemoarchive", "az://dlapdemotail");
            assertThat(out.get(0).verdict().level()).as("account before").isEqualTo(CoverageLevel.FULL);
            assertThat(out.get(1).verdict().known()).isFalse();
            assertThat(out.get(1).verdict().reasons()).singleElement().asString()
                    .contains("Microsoft.Insights/diagnosticSettings/read").contains("dlapdemoarchive")
                    .contains("HTTP " + status);
            assertThat(out.get(2).verdict().level()).as("account after").isEqualTo(CoverageLevel.NONE);
            assertThat(out.get(1).scopeId()).isEqualTo(SUB);
        }
    }

    @Test
    void aTransportFailureOnOneAccountIsUnknownForThatAccountOnly() {
        AzureCoverageApi api = new AzureCoverageApi() {
            @Override
            public List<com.fasterxml.jackson.databind.JsonNode> listStorageAccounts(String subscriptionId) {
                List<com.fasterxml.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
                CoverageFixtures.parse(threeAccounts()).path("value").forEach(out::add);
                return out;
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode listBlobDiagnosticSettings(String storageAccountId) {
                if (storageAccountId.equals(ARCHIVE)) {
                    throw new CoverageTransportException("call to http://127.0.0.1/x failed: HttpTimeoutException");
                }
                return json("azure/diagnostic-settings-storage-read.json");
            }
        };
        List<ReadCoverageObservation> out = new AzureReadCoverageCollector(api, List.of(SUB)).collect();

        assertThat(out).hasSize(3);
        assertThat(out.get(1).verdict().known()).isFalse();
        assertThat(out.get(1).verdict().reasons()).singleElement().asString().contains("HttpTimeoutException");
        assertThat(out.get(0).verdict().level()).isEqualTo(CoverageLevel.FULL);
        assertThat(out.get(2).verdict().level()).isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void anInterruptedRunIsNotSwallowedAsAnUnknownAccount() {
        AzureCoverageApi api = new AzureCoverageApi() {
            @Override
            public List<com.fasterxml.jackson.databind.JsonNode> listStorageAccounts(String subscriptionId) {
                return List.of(CoverageFixtures.parse("{\"id\":\"" + LAKE + "\",\"name\":\"dlapdemolake\"}"));
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode listBlobDiagnosticSettings(String storageAccountId) {
                Thread.currentThread().interrupt();
                throw new CoverageTransportException("interrupted calling " + storageAccountId);
            }
        };
        try {
            assertThatThrownBy(() -> new AzureReadCoverageCollector(api, List.of(SUB)).collect())
                    .isInstanceOf(CoverageTransportException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailureOfTheWholeCollectorStillPublishesNothing() throws IOException {
        // The storage account listing answers a server error: not per account, so the run ends.
        try (LocalJsonServer server = new LocalJsonServer().answer(LIST, 500, "{}")) {
            assertThatThrownBy(() -> collect(server)).isInstanceOf(CoverageTransportException.class)
                    .hasMessageContaining("Microsoft.Storage/storageAccounts/read").hasMessageContaining("HTTP 500");
        }
        // The listing is refused: the subscription gets no observation, so nothing is published for it.
        try (LocalJsonServer server = new LocalJsonServer().answer(LIST, 403, "{\"error\":{\"code\":\"AuthorizationFailed\"}}")) {
            assertThat(collect(server)).isEmpty();
        }
        // A token that cannot be obtained ends the run before any account is read.
        AzureCoverageRestClient noToken = new AzureCoverageRestClient(CoverageFixtures.http(),
                () -> request -> Mono.empty(), "http://127.0.0.1:1");
        assertThatThrownBy(() -> new AzureReadCoverageCollector(noToken, List.of(SUB)).collect())
                .isInstanceOf(CoverageTransportException.class).hasMessageContaining("token");
    }

    @Test
    void aNextLinkOutsideTheEndpointIsRefusedSoTheTokenIsNotSentElsewhere() throws IOException {
        try (LocalJsonServer server = new LocalJsonServer()
                .answer("GET /subscriptions/" + SUB + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-05-01",
                        200, "{\"value\":[],\"nextLink\":\"https://elsewhere.example.com/page2\"}")) {
            assertThatThrownBy(() -> collect(server)).isInstanceOf(CoverageTransportException.class)
                    .hasMessageContaining("nextLink");
        }
    }
}
