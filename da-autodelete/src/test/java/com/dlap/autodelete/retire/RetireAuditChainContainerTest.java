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

package com.dlap.autodelete.retire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.ChainVerification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * RET-02 against the real AUD-01 schema: the executor's records land in the {@code retire.delete}
 * stream of {@code dlap_common.audit_log} as {@code da_autodelete_user}, form one verified hash
 * chain, carry canonical asset URIs and machine reasons, and the service cannot write another
 * service's stream. Requires Docker; the database is bootstrapped by the platform init scripts
 * (00 to 02), exactly as the compose stack does.
 */
@Testcontainers
class RetireAuditChainContainerTest {

    /** Same list the compose files pass to the init script (ADR-0037). */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";
    private static final Instant WHEN = Instant.parse("2026-11-01T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    private static RetireAuditDatabase db;

    @BeforeAll
    static void connect() {
        String url = RetireAuditDatabase.commonUrlNextTo("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/da_autodelete");
        db = new RetireAuditDatabase(url, "da_autodelete_user", "da_autodelete_pass", 2);
    }

    @AfterAll
    static void close() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void executedAndRefusedActionsFormOneVerifiedChainInTheDeleteStream() throws IOException {
        RetireAuditLog audit = new RetireAuditLog(new AuditLogWriter(db.dataSource(), Clock.fixed(WHEN, ZoneOffset.UTC)));
        String record = UUID.randomUUID().toString();

        audit.record(RetireAuditLog.SOFT_DELETED, WHEN, RetireAuditLog.SERVICE, "GS://Dlap-GCP-E2E", AuditOutcome.SUCCEEDED,
                record, Map.of("approvers", List.of(Approvals.ALICE, Approvals.BOB), "windowDays", 30L,
                        "windowClamped", false));
        audit.refused(WHEN, RetireAuditLog.SERVICE, "gs://dlap-gcp-e2e", record, "hard_delete",
                RefusalReason.COVERAGE_NOT_FULL, "read coverage is PARTIAL; a destructive action needs FULL",
                Map.of("readCoverage", "PARTIAL"));
        audit.record(RetireAuditLog.APPLY, WHEN, RetireAuditLog.SERVICE, "gs://dlap-gcp-e2e", AuditOutcome.REQUESTED,
                record, Map.of("mode", "NATIVE_RULE", "dryRun", false));
        audit.record(RetireAuditLog.APPLY, WHEN, RetireAuditLog.SERVICE, "gs://dlap-gcp-e2e", AuditOutcome.SUCCEEDED,
                record, Map.of("mode", "NATIVE_RULE", "dryRun", false));
        audit.record(RetireAuditLog.RESTORED, WHEN, AuditActor.user(Approvals.CAROL), "gs://dlap-gcp-e2e",
                AuditOutcome.SUCCEEDED, record, null);

        AuditLogReader reader = new AuditLogReader(db.dataSource());
        ChainVerification verification = new AuditChainVerifier().verify(AuditStreams.RETIRE_DELETE, reader, List.of());
        assertThat(verification.isValid()).as(verification.toString()).isTrue();
        assertThat(verification.entriesChecked()).isEqualTo(5);

        List<AuditEntry> entries = reader.page(AuditStreams.RETIRE_DELETE, 0, 10);
        ObjectMapper json = new ObjectMapper();
        JsonNode first = json.readTree(entries.get(0).record());
        assertThat(first.path("eventType").asText()).isEqualTo("retire.delete.soft_deleted");
        assertThat(first.path("target").path("id").asText()).as("canonical URI").isEqualTo("gs://dlap-gcp-e2e");
        assertThat(first.path("correlationId").asText()).isEqualTo(record);
        assertThat(first.path("details").path("approvers").get(1).asText()).isEqualTo(Approvals.BOB);
        JsonNode refusal = json.readTree(entries.get(1).record());
        assertThat(refusal.path("outcome").asText()).isEqualTo("DENIED");
        assertThat(refusal.path("details").path("reason").asText()).isEqualTo("coverage-not-full");
        assertThat(refusal.path("details").path("stage").asText()).isEqualTo("hard_delete");
        assertThat(json.readTree(entries.get(4).record()).path("actor").path("kind").asText()).isEqualTo("USER");
        assertThat(entries.get(1).prevHash()).isEqualTo(entries.get(0).entryHash());
    }

    @Test
    void theDeleteServiceCannotWriteAnotherServicesStream() {
        AuditLogWriter writer = new AuditLogWriter(db.dataSource(), Clock.fixed(WHEN, ZoneOffset.UTC));
        AuditEvent event = AuditEvent.builder("retire.archive.executed").occurredAt(WHEN).actor(RetireAuditLog.SERVICE)
                .outcome(AuditOutcome.SUCCEEDED).build();
        assertThatThrownBy(() -> writer.append(AuditStreams.RETIRE_ARCHIVE, event)).isInstanceOf(AuditException.class);
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
                        postgres.withCopyFileToContainer(MountableFile.forHostPath(script, mode),
                                "/docker-entrypoint-initdb.d/" + name);
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
        throw new IllegalStateException("deployment/platform/database/init-scripts not found above "
                + System.getProperty("user.dir"));
    }
}
