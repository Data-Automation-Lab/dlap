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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Flyway V7 and V8 on a real PostgreSQL: decision rows are immutable, a request keeps its identity and
 * moves once out of PENDING, and the hard-delete floor of two approvers holds in the database too
 * (docs/specs/approvals.md, "Storage").
 */
@Testcontainers
class ApprovalSchemaTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private JdbcTemplate jdbc;
    private ApprovalStore store;

    @BeforeEach
    void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        store = new ApprovalStore(ds, new ObjectMapper(), SCHEMA);
    }

    private ApprovalRequest request(ActionClass actionClass, int required) {
        return new ApprovalRequest(UUID.randomUUID(), "api:test:" + UUID.randomUUID(), ApprovalRequest.ORIGIN_API,
                actionClass, "delete", Map.of("softDeleteWindowDays", 0), UUID.randomUUID(), "gs://b", "GCP",
                "GCS_BUCKET", null, null, null, null, "sub-req", ApprovalRequest.REQUESTER_USER, "sub-req",
                List.of("sub-req"), "why", required, 0, ApprovalStatus.PENDING, T0, T0.plusSeconds(3600), null, null,
                ApprovalRequest.DISPATCH_NONE, null);
    }

    private ApprovalRequest pendingWithDecision() {
        ApprovalRequest r = request(ActionClass.HARD_DELETE, 2);
        assertThat(store.insertRequest(r)).isTrue();
        store.insertDecision(new ApprovalDecision(UUID.randomUUID(), r.id(), "sub-a", "a", List.of("ROLE_CDO"),
                DecisionKind.APPROVE, "ok", T0));
        return r;
    }

    @Test
    void v7CreatesTheApprovalTablesAndV8AddsTheWithdrawal() {
        Integer v7 = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history "
                + "WHERE version = '7' AND description = 'approvals' AND success", Integer.class);
        Integer v8 = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".flyway_schema_history "
                + "WHERE version = '8' AND description = 'approval withdrawal' AND success", Integer.class);
        assertThat(v7).isEqualTo(1);
        assertThat(v8).isEqualTo(1);
    }

    @Test
    void aWithdrawnRequestKeepsWhoForWhomWhyAndWhenAndNeverChangesAgain() {
        ApprovalRequest r = pendingWithDecision();
        assertThat(store.withdraw(r.id(), T0.plusSeconds(60), "sub-robot", "sub-alice", "restored by the owner"))
                .isTrue();

        ApprovalRequest after = store.find(r.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(after.withdrawnBy()).isEqualTo("sub-robot");
        assertThat(after.onBehalfOf()).isEqualTo("sub-alice");
        assertThat(after.withdrawReason()).isEqualTo("restored by the owner");
        assertThat(after.withdrawnAt()).isEqualTo(T0.plusSeconds(60)).isEqualTo(after.resolvedAt());
        assertThat(store.withdraw(r.id(), T0.plusSeconds(90), "sub-other", "sub-bob", "withdrawn a second time"))
                .as("only a pending row moves").isFalse();
        for (String change : List.of("withdrawn_by = 'sub-other'", "on_behalf_of = 'sub-bob'",
                "withdraw_reason = 'a different reason'", "withdrawn_at = withdrawn_at + interval '1 minute'",
                "status = 'APPROVED'", "status = 'PENDING', resolved_at = NULL")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_requests SET " + change
                    + " WHERE id = ?", r.id())).as(change).isInstanceOf(DataAccessException.class);
        }
        assertThat(store.find(r.id()).orElseThrow()).isEqualTo(after);
    }

    @Test
    void theDatabaseRefusesAWithdrawnStateWithoutItsFactsAndTheFactsWithoutTheState() {
        ApprovalRequest incomplete = pendingWithDecision();
        // WITHDRAWN through the generic transition names nobody and no reason.
        assertThatThrownBy(() -> store.resolve(incomplete.id(), ApprovalStatus.WITHDRAWN, 0, T0.plusSeconds(5),
                ApprovalRequest.DISPATCH_NONE)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_requests_withdrawal");
        // A reason shorter than ten characters.
        assertThatThrownBy(() -> store.withdraw(incomplete.id(), T0.plusSeconds(5), "sub-robot", "sub-alice", "short"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_requests_withdrawal");
        // The facts on a request that is not withdrawn.
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_requests SET status = 'REJECTED', "
                + "resolved_at = now(), withdrawn_by = 'sub-robot' WHERE id = ?", incomplete.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_requests_withdrawal");
        assertThat(store.find(incomplete.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void aWithdrawnRequestCannotBeDeletedOrTruncated() {
        ApprovalRequest r = pendingWithDecision();
        store.withdraw(r.id(), T0.plusSeconds(60), "sub-robot", "sub-alice", "restored by the owner");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".approval_requests WHERE id = ?", r.id()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("cannot be deleted");
    }

    @Test
    void migrationV8RunsAgainOnADatabaseThatHasItAlready() throws Exception {
        String script = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V8__approval_withdrawal.sql"));
        // One statement string, so the search path and the script share a connection.
        String rerun = "SET search_path TO " + SCHEMA + ";\n" + script;
        jdbc.execute(rerun);
        jdbc.execute(rerun);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'approval_requests' AND column_name IN ('withdrawn_by', 'on_behalf_of', "
                + "'withdraw_reason', 'withdrawn_at')", Integer.class, SCHEMA)).isEqualTo(4);
    }

    @Test
    void decisionRowsCannotBeUpdated() {
        ApprovalRequest r = pendingWithDecision();
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_decisions SET comment = 'edited' "
                + "WHERE request_id = ?", r.id()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("immutable");
        assertThat(store.decisions(r.id())).extracting(ApprovalDecision::comment).containsExactly("ok");
    }

    @Test
    void decisionRowsCannotBeDeleted() {
        ApprovalRequest r = pendingWithDecision();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".approval_decisions WHERE request_id = ?",
                r.id())).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("immutable");
        assertThat(store.decisions(r.id())).hasSize(1);
    }

    @Test
    void decisionsAndRequestsCannotBeTruncated() {
        pendingWithDecision();
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + SCHEMA + ".approval_decisions"))
                .isInstanceOf(DataAccessException.class).rootCause()
                .hasMessageContaining("approval_decisions rows are immutable (TRUNCATE refused)");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + SCHEMA + ".approval_requests CASCADE"))
                .isInstanceOf(DataAccessException.class).rootCause()
                .hasMessageContaining("approval_requests rows are immutable (TRUNCATE refused)");
    }

    @Test
    void aPersonDecidesARequestOnce() {
        ApprovalRequest r = pendingWithDecision();
        assertThatThrownBy(() -> store.insertDecision(new ApprovalDecision(UUID.randomUUID(), r.id(), "sub-a", "a",
                List.of("ROLE_CDO"), DecisionKind.APPROVE, "again", T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmptyCommentIsRefusedByTheDatabase() {
        ApprovalRequest r = pendingWithDecision();
        assertThatThrownBy(() -> store.insertDecision(new ApprovalDecision(UUID.randomUUID(), r.id(), "sub-b", "b",
                List.of("ROLE_CDO"), DecisionKind.REJECT, "  ", T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theIdentityOfARequestNeverChanges() {
        ApprovalRequest r = pendingWithDecision();
        for (String change : List.of("required_approvals = 1", "action_class = 'ARCHIVE'", "requested_by = 'x'",
                "excluded_subjects = ARRAY['x']", "expires_at = expires_at + interval '1 day'",
                "asset_id = gen_random_uuid()", "reason = 'other'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_requests SET " + change
                    + " WHERE id = ?", r.id())).as(change).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".approval_requests WHERE id = ?", r.id()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("cannot be deleted");
    }

    @Test
    void aTerminalRequestCannotBeReopenedOrRecounted() {
        ApprovalRequest r = pendingWithDecision();
        assertThat(store.resolve(r.id(), ApprovalStatus.REJECTED, 1, T0.plusSeconds(60),
                ApprovalRequest.DISPATCH_NONE)).isTrue();

        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_requests SET status = 'PENDING', "
                + "resolved_at = NULL WHERE id = ?", r.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_requests SET status = 'APPROVED' "
                + "WHERE id = ?", r.id())).isInstanceOf(DataAccessException.class);
        assertThat(store.resolve(r.id(), ApprovalStatus.APPROVED, 2, T0, ApprovalRequest.DISPATCH_NONE))
                .as("the store's own transition only moves a pending row").isFalse();
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void theApprovalCountOnlyGrows() {
        ApprovalRequest r = pendingWithDecision();
        store.countApproval(r.id(), 1);
        assertThatThrownBy(() -> store.countApproval(r.id(), 0)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aHardDeleteNeverNeedsFewerThanTwoApprovalsInTheDatabase() {
        assertThatThrownBy(() -> store.insertRequest(request(ActionClass.HARD_DELETE, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_requests_required");
        assertThat(store.insertRequest(request(ActionClass.ARCHIVE, 1))).isTrue();
    }

    @Test
    void theRequesterIsAlwaysExcluded() {
        ApprovalRequest r = request(ActionClass.ARCHIVE, 1);
        ApprovalRequest notExcluded = new ApprovalRequest(r.id(), r.requestKey(), r.origin(), r.actionClass(),
                r.actionType(), r.actionParameters(), r.assetId(), r.assetUri(), r.provider(), r.serviceType(),
                r.policyDbId(), r.policyId(), r.policyName(), r.fireId(), r.requestedBy(), r.requesterKind(),
                r.submittedBy(), List.of("someone-else"), r.reason(), r.requiredApprovals(), 0, r.status(),
                r.createdAt(), r.expiresAt(), null, null, r.dispatchStatus(), null);
        assertThatThrownBy(() -> store.insertRequest(notExcluded)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_approval_requests_requester_excluded");
    }

    @Test
    void aSentDispatchIsFinal() {
        ApprovalRequest r = pendingWithDecision();
        store.resolve(r.id(), ApprovalStatus.APPROVED, 2, T0, ApprovalRequest.DISPATCH_QUEUED);
        store.markDispatched(r.id(), T0.plusSeconds(1));
        assertThat(store.find(r.id()).orElseThrow().dispatchStatus()).isEqualTo(ApprovalRequest.DISPATCH_SENT);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".approval_requests SET dispatch_status = 'QUEUED' "
                + "WHERE id = ?", r.id())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void theSchemaNameIsValidatedBeforeAnyStatementIsBuilt() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        for (String bad : List.of("dlap; drop table x", "Dlap", "", "a.b", "1abc")) {
            assertThatThrownBy(() -> new ApprovalStore(ds, new ObjectMapper(), bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
