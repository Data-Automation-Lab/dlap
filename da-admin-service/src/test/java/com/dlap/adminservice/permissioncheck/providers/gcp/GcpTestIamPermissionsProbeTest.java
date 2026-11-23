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

package com.dlap.adminservice.permissioncheck.providers.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import com.dlap.adminservice.permissioncheck.PermissionCheckService;
import com.dlap.adminservice.permissioncheck.PermissionProbe;
import com.dlap.adminservice.permissioncheck.TraceSource;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The GCP probe against a stand-in for the Cloud Storage and BigQuery {@code testIamPermissions}
 * APIs and the metadata server, and the report the self-check builds from the answers against the
 * committed GCP Actor trace. A live project is the founder's check.
 */
class GcpTestIamPermissionsProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private StubHttpServer server;
    private GcpPermissionCheckProperties config;

    @BeforeEach
    void start() throws IOException {
        server = new StubHttpServer();
        config = new GcpPermissionCheckProperties();
        config.setStorageEndpoint(server.url());
        config.setBigqueryEndpoint(server.url() + "/");
        config.setMetadataEndpoint(server.url());
        Path token = dir.resolve("token");
        Files.writeString(token, "test-token-not-real\n");
        config.setAccessTokenFile(token.toString());
        GcpPermissionCheckProperties.Executor delete = new GcpPermissionCheckProperties.Executor();
        delete.setBuckets(List.of("dlap-data"));
        delete.setBigqueryTables(List.of("dlap-project.sales.orders"));
        config.getExecutors().put("delete", delete);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private GcpTestIamPermissionsProbe probe() {
        return new GcpTestIamPermissionsProbe(config, new HttpCalls(Duration.ofSeconds(5)),
                Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));
    }

    private PermissionCheckReport check() {
        TraceSource repo = new TraceSource(ActorRoleTraceabilityTest.repositoryRoot().resolve("deployment/iam"));
        return new PermissionCheckService(repo, List.<PermissionProbe>of(probe())).check("gcp").orElseThrow();
    }

    private static List<String> asked(StubHttpServer.Request r) {
        List<String> out = new ArrayList<>();
        String query = r.uri().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                out.add(URLDecoder.decode(pair.substring(pair.indexOf('=') + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** Answers with the asked permissions that the rule holds. */
    private void answer(List<String> held) {
        server.respond(r -> {
            List<String> asked;
            if (r.uri().getPath().endsWith(":testIamPermissions")) {
                try {
                    asked = new ArrayList<>();
                    JSON.readTree(r.body()).path("permissions").forEach(n -> asked.add(n.asText()));
                } catch (IOException e) {
                    return StubHttpServer.Response.of(400, "bad");
                }
            } else {
                asked = asked(r);
            }
            List<String> out = asked.stream().filter(held::contains).toList();
            return StubHttpServer.Response.of(200, out.isEmpty() ? "{\"kind\":\"storage#testIamPermissionsResponse\"}"
                    : "{\"permissions\":" + JSON.valueToTree(out) + "}");
        });
    }

    @Test
    void theSelfCheckReportsAMissingAndAnExtraPermissionFromTestIamPermissions() {
        answer(List.of("storage.buckets.get", "storage.buckets.delete", "bigquery.tables.get",
                "bigquery.tables.update"));

        PermissionCheckReport report = check();

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.method()).isEqualTo("testIamPermissions");
        assertThat(report.missing()).containsExactly("delete: storage.buckets.update on dlap-data");
        assertThat(report.extra()).containsExactly("delete: storage.buckets.delete on dlap-data");
        assertThat(report.targets()).extracting(PermissionCheckReport.TargetReport::scope)
                .containsExactly("bucket", "bucket_objects", "dataset");

        StubHttpServer.Request bucket = server.requests().get(0);
        assertThat(bucket.method()).isEqualTo("GET");
        assertThat(bucket.uri().getPath()).isEqualTo("/storage/v1/b/dlap-data/iam/testPermissions");
        assertThat(asked(bucket)).contains("storage.buckets.get", "storage.buckets.update", "storage.objects.list",
                "storage.buckets.delete", "storage.buckets.setIamPolicy");
        assertThat(bucket.headers().get("authorization")).isEqualTo("Bearer test-token-not-real");
        StubHttpServer.Request table = server.requests().get(2);
        assertThat(table.method()).isEqualTo("POST");
        assertThat(table.uri().getPath()).isEqualTo("/bigquery/v2/projects/dlap-project/datasets/sales/tables/orders"
                + ":testIamPermissions");
        assertThat(table.text()).contains("bigquery.tables.update", "bigquery.tables.getData");
    }

    @Test
    void theReportNamesTheCallingIdentityItCheckedAndSaysNothingIsImpersonated() {
        // Q-727: testIamPermissions answers for the caller, so the report says whose permissions it read.
        answer(List.of("storage.buckets.get", "storage.buckets.update", "bigquery.tables.get", "bigquery.tables.update"));
        PermissionCheckReport tokenFile = check();
        assertThat(tokenFile.identityBasis()).isEqualTo(PermissionCheckReport.IdentityBasis.CALLER);
        assertThat(tokenFile.identityChecked()).isEqualTo("the account of the access token file");
        assertThat(tokenFile.identityNote()).contains("calling identity", "nothing is impersonated", "Q-727");

        config.setAccessTokenFile("");
        server.respond(r -> StubHttpServer.Response.of(200, "{\"access_token\":\"t\",\"expires_in\":3600}"));
        PermissionCheckReport attached = check();
        assertThat(attached.identityChecked()).isEqualTo("the attached service account");
        assertThat(attached.identityBasis()).isEqualTo(PermissionCheckReport.IdentityBasis.CALLER);
    }

    @Test
    void theExactGrantIsAPass() {
        answer(List.of("storage.buckets.get", "storage.buckets.update", "bigquery.tables.get", "bigquery.tables.update"));
        assertThat(check().status()).isEqualTo(Status.PASS);
    }

    @Test
    void usesTheMetadataServerTokenOnceUntilItExpires() {
        config.setAccessTokenFile("");
        List<String> paths = new ArrayList<>();
        server.respond(r -> {
            paths.add(r.uri().getPath());
            if (r.uri().getPath().startsWith("/computeMetadata/")) {
                assertThat(r.headers().get("metadata-flavor")).isEqualTo("Google");
                return StubHttpServer.Response.of(200, "{\"access_token\":\"metadata-token\",\"expires_in\":3599}");
            }
            assertThat(r.headers().get("authorization")).isEqualTo("Bearer metadata-token");
            return StubHttpServer.Response.of(200, "{}");
        });
        PermissionCheckReport report = check();
        assertThat(report.missing()).hasSize(4);
        assertThat(paths.stream().filter(p -> p.startsWith("/computeMetadata/"))).hasSize(1);
        assertThat(report.targets().get(0).principal()).isEqualTo("the attached service account");
    }

    @Test
    void aFailedCallIsAnErrorAndABadTokenAnswerIsNamed() {
        server.respond(r -> StubHttpServer.Response.of(403, "{\"error\":{\"code\":403}}"));
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.ERROR);
        assertThat(report.targets().get(0).error()).contains("GCS testIamPermissions on bucket dlap-data", "403");

        config.setAccessTokenFile("");
        server.respond(r -> StubHttpServer.Response.of(200, "{}"));
        assertThat(check().targets().get(0).error()).contains("no access token");
        server.respond(r -> StubHttpServer.Response.of(404, "no"));
        assertThat(check().targets().get(0).error()).contains("GCE metadata token");
        server.respond(r -> StubHttpServer.Response.of(200, "not json"));
        assertThat(check().targets().get(0).error()).contains("not JSON");
    }

    @Test
    void refusesABucketOrTableThatIsNotOne() {
        config.getExecutors().get("delete").setBuckets(List.of("data/*"));
        assertThatThrownBy(() -> probe().targets()).hasMessageContaining("not a bucket name");
        config.getExecutors().get("delete").setBuckets(List.of());
        config.getExecutors().get("delete").setBigqueryTables(List.of("sales.orders"));
        assertThatThrownBy(() -> probe().targets()).hasMessageContaining("project.dataset.table");
        config.getExecutors().get("delete").setBigqueryTables(List.of());
        assertThat(check().status()).isEqualTo(Status.NOT_CONFIGURED);
    }
}
