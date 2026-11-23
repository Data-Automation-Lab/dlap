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

package com.dlap.autoarchival.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.autoarchival.approval.ApprovalRefusedException.Reason;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * The re-read of docs/specs/approvals.md section 9, rule 3, against a loopback stand-in for the
 * policy engine that answers with the exact fields of {@code ApprovalApi.ApprovalView}.
 */
class PolicyEngineApprovalClientTest {

    private static final String ID = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";

    private HttpServer server;
    private PolicyEngineApprovalClient client;
    private volatile int status;
    private volatile String body;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = new PolicyEngineApprovalClient("http://127.0.0.1:" + server.getAddress().getPort(),
                () -> "tok-123", Duration.ofSeconds(3));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String view(String st, boolean executable, String cls, String asset) {
        return "{\"id\":\"" + ID + "\",\"status\":\"" + st + "\",\"executable\":" + executable + ",\"actionClass\":\""
                + cls + "\",\"assetId\":\"" + asset + "\",\"requiredApprovals\":1,\"decisions\":["
                + "{\"approverSub\":\"alice-sub\",\"decision\":\"APPROVE\"}]}";
    }

    private Reason refusal(Runnable r) {
        try {
            r.run();
        } catch (ApprovalRefusedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void anExecutableApprovalForTheSameAssetIsAcceptedAndCarriesTheServiceToken() {
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", ASSET);

        ArchiveApproval a = client.fetchExecutable(ID, ASSET);

        assertThat(a.approvalId()).isEqualTo(ID);
        assertThat(a.status()).isEqualTo("APPROVED");
        assertThat(a.approvers()).containsExactly("alice-sub");
        assertThat(a.breakGlass()).isFalse();
        assertThat(requests).containsExactly("GET /api/v1/policyengine/approvals/" + ID + " ");
        assertThat(authorization).containsExactly("Bearer tok-123");
    }

    @Test
    void theScopeTheApprovalWasGivenForIsCarriedBack() {
        status = 200;
        body = "{\"id\":\"" + ID + "\",\"status\":\"APPROVED\",\"executable\":true,\"actionClass\":\"ARCHIVE\","
                + "\"assetId\":\"" + ASSET + "\",\"assetUri\":\"gs://pilot/\",\"provider\":\"GCP\","
                + "\"actionParameters\":{\"tier\":\"COLD\",\"prefix\":\"finance/\",\"afterDays\":45,"
                + "\"dry\":false,\"nested\":{\"a\":1},\"gone\":null},\"requiredApprovals\":1,\"decisions\":[]}";

        ArchiveApproval a = client.fetchExecutable(ID, ASSET);

        assertThat(a.assetUri()).isEqualTo("gs://pilot/");
        assertThat(a.provider()).isEqualTo("GCP");
        assertThat(a.actionParameters()).containsEntry("tier", "COLD").containsEntry("prefix", "finance/")
                .containsEntry("afterDays", 45L).containsEntry("dry", false).containsEntry("nested", "{\"a\":1}")
                .containsKey("gone");
    }

    @Test
    void anApprovalWithoutALocationCarriesNone() {
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", ASSET);

        ArchiveApproval a = client.fetchExecutable(ID, ASSET);

        assertThat(a.assetUri()).isNull();
        assertThat(a.provider()).isNull();
        assertThat(a.actionParameters()).isEmpty();
    }

    @Test
    void anActionWithNoAssetIdIsNeverChecked() {
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", ASSET);

        assertThat(refusal(() -> client.fetchExecutable(ID, null))).isEqualTo(Reason.ASSET_MISMATCH);
    }

    @Test
    void aBreakGlassReleaseIsExecutableAndFlagged() {
        status = 200;
        body = view("APPROVED_BREAK_GLASS", true, "ARCHIVE", ASSET);

        assertThat(client.fetchExecutable(ID, ASSET).breakGlass()).isTrue();
    }

    @Test
    void theAssetCaseDoesNotMatter() {
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", ASSET.toUpperCase());

        assertThat(client.fetchExecutable(ID, ASSET)).isNotNull();
    }

    @Test
    void aPendingApprovalWaitsAndIsNotRefused() {
        status = 200;
        body = view("PENDING", false, "ARCHIVE", ASSET);

        assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.NOT_DECIDED);
    }

    @Test
    void rejectedAndExpiredApprovalsAreRefused() {
        status = 200;
        for (String st : List.of("REJECTED", "EXPIRED")) {
            body = view(st, false, "ARCHIVE", ASSET);
            assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.NOT_EXECUTABLE);
        }
    }

