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

package com.dlap.discovery.providers.aws.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.discovery.coverage.CoverageFixtures;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.providers.aws.coverage.AwsSignedJsonClient.CloudTrailApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * COV-01, AWS transport: the CloudTrail JSON 1.1 calls and STS GetCallerIdentity against a local
 * HTTP server, checking the target header, the SigV4 scope and the mapping of refusals.
 */
class AwsSignedJsonClientTest {

    private HttpServer server;
    private final List<String> targets = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        targets.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Amz-Target")));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private AwsSignedJsonClient client() {
        URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new AwsSignedJsonClient(CoverageFixtures.http(),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "test-secret-not-real")),
                region -> local, region -> local);
    }

    @Test
    void describeTrailsIsAJson11CallSignedForCloudTrailInTheRegion() {
        reply = "{\"trailList\":[]}";
        assertThat(client().describeTrails("eu-west-1").path("trailList").isArray()).isTrue();

        assertThat(targets).containsExactly("com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.DescribeTrails");
        assertThat(bodies).containsExactly("{\"includeShadowTrails\":true}");
        assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/")
                .contains("/eu-west-1/cloudtrail/aws4_request");
    }

    @Test
    void selectorsAndStatusNameTheTrailAndUseItsHomeRegion() {
        String arn = "arn:aws:cloudtrail:us-east-1:111122223333:trail/org-trail";
        client().getEventSelectors(arn, "us-east-1");
        client().getTrailStatus(arn, "us-east-1");
        assertThat(targets).containsExactly(
                "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.GetEventSelectors",
                "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.GetTrailStatus");
        assertThat(bodies).containsExactly("{\"TrailName\":\"" + arn + "\"}", "{\"Name\":\"" + arn + "\"}");
        assertThat(authorization.get()).contains("/us-east-1/cloudtrail/aws4_request");
    }

    @Test
    void anAccessDeniedErrorNamesTheIamAction() {
        status = 400;
        reply = "{\"__type\":\"com.amazonaws.cloudtrail#AccessDeniedException\",\"Message\":\"not authorized\"}";
        assertThatThrownBy(() -> client().getEventSelectors("arn:aws:cloudtrail:us-east-1:111122223333:trail/t", "us-east-1"))
                .isInstanceOf(MissingPermissionException.class)
                .hasMessage("missing permission cloudtrail:GetEventSelectors on arn:aws:cloudtrail:us-east-1:111122223333:trail/t");

        status = 403;
        reply = "";
        assertThatThrownBy(() -> client().describeTrails("eu-west-1"))
                .isInstanceOf(MissingPermissionException.class)
                .hasMessage("missing permission cloudtrail:DescribeTrails on region eu-west-1");
    }

    @Test
    void anotherErrorCarriesTheTypeButNotTheMessage() {
        status = 400;
        reply = "{\"__type\":\"TrailNotFoundException\",\"Message\":\"secret detail\"}";
        assertThatThrownBy(() -> client().getTrailStatus("arn:aws:cloudtrail:us-east-1:111122223333:trail/t", "us-east-1"))
                .isInstanceOf(CloudTrailApiException.class)
                .hasMessage("GetTrailStatus answered HTTP 400 TrailNotFoundException");
    }

    @Test
    void callerAccountIsReadFromTheStsXml() {
        reply = "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult>"
                + "<Arn>arn:aws:iam::111122223333:user/dlap-reader</Arn><UserId>AIDAEXAMPLEUSERID</UserId>"
                + "<Account>111122223333</Account></GetCallerIdentityResult></GetCallerIdentityResponse>";
        assertThat(client().callerAccount("us-east-1")).isEqualTo("111122223333");
        assertThat(bodies).containsExactly("Action=GetCallerIdentity&Version=2011-06-15");
        assertThat(authorization.get()).contains("/us-east-1/sts/aws4_request");
    }

    @Test
    void publicEndpointsFollowTheRegion() {
        assertThat(AwsSignedJsonClient.publicCloudTrailEndpoint("eu-west-1"))
                .hasToString("https://cloudtrail.eu-west-1.amazonaws.com/");
        assertThat(AwsSignedJsonClient.publicCloudTrailEndpoint("cn-north-1"))
                .hasToString("https://cloudtrail.cn-north-1.amazonaws.com.cn/");
        assertThat(AwsSignedJsonClient.publicStsEndpoint("us-east-1")).hasToString("https://sts.us-east-1.amazonaws.com/");
    }
}
