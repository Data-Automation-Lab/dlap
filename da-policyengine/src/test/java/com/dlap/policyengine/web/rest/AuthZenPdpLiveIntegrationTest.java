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

package com.dlap.policyengine.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.access.AccessDecisionService;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Live coverage of the AuthZEN PDP wired path (ADR-0040, enforcement-contract.md sections 1 to
 * 3 and 6). The wire contract is pinned by {@link AuthZenConformanceTest} and the combining
 * logic by {@code AccessDecisionServiceTest}, both with mocks. This test runs what they cannot:
 * the real Spring Boot context on a Testcontainers PostgreSQL (Flyway, then Hibernate
 * validation), the real security filter chain verifying a signed JWT and mapping its
 * {@code realm_access.roles} claim, the real {@code @PreAuthorize} on the endpoint, and the
 * decision-log event reaching an embedded Kafka broker.
 *
 * <p>The only substitution is the JWT decoder's key source: production fetches Keycloak's JWKS,
 * the test verifies against its own generated RSA key. Needs a running Docker daemon for the
 * database; the broker runs inside the test JVM. The path against a full dev stack with a real
 * Keycloak is {@code e2e-tests/authzen-pdp-verify.ps1}.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // The test JwtDecoder below replaces the production bean of the same name.
                "spring.main.allow-bean-definition-overriding=true",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        })
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = DlapConstants.KafkaTopic.ACCESS_DECISION)
class AuthZenPdpLiveIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    /** Signs the test tokens; its public half is what the decoder trusts. */
    private static final RSAKey SIGNING_KEY = generateKey();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An ENABLED AccessPolicy that allows DATA_ANALYST to read EU_RESIDENT with a mask obligation. */
    private static final String ACCESS_POLICY_YAML = ""
            + "apiVersion: dlap/v1\n"
            + "kind: AccessPolicy\n"
            + "metadata: {id: p-live-allow}\n"
            + "spec:\n"
            + "  subjects: {roles: [DATA_ANALYST]}\n"
            + "  actions: [read]\n"
            + "  match: {labels: {anyOf: [EU_RESIDENT]}}\n"
            + "  effect: allow\n"
            + "  obligations:\n"
            + "    - {type: mask, labels: [PII]}\n";

    /** AuthZEN evaluation request body (subject, action, resource, context). */
    private static final String EVALUATION_BODY = "{"
            + "\"subject\":{\"type\":\"agent\",\"id\":\"svc-live\","
            + "\"properties\":{\"roles\":[\"DATA_ANALYST\"]}},"
            + "\"action\":{\"name\":\"read\"},"
            + "\"resource\":{\"type\":\"asset\",\"properties\":{\"labels\":[\"EU_RESIDENT\"]}},"
            + "\"context\":{}}";

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private AccessDecisionService accessDecisionService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    /** Replaces the production decoder (JWKS fetch) with one that trusts {@link #SIGNING_KEY}. */
    @TestConfiguration
    static class TestJwtDecoderConfiguration {
        @Bean(name = "jwtDecoder")
        JwtDecoder jwtDecoder() throws JOSEException {
            return NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("live-test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the test signing key", e);
        }
    }

    /** A signed token the way Keycloak shapes it: roles under {@code realm_access.roles}. */
    private static String token(String... roles) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("svc-live")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private ResponseEntity<String> evaluate(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange("/access/v1/evaluation", HttpMethod.POST,
                new HttpEntity<>(EVALUATION_BODY, headers), String.class);
    }

    private void seedEnabledAccessPolicy() {
        // No id: Policy has a generated id, and since Hibernate ORM 6.6 saving one with a preset id is a
        // merge of a detached entity whose row is missing, which fails optimistic locking (BOOT-01).
        Policy p = new Policy();
        p.setName("p-live-allow-" + System.nanoTime());
        p.setDescription("live PDP integration fixture");
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument(ACCESS_POLICY_YAML);
        policyRepository.save(p);
        // The PDP caches the parsed set; reload it so the new policy is visible.
        accessDecisionService.refresh();
    }

    @Test
    void enabledAllowPolicy_yieldsAuthZenAllowWithObligations_andPublishesTheDecisionEvent() throws Exception {
        seedEnabledAccessPolicy();

        ResponseEntity<String> response = evaluate(token(DlapConstants.UserRole.SERVICE_ACCOUNT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("decision").isBoolean()).isTrue();
        assertThat(body.get("decision").asBoolean()).isTrue();
        String decisionId = body.at("/context/id").asText();
        assertThat(decisionId).isNotBlank();
        assertThat(body.at("/context/reason_admin/en").asText()).isNotBlank();
        assertThat(body.at("/context/obligations/0/type").asText()).isEqualTo("mask");
        assertThat(body.at("/context/obligations/0/labels/0").asText()).isEqualTo("PII");

        // The decision-log event (enforcement-contract.md section 6) lands on the topic with the
        // same decision id, so the drift report can join a later read to this verdict.
        JsonNode event = awaitDecisionEvent(decisionId);
        assertThat(event.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(event.get("subjectId").asText()).isEqualTo("svc-live");
        assertThat(event.get("action").asText()).isEqualTo("read");
    }

    @Test
    void adminRole_isAlsoAllowedToCallTheEndpoint() throws Exception {
        ResponseEntity<String> response = evaluate(token(DlapConstants.UserRole.ADMIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void nonServiceRole_isForbidden() throws Exception {
        ResponseEntity<String> response = evaluate(token(DlapConstants.UserRole.DATA_ANALYST));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingToken_isUnauthorized() {
        ResponseEntity<String> response = evaluate(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenSignedByAnotherKey_isUnauthorized() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("other").generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("svc-live")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of(DlapConstants.UserRole.SERVICE_ACCOUNT)))
                .build();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("other").build(), claims);
        forged.sign(new RSASSASigner(otherKey));

        ResponseEntity<String> response = evaluate(forged.serialize());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Reads the access-decision topic until the event for {@code decisionId} arrives. */
    private JsonNode awaitDecisionEvent(String decisionId) throws Exception {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "live-test-" + UUID.randomUUID(), "true", embeddedKafka);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, DlapConstants.KafkaTopic.ACCESS_DECISION);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record
                        : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                    JsonNode event = JSON.readTree(record.value());
                    if (decisionId.equals(event.path("decisionId").asText())) {
                        return event;
                    }
                }
            }
        }
        throw new AssertionError("no access-decision event with decisionId " + decisionId + " within 30 s");
    }
}
