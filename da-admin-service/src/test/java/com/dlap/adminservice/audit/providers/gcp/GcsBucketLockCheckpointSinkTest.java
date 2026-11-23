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

package com.dlap.adminservice.audit.providers.gcp;

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
 * The GCS bucket-lock sink against a stub of the JSON API and the metadata server. fake-gcs-server
 * has no bucket lock, so the stub stands in; real enforcement is a live check (spec "Export").
 */
class GcsBucketLockCheckpointSinkTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
    private static final String LOCKED_30_DAYS = "{\"retentionPolicy\":{\"retentionPeriod\":\"2592000\","
            + "\"effectiveTime\":\"2026-01-01T00:00:00Z\",\"isLocked\":true}}";

    @TempDir
    Path dir;

    private final StubHttpServer stub = new StubHttpServer();
    private final KeyPair pair = AuditTestKeys.newPair();

    @AfterEach
    void stop() {
        stub.close();
    }

    private AuditProperties.Gcs config(String tokenFile) {
        AuditProperties.Gcs c = new AuditProperties.Gcs();
        c.setBucket("dlap-audit-checkpoints");
        c.setEndpoint(stub.url());
        c.setMetadataEndpoint(stub.url());
        c.setAccessTokenFile(tokenFile);
        c.setMinRetentionDays(30);
        return c;
    }

    private GcsBucketLockCheckpointSink sink(String tokenFile) {
        return new GcsBucketLockCheckpointSink(config(tokenFile), "dlap-audit", new HttpCalls(Duration.ofSeconds(5)), CLOCK);
    }

    @Test
    void preflightAcceptsALockedRetentionPolicyOfAtLeastTheMinimum() throws Exception {
        Path token = Files.writeString(dir.resolve("token"), "file-token\n", StandardCharsets.US_ASCII);
        stub.respond(r -> Response.of(200, LOCKED_30_DAYS));
        sink(token.toString()).preflight();
        Request r = stub.requests().get(0);
        assertThat(r.uri().getPath()).isEqualTo("/storage/v1/b/dlap-audit-checkpoints");
        assertThat(r.uri().getQuery()).isEqualTo("fields=retentionPolicy");
        assertThat(r.headers()).containsEntry("authorization", "Bearer file-token");
    }

    @Test
    void preflightRefusesAnUnlockedOrShortPolicy() {
        stub.respond(r -> r.uri().getPath().startsWith("/computeMetadata")
                ? Response.of(200, "{\"access_token\":\"meta-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}")
                : Response.of(200, "{\"retentionPolicy\":{\"retentionPeriod\":\"2592000\",\"isLocked\":false}}"));
        assertThatThrownBy(() -> sink("").preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("no locked retention policy");
        stub.respond(r -> r.uri().getPath().startsWith("/computeMetadata")
                ? Response.of(200, "{\"access_token\":\"meta-token\",\"expires_in\":3599}")
                : Response.of(200, "{\"retentionPolicy\":{\"retentionPeriod\":\"86400\",\"isLocked\":true}}"));
        assertThatThrownBy(() -> sink("").preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("less than 30 days");
        stub.respond(r -> r.uri().getPath().startsWith("/computeMetadata")
                ? Response.of(200, "{\"access_token\":\"meta-token\",\"expires_in\":3599}") : Response.of(200, "{}"));
        assertThatThrownBy(() -> sink("").preflight()).isInstanceOf(CheckpointExportException.class);
        stub.respond(r -> Response.of(403, "{\"error\":{\"code\":403}}"));
        assertThatThrownBy(() -> sink("").preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void exportUploadsSignatureThenPayloadOnlyIfAbsentWithTheMetadataServerToken() {
        stub.respond(r -> {
            if (r.uri().getPath().startsWith("/computeMetadata")) {
                return "Google".equals(r.headers().get("metadata-flavor"))
                        ? Response.of(200, "{\"access_token\":\"meta-token\",\"expires_in\":3599}") : Response.of(403, "");
            }
            return Response.of(200, "{\"name\":\"x\",\"generation\":\"1729500000000000\"}");
        });
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "admin.access", 3);
        GcsBucketLockCheckpointSink sink = sink("");
        assertThat(sink.export(cp))
                .isEqualTo("gs://dlap-audit-checkpoints/dlap-audit/admin.access/00000000000000000003.json#1729500000000000");
        List<Request> uploads = stub.requests().stream().filter(r -> r.uri().getPath().startsWith("/upload")).toList();
        assertThat(uploads).hasSize(2);
        assertThat(uploads.get(0).uri().getQuery())
                .isEqualTo("uploadType=media&ifGenerationMatch=0&name=dlap-audit/admin.access/00000000000000000003.sig");
        assertThat(uploads.get(1).uri().getQuery()).endsWith("name=dlap-audit/admin.access/00000000000000000003.json");
        assertThat(uploads.get(1).body()).isEqualTo(cp.payloadBytes());
        assertThat(uploads.get(1).headers()).containsEntry("content-type", "application/json")
                .containsEntry("authorization", "Bearer meta-token");
        assertThat(stub.requests().stream().filter(r -> r.uri().getPath().startsWith("/computeMetadata")).count())
                .as("the metadata token is cached").isEqualTo(1);
    }

    @Test
    void anExistingObjectWithTheSameBytesIsAcceptedAndOtherBytesAreRefused() throws Exception {
        Path token = Files.writeString(dir.resolve("token"), "file-token", StandardCharsets.US_ASCII);
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "admin.access", 4);
        stub.respond(r -> r.method().equals("POST") ? Response.of(412, "{\"error\":{\"code\":412}}")
                : new Response(200, Map.of(), r.uri().getPath().endsWith(".sig") ? cp.signatureBytes() : cp.payloadBytes()));
        assertThat(sink(token.toString()).export(cp))
                .isEqualTo("gs://dlap-audit-checkpoints/dlap-audit/admin.access/00000000000000000004.json");
        stub.respond(r -> r.method().equals("POST") ? Response.of(412, "") : Response.of(200, "different"));
        assertThatThrownBy(() -> sink(token.toString()).export(cp)).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("already exists with other content");
        stub.respond(r -> Response.of(503, "busy"));
        assertThatThrownBy(() -> sink(token.toString()).export(cp)).hasMessageContaining("HTTP 503");
    }

    @Test
    void anEmptyOrMissingTokenFileAndBadSettingsAreRefused() throws Exception {
        Path empty = Files.writeString(dir.resolve("empty"), "  ", StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> sink(empty.toString()).preflight()).hasMessageContaining("is empty");
        assertThatThrownBy(() -> sink(dir.resolve("none").toString()).preflight()).hasMessageContaining("cannot be read");
        AuditProperties.Gcs bad = config("");
        bad.setBucket("");
        assertThatThrownBy(() -> new GcsBucketLockCheckpointSink(bad, "p", new HttpCalls(Duration.ofSeconds(1)), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        AuditProperties.Gcs zero = config("");
        zero.setMinRetentionDays(0);
        assertThatThrownBy(() -> new GcsBucketLockCheckpointSink(zero, "p", new HttpCalls(Duration.ofSeconds(1)), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sink("").name()).isEqualTo("gcs");
    }
}
