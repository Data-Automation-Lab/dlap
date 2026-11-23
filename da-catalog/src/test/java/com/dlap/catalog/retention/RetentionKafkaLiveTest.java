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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.catalog.DaCatalogApplication;
import com.dlap.common.constants.DlapConstants;
import com.dlap.proto.log.v1.BindingScope;
import com.dlap.proto.log.v1.BindingState;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;

/**
 * Live check of the retention wiring on real infrastructure (plan critique item 18: where a mock
 * cannot show a feature, run it): the whole da-catalog application context against a Testcontainers
 * PostgreSQL 15.1 with the platform init scripts and a real Kafka broker (KRaft, in the test JVM).
 * Nothing in the retention path is mocked: the shipped listener container factory (manual
 * acknowledgment, error handler, dead-letter topic), the real {@code KafkaTemplate} and the
 * scheduled, ShedLock-guarded publisher.
 *
 * <p>It proves what the unit and container tests cannot: that a {@code setRetention} record on
 * {@code dlap.policies.actions} becomes a rule and an evaluation, that the resulting event arrives
 * on {@code dlap.catalog.asset.retention-due} keyed by the asset URI, that a binding status and its
 * tombstone are read with the record key, and that a record that is not a binding status ends on the
 * {@code .DLT} topic. Requires Docker.
 */
@SpringBootTest(classes = DaCatalogApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "dlap.catalog.retention.publish.initial-delay=PT1S",
        "dlap.catalog.retention.publish.interval=PT1S",
        "logging.level.org.apache.kafka=ERROR",
        "logging.level.org.springframework.kafka=WARN"})
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
        DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE})
