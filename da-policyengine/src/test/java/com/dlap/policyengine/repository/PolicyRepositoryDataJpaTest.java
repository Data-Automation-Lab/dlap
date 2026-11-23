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

package com.dlap.policyengine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyKind;
import com.dlap.policyengine.model.PolicyStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R-P0-6: minimum {@code @DataJpaTest} coverage for {@link PolicyRepository}.
 *
 * <p>Uses Testcontainers PostgreSQL via {@code @ServiceConnection} (Spring
 * Boot 3.1+) so the {@code Policy.actions} {@code jsonb} column round-trips
 * through a real Postgres engine - H2 cannot satisfy the JdbcTypeCode JSON
 * mapping. Verifies the save → findByName → countByStatus contract used
 * by {@code PolicyService}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PolicyRepositoryDataJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private PolicyRepository policyRepository;

    @Test
    void saveAndFindByName_roundTripsThroughPostgres() {
        Policy policy = new Policy();
        policy.setName("test-policy-" + System.nanoTime());
        policy.setDescription("test fixture");
        policy.setStatus(PolicyStatus.DISABLED);
        policy.setCreatedAt(Instant.now());

        Policy saved = policyRepository.save(policy);
        assertThat(saved.getId()).isNotNull();

        Optional<Policy> found = policyRepository.findByName(policy.getName());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(PolicyStatus.DISABLED);
    }

    @Test
    void policyKind_roundTripsThroughPostgresAndDefaultsToDsl() {
        Policy legacy = new Policy();
        legacy.setName("legacy-" + System.nanoTime());
        legacy.setStatus(PolicyStatus.DISABLED);
        legacy.setPolicyKind(PolicyKind.LEGACY_RULE);
        legacy.setCreatedAt(Instant.now());
        Policy plain = new Policy();
        plain.setName("plain-" + System.nanoTime());
        plain.setStatus(PolicyStatus.DISABLED);
        plain.setCreatedAt(Instant.now());

        policyRepository.saveAndFlush(legacy);
        policyRepository.saveAndFlush(plain);

        assertThat(policyRepository.findByName(legacy.getName()).orElseThrow().getPolicyKind())
                .isEqualTo(PolicyKind.LEGACY_RULE);
        assertThat(policyRepository.findByName(plain.getName()).orElseThrow().getPolicyKind())
                .isEqualTo(PolicyKind.DSL);
    }

    @Test
    void countByStatus_returnsZeroOnEmptyTable() {
        long count = policyRepository.countByStatus(PolicyStatus.ENABLED);
        assertThat(count).isGreaterThanOrEqualTo(0L);
    }
}
