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

package com.dlap.discovery.client.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dlap.common.auth.ServiceTokenProvider;
import com.dlap.discovery.client.admin.AdminServiceException.Reason;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * DISC-01: in non-dev profiles the scan API resolves connections from da-admin-service over
 * M2M. These tests pin the wire contract (path, bearer token), the mapping from the admin DTO
 * to what the crawlers need, and that every failure becomes a classified
 * {@link AdminServiceException} rather than a stand-in connection.
 */
class HttpAdminServiceClientTest {

    private static final String BASE = "http://admin.test";
    private static final String ONE = BASE + "/api/v1/admin/cloud-connections/conn-1";
    private static final String ALL = BASE + "/api/v1/admin/cloud-connections";

    private MockRestServiceServer server;
    private HttpAdminServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAdminServiceClient(builder, () -> "tok-123", BASE);
    }

    private static String connection(String type, String params, boolean enabled) {
        return "{\"id\":\"conn-1\",\"name\":\"Prod\",\"providerType\":\"" + type + "\","
                + "\"connectionParameters\":" + params + ",\"enabled\":" + enabled + ","
                + "\"lastConnectionTestStatus\":\"SUCCESS\",\"somethingNew\":1}";
    }

    @Test
    void gcpConnection_isResolvedWithTheBearerToken_andKeyedOnItsProject() {
        server.expect(requestTo(ONE))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess(connection("GCP", "{\"projectId\":\" my-proj \"}", true),
                        MediaType.APPLICATION_JSON));

        AdminConnectionDetails details = client.getConnectionDetails("conn-1");

        assertThat(details.getProvider()).isEqualTo(CloudProvider.GCP);
        assertThat(details.getAccountId()).isEqualTo("my-proj");
        assertThat(details.getConnectionName()).isEqualTo("Prod");
        server.verify();
    }

    @Test
    void awsAndAzureConnections_useTheirOwnScopeKeys() {
        server.expect(requestTo(ONE)).andRespond(withSuccess(
                connection("aws", "{\"accountId\":\"123456789012\",\"projectId\":\"ignored\"}", true),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ONE)).andRespond(withSuccess(
                connection("AZURE", "{\"subscriptionId\":\"sub-guid\"}", true), MediaType.APPLICATION_JSON));

        AdminConnectionDetails aws = client.getConnectionDetails("conn-1");
        assertThat(aws.getProvider()).isEqualTo(CloudProvider.AWS);
        assertThat(aws.getAccountId()).isEqualTo("123456789012");

        AdminConnectionDetails azure = client.getConnectionDetails("conn-1");
        assertThat(azure.getProvider()).isEqualTo(CloudProvider.AZURE);
        assertThat(azure.getAccountId()).isEqualTo("sub-guid");
    }

    @Test
    void connectionWithoutAScopeParameter_isKeyedOnItsId() {
        server.expect(requestTo(ONE)).andRespond(withSuccess(
                connection("GCP", "{\"region\":\"us-central1\"}", true), MediaType.APPLICATION_JSON));

        assertThat(client.getConnectionDetails("conn-1").getAccountId()).isEqualTo("conn-1");
    }

    @Test
    void connectionWithNullParameters_isKeyedOnItsId() {
        server.expect(requestTo(ONE)).andRespond(withSuccess(
                connection("GCP", "null", true), MediaType.APPLICATION_JSON));

        assertThat(client.getConnectionDetails("conn-1").getAccountId()).isEqualTo("conn-1");
    }

    @Test
    void connectionIdIsEncodedAsOnePathSegment() {
        server.expect(requestTo(BASE + "/api/v1/admin/cloud-connections/a%2Fb%20c"))
                .andRespond(withSuccess(connection("GCP", "{}", true), MediaType.APPLICATION_JSON));

        client.getConnectionDetails("a/b c");

        server.verify();
    }

    @Test
    void unknownConnection_isNotFound() {
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND));
    }

    @Test
    void disabledConnection_isRefused() {
        server.expect(requestTo(ONE)).andRespond(withSuccess(
                connection("GCP", "{}", false), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.DISABLED);
                    assertThat(e.getMessage()).contains("Prod").contains("disabled");
                });
    }

    @Test
    void otherProviderType_isNotScannable() {
        server.expect(requestTo(ONE)).andRespond(withSuccess(
                connection("OTHER", "{}", true), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_PROVIDER));
    }

    @Test
    void theAdminServiceRefusingTheServiceAccount_isRejected_withoutLeakingTheBody() {
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"secret\":\"do-not-leak\"}"));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.REJECTED);
                    assertThat(e.getMessage()).contains("403").doesNotContain("do-not-leak");
                });
    }

    @Test
    void serverError_andUnreachableService_areUnavailable() {
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(ONE)).andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE);
                    assertThat(e.getMessage()).contains("unreachable");
                });
    }

    @Test
    void unreadableAnswer_isUnavailable() {
        server.expect(requestTo(ONE)).andRespond(withSuccess("<html>not json</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE));
    }

    @Test
    void blankId_isRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.getConnectionDetails(" "))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND));
        server.verify(); // no expectations set, none consumed
    }

    @Test
    void listConnections_flagsDisabledAndUnscannableOnes_andDropsNothingElse() {
        String body = "[" + connection("GCP", "{}", true)
                + "," + connection("OTHER", "{}", true).replace("conn-1", "conn-2")
                + "," + connection("AWS", "{}", false).replace("conn-1", "conn-3") + "]";
        server.expect(requestTo(ALL))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<AdminConnectionSummary> all = client.listConnections();

        assertThat(all).containsExactly(
                new AdminConnectionSummary("conn-1", "Prod", "GCP", true, true),
                new AdminConnectionSummary("conn-2", "Prod", "OTHER", true, false),
                new AdminConnectionSummary("conn-3", "Prod", "AWS", false, false));
    }

    @Test
    void listConnections_withNoConnections_isEmpty() {
        server.expect(requestTo(ALL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.listConnections()).isEmpty();
    }

    @Test
    void listConnections_when404_meansTheApiIsMissing_notAMissingConnection() {
        server.expect(requestTo(ALL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.listConnections())
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.REJECTED));
    }

    @Test
    void tokenFailure_isReportedAndNoRequestIsSent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer none = MockRestServiceServer.bindTo(builder).build();
        HttpAdminServiceClient noToken = new HttpAdminServiceClient(builder, () -> {
            throw new AdminServiceException(Reason.MISCONFIGURED, "service-auth is off");
        }, BASE);

        assertThatThrownBy(() -> noToken.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.MISCONFIGURED));
        none.verify();
    }

    @Test
    void withServiceAuthOff_theProductionConstructorFailsClosed() {
        ObjectProvider<ServiceTokenProvider> absent =
                new StaticListableBeanFactory().getBeanProvider(ServiceTokenProvider.class);
        HttpAdminServiceClient failClosed = new HttpAdminServiceClient(absent, "http://127.0.0.1:9", 100, 100);

        assertThatThrownBy(() -> failClosed.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.MISCONFIGURED);
                    assertThat(e.getMessage()).contains("dlap.service-auth.enabled");
                });
    }

    @Test
    void aTokenProviderThatCannotGetAToken_isUnavailable() {
        ObjectProvider<ServiceTokenProvider> broken = providerOf(new ServiceTokenProvider("http://unused", "c", "s") {
            @Override
            public String getAccessToken() {
                throw new IllegalStateException("keycloak down");
            }
        });
        HttpAdminServiceClient noToken = new HttpAdminServiceClient(broken, "http://127.0.0.1:9", 100, 100);

        assertThatThrownBy(() -> noToken.listConnections())
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE));
    }

    /** End to end over a real socket: production constructor, real timeouts, real JSON. */
    @Test
    void productionConstructor_talksHttp_andSendsTheServiceAccountToken() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getRawPath());
            byte[] body = connection("GCP", "{\"projectId\":\"live-proj\"}", true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            ObjectProvider<ServiceTokenProvider> provider = providerOf(new ServiceTokenProvider("http://unused", "c", "s") {
                @Override
                public String getAccessToken() {
                    return "service-token";
                }
            });
            HttpAdminServiceClient live = new HttpAdminServiceClient(provider,
                    "http://127.0.0.1:" + http.getAddress().getPort(), 2000, 2000);

            AdminConnectionDetails details = live.getConnectionDetails("conn-1");

            assertThat(details.getAccountId()).isEqualTo("live-proj");
            assertThat(auth.get()).isEqualTo("Bearer service-token");
            assertThat(path.get()).isEqualTo("/api/v1/admin/cloud-connections/conn-1");
        } finally {
            http.stop(0);
        }
    }

    @Test
    void anUnreachablePort_isUnavailable_notAHang() {
        ObjectProvider<ServiceTokenProvider> provider = providerOf(new ServiceTokenProvider("http://unused", "c", "s") {
            @Override
            public String getAccessToken() {
                return "service-token";
            }
        });
        HttpAdminServiceClient dead = new HttpAdminServiceClient(provider, "http://127.0.0.1:1", 500, 500);

        assertThatThrownBy(() -> dead.getConnectionDetails("conn-1"))
                .isInstanceOfSatisfying(AdminServiceException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE));
    }

    private static ObjectProvider<ServiceTokenProvider> providerOf(ServiceTokenProvider provider) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("serviceTokenProvider", provider);
        return factory.getBeanProvider(ServiceTokenProvider.class);
    }
}
