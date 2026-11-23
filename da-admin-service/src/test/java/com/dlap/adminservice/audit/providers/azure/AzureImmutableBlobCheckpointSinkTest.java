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

package com.dlap.adminservice.audit.providers.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.adminservice.audit.AuditProperties;
import com.dlap.adminservice.audit.AuditTestKeys;
import com.dlap.adminservice.audit.export.CheckpointExportException;
import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.adminservice.audit.export.StubHttpServer;
import com.dlap.adminservice.audit.export.StubHttpServer.Request;
import com.dlap.adminservice.audit.export.StubHttpServer.Response;

/**
 * The Azure immutable-blob sink against a stub of the Blob REST API and IMDS. Azurite does not
 * enforce immutability policies, so the stub stands in; real enforcement is a live check.
 */
class AzureImmutableBlobCheckpointSinkTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final StubHttpServer stub = new StubHttpServer();
    private final KeyPair pair = AuditTestKeys.newPair();

    @AfterEach
    void stop() {
        stub.close();
    }

    private AuditProperties.Azure config(String tokenFile) {
        AuditProperties.Azure c = new AuditProperties.Azure();
        c.setAccountUrl(stub.url() + "/");
        c.setContainer("dlap-audit");
        c.setRetentionDays(365);
        c.setAccessTokenFile(tokenFile);
        c.setImdsEndpoint(stub.url());
        c.setManagedIdentityClientId("11111111-2222-3333-4444-555555555555");
        return c;
    }

    private AzureImmutableBlobCheckpointSink sink(String tokenFile) {
        return new AzureImmutableBlobCheckpointSink(config(tokenFile), "checkpoints", new HttpCalls(Duration.ofSeconds(5)), CLOCK);
    }

    @Test
    void preflightNeedsVersionLevelImmutabilityOnTheContainer() throws Exception {
        Path token = Files.writeString(dir.resolve("t"), "file-token", StandardCharsets.US_ASCII);
        stub.respond(r -> Response.of(200, Map.of("x-ms-immutable-storage-with-versioning-enabled", "true"), ""));
        sink(token.toString()).preflight();
        Request r = stub.requests().get(0);
        assertThat(r.uri().getPath()).isEqualTo("/dlap-audit");
        assertThat(r.uri().getQuery()).isEqualTo("restype=container");
        assertThat(r.headers()).containsEntry("authorization", "Bearer file-token")
                .containsEntry("x-ms-version", AzureImmutableBlobCheckpointSink.SERVICE_VERSION)
                .containsEntry("x-ms-date", "Wed, 21 Oct 2026 12:00:00 GMT");

        stub.respond(r2 -> Response.of(200, ""));
        assertThatThrownBy(() -> sink(token.toString()).preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("does not have version-level immutability");
        stub.respond(r2 -> Response.of(404, "<Error><Code>ContainerNotFound</Code></Error>"));
        assertThatThrownBy(() -> sink(token.toString()).preflight()).hasMessageContaining("HTTP 404");
    }

    @Test
    void exportPutsLockedBlobsWithoutOverwriteUsingTheManagedIdentityToken() {
        stub.respond(r -> {
            if (r.uri().getPath().startsWith("/metadata/identity")) {
                return "true".equals(r.headers().get("metadata"))
                        ? Response.of(200, "{\"access_token\":\"mi-token\",\"expires_on\":\"1792000000\",\"token_type\":\"Bearer\"}")
                        : Response.of(400, "");
            }
            return Response.of(201, Map.of("x-ms-version-id", "2026-10-21T12:00:00.0000000Z"), "");
        });
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "report.export", 12);
        String location = sink("").export(cp);
        assertThat(location).isEqualTo(stub.url() + "/dlap-audit/checkpoints/report.export/00000000000000000012.json"
                + "#2026-10-21T12:00:00.0000000Z");
        Request imds = stub.requests().get(0);
        assertThat(imds.uri().getQuery()).contains("resource=https://storage.azure.com/")
                .contains("client_id=11111111-2222-3333-4444-555555555555");
        List<Request> puts = stub.requests().stream().filter(r -> r.method().equals("PUT")).toList();
        assertThat(puts).extracting(r -> r.uri().getPath()).containsExactly(
                "/dlap-audit/checkpoints/report.export/00000000000000000012.sig",
                "/dlap-audit/checkpoints/report.export/00000000000000000012.json");
        for (Request r : puts) {
            assertThat(r.headers()).containsEntry("authorization", "Bearer mi-token")
                    .containsEntry("x-ms-blob-type", "BlockBlob")
                    .containsEntry("if-none-match", "*")
                    .containsEntry("x-ms-immutability-policy-mode", "Locked")
                    .containsEntry("x-ms-immutability-policy-until-date", "Thu, 21 Oct 2027 12:00:00 GMT");
        }
        assertThat(puts.get(1).body()).isEqualTo(cp.payloadBytes());
    }

    @Test
    void anExistingBlobWithTheSameBytesIsAcceptedAndOtherBytesAreRefused() throws Exception {
        Path token = Files.writeString(dir.resolve("t"), "file-token", StandardCharsets.US_ASCII);
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "report.export", 13);
        stub.respond(r -> r.method().equals("PUT") ? Response.of(409, "<Error><Code>BlobAlreadyExists</Code></Error>")
                : new Response(200, Map.of(), r.uri().getPath().endsWith(".sig") ? cp.signatureBytes() : cp.payloadBytes()));
        assertThat(sink(token.toString()).export(cp)).endsWith("/report.export/00000000000000000013.json");
        stub.respond(r -> r.method().equals("PUT") ? Response.of(412, "") : Response.of(200, "nope"));
        assertThatThrownBy(() -> sink(token.toString()).export(cp)).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("already exists with other content");
        stub.respond(r -> Response.of(403, "<Error><Code>AuthorizationPermissionMismatch</Code></Error>"));
        assertThatThrownBy(() -> sink(token.toString()).export(cp)).hasMessageContaining("HTTP 403");
    }

    @Test
    void badSettingsAreRefusedAtStartup() {
        HttpCalls http = new HttpCalls(Duration.ofSeconds(1));
        AuditProperties.Azure noUrl = config("");
        noUrl.setAccountUrl("not a url");
        assertThatThrownBy(() -> new AzureImmutableBlobCheckpointSink(noUrl, "p", http, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        AuditProperties.Azure badContainer = config("");
        badContainer.setContainer("Bad--Name");
        assertThatThrownBy(() -> new AzureImmutableBlobCheckpointSink(badContainer, "p", http, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        AuditProperties.Azure noRetention = config("");
        noRetention.setRetentionDays(0);
        assertThatThrownBy(() -> new AzureImmutableBlobCheckpointSink(noRetention, "p", http, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sink("").name()).isEqualTo("azure");
    }
}
