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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * OBS-01 acceptance for da-mcp-pep: the whole service, with its own {@code application.properties}
 * and the real security chain, serves the actuator on a separate management port
 * ({@code MANAGEMENT_SERVER_PORT}), opens only health and the Prometheus scrape there, and does
 * not serve the actuator on the application port.
 *
 * <p>Scope: a full-context test, not a slice test, and it needs no Docker. It boots the same context as
 * {@code ProdProfileContextTest} (prod profile, the documented environment) with
 * {@code management.server.port=0}, then calls both ports over HTTP with no token. The reference PEP
 * needs a token on its own endpoints (SEC-07: a JWT resource server), so the closed
 * actuator paths answer 401 to a caller without one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0"
})
@ActiveProfiles("prod")
@AutoConfigureMetrics
class ManagementPortContextTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** The documented environment of da-mcp-pep under prod. */
    @DynamicPropertySource
    static void documentedEnvironment(DynamicPropertyRegistry registry) {
        registry.add("DLAP_PDP_BASE_URL", () -> "http://localhost:1");
        registry.add("DLAP_PDP_TOKEN", () -> UUID.randomUUID().toString());
        // SEC-07: the PEP refuses to start without a key set, an issuer and an audience.
        JwtTestSupport.register(registry);
    }

    @Autowired
    private Environment environment;

    /** The aggregate health, liveness and readiness answer without a token on the management port. */
    @Test
    void healthIsPublicOnTheManagementPort() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(managementPort(), path);

            assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"")
                    .doesNotContain("\"components\"").doesNotContain("\"details\"");
        }
    }

    /** The scrape is open on the management port and carries the series the platform alerts read. */
    @Test
    void prometheusIsOpenOnTheManagementPort() throws Exception {
        get(applicationPort(), "/mcp/v1/obs01-anything");

        HttpResponse<String> response = get(managementPort(), "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes{")
                .contains("process_start_time_seconds")
                .contains("http_server_requests_seconds_count{");
    }

    /**
     * A decision taken while the PDP is unreachable is counted under the series the McpPepFailClosed alert
     * reads. The PDP address of this test points at a closed port, so the shipped enforce mode denies.
     */
    @Test
    void aFailClosedDecisionIsCountedUnderTheSeriesTheAlertReads() throws Exception {
        String body = "{\"principalId\":\"agent-1\",\"roles\":[],\"tier\":\"T1\",\"tool\":\"query\","
                + "\"resourceUri\":\"bq://p/d/t\",\"action\":\"read\",\"context\":{}}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + applicationPort() + "/mcp/v1/authorize"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + JwtTestSupport.validToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> verdict = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> scrape = get(managementPort(), "/actuator/prometheus");

        assertThat(verdict.statusCode()).as(verdict.body()).isEqualTo(403);
        assertThat(scrape.body())
                .contains("dlap_mcppep_decisions_total{allow=\"false\",failClosed=\"true\"")
                .contains("dlap_mcppep_enforce_duration_seconds_count");
    }

    /**
     * The application port does not serve the actuator at all, so the scrape is not there. The exact
     * error status is not the point: the module's exception handler answers an unknown path with 500
     * where Spring answers 404.
     */
    @Test
    void theApplicationPortDoesNotServeThePrometheusScrape() throws Exception {
        HttpResponse<String> response = get(applicationPort(), "/actuator/prometheus");

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
    }

    /** Every other actuator endpoint is closed on the management port. */
    @Test
    void everythingElseIsClosedOnTheManagementPort() throws Exception {
        assertThat(get(managementPort(), "/actuator/metrics").statusCode()).as("metrics").isEqualTo(401);
        assertThat(get(managementPort(), "/actuator/info").statusCode()).as("info").isEqualTo(401);
    }

    private int applicationPort() {
        return Integer.parseInt(environment.getRequiredProperty("local.server.port"));
    }

    private int managementPort() {
        return Integer.parseInt(environment.getRequiredProperty("local.management.port"));
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
