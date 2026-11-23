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

package com.dlap.autodelete.retire;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.autodelete.client.PolicyEngineApprovalFeignClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

/**
 * Q-676 (RET-06): a restore withdraws the pending HARD_DELETE approval through the policy engine's
 * service-account-only operation {@code POST /api/v1/policyengine/approvals/{id}/withdraw} (APPR-03), with the
 * executor's own token and the restorer named in {@code onBehalfOf}. The real Feign interface runs over HTTP
 * against a local server, with an interceptor standing in for the shared service-token interceptor of da-protos.
 */
class ApprovalWithdrawerTest {

    private static final UUID APPROVAL = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-0000000000aa");
    private static final UUID RECORD = UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1");
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private ApprovalWithdrawer withdrawer;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"id\":\"" + APPROVAL + "\",\"status\":\"WITHDRAWN\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        String basePath = PolicyEngineApprovalFeignClient.class.getAnnotation(FeignClient.class).path();
        PolicyEngineApprovalFeignClient feign = Feign.builder()
                .contract(new SpringMvcContract())
                .encoder((object, type, template) -> {
                    try {
                        template.body(JSON.writeValueAsBytes(object), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .decoder((response, type) -> JSON.readValue(response.body().asInputStream(), JSON.constructType(type)))
                .requestInterceptor(t -> t.header("Authorization", "Bearer the-service-account-token"))
                .target(PolicyEngineApprovalFeignClient.class,
                        "http://127.0.0.1:" + server.getAddress().getPort() + basePath);
        withdrawer = new ApprovalWithdrawer(feign);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Q-676: the service account withdraws, on behalf of the restorer, with a reason of at least 10 characters. */
    @Test
    void theServiceAccountWithdrawsThePendingRequestOnBehalfOfTheRestorer() throws IOException {
        ApprovalWithdrawer.Outcome outcome = withdrawer.withdraw(APPROVAL, "sub-of-restorer", RECORD, "kept by mistake");

        assertThat(outcome.result()).isEqualTo(ApprovalWithdrawer.Result.WITHDRAWN);
        assertThat(outcome.text()).isEqualTo("WITHDRAWN");
        assertThat(path.get()).isEqualTo("POST /api/v1/policyengine/approvals/" + APPROVAL + "/withdraw");
        assertThat(authorization.get()).as("the executor's own token, never the restorer's")
                .isEqualTo("Bearer the-service-account-token");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("onBehalfOf").asText()).isEqualTo("sub-of-restorer");
        assertThat(sent.path("reason").asText()).hasSizeGreaterThanOrEqualTo(10).contains(RECORD.toString())
                .contains("kept by mistake");
        assertThat(sent.has("decision")).as("no REJECT decision any more").isFalse();
    }

    /** Q-676: a 409 names the current status; the label is APPROVAL_ALREADY_DECIDED, not ALREADY_CLOSED. */
    @Test
    void aRequestThatIsNotPendingAnyMoreIsApprovalAlreadyDecidedWithItsStatus() {
        status = 409;
        answer = "{\"status\":\"APPROVED\"}";

        ApprovalWithdrawer.Outcome outcome = withdrawer.withdraw(APPROVAL, "sub", RECORD, null);

        assertThat(outcome.result()).isEqualTo(ApprovalWithdrawer.Result.APPROVAL_ALREADY_DECIDED);
        assertThat(outcome.text()).isEqualTo("APPROVAL_ALREADY_DECIDED:APPROVED");
    }

    /**
     * Q-676, Q-700: a policy engine without the operation (405, or a 404 whose body does not say not-found) is
     * "endpoint not available", never a failure.
     */
    @Test
    void a404Or405IsEndpointNotAvailable() {
        String[][] cases = {
            {"404", ""},
            {"404", "<html><body>Whitelabel Error Page</body></html>"},
            {"404", "{\"status\":404,\"error\":\"Not Found\",\"path\":\"/api/v1/policyengine/approvals/x/withdraw\"}"},
            {"404", "{\"status\":404,\"reason\":\"no-handler\"}"},
            {"405", ""},
            {"405", "{\"status\":405,\"reason\":\"not-found\"}"},
        };
        for (String[] c : cases) {
            status = Integer.parseInt(c[0]);
            answer = c[1];

            ApprovalWithdrawer.Outcome outcome = withdrawer.withdraw(APPROVAL, "sub", RECORD, null);

            assertThat(outcome.result()).as("HTTP %s %s", c[0], c[1])
                    .isEqualTo(ApprovalWithdrawer.Result.ENDPOINT_NOT_AVAILABLE);
            assertThat(outcome.text()).isEqualTo("ENDPOINT_NOT_AVAILABLE:http-" + c[0]);
        }
    }

    /**
     * Q-700 (RET-08, decision D38): the withdraw operation's own 404 for a request it does not know (body reason
     * not-found, approvals.md section 8) is REQUEST_NOT_FOUND, not ENDPOINT_NOT_AVAILABLE.
     */
    @Test
    void a404WhoseReasonIsNotFoundIsRequestNotFound() {
        status = 404;
        answer = "{\"timestamp\":\"2026-11-01T10:00:00Z\",\"status\":404,\"error\":\"Not Found\","
                + "\"message\":\"approval request not found\",\"reason\":\"not-found\","
                + "\"path\":\"/api/v1/policyengine/approvals/x/withdraw\"}";

        ApprovalWithdrawer.Outcome outcome = withdrawer.withdraw(APPROVAL, "sub", RECORD, null);

        assertThat(outcome.result()).isEqualTo(ApprovalWithdrawer.Result.REQUEST_NOT_FOUND);
        assertThat(outcome.text()).isEqualTo("REQUEST_NOT_FOUND");
    }

    @Test
    void aRefusalByThePolicyEngineIsReportedWithItsReason() {
        status = 403;
        answer = "{\"status\":403,\"reason\":\"on-behalf-refused\"}";

        ApprovalWithdrawer.Outcome outcome = withdrawer.withdraw(APPROVAL, "sub", RECORD, null);

        assertThat(outcome.result()).isEqualTo(ApprovalWithdrawer.Result.REFUSED);
        assertThat(outcome.text()).isEqualTo("REFUSED:on-behalf-refused");
    }

    @Test
    void a200ThatIsNotWithdrawnIsReportedAsDecided() {
        answer = "{\"id\":\"" + APPROVAL + "\",\"status\":\"REJECTED\"}";

        assertThat(withdrawer.withdraw(APPROVAL, "sub", RECORD, null).text())
                .isEqualTo("APPROVAL_ALREADY_DECIDED:REJECTED");
    }

    @Test
    void aServerErrorOrAnUnreachablePolicyEngineIsReportedNotThrown() {
        status = 503;
        answer = "";
        assertThat(withdrawer.withdraw(APPROVAL, "sub", RECORD, null).result())
                .isEqualTo(ApprovalWithdrawer.Result.UNREACHABLE);

        server.stop(0);
        assertThat(withdrawer.withdraw(APPROVAL, "sub", RECORD, null).result())
                .isEqualTo(ApprovalWithdrawer.Result.UNREACHABLE);
    }

    @Test
    void withoutAnApprovalNothingIsAttempted() {
        assertThat(withdrawer.withdraw(null, "sub", RECORD, null).result())
                .isEqualTo(ApprovalWithdrawer.Result.NOT_ATTEMPTED);
        assertThat(path.get()).as("no call was made").isNull();
    }

    @Test
    void aLongNoteIsCutBelowTheReasonLimit() {
        withdrawer.withdraw(APPROVAL, "sub", RECORD, "n".repeat(5000));

        assertThat(body.get().length()).isLessThan(800);
    }
}
