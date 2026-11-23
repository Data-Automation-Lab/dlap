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

package com.dlap.autodelete.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.retire.RetireAuditLog;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * RTR-01 (Q-824): a duplicate of a delete event has no second effect on {@link DeletionProofListener}'s
 * state. Delivery on the typed delete topic is at least once (log-event.md section 5.5). The listener,
 * the recorder and the repositories are the service's own on PostgreSQL 15.1 with the module's Flyway
 * schema; only the audit log (its writer is covered by the audit chain tests) and the clock are test
 * doubles. The clock moves between deliveries, so a second application would show in the timestamps.
 * Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = DeletionProofListenerDuplicateContainerTest.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class DeletionProofListenerDuplicateContainerTest {

    private static final Instant APPLIED = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant EVENT = Instant.parse("2026-12-03T01:00:00Z");
    private static final Instant FIRST_DELIVERY = Instant.parse("2026-12-03T01:00:05Z");

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(FIRST_DELIVERY);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** JPA, the repositories, the recorder and the listener; the audit log and the clock are doubles. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.autodelete.model", "com.dlap.autodelete.domain"})
    @EnableJpaRepositories(basePackageClasses = SoftDeleteRecordRepository.class)
    @Import({ProofRecorder.class, DeletionProofListener.class})
    static class Config {

        @Bean
        MovableClock clock() {
            return new MovableClock();
        }

        @Bean
        RetireAuditLog audit() {
            return mock(RetireAuditLog.class);
        }

        @Bean
        RetireProperties retireProperties() {
            return RetireProperties.defaults();
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
            .withDatabaseName("dlap")
            .withUsername("dlap")
            .withPassword("dlap")
            .withEnv("POSTGRES_MULTIPLE_DATABASES", "da_autodelete")
            .withCopyFileToContainer(MountableFile.forHostPath(initScript(), 0755),
                    "/docker-entrypoint-initdb.d/00-create-multiple-databases.sh");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DeletionProofListenerDuplicateContainerTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "da_autodelete_user");
        registry.add("spring.datasource.password", () -> "da_autodelete_pass");
    }

    @Autowired
    private DeletionProofListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovableClock clock;

    @Autowired
    private RetireAuditLog audit;

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();
    private final Acknowledgment ack = mock(Acknowledgment.class);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dlap_autodelete.retire_dispositions");
        jdbc.update("DELETE FROM dlap_autodelete.soft_delete_records");
        clock.set(FIRST_DELIVERY);
        org.mockito.Mockito.clearInvocations(audit, ack);
    }

    /** An applied S3 lifecycle rule on a bucket, waiting for the provider's proof. */
    private UUID awaitingRule(String bucketUri, String mode, String platform) {
        UUID record = UUID.randomUUID();
        jdbc.update("INSERT INTO dlap_autodelete.soft_delete_records (id, asset_id, queued_at, scheduled_hard_delete_at,"
                + " status, cloud_provider, asset_uri, next_check_at) VALUES (?, ?, ?, ?, 'AWAITING_PROOF', 'AWS', ?, ?)",
                record, UUID.randomUUID(), java.sql.Timestamp.from(APPLIED.minus(Duration.ofDays(30))),
                java.sql.Timestamp.from(APPLIED), bucketUri, java.sql.Timestamp.from(APPLIED.plus(Duration.ofDays(1))));
        jdbc.update("INSERT INTO dlap_autodelete.retire_dispositions (id, soft_delete_record_id, asset_id, target_uri,"
                + " cloud_provider, mode, platform, rule_id, rule_document, status, dry_run, approval_id, planned_at,"
                + " applied_at, proof_due_at, updated_at) VALUES (?, ?, ?, ?, 'AWS', ?, ?,"
                + " 'dlap-retire-x', ?, 'AWAITING_PROOF', false, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), record, UUID.randomUUID(), bucketUri, mode, platform, "{\"bucket\":\"b\",\"lifecycle\":{\"rule\":[]}}",
                UUID.randomUUID(), java.sql.Timestamp.from(APPLIED), java.sql.Timestamp.from(APPLIED),
                java.sql.Timestamp.from(APPLIED.plus(Duration.ofDays(1))), java.sql.Timestamp.from(APPLIED));
        return record;
    }

    private DlapLogEvent lifecycleDelete(String eventId, String uri) {
        return DlapLogEvent.newBuilder()
                .setEventId(eventId)
                .setSourceProvider(SourceProvider.AWS)
                .setChangeType(ChangeType.DELETE)
                .setResultStatus(ResultStatus.SUCCESS)
                .setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setSourceService("s3lifecycle.amazonaws.com")
                .setSourceFormat("AWS_CLOUDTRAIL/s3-file")
                .setOperation("DeleteObject")
                .setEventTime(Timestamp.newBuilder().setSeconds(EVENT.getEpochSecond()))
                .setTargetResource(TargetResource.newBuilder().setCanonicalUri(uri))
                .build();
    }

    private void deliver(Object value) {
        listener.onDeleted(new ConsumerRecord<>("dlap.discovery.asset.deleted", 0, 0L, "k", value), ack);
    }

    private Map<String, Object> state(UUID record) {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("record", jdbc.queryForMap("SELECT * FROM dlap_autodelete.soft_delete_records WHERE id = ?", record));
        state.put("disposition", jdbc.queryForMap(
                "SELECT * FROM dlap_autodelete.retire_dispositions WHERE soft_delete_record_id = ?", record));
        return state;
    }

    @Test
    void aDuplicateOfTheProofEventChangesNothingAndAuditsNothing() throws Exception {
        UUID record = awaitingRule("s3://dlap-aws-e2e", "NATIVE_RULE", "S3_LIFECYCLE");
        DlapLogEvent proof = lifecycleDelete("rtr01-proof-1", "s3://dlap-aws-e2e/k/a.parquet");

        deliver(codec.toMap(proof));
        Map<String, Object> afterFirst = state(record);
        assertThat(afterFirst.get("disposition")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "EXECUTED").containsEntry("proof_event_id", "rtr01-proof-1");
        assertThat(afterFirst.get("record")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "RULE_IN_FORCE");

        // The copies arrive later, in both wire forms; each one finds the disposition no longer waiting.
        clock.set(FIRST_DELIVERY.plus(Duration.ofHours(3)));
        deliver(codec.toMap(proof));
        deliver(codec.encode(proof));
        clock.set(FIRST_DELIVERY.plus(Duration.ofDays(2)));
        deliver(codec.toMap(proof));

        assertThat(state(record)).as("three more copies change no column of either row").isEqualTo(afterFirst);
        verify(audit, times(1)).record(eq(RetireAuditLog.EXECUTED), eq(EVENT), any(), any(), eq(AuditOutcome.SUCCEEDED),
                any(), any());
        verify(ack, times(4)).acknowledge();
    }

    @Test
    void aDuplicateOfAnObjectDeleteProofLeavesTheRowHardDeletedOnce() throws Exception {
        UUID record = awaitingRule("s3://dlap-aws-e2e/k/b.parquet", "DIRECT", "ENGINE_AWS");
        DlapLogEvent proof = lifecycleDelete("rtr01-proof-2", "s3://dlap-aws-e2e/k/b.parquet");

        deliver(codec.toMap(proof));
        Map<String, Object> afterFirst = state(record);
        clock.set(FIRST_DELIVERY.plus(Duration.ofHours(1)));
        deliver(codec.toMap(proof));

        assertThat(afterFirst.get("disposition")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "EXECUTED");
        assertThat(afterFirst.get("record")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "HARD_DELETED");
        assertThat(state(record)).isEqualTo(afterFirst);
        verify(audit, times(1)).record(eq(RetireAuditLog.EXECUTED), any(), any(), any(), any(), any(), any());
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_autodelete";
    }

    private static Path initScript() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts/00-create-multiple-databases.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("00-create-multiple-databases.sh not found above " + System.getProperty("user.dir"));
    }
}
