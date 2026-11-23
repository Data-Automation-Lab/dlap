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

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.reporting.drift.DriftType;
import com.dlap.reporting.model.DriftFindingEntity;

/**
 * {@code @DataJpaTest} coverage for {@link DriftFindingRepository}: round-trips
 * the entity through Postgres and exercises the filtered {@code search} query.
 * Uses Testcontainers Postgres (mirrors {@code ReportDefinitionRepositoryDataJpaTest}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DriftFindingRepositoryDataJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private DriftFindingRepository repository;

    private DriftFindingEntity finding(DriftType type, String asset, String subject,
                                       String decisionId, String readEventId, Instant readAt) {
        DriftFindingEntity e = new DriftFindingEntity();
        e.setDriftType(type);
        e.setAssetId(asset);
        e.setSubjectId(subject);
        e.setDecisionId(decisionId);
        e.setReadEventId(readEventId);
        e.setReadAt(readAt);
        e.setDetail("test fixture");
        return e;
    }

    @Test
    void saveAndSearch_roundTripsAndFilters() {
        Instant now = Instant.parse("2026-07-06T12:00:00Z");
        repository.save(finding(DriftType.UNENFORCED_READ, "asset-1", "svc-a",
                null, "evt-1", now));
        repository.save(finding(DriftType.ENFORCEMENT_BYPASS, "asset-2", "svc-b",
                "dec-9", "evt-2", now.plusSeconds(60)));

        // No filters -> both, newest read first.
        Page<DriftFindingEntity> all = repository.search(null, null, null, null, null,
                PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(2);
        assertThat(all.getContent().get(0).getAssetId()).isEqualTo("asset-2");

        // Filter by type.
        Page<DriftFindingEntity> bypass = repository.search(DriftType.ENFORCEMENT_BYPASS,
                null, null, null, null, PageRequest.of(0, 10));
        assertThat(bypass.getTotalElements()).isEqualTo(1);
        assertThat(bypass.getContent().get(0).getDecisionId()).isEqualTo("dec-9");

        // Filter by asset.
        Page<DriftFindingEntity> byAsset = repository.search(null, "asset-1", null, null, null,
                PageRequest.of(0, 10));
        assertThat(byAsset.getTotalElements()).isEqualTo(1);
        assertThat(byAsset.getContent().get(0).getSubjectId()).isEqualTo("svc-a");

        // Filter by time window (only the later finding).
        Page<DriftFindingEntity> windowed = repository.search(null, null, null,
                now.plusSeconds(30), null, PageRequest.of(0, 10));
        assertThat(windowed.getTotalElements()).isEqualTo(1);
        assertThat(windowed.getContent().get(0).getAssetId()).isEqualTo("asset-2");
    }

    @Test
    void search_upperBoundOnly_bothBounds_andSubjectFilter() {
        Instant t0 = Instant.parse("2026-07-06T12:00:00Z");
        repository.save(finding(DriftType.UNENFORCED_READ, "asset-1", "svc-a", null, "evt-a", t0));
        repository.save(finding(DriftType.UNENFORCED_READ, "asset-2", "svc-b", null, "evt-b", t0.plusSeconds(60)));
        repository.save(finding(DriftType.UNENFORCED_READ, "asset-3", "svc-a", null, "evt-c", t0.plusSeconds(120)));

        // Only an upper bound: this is the call shape that failed on PostgreSQL while the lower
        // bound was null ("could not determine data type of parameter").
        Page<DriftFindingEntity> upTo = repository.search(null, null, null,
                null, t0.plusSeconds(90), PageRequest.of(0, 10));
        assertThat(upTo.getContent()).extracting(DriftFindingEntity::getAssetId)
                .containsExactly("asset-2", "asset-1");

        // Both bounds.
        Page<DriftFindingEntity> window = repository.search(null, null, null,
                t0.plusSeconds(30), t0.plusSeconds(90), PageRequest.of(0, 10));
        assertThat(window.getContent()).extracting(DriftFindingEntity::getAssetId)
                .containsExactly("asset-2");

        // Subject filter combined with a lower bound.
        Page<DriftFindingEntity> bySubject = repository.search(null, null, "svc-a",
                t0.plusSeconds(30), null, PageRequest.of(0, 10));
        assertThat(bySubject.getContent()).extracting(DriftFindingEntity::getAssetId)
                .containsExactly("asset-3");
    }

    @Test
    void existsByDriftTypeAndReadEventId_supportsIdempotency() {
        Instant now = Instant.parse("2026-07-06T12:00:00Z");
        repository.save(finding(DriftType.UNENFORCED_READ, "asset-1", "svc-a",
                null, "evt-42", now));

        assertThat(repository.existsByDriftTypeAndReadEventId(
                DriftType.UNENFORCED_READ, "evt-42")).isTrue();
        assertThat(repository.existsByDriftTypeAndReadEventId(
                DriftType.ENFORCEMENT_BYPASS, "evt-42")).isFalse();
    }
}
