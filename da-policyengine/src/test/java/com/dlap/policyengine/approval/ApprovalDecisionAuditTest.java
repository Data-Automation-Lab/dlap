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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * AUD-02a (Q-687, Q-692): approve, reject and break-glass decisions are appended to the audit stream
 * {@code govern.approval} as the last step of their transaction, fail closed with 503
 * {@code audit-unavailable} and no decision row when the append fails, and a retry after an entry whose
 * transaction did not commit replays that entry and commits with its time. The audit chain is the
 * in-memory {@link TestAudits.FakeChain} (the real one is in GovernanceAuditChainContainerTest); the
 * approval tables are real (Flyway V1 to V9 on PostgreSQL). Requires Docker.
 */
@Testcontainers
class ApprovalDecisionAuditTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000002");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMENT = "Checked the retention schedule; the purge is due";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final Caller ALICE = new Caller("sub-alice", "alice", Set.of("ROLE_POLICY_MANAGER"));
    private static final Caller BOB = new Caller("sub-bob", "bob", Set.of("ROLE_CDO"));
    private static final Caller CAROL = new Caller("sub-carol", "carol", Set.of("ROLE_ADMIN"));
    private static final Caller ROBOT = new Caller("sub-robot", "svc-autodelete", Set.of("ROLE_SERVICE_ACCOUNT"));

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final PolicyActionProducer producer = mock(PolicyActionProducer.class);
    private final ApprovalServiceTest.MutableClock clock = new ApprovalServiceTest.MutableClock(T0);
    private TestAudits.FakeChain chain;
    private DataSource dataSource;
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
        store = new ApprovalStore(dataSource, JSON, SCHEMA);
        when(producer.actionsEnabled()).thenReturn(true);
        when(policies.findById(any())).thenReturn(Optional.empty());
        chain = new TestAudits.FakeChain(T0);
        service = serviceWith(new ApprovalAudit(chain.governance()));
    }

    private ApprovalService serviceWith(ApprovalAudit audit) {
        return new ApprovalService(store, ApprovalProperties.defaults(), policies, producer, JSON, clock,
                new SimpleMeterRegistry(), new DataSourceTransactionManager(dataSource),
                DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, audit);
    }

    private ApprovalService serviceWithWriter(AuditLogWriter writer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("auditLogWriter", writer);
        return serviceWith(new ApprovalAudit(beans.getBeanProvider(AuditLogWriter.class)));
    }

    private ApprovalRequest heldHardDelete(String fireId) {
        PolicyActionEvent fire = new PolicyActionEvent("v1", "retire-stale", UUID.randomUUID().toString(),
                "Retire stale", fireId, T0.toString(), "schedule", null, "2026-11-01T02:00:00Z", null, null,
                ASSET.toString(), "gs://prod-customer-exports", "GCP", "GCS_BUCKET", "delete",
                Map.of("softDeleteWindowDays", 0), false, false, Map.of("assetId", ASSET.toString()));
        assertThat(service.hold(fire, ActionClass.HARD_DELETE)).isTrue();
        return store.findByKey("fire:" + fireId).orElseThrow();
    }

    private ApprovalRequest archiveRequest(String key) {
        return service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET, null, null, null,
                Map.of(), "archive the cold prefix", null, key, "sub-dave"), ROBOT).detail().request();
    }

    private static ApprovalException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApprovalException e) {
            return e;
        }
        throw new AssertionError("the call was not refused");
    }

    private List<String> outboxKinds(UUID id) {
        return store.unsent(100).stream().filter(m -> m.requestId().equals(id)).map(ApprovalStore.OutboxMessage::kind)
                .toList();
    }

    /* ----------------------------------------------------------------- every decision is chained */

    @Test
    void everyApprovalOfAHardDeleteIsChainedWithTheApproverAndTheCommentHashOnly() {
        ApprovalRequest r = heldHardDelete("fire-a");

        service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE);
        clock.advance(Duration.ofMinutes(3));
        ApprovalService.Detail d = service.decide(r.id(), DecisionKind.APPROVE, COMMENT + " too", BOB);

        assertThat(d.request().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(chain.appends).hasSize(2);
        AuditEvent first = chain.appends.get(0);
        AuditEvent second = chain.appends.get(1);
        assertThat(first.eventType()).isEqualTo(ApprovalAudit.DECIDED_EVENT);
        assertThat(first.actor().kind().name()).isEqualTo("USER");
        assertThat(first.actor().id()).isEqualTo("sub-alice");
        assertThat(first.target().kind()).isEqualTo(ApprovalAudit.TARGET_KIND);
        assertThat(first.target().id()).isEqualTo(r.id().toString());
        assertThat(first.correlationId()).isEqualTo(r.id().toString());
        assertThat(first.idempotencyKey()).isEqualTo(r.id() + ":decision:sub-alice");
        assertThat(first.occurredAt()).isEqualTo(T0);
        assertThat(first.details()).containsEntry("decision", "APPROVE")
                .containsEntry("actionClass", "HARD_DELETE")
                .containsEntry("requiredApprovals", 2)
                .containsEntry("commentSha256", GovernanceAudit.sha256(COMMENT))
                .containsEntry("decisionId", ApprovalAudit.decisionId(r.id(), "sub-alice").toString());
        assertThat(chain.byKey.get(first.idempotencyKey()).record()).doesNotContain("retention schedule");
        assertThat(second.actor().id()).isEqualTo("sub-bob");
        assertThat(second.occurredAt()).isEqualTo(T0.plus(Duration.ofMinutes(3)));
        assertThat(store.decisions(r.id())).extracting(ApprovalDecision::id)
                .containsExactly(ApprovalAudit.decisionId(r.id(), "sub-alice"), ApprovalAudit.decisionId(r.id(), "sub-bob"));
    }

    @Test
    void aRejectionAndABreakGlassReleaseAreChainedToo() {
        ApprovalRequest rejected = archiveRequest("arch-1");
        ApprovalRequest released = heldHardDelete("fire-b");

        service.decide(rejected.id(), DecisionKind.REJECT, "Not this prefix, it holds live exports", ALICE);
        service.breakGlass(released.id(), "Legal hold lifted, purge ordered by counsel today", CAROL);

        assertThat(chain.appends).extracting(AuditEvent::eventType)
                .containsExactly(ApprovalAudit.DECIDED_EVENT, ApprovalAudit.BREAK_GLASS_EVENT);
        assertThat(chain.appends.get(0).details()).containsEntry("decision", "REJECT");
        assertThat(chain.appends.get(1).details()).containsEntry("decision", "BREAK_GLASS")
                .containsEntry("commentSha256", GovernanceAudit.sha256("Legal hold lifted, purge ordered by counsel today"));
        assertThat(chain.appends.get(1).actor().id()).isEqualTo("sub-carol");
        assertThat(store.find(released.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED_BREAK_GLASS);
    }

    /* ----------------------------------------------------------------- fail closed */

    @Test
    void anAppendFailureOnADecisionGives503AndNoDecisionRow() {
        AuditLogWriter broken = mock(AuditLogWriter.class);
        when(broken.append(eq(AuditStreams.GOVERN_APPROVAL), any()))
                .thenThrow(new AuditException("connection refused", null));
        ApprovalRequest approve = heldHardDelete("fire-c");
        ApprovalRequest reject = archiveRequest("arch-2");
        ApprovalRequest glass = heldHardDelete("fire-d");
        List<String> outboxBefore = outboxKinds(approve.id());
        service = serviceWithWriter(broken);

        ApprovalException a = refusal(() -> service.decide(approve.id(), DecisionKind.APPROVE, COMMENT, ALICE));
        ApprovalException b = refusal(() -> service.decide(reject.id(), DecisionKind.REJECT, COMMENT, ALICE));
        ApprovalException c = refusal(() -> service.breakGlass(glass.id(),
                "Legal hold lifted, purge ordered by counsel today", CAROL));

        for (ApprovalException e : List.of(a, b, c)) {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE);
        }
        for (ApprovalRequest r : List.of(approve, reject, glass)) {
            assertThat(store.decisions(r.id())).as("no decision row for " + r.id()).isEmpty();
            ApprovalRequest after = store.find(r.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(ApprovalStatus.PENDING);
            assertThat(after.approvals()).isZero();
        }
        assertThat(outboxKinds(approve.id())).isEqualTo(outboxBefore);
        assertThat(outboxKinds(glass.id())).doesNotContain(ApprovalEvents.BREAK_GLASS, "ACTION");
    }

    @Test
    void anIllegalArgumentFromTheAppendIs503NotAClientError() {
        AuditLogWriter refusing = mock(AuditLogWriter.class);
        when(refusing.append(any(), any())).thenThrow(new IllegalArgumentException("idempotency key already used"));
        ApprovalRequest r = heldHardDelete("fire-e");
        service = serviceWithWriter(refusing);

        ApprovalException e = refusal(() -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE));

        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE);
        assertThat(store.decisions(r.id())).isEmpty();
    }

    @Test
    void withoutAnAuditWriterNoDecisionIsRecorded() {
        ApprovalRequest r = heldHardDelete("fire-f");
        service = serviceWith(new ApprovalAudit(new StaticListableBeanFactory().getBeanProvider(AuditLogWriter.class)));

        ApprovalException e = refusal(() -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE));

        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(store.decisions(r.id())).isEmpty();
    }

    /* ----------------------------------------------------------------- replay (Q-692) */

    @Test
    void aRetriedDecisionReplaysTheStoredEntryAndCommitsWithItsTime() {
        ApprovalRequest r = heldHardDelete("fire-g");
        chain.loseNextCommit();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.decisions(r.id())).as("the first attempt rolled back").isEmpty();
        assertThat(chain.byKey).containsKey(r.id() + ":decision:sub-alice");

        clock.advance(Duration.ofMinutes(10));
        ApprovalService.Detail d = service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE);

        assertThat(d.decisions()).hasSize(1);
        assertThat(d.decisions().get(0).decidedAt()).as("the time the chain states").isEqualTo(T0);
        assertThat(chain.appends).as("the retry appended nothing").hasSize(1);
        assertThat(chain.byKey).hasSize(1);
        verify(chain.lookup, org.mockito.Mockito.atLeastOnce()).find(AuditStreams.GOVERN_APPROVAL,
                r.id() + ":decision:sub-alice");
    }

    @Test
    void aRetryWithAnotherDecisionThanTheStoredOneIs503AndChangesNothing() {
        ApprovalRequest r = heldHardDelete("fire-h");
        chain.loseNextCommit();
        org.assertj.core.api.Assertions.catchThrowable(() -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE));

        ApprovalException e = refusal(() -> service.decide(r.id(), DecisionKind.REJECT, COMMENT, ALICE));

        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(store.decisions(r.id())).isEmpty();
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    /**
     * PE-01 (Q-716), the orphan entry of docs/specs/approvals.md section 8.1: the entry committed, the
     * decision's own transaction did not, and the request expired before the retry. The retry answers
     * 409 not-pending, no decision row exists, and the append-only chain keeps the entry.
     */
    @Test
    void aRequestThatExpiresBeforeTheRetryLeavesAnOrphanEntryAndTheRetryAnswers409() {
        ApprovalRequest r = heldHardDelete("fire-orphan");
        chain.loseNextCommit();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(chain.byKey).containsKey(r.id() + ":decision:sub-alice");

        clock.advance(ApprovalProperties.defaults().ttl().plus(Duration.ofMinutes(1)));
        assertThat(service.expireOverdue()).isEqualTo(1);
        ApprovalException e = refusal(() -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE));

        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.reason()).isEqualTo(ApprovalException.NOT_PENDING);
        assertThat(store.decisions(r.id())).as("no decision row").isEmpty();
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(chain.byKey).as("the orphan entry stays in the append-only chain")
                .containsOnlyKeys(r.id() + ":decision:sub-alice");
        assertThat(chain.appends).hasSize(1);
    }

    @Test
    void aReplayedWithdrawalAppendIsIdempotent() {
        ApprovalRequest r = service.create(new ApprovalService.CreateCommand("HARD_DELETE", "delete", ASSET, null,
                null, null, Map.of("softDeleteRecordId", "r-9"), "soft-delete window ended", null, "purge-r-9",
                "sub-alice"), ROBOT).detail().request();
        String reason = "Withdrawn: soft-delete record r-9 was restored";
        chain.loseNextCommit();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.withdraw(r.id(), reason, "sub-alice", ROBOT))).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        clock.advance(Duration.ofMinutes(7));
        ApprovalRequest after = service.withdraw(r.id(), reason, "sub-alice", ROBOT).request();

        assertThat(after.status()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(after.withdrawnAt()).as("the stored entry's time").isEqualTo(T0);
        assertThat(after.resolvedAt()).isEqualTo(T0);
        assertThat(chain.byKey).containsOnlyKeys(r.id() + ":withdrawn");
        assertThat(chain.appends).hasSize(1);

        ApprovalException again = refusal(() -> service.withdraw(r.id(), reason, "sub-alice", ROBOT));
        assertThat(again.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.currentStatus()).isEqualTo("WITHDRAWN");
    }

    @Test
    void aRetriedWithdrawalWithAnotherReasonIs503NotA500() {
        ApprovalRequest r = service.create(new ApprovalService.CreateCommand("HARD_DELETE", "delete", ASSET, null,
                null, null, Map.of("softDeleteRecordId", "r-8"), "soft-delete window ended", null, "purge-r-8",
                "sub-alice"), ROBOT).detail().request();
        chain.loseNextCommit();
        org.assertj.core.api.Assertions.catchThrowable(
                () -> service.withdraw(r.id(), "Withdrawn: soft-delete record r-8 was restored", "sub-alice", ROBOT));

        ApprovalException e = refusal(() -> service.withdraw(r.id(), "Withdrawn for another reason entirely",
                "sub-alice", ROBOT));

        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.reason()).isEqualTo(ApprovalException.AUDIT_UNAVAILABLE);
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void anUnreadableChainFailsTheDecisionClosed() {
        ApprovalRequest r = heldHardDelete("fire-i");
        when(chain.lookup.find(any(), any())).thenThrow(new AuditException("audit database down", null));

        ApprovalException e = refusal(() -> service.decide(r.id(), DecisionKind.APPROVE, COMMENT, ALICE));

        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(store.decisions(r.id())).isEmpty();
        verify(chain.writer, never()).append(any(), any());
    }

    @Test
    void aLongOrUnusualSubjectIsHashedInTheKey() {
        UUID id = UUID.fromString("0d4f9c40-1a2b-3c4d-8e5f-000000000002");
        assertThat(ApprovalAudit.decisionKey(id, "sub-alice")).isEqualTo(id + ":decision:sub-alice");
        String odd = ApprovalAudit.decisionKey(id, "user with spaces");
        assertThat(odd).startsWith(id + ":decision:sha256-").hasSize(36 + 10 + 7 + 32);
        assertThat(ApprovalAudit.decisionKey(id, "x".repeat(65))).contains(":decision:sha256-");
        assertThat(ApprovalAudit.decisionId(id, "sub-alice")).isEqualTo(ApprovalAudit.decisionId(id, "sub-alice"))
                .isNotEqualTo(ApprovalAudit.decisionId(id, "sub-bob"));
    }
}