class RetentionKafkaLiveTest {

    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/dlap_common");
        registry.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    private UUID seedAsset(String uri, String created) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dlap_common.dlap_assets (asset_id, asset_uri, cloud_provider, service_type, "
                        + "asset_name, status, cloud_created_at, discovered_at) VALUES (?, ?, 'GCP', 'BIGQUERY_TABLE', "
                        + "?, 'ACTIVE', ?, ?)", id, uri, uri, Instant.parse(created).atOffset(ZoneOffset.UTC),
                Instant.parse(created).atOffset(ZoneOffset.UTC));
        return id;
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "live-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /** Polls until a record matching the key shows up, or fails after the timeout. */
    private ConsumerRecord<String, String> awaitRecord(String topic, java.util.function.Predicate<ConsumerRecord<String,
            String>> match, Duration timeout) {
        try (KafkaConsumer<String, String> consumer = consumer(topic)) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(r)) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no matching record on " + topic + " within " + timeout);
    }

    @Test
    void aSetRetentionRecordBecomesARuleAnEvaluationAndAnEventOnTheRetentionDueTopic() throws Exception {
        UUID asset = seedAsset("bq://live/ds/due", "2020-01-01T00:00:00Z");
        UUID policy = UUID.randomUUID();
        String action = "{\"schemaVersion\":\"v1\",\"policyId\":\"retain-short\",\"policyDbId\":\"" + policy + "\","
                + "\"actionType\":\"setRetention\",\"assetId\":\"" + asset + "\","
                + "\"firedAt\":\"2026-10-20T10:00:00Z\","
                + "\"actionParameters\":{\"retentionDays\":10,\"basis\":\"CREATED\"}}";

        kafka.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset.toString(), action).get();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT retention_status FROM dlap_common.dlap_assets WHERE asset_id = ?", String.class, asset))
                .isEqualTo("DUE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.retention_rule WHERE asset_id = ? "
                + "AND policy_id = ? AND source = 'POLICY'", Long.class, asset, policy)).isEqualTo(1);

        ConsumerRecord<String, String> record = awaitRecord(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE,
                r -> "bq://live/ds/due".equals(r.key()), Duration.ofSeconds(60));
        JsonNode event = JSON.readTree(record.value());
        assertThat(event.get("schemaVersion").asText()).isEqualTo("v1");
        assertThat(event.get("assetId").asText()).isEqualTo(asset.toString());
        assertThat(event.get("previousStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(event.get("status").asText()).isEqualTo("DUE");
        assertThat(event.get("retentionSource").asText()).isEqualTo("POLICY");
        assertThat(event.get("retentionPolicyId").asText()).isEqualTo(policy.toString());
        assertThat(event.get("retentionDays").asInt()).isEqualTo(10);
        assertThat(event.get("expiresAt").asText()).isEqualTo("2020-01-11T00:00:00Z");
        assertThat(event.get("legalHold").asBoolean()).isFalse();
        assertThat(event.get("eventId").asText()).hasSize(64);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dlap_catalog.retention_outbox WHERE published_at IS NOT NULL AND payload::jsonb "
                        + "->> 'assetId' = ?", Long.class, asset.toString())).isEqualTo(1));
        // The publisher job ran under its ShedLock row.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_catalog.shedlock WHERE name = ?", Long.class,
                RetentionDuePublisher.LOCK_NAME)).isEqualTo(1);
    }

    @Test
    void aBindingStatusOnTheTopicIsStoredByKeyAndATombstoneRemovesIt() throws Exception {
        String bindingId = "live-binding-" + UUID.randomUUID();
        Instant reported = Instant.parse("2026-10-20T10:00:00Z");
        LogBindingStatus status = LogBindingStatus.newBuilder()
                .setBindingId(bindingId)
                .setSourceProvider(SourceProvider.GCP)
                .setSourceKind("GCP_CLOUD_AUDIT")
                .setScope(BindingScope.newBuilder().setScopeType("GCP_PROJECT").setScopeId("live-project"))
                .setState(BindingState.BINDING_STATE_ACTIVE)
                .setReportedTime(Timestamp.newBuilder().setSeconds(reported.getEpochSecond()))
                .setLowWatermark(Timestamp.newBuilder().setSeconds(reported.getEpochSecond() - 60))
                .setReadCoverage(com.dlap.proto.log.v1.ReadCoverage.READ_COVERAGE_CAPTURED)
                .setReadCoverageSince(Timestamp.newBuilder().setSeconds(reported.getEpochSecond() - 86_400))
                .setSchemaVersion("v1")
                .build();

        kafka.send(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, bindingId,
                JsonFormat.printer().omittingInsignificantWhitespace().print(status)).get();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc.queryForMap(
                "SELECT read_coverage, scope_id, source_provider FROM dlap_catalog.log_binding_status "
                        + "WHERE binding_id = ?", bindingId))
                .containsEntry("read_coverage", "READ_COVERAGE_CAPTURED")
                .containsEntry("scope_id", "live-project").containsEntry("source_provider", "GCP"));

        kafka.send(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, bindingId, null).get();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dlap_catalog.log_binding_status WHERE binding_id = ?", Long.class, bindingId))
                .isZero());
    }

    @Test
    void aRecordThatIsNotABindingStatusEndsOnTheDeadLetterTopicAfterTheRetries() throws Exception {
        String poison = "this is not a binding status " + UUID.randomUUID();

        kafka.send(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, "poison-key", poison).get();

        ConsumerRecord<String, String> dead = awaitRecord(
                DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS + ".DLT", r -> poison.equals(r.value()),
                Duration.ofSeconds(90));
        assertThat(dead.key()).isEqualTo("poison-key");
    }

    private static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES);
        try (Stream<Path> scripts = Files.list(initScriptsDir())) {
            scripts.filter(Files::isRegularFile)
                    .filter(script -> !script.getFileName().toString().startsWith("03-"))
                    .sorted()
                    .forEach(script -> {
                        String name = script.getFileName().toString();
                        int mode = name.endsWith(".sh") ? 0755 : 0644;
                        postgres.withCopyFileToContainer(
                                MountableFile.forHostPath(script, mode), "/docker-entrypoint-initdb.d/" + name);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the platform init scripts", e);
        }
        return postgres;
    }

    private static Path initScriptsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("deployment/platform/database/init-scripts not found");
    }
}
