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

package com.dlap.policyengine.web.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyRepository;

/**
 * PE-02 (Q-757) against the real thing: the exception a second insert of a live policy name raises on
 * PostgreSQL 15 through Hibernate, with Flyway V1 to V9 applied, is the one {@link GlobalExceptionHandler}
 * maps to 409 {@code name-conflict}; a violation of another constraint of the same table (a not-null
 * column) is not. Requires Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiveNameRaceContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private JdbcTemplate jdbc;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static Policy policy(String name) {
        Policy policy = new Policy();
        policy.setName(name);
        policy.setDescription("race fixture");
        policy.setStatus(PolicyStatus.DISABLED);
        policy.setCreatedAt(Instant.now());
        return policy;
    }

    @Test
    void aSecondLivePolicyWithTheSameNameAnswers409NameConflict() {
        String name = "race-" + System.nanoTime();
        policies.saveAndFlush(policy(name));

        assertThatThrownBy(() -> policies.saveAndFlush(policy(name))).isInstanceOfSatisfying(
                DataIntegrityViolationException.class, failure -> {
                    ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(failure,
                            new MockHttpServletRequest("POST", "/api/v1/policyengine/policies"));
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(response.getBody().get("reason")).isEqualTo("name-conflict");
                    assertThat(response.getBody().toString()).doesNotContain(name);
                });
    }

    @Test
    void aNameFreedByASoftDeleteCanBeTakenAgain() {
        String name = "freed-" + System.nanoTime();
        Policy first = policy(name);
        first.setDeletedAt(Instant.now());
        policies.saveAndFlush(first);

        assertThat(policies.saveAndFlush(policy(name)).getId()).isNotNull();
    }

    @Test
    void aViolationOfAnotherConstraintOfTheTableStaysA500() {
        assertThatThrownBy(() -> jdbc.update("insert into dlap_policyengine.policies (id, name) values (gen_random_uuid(), ?)",
                "notnull-" + System.nanoTime())).isInstanceOfSatisfying(
                DataIntegrityViolationException.class, failure -> {
                    ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(failure,
                            new MockHttpServletRequest("POST", "/api/v1/policyengine/policies"));
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
                });
    }
}
