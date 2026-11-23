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

import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * DEP-01 acceptance: the full da-mcp-pep context starts under the {@code prod} profile with the
 * documented environment (deployment/PRODUCTION.md, "Environment under the prod profile").
 *
 * <p>Scope: a full-context test, not a slice test, and it needs no Docker. The reference
 * policy-enforcement point is stateless (no datasource, no Flyway, no Kafka), so the only
 * documented variables are the PDP address and the bearer token it presents. The enforcement
 * mode is left at its shipped default, which must fail closed.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProdProfileContextTest {

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

    @Autowired
    private RequestMappingHandlerAdapter requestMappingHandlerAdapter;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * BOOT-01 (ADR-0066): Spring MVC writes JSON with the Jackson 2 ObjectMapper that Spring Boot
     * configures (spring-boot-jackson2 and spring.http.converters.preferred-json-mapper=jackson2), not
     * with Jackson 3, so response bodies keep the form they had on Spring Boot 3.
     *
     * @throws Exception if the date cannot be written
     */
    @Test
    @SuppressWarnings("removal")
    void httpJsonStaysOnJackson2() throws Exception {
        assertThat(requestMappingHandlerAdapter.getMessageConverters())
                .anyMatch(converter -> converter instanceof MappingJackson2HttpMessageConverter jackson2
                        && jackson2.getObjectMapper() == objectMapper)
                .noneMatch(converter -> converter.getClass().getName()
                        .equals("org.springframework.http.converter.json.JacksonJsonHttpMessageConverter"));
        assertThat(objectMapper.writeValueAsString(Instant.parse("2026-10-19T12:00:00Z")))
                .as("dates stay ISO-8601 text, as on Spring Boot 3")
                .isEqualTo("\"2026-10-19T12:00:00Z\"");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private TestRestTemplate restTemplate;

    /** The context is up, and the prod profile is what is running. */
    @Test
    void contextStartsUnderProd() {
        assertThat(environment.getActiveProfiles()).contains("prod");
    }

    /** An unreachable PDP must deny: the shipped mode is enforce, never observe. */
    @Test
    void enforcementModeDefaultsToFailClosed() {
        assertThat(environment.getProperty("dlap.mcppep.mode")).isEqualTo("enforce");
    }

    /** The PEP owns no state: nothing may open a datasource. */
    @Test
    void opensNoDatasource() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    }

    /** The aggregate health is UP. */
    @Test
    void healthIsUp() {
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
    }

    /** The public health endpoint answers 200 and UP on the application port. */
    @Test
    void healthEndpointIsPublic() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    /** SEC-07: under prod the authorize endpoint answers 401 to a caller with no token, not a verdict. */
    @Test
    void authorizeNeedsAToken() {
        var response = restTemplate.postForEntity("/mcp/v1/authorize", java.util.Map.of("principalId", "agent-1"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
