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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.ChainVerification;
import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * AUD-02a against the real AUD-01 schema (Q-687, Q-692): approve, reject and break-glass decisions and
 * withdrawals land in {@code govern.approval} as {@code da_policyengine_user}; a withdrawal whose entry
 * committed while its own transaction did not is replayed on retry, with the stored time and no second
 * entry; an append the database refuses leaves no decision row; and both the Java verifier and the
 * independent {@code scripts/audit-verify.sh} pass afterwards. Requires Docker.
 */
@Testcontainers
class ApprovalDecisionAuditContainerTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000003");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Caller ALICE = new Caller("sub-alice", "alice", Set.of("ROLE_POLICY_MANAGER"));
    private static final Caller BOB = new Caller("sub-bob", "bob", Set.of("ROLE_CDO"));
    private static final Caller CAROL = new Caller("sub-carol", "carol", Set.of("ROLE_ADMIN"));
    private static final Caller ROBOT = new Caller("sub-robot", "svc-autodelete", Set.of("ROLE_SERVICE_ACCOUNT"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AuditDatabase.newPostgres();

    private static DataSource policyEngineDb;

    private final ApprovalServiceTest.MutableClock clock = new ApprovalServiceTest.MutableClock(T0);

    @BeforeAll
    static void migrate() {
        policyEngineDb = new DriverManagerDataSource(AuditDatabase.url(POSTGRES, "da_policyengine"), "dlap", "dlap");
        Flyway.configure().dataSource(policyEngineDb).schemas(SCHEMA).locations("classpath:db/migration").load()
                .migrate();
    }

    private ApprovalService service(AuditLogWriter writer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("auditLogWriter", writer);
        beans.addBean("auditKeyLookup", new AuditKeyLookup(AuditDatabase.commonAs(POSTGRES, "da_policyengine_user")));
        GovernanceAudit audit = new GovernanceAudit(beans.getBeanProvider(AuditLogWriter.class),
                beans.getBeanProvider(AuditKeyLookup.class));
        PolicyRepository policies = mock(PolicyRepository.class);
        PolicyActionProducer producer = mock(PolicyActionProducer.class);
        when(policies.findById(any())).thenReturn(Optional.empty());
        when(producer.actionsEnabled()).thenReturn(true);
        return new ApprovalService(new ApprovalStore(policyEngineDb, JSON, SCHEMA), ApprovalProperties.defaults(),
                policies, producer, JSON, clock, new SimpleMeterRegistry(), new DataSourceTransactionManager(policyEngineDb),
                DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, new ApprovalAudit(audit));
    }

    private AuditLogWriter writer() {
        return new AuditLogWriter(AuditDatabase.commonAs(POSTGRES, "da_policyengine_user"), clock);
    }

    private ApprovalRequest held(ApprovalService service, String fireId) {
        PolicyActionEvent fire = new PolicyActionEvent("v1", "retire-stale", UUID.randomUUID().toString(),
                "Retire stale", fireId, T0.toString(), "schedule", null, "2026-11-01T02:00:00Z", null, null,
                ASSET.toString(), "gs://prod-customer-exports", "GCP", "GCS_BUCKET", "delete",
                Map.of("softDeleteWindowDays", 0), false, false, Map.of("assetId", ASSET.toString()));
        assertThat(service.hold(fire, ActionClass.HARD_DELETE)).isTrue();
        return new ApprovalStore(policyEngineDb, JSON, SCHEMA).findByKey("fire:" + fireId).orElseThrow();
    }

    private ApprovalRequest forPerson(ApprovalService service, String actionClass, String key) {
        return service.create(new ApprovalService.CreateCommand(actionClass, "HARD_DELETE".equals(actionClass)
                ? "delete" : "archive", ASSET, null, null, null, Map.of("softDeleteRecordId", key), "requested",
                null, key, "sub-dave"), ROBOT).detail().request();
    }

    private static List<AuditEntry> entriesOf(UUID approvalId) {
        return new AuditLogReader(AuditDatabase.commonAsSuperuser(POSTGRES)).page(AuditStreams.GOVERN_APPROVAL, 0, 500)
                .stream().filter(e -> e.record().contains("\"correlationId\":\"" + approvalId + "\"")).toList();
    }

    private static JsonNode json(AuditEntry e) throws Exception {
        return JSON.readTree(e.record());
    }

    @Test
    void everyDecisionIsChainedAsTheWriterRoleAndBothVerifiersPass() throws Exception {
        ApprovalService service = service(writer());
        ApprovalRequest hard = held(service, "fire-real-1");
        ApprovalRequest archive = forPerson(service, "ARCHIVE", "arch-real-1");
        ApprovalRequest glass = held(service, "fire-real-2");

        service.decide(hard.id(), DecisionKind.APPROVE, "Retention ended, first approval", ALICE);
        service.decide(hard.id(), DecisionKind.APPROVE, "Retention ended, second approval", BOB);
        service.decide(archive.id(), DecisionKind.REJECT, "Keep this prefix hot for now", ALICE);
        service.breakGlass(glass.id(), "Counsel ordered the purge today, ticket LEGAL-7", CAROL);

        assertThat(entriesOf(hard.id())).hasSize(2);
        JsonNode first = json(entriesOf(hard.id()).get(0));
        assertThat(first.path("eventType").asText()).isEqualTo("govern.approval.decided");
        assertThat(first.path("actor").path("kind").asText()).isEqualTo("USER");
        assertThat(first.path("actor").path("id").asText()).isEqualTo("sub-alice");
        assertThat(first.path("details").path("decision").asText()).isEqualTo("APPROVE");
        assertThat(first.path("idempotencyKey").asText()).isEqualTo(hard.id() + ":decision:sub-alice");
        assertThat(entriesOf(hard.id()).get(0).record()).doesNotContain("Retention ended");
        assertThat(json(entriesOf(archive.id()).get(0)).path("details").path("decision").asText()).isEqualTo("REJECT");
        assertThat(json(entriesOf(glass.id()).get(0)).path("eventType").asText()).isEqualTo("govern.approval.break_glass");

        ChainVerification verification = new AuditChainVerifier().verify(AuditStreams.GOVERN_APPROVAL,
                new AuditLogReader(AuditDatabase.commonAsSuperuser(POSTGRES)), List.of());
        assertThat(verification.isValid()).as(verification.toString()).isTrue();
        AuditDatabase.Script script = AuditDatabase.auditVerify(POSTGRES);
        assertThat(script.exit()).as(script.output()).isZero();
        assertThat(script.output()).contains("govern.approval");
    }

    @Test
    void aReplayedWithdrawalAppendIsIdempotent() throws Exception {
        AuditLogWriter real = writer();
        AuditLogWriter losing = spy(real);
        // First call: the entry commits in dlap_common, then the policy engine's commit is lost.
        doAnswer(i -> {
            i.callRealMethod();
            throw new IllegalStateException("simulated: the withdrawal's own commit failed");
        }).doCallRealMethod().when(losing).append(any(), any());
        ApprovalService service = service(losing);
        ApprovalRequest r = forPerson(service, "HARD_DELETE", "purge-real-9");
        String reason = "Withdrawn: soft-delete record purge-real-9 was restored";

        assertThat(catchThrowable(() -> service.withdraw(r.id(), reason, "sub-alice", ROBOT)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(entriesOf(r.id())).as("the orphan entry").hasSize(1);
        assertThat(new ApprovalStore(policyEngineDb, JSON, SCHEMA).find(r.id()).orElseThrow().status())
                .isEqualTo(ApprovalStatus.PENDING);

        clock.advance(Duration.ofMinutes(4));
        ApprovalRequest after = service.withdraw(r.id(), reason, "sub-alice", ROBOT).request();

        assertThat(after.status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(after.withdrawnAt()).as("the stored entry's time, not the retry's").isEqualTo(T0);
        List<AuditEntry> entries = entriesOf(r.id());
        assertThat(entries).as("no second entry").hasSize(1);
        assertThat(json(entries.get(0)).path("details").path("reasonSha256").asText())
                .isEqualTo(GovernanceAudit.sha256(reason));
        ChainVerification verification = new AuditChainVerifier().verify(AuditStreams.GOVERN_APPROVAL,
                new AuditLogReader(AuditDatabase.commonAsSuperuser(POSTGRES)), List.of());
        assertThat(verification.isValid()).as(verification.toString()).isTrue();
    }

    @Test
    void anAppendTheDatabaseRefusesLeavesNoDecisionRow() {
        // da_catalog_user is not the writer role of govern.approval: the insert trigger refuses it.
        AuditLogWriter wrongRole = new AuditLogWriter(AuditDatabase.commonAs(POSTGRES, "da_catalog_user"), clock);
        ApprovalService refusing = service(wrongRole);
        ApprovalRequest r = held(refusing, "fire-real-3");

        Throwable t = catchThrowable(() -> refusing.decide(r.id(), DecisionKind.APPROVE, "approve it", ALICE));

        assertThat(t).isInstanceOf(ApprovalException.class);
        assertThat(((ApprovalException) t).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(((ApprovalException) t).reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE);
        ApprovalStore store = new ApprovalStore(policyEngineDb, JSON, SCHEMA);
        assertThat(store.decisions(r.id())).isEmpty();
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(entriesOf(r.id())).isEmpty();
    }
}
