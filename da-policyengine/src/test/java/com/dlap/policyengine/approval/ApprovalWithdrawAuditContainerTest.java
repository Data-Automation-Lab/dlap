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

package com.dlap.policyengine.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.ChainVerification;
import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * APPR-03 against the real AUD-01 schema: a withdrawal lands in the {@code govern.approval} stream of
 * {@code dlap_common.audit_log} as {@code da_policyengine_user}, the stream's one writer role, forms a
 * verified hash chain, and a retried withdrawal adds no second entry. The audit database is booted from
 * the platform init scripts (00 to 02), exactly as the compose stack does. Requires Docker.
 */
@Testcontainers
class ApprovalWithdrawAuditContainerTest {

    /** Same list the compose files pass to the init script (ADR-0037). */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";
    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Caller ROBOT = new Caller("sub-robot", "svc-autodelete", Set.of("ROLE_SERVICE_ACCOUNT"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    private static DataSource policyEngineDb;
    private static DataSource commonAsWriter;
    private static DataSource commonAsSuperuser;

    @BeforeAll
    static void connect() {
        String base = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/";
        policyEngineDb = new DriverManagerDataSource(base + "da_policyengine", "dlap", "dlap");
        Flyway.configure().dataSource(policyEngineDb).schemas(SCHEMA).locations("classpath:db/migration").load()
                .migrate();
        commonAsWriter = new DriverManagerDataSource(ApprovalAudit.commonUrl("", base + "da_policyengine"),
                "da_policyengine_user", "da_policyengine_pass");
        commonAsSuperuser = new DriverManagerDataSource(base + "dlap_common", "dlap", "dlap");
    }

    @Test
    void aWithdrawalIsAppendedToTheApprovalStreamAsTheStreamsWriterRoleAndTheChainVerifies() throws Exception {
        ApprovalService service = service();
        UUID first = request("purge-r-1");
        UUID second = request("purge-r-2");

        service.withdraw(first, "Withdrawn: soft-delete record r-1 was restored", "sub-alice", ROBOT);
        service.withdraw(second, "Withdrawn: soft-delete record r-2 was restored", "sub-bob", ROBOT);

        AuditLogReader reader = new AuditLogReader(commonAsSuperuser);
        ChainVerification verification = new AuditChainVerifier().verify(AuditStreams.GOVERN_APPROVAL, reader, List.of());
        assertThat(verification.isValid()).as(verification.toString()).isTrue();
        List<AuditEntry> entries = reader.page(AuditStreams.GOVERN_APPROVAL, 0, 100);
        AuditEntry mine = entries.stream().filter(e -> e.record().contains("\"id\":\"" + first + "\""))
                .findFirst().orElseThrow();
        assertThat(entries).extracting(AuditEntry::record).anyMatch(r -> r.contains(second.toString()));
        JsonNode record = JSON.readTree(mine.record());
        assertThat(record.path("eventType").asText()).isEqualTo("govern.approval.withdrawn");
        assertThat(record.path("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(record.path("actor").path("kind").asText()).isEqualTo("SERVICE");
        assertThat(record.path("actor").path("id").asText()).isEqualTo("sub-robot");
        assertThat(record.path("target").path("kind").asText()).isEqualTo("APPROVAL_REQUEST");
        assertThat(record.path("target").path("id").asText()).isEqualTo(first.toString());
        assertThat(record.path("correlationId").asText()).isEqualTo(first.toString());
        assertThat(Instant.parse(record.path("occurredAt").asText())).isEqualTo(T0);
        assertThat(record.path("details").path("onBehalfOf").asText()).isEqualTo("sub-alice");
        assertThat(record.path("details").path("reasonSha256").asText()).hasSize(64);
        assertThat(mine.record()).as("the reason text stays out of the chain").doesNotContain("restored");
    }

    @Test
    void aRetriedAppendOfTheSameWithdrawalReturnsTheFirstEntry() {
        ApprovalService service = service();
        UUID id = request("purge-r-3");
        service.withdraw(id, "Withdrawn: soft-delete record r-3 was restored", "sub-alice", ROBOT);
        AuditLogReader reader = new AuditLogReader(commonAsSuperuser);
        int before = reader.page(AuditStreams.GOVERN_APPROVAL, 0, 100).size();

        ApprovalRequest withdrawn = new ApprovalStore(policyEngineDb, JSON, SCHEMA).find(id).orElseThrow();
        AuditEntry again = auditOnly().withdrawn(withdrawn);

        assertThat(reader.page(AuditStreams.GOVERN_APPROVAL, 0, 100)).hasSize(before);
        assertThat(reader.page(AuditStreams.GOVERN_APPROVAL, 0, 100)).contains(again);
    }

    private ApprovalAudit auditOnly() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("auditLogWriter", new AuditLogWriter(commonAsWriter, Clock.fixed(T0, java.time.ZoneOffset.UTC)));
        return new ApprovalAudit(beans.getBeanProvider(AuditLogWriter.class));
    }

    private ApprovalService service() {
        PolicyRepository policies = mock(PolicyRepository.class);
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        when(policies.findById(any())).thenReturn(Optional.empty());
        when(producer.actionsEnabled()).thenReturn(true);
        Clock clock = Clock.fixed(T0, java.time.ZoneOffset.UTC);
        return new ApprovalService(new ApprovalStore(policyEngineDb, JSON, SCHEMA), ApprovalProperties.defaults(),
                policies, producer, JSON, clock, new SimpleMeterRegistry(),
                new DataSourceTransactionManager(policyEngineDb), DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
                auditOnly());
    }

    private UUID request(String key) {
        return service().create(new ApprovalService.CreateCommand("HARD_DELETE", "delete", ASSET, null, null, null,
                Map.of("softDeleteRecordId", key), "soft-delete window ended", null, key, "sub-alice"), ROBOT)
                .detail().request().id();
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
