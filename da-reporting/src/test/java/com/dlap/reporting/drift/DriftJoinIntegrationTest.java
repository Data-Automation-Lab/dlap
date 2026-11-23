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

package com.dlap.reporting.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.constants.DlapConstants;
import com.dlap.reporting.repository.AccessDecisionRecordRepository;
import com.dlap.reporting.repository.DriftFindingRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * End-to-end coverage of the Consumed-stage drift join (ADR-0040) on real infrastructure: a
 * decision on {@code dlap.access.decision} ({@link DlapConstants.KafkaTopic#ACCESS_DECISION}) and a
 * read on {@code dlap.discovery.asset.accessed}
 * ({@link DlapConstants.KafkaTopic#DISCOVERY_ASSET_ACCESSED}) are published to a Kafka broker; the
 * real consumers ({@code AccessDecisionConsumer}, {@code AssetAccessedConsumer}), the
 * {@link DriftDetectionOrchestrator} and the Postgres repositories turn them into a finding; and
 * {@code GET /api/v1/reporting/drift/findings} returns it.
 *
 * <p>The pure correlation rules are covered by {@code DriftDetectionServiceTest} and the query by
 * {@code DriftFindingRepositoryDataJpaTest}. This test covers the wiring: JSON deserialization
 * off the wire, manual acknowledgment, idempotency under redelivery, persistence, and the API
 * (called with no time bound, the shape that failed on PostgreSQL before the repository fix).
 *
 * <p>The database is a Testcontainers PostgreSQL, so Docker must be running. The broker runs
 * inside the test JVM ({@code @EmbeddedKafka}, one partition so events are consumed in order).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {DlapConstants.KafkaTopic.ACCESS_DECISION, DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED})
class DriftJoinIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    /** A fixed instant, so the test does not depend on the wall clock. */
    private static final Instant T0 = Instant.parse("2026-07-06T12:00:00Z");

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private DriftFindingRepository driftFindingRepository;

    @Autowired
    private AccessDecisionRecordRepository decisionRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    /** Decision-log event shape per enforcement-contract.md section 6 (camelCase JSON). */
    private static Map<String, Object> decisionEvent(String decisionId, String subject, String asset,
                                                     String decision, Instant at) {
        return Map.of(
                "decisionId", decisionId,
                "subjectId", subject,
                "assetId", asset,
                "decision", decision,
                "timestamp", at.toString());
    }

    /** DlapLogEvent (changeType=ACCESS) shape consumed by AssetAccessedConsumer. */
    private static Map<String, Object> readEvent(String eventId, String subject, String asset, Instant at) {
        return Map.of(
                "eventId", eventId,
                "eventTime", at.toString(),
                "changeType", "ACCESS",
                "targetResource", Map.of("id", asset),
                "principal", Map.of("id", subject));
    }

    /**
     * Publishes a decision and waits until the consumer has stored it. The decision and read
     * topics are consumed by separate listeners, so without this wait a read can be processed
     * before its decision. Since RPT-03 the late decision then retracts or converts the finding
     * ({@code DriftE13IdentityMapIntegrationTest}); waiting keeps the assertions here on the
     * first-pass result.
     */
    private void publishDecisionAndAwaitStored(String asset, Map<String, Object> event) {
        kafkaTemplate.send(DlapConstants.KafkaTopic.ACCESS_DECISION, asset, event);
        String decisionId = (String) event.get("decisionId");
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(decisionRepository.existsByDecisionId(decisionId)).isTrue());
    }

    private void publishRead(String asset, Map<String, Object> event) {
        kafkaTemplate.send(DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED, asset, event);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void denyThenRead_producesEnforcementBypassFinding_andSurfacesViaApi() throws Exception {
        String asset = unique("asset");
        String subject = unique("svc");
        String decisionId = unique("dec");
        String readId = unique("evt");

        publishDecisionAndAwaitStored(asset, decisionEvent(decisionId, subject, asset, "DENY", T0.minusSeconds(30)));
        publishRead(asset, readEvent(readId, subject, asset, T0));

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                        DriftType.ENFORCEMENT_BYPASS, readId)).isTrue());

        // No time bound in the request: the call shape that failed on PostgreSQL.
        mockMvc.perform(get("/api/v1/reporting/drift/findings")
                        .param("type", "ENFORCEMENT_BYPASS")
                        .param("assetId", asset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].assetId").value(asset))
                .andExpect(jsonPath("$.content[0].subjectId").value(subject))
                .andExpect(jsonPath("$.content[0].decisionId").value(decisionId));
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void readWithNoDecision_producesUnenforcedReadFinding() throws Exception {
        String asset = unique("asset");
        String subject = unique("svc");
        String readId = unique("evt");

        publishRead(asset, readEvent(readId, subject, asset, T0));

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                        DriftType.UNENFORCED_READ, readId)).isTrue());

        mockMvc.perform(get("/api/v1/reporting/drift/findings")
                        .param("type", "UNENFORCED_READ")
                        .param("subjectId", subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].assetId").value(asset));
    }

    @Test
    void allowThenRead_producesNoFinding() {
        String asset = unique("asset");
        String subject = unique("svc");
        String enforcedRead = unique("evt-enforced");
        // Sentinel: a read with no decision on another asset. It produces a finding, and it is
        // published after the enforced read on the same single-partition topic, so once it has
        // been processed the enforced read has been processed too.
        String sentinelAsset = unique("asset");
        String sentinelRead = unique("evt-sentinel");

        publishDecisionAndAwaitStored(asset, decisionEvent(unique("dec"), subject, asset, "ALLOW", T0.minusSeconds(30)));
        publishRead(asset, readEvent(enforcedRead, subject, asset, T0));
        publishRead(sentinelAsset, readEvent(sentinelRead, subject, sentinelAsset, T0));

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                        DriftType.UNENFORCED_READ, sentinelRead)).isTrue());

        assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                DriftType.UNENFORCED_READ, enforcedRead)).isFalse();
        assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                DriftType.ENFORCEMENT_BYPASS, enforcedRead)).isFalse();
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void redeliveredRead_isIdempotent() throws Exception {
        String asset = unique("asset");
        String subject = unique("svc");
        String readId = unique("evt");
        String sentinelAsset = unique("asset");
        String sentinelRead = unique("evt-sentinel");

        Map<String, Object> read = readEvent(readId, subject, asset, T0);
        publishRead(asset, read);
        publishRead(asset, read);
        publishRead(sentinelAsset, readEvent(sentinelRead, subject, sentinelAsset, T0));

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                        DriftType.UNENFORCED_READ, sentinelRead)).isTrue());

        mockMvc.perform(get("/api/v1/reporting/drift/findings").param("assetId", asset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * RTR-01 (Q-824): a duplicate of a decision has no second effect. A read with no decision is an
     * UNENFORCED_READ; the first DENY turns it into one ENFORCEMENT_BYPASS; two more copies of the DENY
     * (a producer killed mid-batch leaves them) store no second decision, write no second finding and
     * count no second finding. The sentinel decision is published after the copies on the same
     * single-partition topic, so once it is stored the copies have been processed.
     */
    @Test
    @WithMockUser(roles = "AUDITOR")
    void redeliveredDecision_isIdempotent() throws Exception {
        String asset = unique("asset");
        String subject = unique("svc");
        String readId = unique("evt");
        String decisionId = unique("dec");
        String sentinelAsset = unique("asset");

        publishRead(asset, readEvent(readId, subject, asset, T0));
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                        DriftType.UNENFORCED_READ, readId)).isTrue());

        Map<String, Object> deny = decisionEvent(decisionId, subject, asset, "DENY", T0.minusSeconds(30));
        publishDecisionAndAwaitStored(asset, deny);
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(driftFindingRepository.existsByDriftTypeAndReadEventId(
                        DriftType.ENFORCEMENT_BYPASS, readId)).isTrue());
        double bypassesAfterFirstCopy = meterRegistry.counter(
                DriftDetectionOrchestrator.DRIFT_FINDINGS_COUNTER, "type", DriftType.ENFORCEMENT_BYPASS.name()).count();

        kafkaTemplate.send(DlapConstants.KafkaTopic.ACCESS_DECISION, asset, deny);
        kafkaTemplate.send(DlapConstants.KafkaTopic.ACCESS_DECISION, asset, deny);
        publishDecisionAndAwaitStored(sentinelAsset,
                decisionEvent(unique("dec-sentinel"), subject, sentinelAsset, "ALLOW", T0.minusSeconds(30)));

        assertThat(decisionRepository.findAll().stream().filter(d -> decisionId.equals(d.getDecisionId())).count())
                .as("one stored decision for three copies").isEqualTo(1);
        mockMvc.perform(get("/api/v1/reporting/drift/findings").param("assetId", asset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ENFORCEMENT_BYPASS"));
        assertThat(meterRegistry.counter(DriftDetectionOrchestrator.DRIFT_FINDINGS_COUNTER,
                "type", DriftType.ENFORCEMENT_BYPASS.name()).count())
                .as("no second finding counted").isEqualTo(bypassesAfterFirstCopy);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void findingsEndpoint_isForbiddenToAViewer() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/drift/findings"))
                .andExpect(status().isForbidden());
    }
}
