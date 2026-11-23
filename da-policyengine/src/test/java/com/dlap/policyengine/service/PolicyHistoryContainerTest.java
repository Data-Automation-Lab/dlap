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

package com.dlap.policyengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.ChainVerification;
import com.dlap.policyengine.approval.AuditDatabase;
import com.dlap.policyengine.approval.AuditKeyLookup;
import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.approval.GovernanceAudit;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.PolicyChangeType;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.model.PolicyVersionView;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AUD-02a end to end on the real schemas: {@link PolicyService} on JPA over Flyway V1 to V9 in
 * {@code da_policyengine}, and the real AUD-01 audit log in {@code dlap_common}, booted from the
 * platform init scripts and written as {@code da_policyengine_user}, the one writer role of
 * {@code govern.policy}. Each service call is its own committed transaction, as in the running service.
 * The last test runs the independent verifier {@code scripts/audit-verify.sh} over the database. Requires
 * Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PolicyService.class, PolicyHistoryService.class, PolicyMapper.class, PolicyDraftMapper.class,
        PolicyDslParser.class, PolicyDslTranslator.class, GovernanceAudit.class,
        PolicyHistoryContainerTest.AuditBeans.class})
class PolicyHistoryContainerTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOC = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: p, name: p}\n"
            + "spec:\n"
            + "  triggers: [{type: event, lifecycle: created}]\n"
            + "  action: {type: addLabel, parameters: {label: Prod}}\n";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AuditDatabase.newPostgres();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url("da_policyengine"));
        registry.add("spring.datasource.username", () -> "dlap");
        registry.add("spring.datasource.password", () -> "dlap");
    }

    /** The audit beans of ApprovalConfiguration, with the writer as a spy so one test can break it. */
    @TestConfiguration
    static class AuditBeans {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        AuditLogWriter auditLogWriter(Clock clock) {
            return Mockito.spy(new AuditLogWriter(commonAsWriter(), clock));
        }

        @Bean
        AuditKeyLookup auditKeyLookup() {
            return new AuditKeyLookup(commonAsWriter());
        }
    }

    @Autowired
    private PolicyService service;

    @Autowired
    private PolicyHistoryService history;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private AuditLogWriter writer;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void restore() {
        reset(writer);
        SecurityContextHolder.clearContext();
    }

    private static String url(String database) {
        return AuditDatabase.url(POSTGRES, database);
    }

    private static DataSource commonAsWriter() {
        return AuditDatabase.commonAs(POSTGRES, "da_policyengine_user");
    }

    private static DataSource commonAsSuperuser() {
        return AuditDatabase.commonAsSuperuser(POSTGRES);
    }

    private static PolicyInputDTO input(String name, String description) {
        PolicyInputDTO dto = new PolicyInputDTO();
        dto.setName(name);
        dto.setDescription(description);
        dto.setPolicyDocument(DOC);
        dto.setStatus(PolicyStatus.DISABLED);
        dto.setActions(Map.of("addLabel", "Stale", "days", 400));
        return dto;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static JsonNode record(AuditEntry entry) throws IOException {
        return JSON.readTree(entry.record());
    }

    private static AuditEntry entryAt(long seq) {
        return new AuditLogReader(commonAsSuperuser()).entry(AuditStreams.GOVERN_POLICY, seq).orElseThrow();
    }

    /* ----------------------------------------------------------------- immutable versions */

    @Test
    void policyVersionsAreImmutable() {
        UUID id = service.createPolicy(input("immutable-" + UUID.randomUUID(), "first text"), ALICE).getId();
        service.updatePolicy(id, input(policies.findById(id).orElseThrow().getName(), "second text"), BOB);
        PolicyVersionView v1 = history.version(id, 1);
        JdbcTemplate jdbc = jdbc();

        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".policy_versions SET name = 'x' WHERE policy_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("policy_versions rows are immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".policy_versions WHERE policy_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable (DELETE refused)");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + SCHEMA + ".policy_versions"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable (TRUNCATE refused)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".policy_versions (id, policy_id, version, "
                + "change_type, name, status, deleted, snapshot, content_sha256, changed_by, changed_by_kind, "
                + "changed_at, audit_seq, audit_entry_hash) VALUES (?, ?, 5, 'UPDATED', 'n', 'DISABLED', false, '{}', ?, "
                + "'x', 'USER', now(), 1, ?)", UUID.randomUUID(), id, "a".repeat(64), "b".repeat(64)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("does not follow version 2");

        assertThat(history.version(id, 1)).isEqualTo(v1);
        assertThat(v1.description()).isEqualTo("first text");
        assertThat(history.version(id, 2).description()).isEqualTo("second text");
        String snapshot = jdbc.queryForObject("SELECT snapshot FROM " + SCHEMA
                + ".policy_versions WHERE policy_id = ? AND version = 1", String.class, id);
        assertThat(GovernanceAudit.sha256(snapshot)).isEqualTo(v1.contentSha256());
    }

    /* ----------------------------------------------------------------- soft delete */

    @Test
    void aSoftDeletedPolicyKeepsItsHistory() {
        String name = "soft-" + UUID.randomUUID();
        UUID id = service.createPolicy(input(name, "to be deleted"), ALICE).getId();
        service.setPolicyStatus(id, PolicyStatus.ENABLED, ALICE);
        SecurityContextHolder.getContext().setAuthentication(jwt(BOB));

        service.deletePolicy(id);

        assertThat(policies.findById(id)).as("hidden from every policy query").isEmpty();
        assertThat(policies.findByName(name)).isEmpty();
        assertThatThrownBy(() -> service.getPolicyById(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deletePolicy(id)).isInstanceOf(ResourceNotFoundException.class);
        List<PolicyVersionView> versions = history.versions(id);
        assertThat(versions).extracting(PolicyVersionView::changeType).containsExactly(PolicyChangeType.CREATED,
                PolicyChangeType.STATUS_CHANGED, PolicyChangeType.DELETED);
        assertThat(versions.get(2).deleted()).isTrue();
        assertThat(versions.get(2).status()).isEqualTo(PolicyStatus.DISABLED);
        assertThat(versions.get(2).changedBy()).isEqualTo(BOB.toString());
        assertThat(history.version(id, 3).policyDocument()).isEqualTo(DOC);

        JdbcTemplate jdbc = jdbc();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, deleted_at, deleted_by_user_id, current_version FROM "
                + SCHEMA + ".policies WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DISABLED");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("deleted_by_user_id")).isEqualTo(BOB);
        assertThat(row.get("current_version")).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".policies WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("soft deleted");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".policies SET deleted_at = NULL WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("is deleted and cannot change");

        UUID again = service.createPolicy(input(name, "a new policy under the freed name"), ALICE).getId();
        assertThat(again).isNotEqualTo(id);
        assertThat(history.versions(again)).hasSize(1);
    }

    /* ----------------------------------------------------------------- chained, verified */

    @Test
    void everyPolicyChangeIsChainedAsTheWriterRoleAndTheChainVerifies() throws Exception {
        UUID id = service.createPolicy(input("chained-" + UUID.randomUUID(), "chained"), ALICE).getId();
        service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);
        SecurityContextHolder.getContext().setAuthentication(jwt(BOB));
        service.deletePolicy(id);

        List<PolicyVersionView> versions = history.versions(id);
        assertThat(versions).hasSize(3);
        for (PolicyVersionView v : versions) {
            AuditEntry entry = entryAt(v.auditSeq());
            JsonNode r = record(entry);
            assertThat(entry.entryHash()).isEqualTo(v.auditEntryHash());
            assertThat(r.path("eventType").asText()).isEqualTo(v.changeType().eventType());
            assertThat(r.path("target").path("id").asText()).isEqualTo(id.toString());
            assertThat(r.path("details").path("version").asInt()).isEqualTo(v.version());
            assertThat(r.path("details").path("contentSha256").asText()).isEqualTo(v.contentSha256());
            assertThat(r.path("actor").path("id").asText()).isEqualTo(v.changedBy());
            assertThat(entry.record()).doesNotContain("addLabel");
        }
        assertThat(record(entryAt(versions.get(1).auditSeq())).path("details").path("previousContentSha256").asText())
                .isEqualTo(versions.get(0).contentSha256());

        ChainVerification verification = new AuditChainVerifier().verify(AuditStreams.GOVERN_POLICY,
                new AuditLogReader(commonAsSuperuser()), List.of());
        assertThat(verification.isValid()).as(verification.toString()).isTrue();

        AuditDatabase.Script script = AuditDatabase.auditVerify(POSTGRES);
        assertThat(script.exit()).as(script.output()).isZero();
        assertThat(script.output()).contains("govern.policy");
    }

    /* ----------------------------------------------------------------- fail closed */

    @Test
    void aPolicyChangeTheAuditLogCannotRecordChangesNothing() {
        String name = "unrecorded-" + UUID.randomUUID();
        UUID id = service.createPolicy(input(name, "recorded"), ALICE).getId();
        doThrow(new AuditException("connection refused", null)).when(writer).append(any(), any());

        assertThatThrownBy(() -> service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB))
                .isInstanceOf(AuditUnavailableException.class);
        assertThatThrownBy(() -> service.createPolicy(input(name + "-2", "never stored"), ALICE))
                .isInstanceOf(AuditUnavailableException.class);

        assertThat(policies.findById(id).orElseThrow().getStatus()).isEqualTo(PolicyStatus.DISABLED);
        assertThat(policies.findById(id).orElseThrow().getCurrentVersion()).isEqualTo(1);
        assertThat(history.versions(id)).hasSize(1);
        assertThat(policies.findByName(name + "-2")).isEmpty();
    }

    /* ----------------------------------------------------------------- baseline */

    @Test
    void aPolicyFromBeforeHistoryGetsABaselineOnItsFirstChange() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO " + SCHEMA + ".policies (id, name, status, policy_kind, policy_document, created_at, "
                + "updated_at) VALUES (?, ?, 'DISABLED', 'DSL', ?, now() - interval '9 days', now() - interval '3 days')", id,
                "legacy-" + id, DOC);

        service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);

        List<PolicyVersionView> versions = history.versions(id);
        assertThat(versions).extracting(PolicyVersionView::changeType)
                .containsExactly(PolicyChangeType.BASELINE, PolicyChangeType.STATUS_CHANGED);
        assertThat(versions.get(0).changedByKind()).isEqualTo("SYSTEM");
        assertThat(versions.get(0).status()).isEqualTo(PolicyStatus.DISABLED);
        assertThat(versions.get(1).status()).isEqualTo(PolicyStatus.ENABLED);
    }

    /* ----------------------------------------------------------------- transaction boundaries */

    @Test
    void aVersionIsOnlyEverRecordedInsideTheTransactionOfItsChange() {
        UUID id = service.createPolicy(input("bounded-" + UUID.randomUUID(), "bounded"), ALICE).getId();
        com.dlap.policyengine.model.Policy p = policies.findById(id).orElseThrow();

        assertThatThrownBy(() -> history.prepare(p, PolicyChangeType.UPDATED, BOB))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(history.now()).as("the clock is readable without a transaction, for error bodies").isNotNull();
        assertThat(history.versions(id)).hasSize(1);
    }

    /* ----------------------------------------------------------------- helpers */

    private static org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt(UUID sub) {
        org.springframework.security.oauth2.jwt.Jwt token = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("t").header("alg", "none").subject(sub.toString()).build();
        return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_POLICY_MANAGER")));
    }
}
