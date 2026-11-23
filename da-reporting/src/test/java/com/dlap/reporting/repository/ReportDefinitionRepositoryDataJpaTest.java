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

package com.dlap.reporting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.reporting.model.ReportDefinition;
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
 * R-P0-6: minimum {@code @DataJpaTest} coverage for {@link ReportDefinitionRepository}.
 *
 * <p>Uses Testcontainers PostgreSQL - H2 cannot satisfy the
 * {@code defaultParameters} {@code jsonb} column on {@link ReportDefinition}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ReportDefinitionRepositoryDataJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private ReportDefinitionRepository reportDefinitionRepository;

    @Test
    void saveAndFindByReportKey_roundTripsThroughPostgres() {
        ReportDefinition rd = new ReportDefinition();
        rd.setReportKey("TEST_REPORT_" + System.nanoTime());
        rd.setDisplayName("Test Report");
        rd.setDescription("test fixture");
        rd.setEnabled(true);
        rd.setCreatedAt(Instant.now());

        ReportDefinition saved = reportDefinitionRepository.save(rd);
        assertThat(saved.getId()).isNotNull();

        Optional<ReportDefinition> found = reportDefinitionRepository.findByReportKey(rd.getReportKey());
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("Test Report");
        assertThat(found.get().isEnabled()).isTrue();
    }
}
