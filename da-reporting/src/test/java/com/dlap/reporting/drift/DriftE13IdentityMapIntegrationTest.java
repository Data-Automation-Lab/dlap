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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.dlap.reporting.repository.AccessDecisionRecordRepository;
import com.dlap.reporting.repository.AssetReadRecordRepository;
import com.dlap.reporting.repository.DriftFindingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Test-strategy story E13 (research/hardening/09-test-strategy.md, L6) on real infrastructure:
 * "PDP ALLOW decision followed by a read from the same principal (via the identity map): no
 * {@code UNENFORCED_READ}; without the decision, one finding."
 *
 * <p>The events carry the forms the producers really send, so the join has to do the work:
 * <ul>
 *   <li>the decision is the PDP's section 6 event, JSON text written with a
 *       {@code StringSerializer} (da-policyengine), naming the DLAP subject
 *       {@code svc-marketing-agent} and the asset as {@code bq://acme-prod/sales/<table>} plus a
 *       catalog UUID;</li>
 *   <li>the read is a {@code DlapLogEvent} in the codec's single encoding (da-discovery's router),
 *       a BigQuery data read by the service account
 *       {@code Marketing-Agent@acme-prod.iam.gserviceaccount.com} on
 *       {@code projects/acme-prod/datasets/sales/tables/<table>}, with no canonical URI set.</li>
 * </ul>
 * The identity map is seeded from a file through {@code dlap.reporting.identity-map.seed-file}
 * with the IAM member form {@code serviceAccount:marketing-agent@...}. Only the normalizer
 * (asset) and the map (principal) make the two sides meet.
 *
 * <p>PostgreSQL is a Testcontainers container (Docker must be running); the broker runs in the
 * test JVM with one partition per topic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {DlapConstants.KafkaTopic.ACCESS_DECISION, DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED})