    @Test
    void anApprovedStatusTheServerDoesNotCallExecutableIsRefused() {
        status = 200;
        body = view("APPROVED", false, "ARCHIVE", ASSET);

        assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.NOT_EXECUTABLE);
    }

    @Test
    void anApprovalForAnotherClassIsRefused() {
        status = 200;
        for (String cls : List.of("HARD_DELETE", "SOFT_DELETE", "OTHER")) {
            body = view("APPROVED", true, cls, ASSET);
            assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.WRONG_CLASS);
        }
    }

    @Test
    void anApprovalForAnotherAssetIsRefused() {
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", "99999999-2222-3333-4444-555555555555");

        assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.ASSET_MISMATCH);
    }

    @Test
    void anAnswerForAnotherApprovalIdIsRefused() {
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", ASSET).replace(ID, "someone-else");

        assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.NOT_EXECUTABLE);
    }

    @Test
    void anUnknownApprovalIsRefusedForGood() {
        status = 404;
        body = "{}";

        assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.NOT_EXECUTABLE);
    }

    @Test
    void aServerErrorOrAForbiddenAnswerMeansTheApprovalCouldNotBeCheckedSoNothingRuns() {
        for (int code : new int[] {401, 403, 500, 503}) {
            status = code;
            body = "{}";
            assertThatThrownBy(() -> client.fetchExecutable(ID, ASSET)).isInstanceOfSatisfying(
                    ApprovalRefusedException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.VERIFY_UNAVAILABLE);
                        assertThat(e.isTransient()).isTrue();
                    });
        }
    }

    @Test
    void garbageFromTheServerIsNotTakenForAnApproval() {
        status = 200;
        body = "<html>proxy error</html>";

        assertThat(refusal(() -> client.fetchExecutable(ID, ASSET))).isEqualTo(Reason.VERIFY_UNAVAILABLE);
    }

    @Test
    void anUnreachablePolicyEngineIsTransient() {
        PolicyEngineApprovalClient down = new PolicyEngineApprovalClient("http://127.0.0.1:1", () -> null,
                Duration.ofSeconds(2));

        assertThatThrownBy(() -> down.fetchExecutable(ID, ASSET)).isInstanceOfSatisfying(ApprovalRefusedException.class,
                e -> assertThat(e.isTransient()).isTrue());
    }

    @Test
    void noTokenMeansNoAuthorizationHeader() {
        PolicyEngineApprovalClient anonymous = new PolicyEngineApprovalClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), () -> null, Duration.ofSeconds(3));
        status = 200;
        body = view("APPROVED", true, "ARCHIVE", ASSET);

        anonymous.fetchExecutable(ID, ASSET);

        assertThat(authorization).containsExactly("null");
    }

    @Test
    void asksForAnApprovalNamingTheCallerSoTheyCannotDecideIt() throws Exception {
        status = 201;
        body = view("PENDING", false, "ARCHIVE", ASSET);

        String id = client.ask(ASSET, "s3://pilot/finance/", "AWS", "archive", Map.of("tier", "COLD"),
                "Archive requested through task t-1", "caller-sub", "archive:t-1:" + ASSET);

        assertThat(id).isEqualTo(ID);
        String request = requests.get(0);
        assertThat(request).startsWith("POST /api/v1/policyengine/approvals ");
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = new ObjectMapper().readValue(request.substring(request.indexOf('{')), Map.class);
        assertThat(sent).containsEntry("actionClass", "ARCHIVE").containsEntry("actionType", "archive")
                .containsEntry("assetId", ASSET).containsEntry("onBehalfOf", "caller-sub")
                .containsEntry("idempotencyKey", "archive:t-1:" + ASSET).containsEntry("provider", "AWS");
        assertThat(((Map<?, ?>) sent.get("actionParameters")).get("tier")).isEqualTo("COLD");
    }

    @Test
    void anAskThatIsRefusedOrAnsweredWithoutAnIdFailsClosed() {
        status = 403;
        body = "{}";
        assertThatThrownBy(() -> client.ask(ASSET, "s3://b/", "AWS", "archive", Map.of(), "r", "caller", "k"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 403");
        status = 201;
        body = "{}";
        assertThatThrownBy(() -> client.ask(ASSET, "s3://b/", "AWS", "archive", Map.of(), "r", "caller", "k"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("without an approval id");
        PolicyEngineApprovalClient down = new PolicyEngineApprovalClient("http://127.0.0.1:1", () -> null,
                Duration.ofSeconds(2));
        assertThatThrownBy(() -> down.ask(ASSET, "s3://b/", "AWS", "archive", Map.of(), "r", "caller", "k"))
                .isInstanceOf(IllegalStateException.class);
    }
}
