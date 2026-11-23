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

package com.dlap.adminservice.permissioncheck.providers.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.export.StubHttpServer;
import com.dlap.adminservice.permissioncheck.ActorRoleTraceabilityTest;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.Status;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.TargetReport;
import com.dlap.adminservice.permissioncheck.PermissionCheckService;
import com.dlap.adminservice.permissioncheck.PermissionProbe;
import com.dlap.adminservice.permissioncheck.TraceSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Azure probe against a stand-in for the Azure Resource Manager permissions API and IMDS, and
 * the report the self-check builds from the answers against the committed Azure Actor trace. A live
 * subscription is the founder's check.
 */
class AzurePermissionsProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg/"
            + "providers/Microsoft.Storage/storageAccounts/acct1";
    private static final String CONTAINER = ACCOUNT + "/blobServices/default/containers/logs";
    private static final String MP = "Microsoft.Storage/storageAccounts/managementPolicies/";
    private static final String BLOBS = "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/";
    /** Q-743: the control-plane read of the blob service properties (the versioning check). */
    private static final String BSR = "Microsoft.Storage/storageAccounts/blobServices/read";

    @TempDir
    Path dir;

    private StubHttpServer server;
    private AzurePermissionCheckProperties config;

    @BeforeEach
    void start() throws IOException {
        server = new StubHttpServer();
        config = new AzurePermissionCheckProperties();
        config.setManagementEndpoint(server.url());
        config.setImdsEndpoint(server.url());
        Path token = dir.resolve("token");
        Files.writeString(token, "test-token-not-real");
        config.setAccessTokenFile(token.toString());
        AzurePermissionCheckProperties.Executor delete = new AzurePermissionCheckProperties.Executor();
        delete.setStorageAccountIds(List.of(ACCOUNT));
        delete.setContainerIds(List.of(CONTAINER));
        delete.setObjectActions(true);
        config.getExecutors().put("delete", delete);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private AzurePermissionsProbe probe() {
        return new AzurePermissionsProbe(config, new HttpCalls(Duration.ofSeconds(5)),
                Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));
    }

    private PermissionCheckReport check() {
        TraceSource repo = new TraceSource(ActorRoleTraceabilityTest.repositoryRoot().resolve("deployment/iam"));
        return new PermissionCheckService(repo, List.<PermissionProbe>of(probe())).check("azure").orElseThrow();
    }

    private static String entry(List<String> actions, List<String> notActions, List<String> dataActions) {
        return "{\"actions\":" + JSON.valueToTree(actions) + ",\"notActions\":" + JSON.valueToTree(notActions)
                + ",\"dataActions\":" + JSON.valueToTree(dataActions) + ",\"notDataActions\":[]}";
    }

    @Test
    void theSelfCheckReportsAMissingAndAnExtraPermissionFromTheEffectivePermissions() {
        server.respond(r -> {
            boolean onContainer = r.uri().getPath().startsWith(CONTAINER + "/");
            String inherited = entry(List.of(BSR, MP + "read", MP + "write", "Microsoft.Storage/storageAccounts/read"),
                    List.of(), List.of());
            String body = onContainer
                    ? "{\"value\":[" + inherited + "," + entry(List.of(), List.of(), List.of(BLOBS + "*")) + "]}"
                    : "{\"value\":[" + inherited + "]}";
            return StubHttpServer.Response.of(200, body);
        });

        PermissionCheckReport report = check();

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.method()).isEqualTo("Microsoft.Authorization/permissions");
        assertThat(report.missing()).containsExactly("delete: " + MP + "delete on " + ACCOUNT);
        assertThat(report.extra()).containsExactlyInAnyOrder(
                "delete: " + BLOBS + "*" + " on " + CONTAINER,
                "delete: " + BLOBS + "deleteBlobVersion/action on " + CONTAINER,
                "delete: " + BLOBS + "read on " + CONTAINER,
                "delete: " + BLOBS + "write on " + CONTAINER);
        TargetReport account = report.targets().get(0);
        assertThat(account.readerHeld()).containsExactly("Microsoft.Storage/storageAccounts/read");
        assertThat(report.targets().get(1).missing()).as("blobs/delete is held through the wildcard").isEmpty();

        StubHttpServer.Request r = server.requests().get(0);
        assertThat(r.uri().getPath()).isEqualTo(ACCOUNT + "/providers/Microsoft.Authorization/permissions");
        assertThat(r.uri().getQuery()).isEqualTo("api-version=" + AzurePermissionsProbe.API_VERSION);
        assertThat(r.headers().get("authorization")).isEqualTo("Bearer test-token-not-real");
    }

    @Test
    void theExactGrantIsAPass() {
        server.respond(r -> StubHttpServer.Response.of(200, r.uri().getPath().startsWith(CONTAINER + "/")
                ? "{\"value\":[" + entry(List.of(BSR, MP + "read", MP + "write", MP + "delete"), List.of(), List.of())
                        + "," + entry(List.of(), List.of(), List.of(BLOBS + "delete")) + "]}"
                : "{\"value\":[" + entry(List.of(BSR, MP + "read", MP + "write", MP + "delete"), List.of(), List.of())
                        + "]}"));
        PermissionCheckReport report = check();
        assertThat(report.extra()).isEmpty();
        assertThat(report.missing()).isEmpty();
        assertThat(report.status()).isEqualTo(Status.PASS);
    }

    @Test
    void theReportNamesTheCallingIdentityItCheckedAndSaysNothingIsImpersonated() {
        // Q-727: Microsoft.Authorization/permissions answers for the caller, so the report says whose it read.
        server.respond(r -> StubHttpServer.Response.of(200, "{\"value\":[]}"));
        PermissionCheckReport tokenFile = check();
        assertThat(tokenFile.identityBasis()).isEqualTo(PermissionCheckReport.IdentityBasis.CALLER);
        assertThat(tokenFile.identityChecked()).isEqualTo("the identity of the access token file");
        assertThat(tokenFile.identityNote()).contains("calling identity", "nothing is impersonated", "Q-727");

        config.setAccessTokenFile("");
        server.respond(r -> r.uri().getPath().startsWith("/metadata/identity/")
                ? StubHttpServer.Response.of(200, "{\"access_token\":\"mi\",\"expires_on\":\""
                        + Instant.parse("2026-11-01T11:00:00Z").getEpochSecond() + "\"}")
                : StubHttpServer.Response.of(200, "{\"value\":[]}"));
        assertThat(check().identityChecked()).isEqualTo("the managed identity");
    }

    @Test
    void aDeleteActorWithoutBlobServicesReadIsReportedMissingOnTheAccountOnly() {
        // Q-743: the versioning read needs the control-plane action; without it every Azure container rule waits.
        server.respond(r -> StubHttpServer.Response.of(200, r.uri().getPath().startsWith(CONTAINER + "/")
                ? "{\"value\":[" + entry(List.of(MP + "read", MP + "write", MP + "delete"), List.of(), List.of()) + ","
                        + entry(List.of(), List.of(), List.of(BLOBS + "delete")) + "]}"
                : "{\"value\":[" + entry(List.of(MP + "read", MP + "write", MP + "delete"), List.of(), List.of())
                        + "]}"));
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.missing()).containsExactly("delete: " + BSR + " on " + ACCOUNT);
        assertThat(report.targets().get(0).expected()).contains(BSR);
        assertThat(report.targets().get(1).expected()).as("a container is no place for a control-plane read")
                .doesNotContain(BSR);
    }

    @Test
    void blobServicesReadHeldOnTheAccountIsInheritedByItsContainersAndNeverExtra() {
        server.respond(r -> StubHttpServer.Response.of(200, "{\"value\":[" + entry(
                List.of(BSR, MP + "read", MP + "write", MP + "delete"), List.of(),
                r.uri().getPath().startsWith(CONTAINER + "/") ? List.of(BLOBS + "delete") : List.of()) + "]}"));
        PermissionCheckReport report = check();
        assertThat(report.extra()).isEmpty();
        assertThat(report.missing()).isEmpty();
    }

    // ------------------------------------------------- IAM-06 (Q-761): the archive Actor on a named container

    /** Configures the archive executor alone, on the named container. */
    private void archiveOnly(boolean objectActions) {
        config.getExecutors().clear();
        AzurePermissionCheckProperties.Executor archive = new AzurePermissionCheckProperties.Executor();
        archive.setContainerIds(List.of(CONTAINER));
        archive.setObjectActions(objectActions);
        config.getExecutors().put("archive", archive);
    }

    private static StubHttpServer.Response dataActions(List<String> dataActions) {
        return StubHttpServer.Response.of(200, "{\"value\":[" + entry(List.of(), List.of(), dataActions) + "]}");
    }

    @Test
    void theArchiveActorWithBlobReadAndWriteOnItsNamedContainerIsAPass() {
        archiveOnly(true);
        server.respond(r -> dataActions(List.of(BLOBS + "read", BLOBS + "write")));
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.PASS);
        assertThat(report.targets()).hasSize(1);
        TargetReport t = report.targets().get(0);
        assertThat(t.executor()).isEqualTo("archive");
        assertThat(t.scope()).isEqualTo("container");
        assertThat(t.expected()).containsExactly(BLOBS + "read", BLOBS + "write");
        assertThat(server.requests()).hasSize(1);
        assertThat(server.requests().get(0).uri().getPath())
                .as("asked on the container, never on the account or the subscription")
                .isEqualTo(CONTAINER + "/providers/Microsoft.Authorization/permissions");
    }

    @Test
    void theArchiveActorWithoutTheTierWriteIsMissingItAndTheDeleteActionIsExtra() {
        archiveOnly(true);
        server.respond(r -> dataActions(List.of(BLOBS + "read", BLOBS + "delete")));
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.missing()).containsExactly("archive: " + BLOBS + "write on " + CONTAINER);
        assertThat(report.extra()).containsExactly("archive: " + BLOBS + "delete on " + CONTAINER);
    }

    @Test
    void theArchiveActorHoldingAWildcardOrTheVersionDeleteIsExtra() {
        archiveOnly(true);
        server.respond(r -> dataActions(List.of(BLOBS + "*")));
        PermissionCheckReport report = check();
        assertThat(report.missing()).as("read and write are held through the wildcard").isEmpty();
        assertThat(report.extra()).containsExactlyInAnyOrder(
                "archive: " + BLOBS + "* on " + CONTAINER,
                "archive: " + BLOBS + "delete on " + CONTAINER,
                "archive: " + BLOBS + "deleteBlobVersion/action on " + CONTAINER);
    }

    @Test
    void theArchiveActorOnTheNativePathHoldsNoBlobDataActionAndAnyIsExtra() {
        // object_actions=false: the policy is rendered and applied by its owner, so the archive Actor holds no role.
        archiveOnly(false);
        server.respond(r -> dataActions(List.of(BLOBS + "read")));
        PermissionCheckReport report = check();
        assertThat(report.targets().get(0).expected()).isEmpty();
        assertThat(report.missing()).isEmpty();
        assertThat(report.extra()).containsExactly("archive: " + BLOBS + "read on " + CONTAINER);

        server.respond(r -> dataActions(List.of()));
        assertThat(check().status()).isEqualTo(Status.PASS);
    }

    @Test
    void theDeleteActorHoldingTheArchiveActionsHasThemAsExtraAndOneIdentityForBothActorsFails() {
        // The delete Actor never reads or writes blobs: the archive Actor's two actions are extra on its container.
        server.respond(r -> StubHttpServer.Response.of(200, r.uri().getPath().startsWith(CONTAINER + "/")
                ? "{\"value\":[" + entry(List.of(BSR, MP + "read", MP + "write", MP + "delete"), List.of(),
                        List.of(BLOBS + "delete", BLOBS + "read", BLOBS + "write")) + "]}"
                : "{\"value\":[" + entry(List.of(BSR, MP + "read", MP + "write", MP + "delete"), List.of(), List.of())
                        + "]}"));
        AzurePermissionCheckProperties.Executor archive = new AzurePermissionCheckProperties.Executor();
        archive.setContainerIds(List.of(CONTAINER));
        archive.setObjectActions(true);
        config.getExecutors().put("archive", archive);
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.missing()).isEmpty();
        assertThat(report.extra()).as("one identity holding both Actors is extra for each; the roles stay apart")
                .contains("delete: " + BLOBS + "read on " + CONTAINER, "delete: " + BLOBS + "write on " + CONTAINER,
                        "archive: " + BLOBS + "delete on " + CONTAINER)
                .anyMatch(e -> e.startsWith("archive: " + MP));
    }

    @Test
    void notActionsTakeAPermissionAwayAndMatchingIsCaseInsensitive() throws IOException {
        List<JsonNode> entries = List.of(JSON.readTree(entry(List.of("Microsoft.Storage/*"),
                List.of("microsoft.storage/storageAccounts/managementPolicies/delete"), List.of())));
        assertThat(AzurePermissionsProbe.held(entries, MP + "write")).isTrue();
        assertThat(AzurePermissionsProbe.held(entries, MP + "DELETE")).isFalse();
        assertThat(AzurePermissionsProbe.held(entries, BLOBS + "delete")).as("an action pattern grants no data action")
                .isFalse();
        assertThat(AzurePermissionsProbe.held(List.of(JSON.readTree(entry(List.of(), List.of(), List.of("*")))),
                MP + "write")).as("a data-action pattern grants no control-plane action").isFalse();
        assertThat(AzurePermissionsProbe.isDataAction(BLOBS + "read")).isTrue();
        assertThat(AzurePermissionsProbe.isDataAction("Microsoft.Storage/storageAccounts/blobServices/containers/read"))
                .isFalse();
        assertThat(AzurePermissionsProbe.matches("*", "anything/at/all")).isTrue();
        assertThat(AzurePermissionsProbe.matches("*/read", MP + "read")).isTrue();
        assertThat(AzurePermissionsProbe.matches("*/read", MP + "write")).isFalse();
        assertThat(AzurePermissionsProbe.matches("Microsoft.Storage/storageAccounts/managementPolicies/read",
                "Microsoft.Storage/storageAccounts/managementPoliciesXread")).as("a dot is literal").isFalse();
    }

    @Test
    void followsTheNextLinkOnTheSameEndpointOnlyAndUsesTheManagedIdentityToken() {
        config.setAccessTokenFile("");
        config.setManagedIdentityClientId("client-1");
        List<String> paths = new ArrayList<>();
        server.respond(r -> {
            paths.add(r.uri().getPath() + "?" + r.uri().getQuery());
            if (r.uri().getPath().startsWith("/metadata/identity/")) {
                return StubHttpServer.Response.of(200, "{\"access_token\":\"mi-token\",\"expires_on\":\""
                        + Instant.parse("2026-11-01T11:00:00Z").getEpochSecond() + "\"}");
            }
            if (r.uri().getQuery().contains("page=2")) {
                return StubHttpServer.Response.of(200, "{\"value\":[" + entry(List.of(MP + "delete"), List.of(),
                        List.of()) + "],\"nextLink\":\"https://elsewhere.example/steal\"}");
            }
            return StubHttpServer.Response.of(200, "{\"value\":[" + entry(List.of(BSR, MP + "read", MP + "write"),
                    List.of(), List.of()) + "],\"nextLink\":\"" + server.url() + ACCOUNT
                    + "/providers/Microsoft.Authorization/permissions?api-version=2022-04-01&page=2\"}");
        });
        config.getExecutors().get("delete").setContainerIds(List.of());
        PermissionCheckReport report = check();
        assertThat(report.missing()).isEmpty();
        assertThat(paths).hasSize(3);
        assertThat(paths.get(0)).startsWith("/metadata/identity/oauth2/token?api-version=2018-02-01&resource=")
                .contains("client_id=client-1");
        assertThat(paths).noneMatch(p -> p.contains("steal"));
        assertThat(report.targets().get(0).principal()).isEqualTo("the managed identity");
    }

    @Test
    void aFailedCallIsAnErrorAndAnIdThatIsNotOneIsRefused() {
        server.respond(r -> StubHttpServer.Response.of(403, "{\"error\":{\"code\":\"AuthorizationFailed\"}}"));
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.ERROR);
        assertThat(report.targets().get(0).error()).contains("Azure permissions list", "403");

        config.setAccessTokenFile("");
        server.respond(r -> StubHttpServer.Response.of(200, "{\"expires_on\":\"0\"}"));
        assertThat(check().targets().get(0).error()).contains("no access token");
        server.respond(r -> StubHttpServer.Response.of(400, "bad"));
        assertThat(check().targets().get(0).error()).contains("Azure IMDS token");
        server.respond(r -> StubHttpServer.Response.of(200, "<html>"));
        assertThat(check().targets().get(0).error()).contains("not JSON");

        config.getExecutors().get("delete").setStorageAccountIds(List.of(ACCOUNT.replace("acct1", "*")));
        assertThatThrownBy(() -> probe().targets()).hasMessageContaining("storage-account-ids");
        config.getExecutors().get("delete").setStorageAccountIds(List.of());
        config.getExecutors().get("delete").setContainerIds(List.of(ACCOUNT));
        assertThatThrownBy(() -> probe().targets()).hasMessageContaining("container-ids");
        config.getExecutors().get("delete").setContainerIds(List.of());
        assertThat(check().status()).isEqualTo(Status.NOT_CONFIGURED);
    }
}
