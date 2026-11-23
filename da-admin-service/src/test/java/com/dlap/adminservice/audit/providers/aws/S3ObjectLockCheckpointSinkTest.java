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

package com.dlap.adminservice.audit.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.dlap.adminservice.audit.AuditProperties;
import com.dlap.adminservice.audit.AuditTestKeys;
import com.dlap.adminservice.audit.export.CheckpointExportException;
import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.adminservice.audit.export.StubHttpServer;
import com.dlap.adminservice.audit.export.StubHttpServer.Request;
import com.dlap.adminservice.audit.export.StubHttpServer.Response;
import com.dlap.common.audit.AuditChain;

/**
 * The S3 Object Lock sink against a stub of the S3 REST API: the request shape (Object Lock
 * headers, checksum, no overwrite), a SigV4 signature the stub recomputes, the preflight that
 * refuses a bucket without Object Lock, and instance-profile credentials. Real S3 enforcement of
 * the lock is a live check (spec section "Export").
 */
class S3ObjectLockCheckpointSinkTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
    private static final Map<String, String> ENV = Map.of("AWS_ACCESS_KEY_ID", "AKIDTESTONLY",
            "AWS_SECRET_ACCESS_KEY", "test-only-secret", "AWS_SESSION_TOKEN", "test-session");
    private static final Pattern SIGNED_HEADERS = Pattern.compile("SignedHeaders=([^,]+),");
    private static final String LOCK_ENABLED = "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>"
            + "<Rule><DefaultRetention><Mode>COMPLIANCE</Mode><Days>1</Days></DefaultRetention></Rule></ObjectLockConfiguration>";

    private final StubHttpServer stub = new StubHttpServer();
    private final KeyPair pair = AuditTestKeys.newPair();

    @AfterEach
    void stop() {
        stub.close();
    }

    private AuditProperties.S3 config() {
        AuditProperties.S3 c = new AuditProperties.S3();
        c.setBucket("audit-bucket");
        c.setRegion("eu-west-1");
        c.setEndpoint(stub.url());
        c.setPathStyle(true);
        c.setRetentionDays(30);
        c.setImdsEndpoint(stub.url());
        return c;
    }

    private S3ObjectLockCheckpointSink sink(Function<String, String> env) {
        HttpCalls http = new HttpCalls(Duration.ofSeconds(5));
        return new S3ObjectLockCheckpointSink(config(), "dlap-audit/", http,
                new AwsCredentialsSource(env, http, URI.create(stub.url()), CLOCK), CLOCK);
    }

    /** Recomputes the SigV4 signature from what the server received, with the given secret. */
    private static void assertSignedWith(Request r, AwsCredentials credentials) {
        String auth = r.headers().get("authorization");
        assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/20261021/eu-west-1/s3/aws4_request");
        Matcher m = SIGNED_HEADERS.matcher(auth);
        assertThat(m.find()).isTrue();
        TreeMap<String, String> signed = new TreeMap<>();
        for (String name : m.group(1).split(";")) {
            assertThat(r.headers()).as("signed header %s was sent", name).containsKey(name);
            signed.put(name, r.headers().get(name).trim());
        }
        assertThat(signed).containsKeys("host", "x-amz-content-sha256", "x-amz-date");
        String expected = AwsSigV4.authorization(r.method(), r.uri().getRawPath(),
                AwsSigV4.canonicalQuery(r.uri().getRawQuery()), signed, r.headers().get("x-amz-content-sha256"),
                credentials, "eu-west-1", "s3", r.headers().get("x-amz-date"));
        assertThat(auth).isEqualTo(expected);
        assertThat(r.headers().get("x-amz-content-sha256")).isEqualTo(HexFormat.of().formatHex(AuditChain.sha256(r.body())));
    }

    @Test
    void preflightAcceptsABucketWithObjectLockEnabled() {
        stub.respond(r -> Response.of(200, LOCK_ENABLED));
        sink(ENV::get).preflight();
        Request r = stub.requests().get(0);
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.uri().getPath()).isEqualTo("/audit-bucket/");
        assertThat(r.uri().getRawQuery()).isEqualTo("object-lock");
        assertThat(r.headers()).containsEntry("x-amz-security-token", "test-session");
        assertSignedWith(r, new AwsCredentials("AKIDTESTONLY", "test-only-secret", "test-session", null));
    }

    @Test
    void preflightRefusesABucketWithoutObjectLock() {
        stub.respond(r -> Response.of(404, "<Error><Code>ObjectLockConfigurationNotFoundError</Code></Error>"));
        assertThatThrownBy(() -> sink(ENV::get).preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("no Object Lock configuration").hasMessageContaining("HTTP 404");
        stub.respond(r -> Response.of(200, "<ObjectLockConfiguration></ObjectLockConfiguration>"));
        assertThatThrownBy(() -> sink(ENV::get).preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("does not have Object Lock enabled");
    }

    @Test
    void exportWritesTheSignatureThenThePayloadLockedAndWithoutOverwrite() {
        stub.respond(r -> Response.of(200, Map.of("x-amz-version-id", r.uri().getPath().endsWith(".sig") ? "v-sig" : "v-json"), ""));
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "retire.delete", 42);
        String location = sink(ENV::get).export(cp);
        assertThat(location).isEqualTo("s3://audit-bucket/dlap-audit/retire.delete/00000000000000000042.json#v-json");

        List<Request> puts = stub.requests();
        assertThat(puts).extracting(Request::method).containsExactly("PUT", "PUT");
        assertThat(puts.get(0).uri().getPath()).isEqualTo("/audit-bucket/dlap-audit/retire.delete/00000000000000000042.sig");
        assertThat(puts.get(1).uri().getPath()).isEqualTo("/audit-bucket/dlap-audit/retire.delete/00000000000000000042.json");
        assertThat(puts.get(0).body()).isEqualTo(cp.signatureBytes());
        assertThat(puts.get(1).body()).isEqualTo(cp.payloadBytes());
        for (Request r : puts) {
            assertThat(r.headers()).containsEntry("x-amz-object-lock-mode", "COMPLIANCE")
                    .containsEntry("x-amz-object-lock-retain-until-date", "2026-11-20T12:00:00Z")
                    .containsEntry("if-none-match", "*")
                    .containsEntry("x-amz-sdk-checksum-algorithm", "SHA256")
                    .containsEntry("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(AuditChain.sha256(r.body())));
            assertSignedWith(r, new AwsCredentials("AKIDTESTONLY", "test-only-secret", "test-session", null));
        }
    }

    @Test
    void anExistingObjectWithTheSameBytesIsAcceptedAndOtherBytesAreRefused() {
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "retire.delete", 7);
        stub.respond(r -> r.method().equals("PUT") ? Response.of(412, "<Error><Code>PreconditionFailed</Code></Error>")
                : new Response(200, Map.of(), r.uri().getPath().endsWith(".sig") ? cp.signatureBytes() : cp.payloadBytes()));
        assertThat(sink(ENV::get).export(cp)).isEqualTo("s3://audit-bucket/dlap-audit/retire.delete/00000000000000000007.json");

        stub.respond(r -> r.method().equals("PUT") ? Response.of(412, "") : Response.of(200, "other bytes"));
        assertThatThrownBy(() -> sink(ENV::get).export(cp)).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("already exists with other content");

        stub.respond(r -> Response.of(500, "<Error><Code>InternalError</Code></Error>"));
        assertThatThrownBy(() -> sink(ENV::get).export(cp)).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void instanceProfileCredentialsAreUsedAndCachedWhenTheEnvironmentHasNone() {
        stub.respond(r -> switch (r.uri().getPath()) {
            case "/latest/api/token" -> r.method().equals("PUT") && r.headers().containsKey("x-aws-ec2-metadata-token-ttl-seconds")
                    ? Response.of(200, "imds-token") : Response.of(400, "");
            case "/latest/meta-data/iam/security-credentials/" -> "imds-token".equals(r.headers().get("x-aws-ec2-metadata-token"))
                    ? Response.of(200, "dlap-audit-role\n") : Response.of(401, "");
            case "/latest/meta-data/iam/security-credentials/dlap-audit-role" -> Response.of(200,
                    "{\"Code\":\"Success\",\"AccessKeyId\":\"ASIAROLE\",\"SecretAccessKey\":\"role-secret\","
                            + "\"Token\":\"role-token\",\"Expiration\":\"2026-10-21T18:00:00Z\"}");
            default -> Response.of(200, LOCK_ENABLED);
        });
        S3ObjectLockCheckpointSink sink = sink(name -> null);
        sink.preflight();
        sink.preflight();
        List<Request> requests = stub.requests();
        assertThat(requests.stream().filter(r -> r.uri().getPath().startsWith("/latest/")).count())
                .as("IMDS is asked once; the credentials are cached").isEqualTo(3);
        Request s3 = requests.get(requests.size() - 1);
        assertThat(s3.headers()).containsEntry("x-amz-security-token", "role-token");
        assertSignedWith(s3, new AwsCredentials("ASIAROLE", "role-secret", "role-token", null));
    }

    @Test
    void noCredentialsAnywhereIsAnExportError() {
        stub.respond(r -> Response.of(404, ""));
        assertThatThrownBy(() -> sink(name -> null).preflight()).isInstanceOf(CheckpointExportException.class)
                .hasMessageContaining("no AWS credentials");
    }

    @Test
    void incompleteSettingsAreRefusedAtStartup() {
        HttpCalls http = new HttpCalls(Duration.ofSeconds(1));
        AwsCredentialsSource creds = new AwsCredentialsSource(ENV::get, http, URI.create(stub.url()), CLOCK);
        for (java.util.function.Consumer<AuditProperties.S3> breakIt : List.<java.util.function.Consumer<AuditProperties.S3>>of(
                c -> c.setBucket(""), c -> c.setBucket("Upper_Case"), c -> c.setRegion("moon"),
                c -> c.setRetentionDays(0), c -> c.setMode("STRICT"))) {
            AuditProperties.S3 c = config();
            breakIt.accept(c);
            assertThatThrownBy(() -> new S3ObjectLockCheckpointSink(c, "p", http, creds, CLOCK))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void virtualHostedAddressingPutsTheBucketInTheHostName() {
        AuditProperties.S3 c = config();
        c.setEndpoint("");
        c.setPathStyle(false);
        HttpCalls http = new HttpCalls(Duration.ofSeconds(1));
        S3ObjectLockCheckpointSink sink = new S3ObjectLockCheckpointSink(c, "", http,
                new AwsCredentialsSource(ENV::get, http, URI.create(stub.url()), CLOCK), CLOCK);
        assertThat(sink.bucketBase()).isEqualTo("https://audit-bucket.s3.eu-west-1.amazonaws.com/");
        assertThat(sink.objectUri("p/retire.delete/1 x.json").toString())
                .isEqualTo("https://audit-bucket.s3.eu-west-1.amazonaws.com/p/retire.delete/1%20x.json");
        assertThat(Arrays.asList(sink.name())).containsExactly("s3");
    }
}
