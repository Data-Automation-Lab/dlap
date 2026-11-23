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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.policyengine.model.EventRule;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventSubscription;
import com.dlap.policyengine.model.EventSubscriptionStatus;
import com.dlap.policyengine.model.EventType;

/**
 * SEC-04: the typed subscription filter round-trips through PostgreSQL with the schema
 * Flyway builds (V1 to V5) and Hibernate validates. The two new collections load with their
 * own selects, so they never multiply the rows of the existing eager collections.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class EventSubscriptionRepositoryDataJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private EventSubscriptionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private EventSubscription subscription() {
        EventSubscription s = new EventSubscription("filters-" + System.nanoTime(), UUID.randomUUID());
        s.setStatus(EventSubscriptionStatus.ACTIVE);
        s.setEventTypes(new ArrayList<>(List.of(EventType.ASSET_DISCOVERED, EventType.COMPLIANCE_VIOLATION)));
        s.setSeverities(new ArrayList<>(List.of(EventSeverity.HIGH, EventSeverity.CRITICAL)));
        s.setSourceServices(new ArrayList<>(List.of("da-catalog")));
        s.setAssetPrefixes(List.of("s3://finance/", "bq://analytics-prod/sales", "gs://landing"));
        s.setLabels(List.of("PII", "Finance"));
        s.setCreatedAt(Instant.parse("2026-10-19T00:00:00Z"));
        return s;
    }

    @Test
    void typedFilterRoundTripsWithoutDuplicatingTheOtherCollections() {
        UUID id = repository.saveAndFlush(subscription()).getId();
        entityManager.clear();

        EventSubscription found = repository.findById(id).orElseThrow();
        assertThat(found.getAssetPrefixes()).containsExactlyInAnyOrder("s3://finance/", "bq://analytics-prod/sales", "gs://landing");
        assertThat(found.getLabels()).containsExactlyInAnyOrder("PII", "Finance");
        assertThat(found.getEventTypes()).containsExactlyInAnyOrder(EventType.ASSET_DISCOVERED, EventType.COMPLIANCE_VIOLATION);
        assertThat(found.getSeverities()).containsExactlyInAnyOrder(EventSeverity.HIGH, EventSeverity.CRITICAL);
        assertThat(found.getSourceServices()).containsExactly("da-catalog");
        assertThat(found.getRules()).isEmpty();

        List<EventSubscription> active = repository.findByStatus(EventSubscriptionStatus.ACTIVE);
        assertThat(active).filteredOn(s -> s.getId().equals(id)).singleElement()
                .satisfies(s -> assertThat(s.getLabels()).hasSize(2));
    }

    @Test
    void replacingTheFilterRewritesTheCollections() {
        // The edit happens in a later unit of work, as an update request does. Hibernate ORM 7 does not
        // delete the rows of an element collection that is emptied in the same session in which its owner
        // was persisted, which this test used to do (BOOT-01; see docs/guides/upgrade-boot4.md).
        UUID id = repository.saveAndFlush(subscription()).getId();
        entityManager.clear();
        EventSubscription saved = repository.findById(id).orElseThrow();
        saved.setLabels(List.of("GDPR"));
        saved.setAssetPrefixes(List.of());
        repository.saveAndFlush(saved);
        entityManager.clear();

        EventSubscription found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getLabels()).containsExactly("GDPR");
        assertThat(found.getAssetPrefixes()).isEmpty();
    }

    @Test
    void retiredRuleTableRefusesNewRows() {
        EventSubscription saved = repository.saveAndFlush(subscription());
        EventRule rule = new EventRule("legacy", "true");
        saved.addRule(rule);

        // PostgreSQL names the violated CHECK constraint V5 added.
        assertThatThrownBy(() -> repository.saveAndFlush(saved))
                .rootCause().hasMessageContaining("ck_event_rules_retired");
    }
}