class DriftE13IdentityMapIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    /** Event time of every read: fixed and in the past, so no wall clock can stand in for it. */
    private static final Instant T0 = Instant.parse("2026-07-06T12:00:00Z");

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final String SUBJECT = "svc-marketing-agent";
    private static final String PRINCIPAL_IN_LOG = "Marketing-Agent@acme-prod.iam.gserviceaccount.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path seedFile;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private DriftFindingRepository findingRepository;

    @Autowired
    private AccessDecisionRecordRepository decisionRepository;

    @Autowired
    private AssetReadRecordRepository readRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    private KafkaTemplate<String, String> wire;

    @BeforeAll
    static void writeSeedFile() throws IOException {
        seedFile = Files.createTempFile("rpt03-identity-map", ".csv");
        Files.writeString(seedFile, "# E13 identity map\n"
                + "provider,principal,subject\n"
                + "GCP,serviceAccount:marketing-agent@acme-prod.iam.gserviceaccount.com," + SUBJECT + "\n",
                StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteSeedFile() throws IOException {
        Files.deleteIfExists(seedFile);
    }

    @DynamicPropertySource
    static void identityMap(DynamicPropertyRegistry registry) {
        registry.add("dlap.reporting.identity-map.seed-file", () -> {
            if (seedFile == null) {
                try {
                    writeSeedFile();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return seedFile.toString();
        });
    }

    private KafkaTemplate<String, String> wire() {
        if (wire == null) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
            wire = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props,
                    new StringSerializer(), new StringSerializer()));
        }
        return wire;
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    /** The decision event as da-policyengine publishes it (enforcement-contract.md section 6). */
    private void publishDecision(String decisionId, String table, String verdict, Instant at) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("decisionId", decisionId);
        event.put("timestamp", at.toString());
        event.put("subjectType", "agent");
        event.put("subjectId", SUBJECT);
        event.put("onBehalfOf", null);
        event.put("action", "read");
        event.put("assetId", UUID.randomUUID().toString());
        event.put("assetUri", "bq://acme-prod/sales/" + table);
        event.put("decision", verdict);
        event.put("policyIds", java.util.List.of("access-marketing-sales"));
        event.put("obligations", java.util.List.of());
        event.put("defaultApplied", false);
        event.put("latencyMs", 3);
        try {
            wire().send(DlapConstants.KafkaTopic.ACCESS_DECISION, decisionId, JSON.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishDecisionAndAwaitStored(String decisionId, String table, String verdict, Instant at) {
        publishDecision(decisionId, table, verdict, at);
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(decisionRepository.existsByDecisionId(decisionId)).isTrue());
    }

    /** A BigQuery data read as the registry pipeline routes it onto the accessed topic. */
    private static DlapLogEvent.Builder bigQueryRead(String eventId, String table, String principal, Instant at) {
        return DlapLogEvent.newBuilder()
                .setEventId(eventId)
                .setEventTime(ts(at))
                .setIngestTime(ts(at.plusSeconds(90)))
                .setSourceProvider(SourceProvider.GCP)
                .setSourceService("bigquery.googleapis.com")
                .setChangeType(ChangeType.ACCESS)
                .setOperation("google.cloud.bigquery.v2.TableDataService.List")
                .setResultStatus(ResultStatus.SUCCESS)
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setParseStatus(ParseStatus.PARSE_STATUS_FULL)
                .setSourceFormat("GCP_CLOUD_AUDIT/api-proto")
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.SERVICE_ACCOUNT).setId(principal))
                .setTargetResource(TargetResource.newBuilder()
                        .setKind("bigquery_dataset")
                        .setId("projects/acme-prod/datasets/sales/tables/" + table)
                        .setProject("acme-prod"));
    }

    private void publishRead(DlapLogEvent.Builder read) {
        DlapLogEvent event = read.build();
        wire().send(DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED, event.getTargetResource().getId(),
                DlapLogEventCodec.defaults().encode(event));
    }

    private boolean hasFinding(DriftType type, String readEventId) {
        return findingRepository.existsByDriftTypeAndReadEventId(type, readEventId);
    }

    /** Publishes a read with no decision and waits for its finding: everything published before it was consumed. */
    private void drainReads() {
        String table = unique("sentinel");
        String eventId = unique("evt-sentinel");
        publishRead(bigQueryRead(eventId, table, PRINCIPAL_IN_LOG, T0));
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(hasFinding(DriftType.UNENFORCED_READ, eventId)).isTrue());
    }

    @Test
    void e13_allowThenReadByTheMappedPrincipal_producesNoUnenforcedRead() {
        String table = unique("orders");
        String readId = unique("evt");

        publishDecisionAndAwaitStored(unique("dec"), table, "ALLOW", T0.minusSeconds(30));
        publishRead(bigQueryRead(readId, table, PRINCIPAL_IN_LOG, T0));
        drainReads();

        assertThat(readRepository.existsByEventId(readId)).as("the read was consumed").isTrue();
        assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isFalse();
        assertThat(hasFinding(DriftType.ENFORCEMENT_BYPASS, readId)).isFalse();
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void e13_withoutTheDecision_theSameReadProducesExactlyOneFinding() throws Exception {
        String table = unique("orders");
        String readId = unique("evt");

        publishRead(bigQueryRead(readId, table, PRINCIPAL_IN_LOG, T0));
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isTrue());

        mockMvc.perform(get("/api/v1/reporting/drift/findings")
                        .param("assetId", "bq://acme-prod/sales/" + table))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("UNENFORCED_READ"))
                .andExpect(jsonPath("$.content[0].subjectId").value(SUBJECT))
                .andExpect(jsonPath("$.content[0].readAt").value(T0.toString()));
    }

    @Test
    void e13_readConsumedBeforeItsAllow_isRetractedWhenTheDecisionArrives() {
        String table = unique("orders");
        String readId = unique("evt");

        publishRead(bigQueryRead(readId, table, PRINCIPAL_IN_LOG, T0));
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isTrue());

        publishDecision(unique("dec"), table, "ALLOW", T0.minusSeconds(30));

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isFalse());
        assertThat(hasFinding(DriftType.ENFORCEMENT_BYPASS, readId)).isFalse();
    }

    @Test
    void e13_readConsumedBeforeItsDeny_becomesAnEnforcementBypass() {
        String table = unique("orders");
        String readId = unique("evt");
        String denyId = unique("dec");

        publishRead(bigQueryRead(readId, table, PRINCIPAL_IN_LOG, T0));
        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isTrue());

        publishDecision(denyId, table, "DENY", T0.minusSeconds(10));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            assertThat(hasFinding(DriftType.ENFORCEMENT_BYPASS, readId)).isTrue();
            assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isFalse();
        });
    }

    @Test
    void e13_aPrincipalOutsideTheMap_doesNotInheritTheAllow() {
        String table = unique("orders");
        String readId = unique("evt");

        publishDecisionAndAwaitStored(unique("dec"), table, "ALLOW", T0.minusSeconds(30));
        publishRead(bigQueryRead(readId, table, "other-agent@acme-prod.iam.gserviceaccount.com", T0));

        Awaitility.await().atMost(WAIT).untilAsserted(() ->
                assertThat(hasFinding(DriftType.UNENFORCED_READ, readId)).isTrue());
    }

    @Test
    void readWithoutEventTime_isSkippedAndCounted_notBuffered() {
        String readId = unique("evt");
        double before = skipped("missing_event_time");

        publishRead(bigQueryRead(readId, unique("orders"), PRINCIPAL_IN_LOG, T0).clearEventTime());
        drainReads();

        assertThat(readRepository.existsByEventId(readId)).isFalse();
        assertThat(skipped("missing_event_time")).isEqualTo(before + 1.0);
    }

    private double skipped(String reason) {
        return meterRegistry.find(DriftEventSkips.SKIPPED_COUNTER).tag("stream", "read").tag("reason", reason)
                .counters().stream().mapToDouble(c -> c.count()).sum();
    }
}
