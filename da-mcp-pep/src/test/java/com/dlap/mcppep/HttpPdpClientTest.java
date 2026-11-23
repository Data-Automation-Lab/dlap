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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies {@link HttpPdpClient} parses the AuthZEN §3 response into an
 * {@link McpDecision} (incl. obligations) and maps failures to
 * {@link PdpUnavailableException}. Uses {@link MockRestServiceServer} bound to
 * the RestClient - no live PDP, no Spring context.
 */
class HttpPdpClientTest {

    private static final String BASE_URL = "http://pdp.test:8083";

    private MockRestServiceServer server;
    private HttpPdpClient client;

    private McpAccessRequest req() {
        return new McpAccessRequest(
                "svc-agent", List.of("DATA_ANALYST"), "PRACTITIONER", "tool",
                "bq://project/dataset/table", "asset-1", "read",
                Map.of("purpose", "campaign-analytics"));
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        McpPepProperties props = new McpPepProperties();
        props.getPdp().setBaseUrl(BASE_URL);
        // token empty → no Authorization header (StaticPdpTokenProvider returns empty)
        client = new HttpPdpClient(builder, new AuthZenRequestMapper(),
                new StaticPdpTokenProvider(props), props);
    }

    @Test
    void parsesAllowWithObligations() {
        String responseJson = """
                {
                  "decision": true,
                  "context": {
                    "id": "dec-0191c2",
                    "reason_admin": { "en": "allowed by access-eu-marketing-aggregate" },
                    "obligations": [ { "type": "mask", "labels": ["PII"] } ]
                  }
                }
                """;
        server.expect(requestTo(BASE_URL + HttpPdpClient.EVALUATION_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        McpDecision d = client.evaluate(req());

        assertThat(d.allow()).isTrue();
        assertThat(d.reason()).isEqualTo("allowed by access-eu-marketing-aggregate");
        assertThat(d.obligations()).hasSize(1);
        assertThat(d.obligations().get(0).type()).isEqualTo("mask");
        assertThat(d.obligations().get(0).labels()).containsExactly("PII");
        assertThat(d.fromCache()).isFalse();
        assertThat(d.failClosed()).isFalse();
        server.verify();
    }

    @Test
    void parsesDenyReason() {
        String responseJson = """
                {
                  "decision": false,
                  "context": { "id": "dec-x", "reason_admin": { "en": "denied by default-decision" } }
                }
                """;
        server.expect(requestTo(BASE_URL + HttpPdpClient.EVALUATION_PATH))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        McpDecision d = client.evaluate(req());

        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo("denied by default-decision");
        assertThat(d.obligations()).isEmpty();
    }

    @Test
    void serverErrorBecomesPdpUnavailable() {
        server.expect(requestTo(BASE_URL + HttpPdpClient.EVALUATION_PATH))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.evaluate(req()))
                .isInstanceOf(PdpUnavailableException.class);
    }
}
