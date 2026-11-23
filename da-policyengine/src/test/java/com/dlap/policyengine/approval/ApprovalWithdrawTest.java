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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditOutcome;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * APPR-03 (Q-676) acceptance on a real PostgreSQL with Flyway V7 and V8: a service account
 * withdraws a pending request on behalf of a person; nobody else can; only a pending request can be
 * withdrawn; a withdrawn request can be neither approved nor released; the withdrawal is audited
 * and a failed audit append undoes it (docs/specs/approvals.md sections 4, 5 and 8).
 */
@Testcontainers
class ApprovalWithdrawTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REASON = "Withdrawn: soft-delete record r-1 was restored";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final Caller ALICE = new Caller("sub-alice", "alice", Set.of("ROLE_POLICY_MANAGER"));
    private static final Caller BOB = new Caller("sub-bob", "bob", Set.of("ROLE_CDO"));
    private static final Caller CAROL = new Caller("sub-carol", "carol", Set.of("ROLE_ADMIN"));
    private static final Caller DAVE = new Caller("sub-dave", "dave", Set.of("ROLE_DATA_ENGINEER"));
    private static final Caller ROBOT = new Caller("sub-robot", "svc-autodelete", Set.of("ROLE_SERVICE_ACCOUNT"));

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final PolicyActionProducer producer = mock(PolicyActionProducer.class);
    private final AuditLogWriter auditWriter = mock(AuditLogWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ApprovalServiceTest.MutableClock clock = new ApprovalServiceTest.MutableClock(T0);
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ApprovalStore store;
    private ApprovalService service;

    @BeforeEach
    void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        store = new ApprovalStore(dataSource, JSON, SCHEMA);
        when(producer.actionsEnabled()).thenReturn(true);
        when(policies.findById(any())).thenReturn(Optional.empty());
        when(auditWriter.append(eq(AuditStreams.GOVERN_APPROVAL), any(AuditEvent.class)))
                .thenAnswer(i -> new AuditEntry(AuditStreams.GOVERN_APPROVAL, 1, "0".repeat(64), "1".repeat(64), "{}"));
        service = serviceWith(auditWriter);
    }

    private ApprovalService serviceWith(AuditLogWriter writer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (writer != null) {
            beans.addBean("auditLogWriter", writer);
        }
        return new ApprovalService(store, ApprovalProperties.defaults(), policies, producer, JSON, clock, meters,
                new DataSourceTransactionManager(dataSource), DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
                new ApprovalAudit(beans.getBeanProvider(AuditLogWriter.class)));
    }

    private static PolicyActionEvent fire(String fireId) {
        return new PolicyActionEvent("v1", "retire-stale", UUID.randomUUID().toString(), "Retire stale", fireId,
                T0.toString(), "schedule", null, "2026-11-01T02:00:00Z", null, null, ASSET.toString(),
                "gs://prod-customer-exports", "GCP", "GCS_BUCKET", "delete", Map.of("softDeleteWindowDays", 0), false,
                false, Map.of("assetId", ASSET.toString(), "cloudProvider", "GCP"));
    }

    /** A hard delete held for approval by a policy fire. */
    private ApprovalRequest heldHardDelete() {
        assertThat(service.hold(fire("fire-1"), ActionClass.HARD_DELETE)).isTrue();
        return store.findByKey("fire:fire-1").orElseThrow();
    }

    /** The hard delete an executor asks for through the API, for a person, as the delete executor does. */
    private ApprovalRequest requestedByRobotForAlice() {
        return service.create(new ApprovalService.CreateCommand("HARD_DELETE", "delete", ASSET, null, null, null,
                Map.of("softDeleteRecordId", "r-1"), "soft-delete window ended", null, "purge-r-1", "sub-alice"),
                ROBOT).detail().request();
    }

    private List<ApprovalStore.OutboxMessage> outbox() {
        return store.unsent(100);
    }

    /** Runs the call and returns the refusal it throws; fails when it does not throw one. */
    private static ApprovalException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApprovalException e) {
            return e;
        }
        throw new AssertionError("the call was not refused");
    }

    /* ----------------------------------------------------------------- the happy path */

    @Test
    void aServiceAccountWithdrawsAPendingRequest() throws Exception {
        ApprovalRequest r = requestedByRobotForAlice();
        clock.advance(Duration.ofMinutes(5));

        ApprovalService.Detail d = service.withdraw(r.id(), "  " + REASON + "  ", " sub-alice ", ROBOT);

        ApprovalRequest after = d.request();
        assertThat(after.status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(after.status().executable()).isFalse();
        assertThat(after.withdrawnBy()).isEqualTo("sub-robot");
        assertThat(after.onBehalfOf()).isEqualTo("sub-alice");
        assertThat(after.withdrawReason()).isEqualTo(REASON);
        assertThat(after.withdrawnAt()).as("the injected clock, not the wall clock").isEqualTo(T0.plusSeconds(300));
        assertThat(after.resolvedAt()).isEqualTo(after.withdrawnAt());
        assertThat(after.approvals()).isZero();
        assertThat(after.dispatchStatus()).isEqualTo(ApprovalRequest.DISPATCH_NONE);
        assertThat(d.decisions()).as("a withdrawal is not a decision").isEmpty();
        assertThat(store.find(r.id()).orElseThrow()).isEqualTo(after);
        assertThat(meters.get(ApprovalService.WITHDRAWN_COUNTER).tag("actionClass", "HARD_DELETE").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get(ApprovalService.RESOLVED_COUNTER).tag("status", "WITHDRAWN").counter().count())
                .isEqualTo(1.0);

        List<ApprovalStore.OutboxMessage> out = outbox();
        assertThat(out).extracting(ApprovalStore.OutboxMessage::kind).containsExactly("REQUESTED", "DECIDED");
        Map<String, Object> decided = JSON.readValue(out.get(1).payload(), new TypeReference<Map<String, Object>>() { });
        assertThat(decided).containsEntry("status", "WITHDRAWN").containsEntry("executable", false)
                .containsEntry("withdrawnBy", "sub-robot").containsEntry("onBehalfOf", "sub-alice")
                .containsEntry("withdrawnAt", T0.plusSeconds(300).toString());
        assertThat(decided.toString()).as("the free-text reason never goes on a topic").doesNotContain("restored");
    }

    @Test
    void aWithdrawalIsNotAnApprovalSoTheSelfApprovalRuleDoesNotApply() {
        // The person is the requester and the robot is the submitter: both are excluded from deciding,
        // yet the robot withdraws for the requester, and a withdrawal needs no approver role or MFA marker.
        ApprovalRequest r = requestedByRobotForAlice();
        assertThat(r.excludedSubjects()).containsExactlyInAnyOrder("sub-alice", "sub-robot");

        ApprovalService.Detail d = service.withdraw(r.id(), REASON, "sub-alice", ROBOT);

        assertThat(d.request().status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(meters.find(ApprovalService.REFUSED_COUNTER).counters()).isEmpty();
    }

    @Test
    void aPolicyFireRequestCanBeWithdrawnToo() {
        ApprovalRequest r = heldHardDelete();
        assertThat(service.withdraw(r.id(), REASON, "sub-alice", ROBOT).request().status())
                .isEqualTo(ApprovalStatus.WITHDRAWN);
        // The one-open-request-per-policy rule is released: the next fire asks again.
        assertThat(service.hold(fire("fire-2"), ActionClass.HARD_DELETE)).isTrue();
    }

    @Test
    void aRequestWithOneApprovalAlreadyInStaysWithdrawable() {
        ApprovalRequest r = heldHardDelete();
        service.decide(r.id(), DecisionKind.APPROVE, "first of two", ALICE);

        ApprovalService.Detail d = service.withdraw(r.id(), REASON, "sub-bob", ROBOT);

        assertThat(d.request().status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(d.request().approvals()).isEqualTo(1);
        assertThat(d.decisions()).extracting(ApprovalDecision::approverSub).containsExactly("sub-alice");
    }

    /* ----------------------------------------------------------------- who may withdraw */

    @Test
    void noUserTokenWithdrawsEvenAnAdministrator() {
        ApprovalRequest r = heldHardDelete();
        for (Caller user : List.of(CAROL, ALICE, BOB, DAVE,
                new Caller("sub-auditor", null, Set.of("ROLE_AUDITOR")))) {
            ApprovalException e = refusal(() -> service.withdraw(r.id(), REASON, "sub-x", user));
            assertThat(e.status()).as(user.subject()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.reason()).isEqualTo(ApprovalException.SERVICE_ACCOUNT_ONLY);
        }
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        verify(auditWriter, never()).append(any(), any());
    }

    /* ----------------------------------------------------------------- only from PENDING */

    @Test
    void aDecidedOrReleasedRequestIsRefusedWith409AndItsStatus() {
        ApprovalRequest approved = heldHardDelete();
        service.decide(approved.id(), DecisionKind.APPROVE, "one", ALICE);
        service.decide(approved.id(), DecisionKind.APPROVE, "two", BOB);

        ApprovalRequest glass = service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET, null,
                null, null, null, "cold", null, "k-glass", null), DAVE).detail().request();
        service.breakGlass(glass.id(), "ransomware spreading through this bucket", CAROL);

        ApprovalRequest rejected = service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET,
                null, null, null, null, "cold", null, "k-rej", null), DAVE).detail().request();
        service.decide(rejected.id(), DecisionKind.REJECT, "no", ALICE);

        ApprovalRequest expired = service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET,
                null, null, null, null, "cold", null, "k-exp", null), DAVE).detail().request();
        clock.advance(Duration.ofDays(8));
        service.expireOverdue();

        for (Map.Entry<ApprovalRequest, ApprovalStatus> c : Map.of(approved, ApprovalStatus.APPROVED,
                glass, ApprovalStatus.APPROVED_BREAK_GLASS, rejected, ApprovalStatus.REJECTED,
                expired, ApprovalStatus.EXPIRED).entrySet()) {
            ApprovalRequest before = store.find(c.getKey().id()).orElseThrow();
            ApprovalException e = refusal(() -> service.withdraw(before.id(), REASON, "sub-alice", ROBOT));
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.reason()).isEqualTo(ApprovalException.NOT_PENDING);
            assertThat(e.currentStatus()).isEqualTo(c.getValue().name());
            assertThat(store.find(before.id()).orElseThrow()).as("nothing changed").isEqualTo(before);
        }
        // The decisions above are chained since AUD-02a; no withdrawal is.
        verify(auditWriter, never()).append(any(), org.mockito.ArgumentMatchers.argThat(
                e -> ApprovalAudit.WITHDRAWN_EVENT.equals(e.eventType())));
    }

    @Test
    void aWithdrawnRequestCannotBeWithdrawnAgain() {
        ApprovalRequest r = heldHardDelete();
        service.withdraw(r.id(), REASON, "sub-alice", ROBOT);
        ApprovalRequest first = store.find(r.id()).orElseThrow();

        ApprovalException e = refusal(() -> service.withdraw(r.id(), "Withdrawn again by another run", "sub-bob", ROBOT));

        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.currentStatus()).isEqualTo("WITHDRAWN");
        assertThat(store.find(r.id()).orElseThrow()).isEqualTo(first);
        verify(auditWriter, times(1)).append(any(), any());
    }

    @Test
    void aMissingRequestIs404() {
        ApprovalException e = refusal(() -> service.withdraw(UUID.randomUUID(), REASON, "sub-alice", ROBOT));
        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.reason()).isEqualTo(ApprovalException.NOT_FOUND);
    }

    @Test
    void aPendingRequestPastItsTimeIsExpiredNotWithdrawn() {
        ApprovalRequest r = heldHardDelete();
        clock.advance(Duration.ofDays(7));

        ApprovalException e = refusal(() -> service.withdraw(r.id(), REASON, "sub-alice", ROBOT));

        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.reason()).isEqualTo(ApprovalException.EXPIRED);
        assertThat(e.currentStatus()).isEqualTo("EXPIRED");
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        verify(auditWriter, never()).append(any(), any());
    }

    @Test
    void aWithdrawalAndARejectionRacingHaveOneWinner() throws Exception {
        ApprovalRequest r = heldHardDelete();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> withdraw = pool.submit(() -> {
                start.await();
                try {
                    return service.withdraw(r.id(), REASON, "sub-alice", ROBOT).request().status();
                } catch (ApprovalException e) {
                    return e.currentStatus();
                }
            });
            Future<Object> reject = pool.submit(() -> {
                start.await();
                try {
                    return service.decide(r.id(), DecisionKind.REJECT, "no", ALICE).request().status();
                } catch (ApprovalException e) {
                    return e.reason();
                }
            });
            Object w = withdraw.get();
            Object d = reject.get();
            ApprovalStatus end = store.find(r.id()).orElseThrow().status();
            assertThat(end).isIn(ApprovalStatus.WITHDRAWN, ApprovalStatus.REJECTED);
            if (end == ApprovalStatus.WITHDRAWN) {
                assertThat(w).isEqualTo(ApprovalStatus.WITHDRAWN);
                assertThat(d).isEqualTo(ApprovalException.NOT_PENDING);
            } else {
                assertThat(w).isEqualTo("REJECTED");
                assertThat(d).isEqualTo(ApprovalStatus.REJECTED);
            }
            assertThat(outbox().stream().filter(m -> "DECIDED".equals(m.kind()))).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /* ----------------------------------------------------------------- a withdrawn request is dead */

    @Test
    void aWithdrawnRequestCanBeNeitherApprovedNorRejectedNorReleased() {
        ApprovalRequest r = heldHardDelete();
        service.withdraw(r.id(), REASON, "sub-alice", ROBOT);

        for (Runnable attempt : List.<Runnable>of(
                () -> service.decide(r.id(), DecisionKind.APPROVE, "too late", BOB),
                () -> service.decide(r.id(), DecisionKind.REJECT, "too late", BOB),
                () -> service.breakGlass(r.id(), "ransomware spreading through this bucket", CAROL))) {
            ApprovalException e = refusal(attempt);
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.reason()).isEqualTo(ApprovalException.NOT_PENDING);
        }
        ApprovalRequest after = store.find(r.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(after.status().executable()).isFalse();
        assertThat(ApprovalApi.ApprovalView.of(service.get(r.id())).executable()).isFalse();
        assertThat(store.decisions(r.id())).isEmpty();
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind)
                .as("no ACTION message: the fire is never released").containsExactly("REQUESTED", "DECIDED");
        assertThat(service.inbox(BOB, 0, 50).rows()).as("it leaves every inbox").isEmpty();
    }

    /* ----------------------------------------------------------------- the body */

    @Test
    void aShortReasonOrNoPersonIsABadRequestAndChangesNothing() {
        ApprovalRequest r = heldHardDelete();
        for (String[] bad : new String[][] {{"too short", "sub-alice"}, {"         ", "sub-alice"}, {null, "sub-alice"},
                {REASON, null}, {REASON, "   "}, {REASON, "x".repeat(256)}}) {
            ApprovalException e = refusal(() -> service.withdraw(r.id(), bad[0], bad[1], ROBOT));
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.reason()).isEqualTo(ApprovalException.INVALID);
        }
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(service.withdraw(r.id(), "ten chars!", "sub-alice", ROBOT).request().withdrawReason())
                .as("exactly ten characters are enough").isEqualTo("ten chars!");
    }

    /* ----------------------------------------------------------------- the audit entry */

    @Test
    void theWithdrawalIsAuditedInTheApprovalStream() {
        ApprovalRequest r = requestedByRobotForAlice();
        clock.advance(Duration.ofMinutes(2));

        service.withdraw(r.id(), REASON, "sub-alice", ROBOT);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, times(1)).append(eq(AuditStreams.GOVERN_APPROVAL), event.capture());
        AuditEvent e = event.getValue();
        assertThat(e.eventType()).isEqualTo("govern.approval.withdrawn");
        assertThat(e.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(e.actor()).isEqualTo(AuditActor.service("sub-robot"));
        assertThat(e.target().kind()).isEqualTo("APPROVAL_REQUEST");
        assertThat(e.target().id()).isEqualTo(r.id().toString());
        assertThat(e.occurredAt()).as("business time from the injected clock").isEqualTo(T0.plusSeconds(120));
        assertThat(e.correlationId()).isEqualTo(r.id().toString());
        assertThat(e.idempotencyKey()).isEqualTo(r.id() + ":withdrawn");
        assertThat(e.details()).containsEntry("approvalId", r.id().toString()).containsEntry("actionClass", "HARD_DELETE")
                .containsEntry("actionType", "delete").containsEntry("assetId", ASSET.toString())
                .containsEntry("previousStatus", "PENDING").containsEntry("onBehalfOf", "sub-alice")
                .containsEntry("reasonSha256", ApprovalAudit.sha256(REASON));
        assertThat(e.details().toString()).as("no free text in the immutable chain").doesNotContain("restored");
    }

    @Test
    void ifTheAuditLogRefusesTheEntryTheWithdrawalDoesNotHappen() {
        when(auditWriter.append(any(), any())).thenThrow(new AuditException("audit database is down", null));
        ApprovalRequest r = heldHardDelete();

        ApprovalException e = refusal(() -> service.withdraw(r.id(), REASON, "sub-alice", ROBOT));

        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE);
        ApprovalRequest after = store.find(r.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(after.withdrawnBy()).isNull();
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind).containsExactly("REQUESTED");
    }

    @Test
    void withoutAnAuditWriterAWithdrawalFailsClosed() {
        ApprovalService noAudit = serviceWith(null);
        ApprovalRequest r = heldHardDelete();

        ApprovalException e = refusal(() -> noAudit.withdraw(r.id(), REASON, "sub-alice", ROBOT));

        assertThat(e.reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE);
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void theNineArgumentConstructorHasNoAuditWriterAndFailsClosed() {
        ApprovalService legacy = new ApprovalService(store, ApprovalProperties.defaults(), policies, producer, JSON,
                clock, meters, new DataSourceTransactionManager(dataSource),
                DlapConstants.KafkaTopic.POLICY_ACTION_EVENT);
        ApprovalRequest r = heldHardDelete();

        assertThatThrownBy(() -> legacy.withdraw(r.id(), REASON, "sub-alice", ROBOT))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE));
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void theCommonDatabaseUrlIsDerivedFromTheServiceUrl() {
        assertThat(ApprovalAudit.commonUrl("", "jdbc:postgresql://db:5432/da_policyengine?sslmode=require"))
                .isEqualTo("jdbc:postgresql://db:5432/dlap_common?sslmode=require");
        assertThat(ApprovalAudit.commonUrl(null, "jdbc:postgresql://db:5432/da_policyengine"))
                .isEqualTo("jdbc:postgresql://db:5432/dlap_common");
        assertThat(ApprovalAudit.commonUrl(" ", "")).isEqualTo("jdbc:postgresql://localhost:5432/dlap_common");
        assertThat(ApprovalAudit.commonUrl("jdbc:postgresql://audit:5432/x", "jdbc:postgresql://db/y"))
                .isEqualTo("jdbc:postgresql://audit:5432/x");
    }

    @Test
    void theWithdrawalColumnsAreEmptyForARequestThatWasNotWithdrawn() {
        ApprovalRequest r = heldHardDelete();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".approval_requests WHERE id = ? "
                + "AND withdrawn_by IS NULL AND on_behalf_of IS NULL AND withdraw_reason IS NULL "
                + "AND withdrawn_at IS NULL", Integer.class, r.id())).isEqualTo(1);
    }
}
