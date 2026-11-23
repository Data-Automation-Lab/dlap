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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SEC-07 acceptance: the whole da-mcp-pep service, with its own {@code application.properties} and the
 * real security chain, is a JWT resource server that checks signature, validity period, issuer and audience.
 *
 * <p>Scope: a full-context test, not a slice test, and it needs no Docker and no Keycloak. Keycloak is
 * replaced by {@link JwtTestSupport}: an in-process key set and a signer, so the real decoder verifies real
 * RS256 tokens. The PDP is replaced by a stub that allows everything and counts its calls, so a 200 can
 * only come from a request that passed authentication and authorization. Each test calls the running
 * server over HTTP.
 *
 * <p>Live Keycloak (a token from the dlap realm) is not exercised here: the realm has no audience mapper for the PEP
 * yet (Q-381), so a live check needs IAM-02 first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpPepAuthenticationTest {

    private static final String AUTHORIZE = "/mcp/v1/authorize";

    /** A request for a resource nobody asked about yet, so the decision cache never answers it. */
    private static String body(String roles) {
        return "{\"principalId\":\"agent-1\",\"roles\":[" + roles + "],\"tier\":\"T1\","
                + "\"tool\":\"query\",\"resourceUri\":\"bq://p/d/" + UUID.randomUUID()
                + "\",\"action\":\"read\",\"context\":{}}";
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @DynamicPropertySource
    static void authentication(DynamicPropertyRegistry registry) {
        JwtTestSupport.register(registry);
    }

    /** Counts the evaluations that reach the PDP seam and allows each one. */
    private static final AtomicInteger PDP_CALLS = new AtomicInteger();

    /** The request the PEP sent to the PDP seam last, after the subject was bound. */
    private static final AtomicReference<McpAccessRequest> LAST_PDP_REQUEST = new AtomicReference<>();

    @TestConfiguration
    static class AllowingPdp {

        @Bean
        @Primary
        PdpClient allowingPdpClient() {
            return request -> {
                PDP_CALLS.incrementAndGet();
                LAST_PDP_REQUEST.set(request);
                return McpDecision.allow("stub allow", List.of());
            };
        }
    }

    @Autowired
    private Environment environment;

    // ---- no token, bad token: 401 ----

    /** The protected endpoint answers 401 with a Bearer challenge when there is no token, and nothing reaches the PDP. */
    @Test
    void noTokenIsUnauthorized() throws Exception {
        int before = PDP_CALLS.get();

        HttpResponse<String> response = post(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                challenge -> assertThat(challenge).startsWith("Bearer"));
        assertThat(response.body()).doesNotContain("allow");
        assertThat(PDP_CALLS.get()).isEqualTo(before);
    }

    /** A bearer value that is not a JWT is 401. */
    @Test
    void aMalformedTokenIsUnauthorized() throws Exception {
        assertThat(post("not-a-jwt").statusCode()).isEqualTo(401);
    }

    /** A different authentication scheme is 401, not a fallback to an anonymous caller. */
    @Test
    void basicAuthenticationIsUnauthorized() throws Exception {
        HttpRequest request = request(AUTHORIZE).header("Authorization", "Basic YWRtaW46YWRtaW4=")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body("\"DATA_ANALYST\""))).build();

        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    /** A token signed with a key that is not in the realm key set is 401. */
    @Test
    void aTokenSignedByAnotherKeyIsUnauthorized() throws Exception {
        assertThat(post(JwtTestSupport.token().signedByAnotherKey().build()).statusCode()).isEqualTo(401);
    }

    /** A token minted by another issuer is 401, even with the right signature and audience. */
    @Test
    void aTokenOfAnotherIssuerIsUnauthorized() throws Exception {
        assertThat(post(JwtTestSupport.token().issuer("http://keycloak:8080/realms/other").build()).statusCode())
                .isEqualTo(401);
    }

    /** A token for another audience is 401, even with the right signature and issuer. */
    @Test
    void aTokenForAnotherAudienceIsUnauthorized() throws Exception {
        assertThat(post(JwtTestSupport.token().audience("account").build()).statusCode()).isEqualTo(401);
    }

    /** A token without an audience is 401. */
    @Test
    void aTokenWithoutAnAudienceIsUnauthorized() throws Exception {
        assertThat(post(JwtTestSupport.token().audience().build()).statusCode()).isEqualTo(401);
    }

    /** An expired token is 401. */
    @Test
    void anExpiredTokenIsUnauthorized() throws Exception {
        String token = JwtTestSupport.token().expiresAt(Instant.now().minusSeconds(3600)).build();

        assertThat(post(token).statusCode()).isEqualTo(401);
    }

    /** A token that is not yet valid is 401. */
    @Test
    void aTokenNotYetValidIsUnauthorized() throws Exception {
        String token = JwtTestSupport.token().notBefore(Instant.now().plusSeconds(3600)).build();

        assertThat(post(token).statusCode()).isEqualTo(401);
    }

    // ---- authenticated: roles decide between 200 and 403 ----

    /** A valid service-account token is let through and gets the verdict. */
    @Test
    void aServiceAccountTokenGetsTheVerdict() throws Exception {
        int before = PDP_CALLS.get();

        HttpResponse<String> response = post(JwtTestSupport.validToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"allow\":true");
        assertThat(PDP_CALLS.get()).isEqualTo(before + 1);
    }

    /** The second accepted issuer of the comma separated list works too. */
    @Test
    void aTokenOfTheSecondConfiguredIssuerIsAccepted() throws Exception {
        String token = JwtTestSupport.token().issuer(JwtTestSupport.PUBLIC_ISSUER).build();

        assertThat(post(token).statusCode()).isEqualTo(200);
    }

    /** An administrator token is let through, as at the PDP, when the request is about the administrator. */
    @Test
    void anAdminTokenGetsTheVerdict() throws Exception {
        String token = JwtTestSupport.token().subject("agent-1").realmRoles("ROLE_ADMIN").build();

        assertThat(post(token).statusCode()).isEqualTo(200);
    }

    // ---- PEP-02: the subject comes from the token (Q-382, D26) ----

    /** A user token that names another principal is refused with 403, and nothing reaches the PDP. */
    @Test
    void aUserTokenNamingAnotherPrincipalIsForbidden() throws Exception {
        int before = PDP_CALLS.get();
        String token = JwtTestSupport.token().subject("user-1").realmRoles("ROLE_ADMIN").build();

        HttpResponse<String> response = post(token, body("\"ADMIN\""));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"allow\":false").contains("principalId must be the subject of the token")
                .doesNotContain("agent-1");
        assertThat(PDP_CALLS.get()).isEqualTo(before);
    }

    /** A user token naming itself gets the verdict, and the PDP is asked about the token subject. */
    @Test
    void aUserTokenNamingItselfGetsTheVerdictForTheTokenSubject() throws Exception {
        String token = JwtTestSupport.token().subject("user-1").realmRoles("ROLE_ADMIN").build();

        HttpResponse<String> response = post(token, bodyFor("user-1", "\"VIEWER\"", "\"EXECUTIVE\""));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(LAST_PDP_REQUEST.get().principalId()).isEqualTo("user-1");
    }

    /** A user token that names nobody is asked about as itself. */
    @Test
    void aUserTokenNamingNobodyIsBoundToTheTokenSubject() throws Exception {
        String token = JwtTestSupport.token().subject("user-2").realmRoles("ROLE_ADMIN").build();

        HttpResponse<String> response = post(token, bodyFor(null, "\"VIEWER\"", null));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(LAST_PDP_REQUEST.get().principalId()).isEqualTo("user-2");
    }

    /** Roles and tier asserted in the body are ignored for a user token; the token's own reach the PDP. */
    @Test
    void aUserTokenRolesAndTierComeFromTheToken() throws Exception {
        String token = JwtTestSupport.token().subject("user-3").realmRoles("ROLE_ADMIN", "ROLE_VIEWER")
                .claim("tier", "READER").build();

        HttpResponse<String> response = post(token, bodyFor("user-3", "\"CDO\",\"DIRECTOR\"", "\"EXECUTIVE\""));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        McpAccessRequest sent = LAST_PDP_REQUEST.get();
        assertThat(sent.roles()).containsExactlyInAnyOrder("ADMIN", "VIEWER");
        assertThat(sent.tier()).isEqualTo("READER");
    }

    /** A user token without a tier claim has no tier: the body cannot supply one. */
    @Test
    void aUserTokenWithoutATierClaimHasNoTier() throws Exception {
        String token = JwtTestSupport.token().subject("user-4").realmRoles("ROLE_ADMIN").build();

        post(token, bodyFor("user-4", "\"ADMIN\"", "\"EXECUTIVE\""));

        assertThat(LAST_PDP_REQUEST.get().tier()).isNull();
    }

    /** A service account may name the subject: the PDP is asked about the principal the gateway names. */
    @Test
    void aServiceAccountMayNameTheSubject() throws Exception {
        HttpResponse<String> response = post(JwtTestSupport.validToken(),
                bodyFor("agent-77", "\"DATA_ANALYST\"", "\"READER\""));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        McpAccessRequest sent = LAST_PDP_REQUEST.get();
        assertThat(sent.principalId()).isEqualTo("agent-77");
        assertThat(sent.roles()).containsExactly("DATA_ANALYST");
        assertThat(sent.tier()).isEqualTo("READER");
    }

    /** A service account that names nobody is asked about as itself. */
    @Test
    void aServiceAccountNamingNobodyIsBoundToItsOwnSubject() throws Exception {
        String token = JwtTestSupport.token().subject("gateway-sa").build();

        HttpResponse<String> response = post(token, bodyFor(null, "\"DATA_ANALYST\"", null));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(LAST_PDP_REQUEST.get().principalId()).isEqualTo("gateway-sa");
    }

    /** A token that holds both machine and human roles counts as a service account. */
    @Test
    void aTokenWithTheServiceAccountRoleMayNameTheSubject() throws Exception {
        String token = JwtTestSupport.token().realmRoles("ROLE_SERVICE_ACCOUNT", "ROLE_ADMIN").build();

        assertThat(post(token, bodyFor("someone-else", "\"VIEWER\"", null)).statusCode()).isEqualTo(200);
        assertThat(LAST_PDP_REQUEST.get().principalId()).isEqualTo("someone-else");
    }

    /** Roles in the top-level {@code roles} claim count as well, as in the other services. */
    @Test
    void rolesInTheTopLevelClaimCount() throws Exception {
        assertThat(post(JwtTestSupport.token().topLevelRoles("SERVICE_ACCOUNT").build()).statusCode()).isEqualTo(200);
    }

    // ---- Q-459 (D29): any token with the PEP audience gets a decision ----

    /**
     * Acceptance: a user token with the PEP audience and no service role gets a decision. It is asked
     * about as itself, with its own roles; before Q-459 the same token was answered 403 by the role gate.
     */
    @Test
    void aUserTokenWithThePepAudienceAndNoServiceRoleGetsADecision() throws Exception {
        int before = PDP_CALLS.get();
        String token = JwtTestSupport.token().subject("user-5").realmRoles("VIEWER", "DATA_ANALYST").build();

        HttpResponse<String> response = post(token, bodyFor("user-5", "\"CDO\"", null));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"allow\":true");
        assertThat(PDP_CALLS.get()).isEqualTo(before + 1);
        McpAccessRequest sent = LAST_PDP_REQUEST.get();
        assertThat(sent.principalId()).isEqualTo("user-5");
        assertThat(sent.roles()).containsExactlyInAnyOrder("VIEWER", "DATA_ANALYST");
    }

    /**
     * Acceptance: a user token without the PEP audience gets 401, whatever its roles, and nothing
     * reaches the PDP.
     */
    @Test
    void aUserTokenWithoutThePepAudienceIsUnauthorizedAndNothingReachesThePdp() throws Exception {
        int before = PDP_CALLS.get();
        String token = JwtTestSupport.token().subject("user-6").realmRoles("VIEWER", "ADMIN").audience("account").build();

        HttpResponse<String> response = post(token, bodyFor("user-6", "\"ADMIN\"", null));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("\"allow\":true");
        assertThat(PDP_CALLS.get()).isEqualTo(before);
    }

    /** A token with the audience and no role at all gets a decision as itself, with no roles. */
    @Test
    void aTokenWithoutRolesGetsADecisionAsItself() throws Exception {
        String token = JwtTestSupport.token().subject("user-7").realmRoles().build();

        HttpResponse<String> response = post(token, bodyFor(null, "\"ADMIN\"", null));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        McpAccessRequest sent = LAST_PDP_REQUEST.get();
        assertThat(sent.principalId()).isEqualTo("user-7");
        assertThat(sent.roles()).isEmpty();
    }

    /** The wider gate does not widen the subject: a token without the service role still cannot name another principal. */
    @Test
    void aTokenWithoutAServiceRoleStillCannotNameAnotherPrincipal() throws Exception {
        int before = PDP_CALLS.get();
        String token = JwtTestSupport.token().subject("user-8").realmRoles("VIEWER").build();

        HttpResponse<String> response = post(token, bodyFor("agent-1", "\"VIEWER\"", null));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(PDP_CALLS.get()).isEqualTo(before);
    }

    /** A role named in the request body does not grant anything: only the token's roles reach the PDP. */
    @Test
    void rolesInTheBodyDoNotElevateAUserToken() throws Exception {
        String token = JwtTestSupport.token().subject("user-9").realmRoles("VIEWER").build();

        HttpResponse<String> response = post(token, bodyFor("user-9", "\"SERVICE_ACCOUNT\",\"ADMIN\"", "\"EXECUTIVE\""));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(LAST_PDP_REQUEST.get().roles()).containsExactly("VIEWER");
    }

    // ---- the rest of the surface ----

    /** Liveness, readiness and the aggregate health stay open, and show no component detail. */
    @Test
    void healthStaysOpen() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(path, null);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"")
                    .doesNotContain("\"components\"").doesNotContain("\"details\"");
        }
    }

    /** No other actuator endpoint is open to an anonymous caller, and a token does not open them. */
    @Test
    void otherActuatorEndpointsAreClosed() throws Exception {
        for (String path : new String[] {"/actuator/info", "/actuator/metrics"}) {
            assertThat(get(path, null).statusCode()).as(path + " without a token").isEqualTo(401);
            assertThat(get(path, JwtTestSupport.token().realmRoles("ADMIN").build()).statusCode())
                    .as(path + " with an admin token").isEqualTo(403);
        }
    }

    /** An anonymous caller is told 401 for any path, so the answer does not reveal which paths exist. */
    @Test
    void everyOtherPathNeedsAToken() throws Exception {
        for (String path : new String[] {"/", "/mcp/v1/anything", "/v3/api-docs", "/swagger-ui/index.html", "/error"}) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(401);
        }
    }

    /** The authorize endpoint carries the audience gate, so the decoder's 401 is not the only line of defence. */
    @Test
    void theHandlerCarriesTheAudienceGate() throws Exception {
        var annotation = McpPepController.class.getMethod("authorize", McpAccessRequest.class,
                org.springframework.security.core.Authentication.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("@pepAudienceGate.carriesPepAudience(authentication)")
                .doesNotContain("permitAll").doesNotContain("isAuthenticated");
    }

    // ---- start-up refusals ----

    /** The PEP does not start without an expected audience, so it cannot run accepting every audience. */
    @Test
    void refusesToStartWithoutAnAudience() {
        assertThatThrownBy(() -> startWith("--dlap.security.jwt.expected-audience="))
                .hasStackTraceContaining("DLAP_SECURITY_JWT_EXPECTED_AUDIENCE");
    }

    /** The PEP does not start without an expected issuer. */
    @Test
    void refusesToStartWithoutAnIssuer() {
        assertThatThrownBy(() -> startWith("--dlap.security.jwt.expected-issuer="))
                .hasStackTraceContaining("DLAP_SECURITY_JWT_EXPECTED_ISSUER");
    }

    /** The shipped defaults leave the issuer and the audience empty, so a bare start is refused. */
    @Test
    void shippedDefaultsNameNeitherIssuerNorAudience() {
        // The properties registered for this test class apply to its own context only, not to this one.
        assertThatThrownBy(() -> new SpringApplicationBuilder(McpPepApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + JwtTestSupport.jwkSetUri()))
                .hasStackTraceContaining("DLAP_SECURITY_JWT_EXPECTED_ISSUER");
    }

    private static void startWith(String blankedSetting) {
        // A repeated command-line option is joined to its first value, so a blanked setting is passed alone.
        List<String> args = new java.util.ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off",
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + JwtTestSupport.jwkSetUri()));
        if (!blankedSetting.startsWith("--dlap.security.jwt.expected-issuer")) {
            args.add("--dlap.security.jwt.expected-issuer=" + JwtTestSupport.ISSUER);
        }
        if (!blankedSetting.startsWith("--dlap.security.jwt.expected-audience")) {
            args.add("--dlap.security.jwt.expected-audience=" + JwtTestSupport.AUDIENCE);
        }
        args.add(blankedSetting);
        try (var ignored = new SpringApplicationBuilder(McpPepApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new))) {
            // started: the assertion in the caller fails because no exception was thrown
        }
    }

    // ---- HTTP helpers ----

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                .timeout(Duration.ofSeconds(20));
    }

    private HttpResponse<String> post(String bearer) throws IOException, InterruptedException {
        return post(bearer, body("\"DATA_ANALYST\""));
    }

    private HttpResponse<String> post(String bearer, String json) throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(AUTHORIZE).header("Content-Type", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A request for an unseen resource that names the given principal (or none), roles and tier (or none). */
    private static String bodyFor(String principal, String roles, String tier) {
        return "{" + (principal == null ? "" : "\"principalId\":\"" + principal + "\",")
                + "\"roles\":[" + roles + "]," + (tier == null ? "" : "\"tier\":" + tier + ",")
                + "\"tool\":\"query\",\"resourceUri\":\"bq://p/d/" + UUID.randomUUID()
                + "\",\"action\":\"read\",\"context\":{}}";
    }

    private HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(path);
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private int port() {
        return Integer.parseInt(environment.getRequiredProperty("local.server.port"));
    }
}
