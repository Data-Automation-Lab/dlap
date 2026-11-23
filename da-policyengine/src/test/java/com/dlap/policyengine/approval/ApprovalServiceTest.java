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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * APPR-01 acceptance on a real PostgreSQL with Flyway V7: N distinct approvers per class, the
 * requester never approves, the approver is the JWT sub with a comment, requests expire,
 * break-glass needs a justification and alerts, and approved fires are released through the
 * outbox (docs/specs/approvals.md).
 */
@Testcontainers
class ApprovalServiceTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID ASSET = UUID.fromString("1f2d3a4b-0000-4000-8000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final PolicyActionProducer producer = mock(PolicyActionProducer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(T0);
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ApprovalStore store;
    private ApprovalService service;

    private static final Caller ALICE = new Caller("sub-alice", "alice", Set.of("ROLE_POLICY_MANAGER"));
    private static final Caller BOB = new Caller("sub-bob", "bob", Set.of("ROLE_CDO"));
    private static final Caller CAROL = new Caller("sub-carol", "carol", Set.of("ROLE_ADMIN"));
    private static final Caller DAVE = new Caller("sub-dave", "dave", Set.of("ROLE_DATA_ENGINEER"));
    private static final Caller ROBOT = new Caller("sub-robot", null, Set.of("ROLE_SERVICE_ACCOUNT"));

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
        service = service(ApprovalProperties.defaults());
    }

    private ApprovalService service(ApprovalProperties properties) {
        return new ApprovalService(store, properties, policies, producer, JSON, clock, meters,
                new DataSourceTransactionManager(dataSource), DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
                TestAudits.accepting());
    }

    private static PolicyActionEvent fire(String actionType, Map<String, Object> parameters, UUID policyDbId,
            String fireId) {
        return new PolicyActionEvent("v1", "retire-stale", policyDbId.toString(), "Retire stale", fireId,
                T0.toString(), "schedule", null, "2026-10-20T02:00:00Z", null, null, ASSET.toString(),
                "gs://prod-customer-exports", "GCP", "GCS_BUCKET", actionType, parameters, false, false,
                Map.of("assetId", ASSET.toString(), "cloudProvider", "GCP"));
    }

    private ApprovalRequest heldHardDelete() {
        UUID policyDbId = UUID.randomUUID();
        assertThat(service.hold(fire("delete", Map.of("softDeleteWindowDays", 0), policyDbId, "fire-1"),
                ActionClass.HARD_DELETE)).isTrue();
        return store.findByKey("fire:fire-1").orElseThrow();
    }

    private List<ApprovalStore.OutboxMessage> outbox() {
        return store.unsent(100);
    }

    private static Map<String, Object> json(String text) throws Exception {
        return JSON.readValue(text, new TypeReference<Map<String, Object>>() { });
    }

    /* ----------------------------------------------------------------- quorum */

    @Test
    void aHardDeleteNeedsTwoDistinctApproversAndThenReleasesTheFire() throws Exception {
        ApprovalRequest r = heldHardDelete();
        assertThat(r.requiredApprovals()).isEqualTo(2);
        assertThat(r.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(r.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(7)));

        ApprovalService.Detail afterOne = service.decide(r.id(), DecisionKind.APPROVE, "  looks stale to me  ", ALICE);
        assertThat(afterOne.request().status()).as("one approval is not a quorum").isEqualTo(ApprovalStatus.PENDING);
        assertThat(afterOne.request().approvals()).isEqualTo(1);
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind).containsExactly("REQUESTED");

        clock.advance(Duration.ofMinutes(5));
        ApprovalService.Detail done = service.decide(r.id(), DecisionKind.APPROVE, "confirmed with the owner", BOB);

        assertThat(done.request().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(done.request().approvals()).isEqualTo(2);
        assertThat(done.request().resolvedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(done.request().dispatchStatus()).isEqualTo(ApprovalRequest.DISPATCH_QUEUED);
        assertThat(done.decisions()).extracting(ApprovalDecision::approverSub).containsExactly("sub-alice", "sub-bob");
        assertThat(done.decisions()).extracting(ApprovalDecision::comment)
                .containsExactly("looks stale to me", "confirmed with the owner");
        assertThat(done.decisions().get(0).approverName()).isEqualTo("alice");
        assertThat(done.decisions().get(0).approverRoles()).containsExactly("ROLE_POLICY_MANAGER");

        List<ApprovalStore.OutboxMessage> out = outbox();
        assertThat(out).extracting(ApprovalStore.OutboxMessage::kind).containsExactly("REQUESTED", "DECIDED", "ACTION");
        ApprovalStore.OutboxMessage action = out.get(2);
        assertThat(action.topic()).isEqualTo(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT);
        assertThat(action.key()).isEqualTo(ASSET.toString());
        Map<String, Object> released = json(action.payload());
        assertThat(released).containsEntry("requireApproval", false).containsEntry("fireId", "fire-1")
                .containsEntry("actionType", "delete");
        @SuppressWarnings("unchecked")
        Map<String, Object> approval = (Map<String, Object>) released.get("approval");
        assertThat(approval).containsEntry("approvalId", r.id().toString()).containsEntry("status", "APPROVED")
                .containsEntry("actionClass", "HARD_DELETE").containsEntry("requiredApprovals", 2)
                .containsEntry("approvers", List.of("sub-alice", "sub-bob")).containsEntry("breakGlass", false);
        Map<String, Object> decided = json(out.get(1).payload());
        assertThat(decided).containsEntry("eventType", "DECIDED").containsEntry("status", "APPROVED")
                .containsEntry("executable", true);
        assertThat(decided.toString()).as("comments never go on a topic").doesNotContain("looks stale");
    }

    @Test
    void twoApproversDecidingAtOnceAreBothCountedAndReleaseTheFireOnce() throws Exception {
        ApprovalRequest r = heldHardDelete();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> a = pool.submit(() -> {
                start.await();
                return service.decide(r.id(), DecisionKind.APPROVE, "alice agrees", ALICE);
            });
            java.util.concurrent.Future<?> b = pool.submit(() -> {
                start.await();
                return service.decide(r.id(), DecisionKind.APPROVE, "bob agrees", BOB);
            });
            a.get(60, java.util.concurrent.TimeUnit.SECONDS);
            b.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ApprovalRequest after = store.find(r.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(after.approvals()).isEqualTo(2);
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind)
                .containsExactly("REQUESTED", "DECIDED", "ACTION");
    }

    @Test
    void theHardDeleteFloorCannotBeConfiguredAway() {
        assertThatThrownBy(() -> new ApprovalProperties.Required(1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required.hard-delete");
        assertThat(service.requiredFor(ActionClass.HARD_DELETE, 1)).as("a policy may not lower it").isEqualTo(2);
        assertThat(service.requiredFor(ActionClass.HARD_DELETE, 3)).as("a policy may raise it").isEqualTo(3);
        assertThat(service.requiredFor(ActionClass.ARCHIVE, null)).isEqualTo(1);
        assertThat(service.requiredFor(ActionClass.ARCHIVE, 99)).isEqualTo(ApprovalProperties.CEILING);
        assertThat(service.requiredFor(ActionClass.SOFT_DELETE, "not a number")).isEqualTo(1);
    }

    @Test
    void anArchiveNeedsOneApprover() {
        service.hold(fire("archive", Map.of(), UUID.randomUUID(), "fire-a"), ActionClass.ARCHIVE);
        ApprovalRequest r = store.findByKey("fire:fire-a").orElseThrow();
        assertThat(r.requiredApprovals()).isEqualTo(1);

        assertThat(service.decide(r.id(), DecisionKind.APPROVE, "ok", ALICE).request().status())
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void theSameApproverCannotCountTwice() {
        ApprovalRequest r = heldHardDelete();
        service.decide(r.id(), DecisionKind.APPROVE, "first", ALICE);

        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "second", ALICE))
                .isInstanceOfSatisfying(ApprovalException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.reason()).isEqualTo(ApprovalException.ALREADY_DECIDED);
                });
        assertThat(store.find(r.id()).orElseThrow().approvals()).isEqualTo(1);
    }

    @Test
    void oneRejectionEndsTheRequestAndReleasesNothing() {
        ApprovalRequest r = heldHardDelete();
        service.decide(r.id(), DecisionKind.APPROVE, "fine", ALICE);

        ApprovalService.Detail d = service.decide(r.id(), DecisionKind.REJECT, "this bucket is under audit", BOB);

        assertThat(d.request().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(d.request().status().executable()).isFalse();
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind).containsExactly("REQUESTED", "DECIDED");
        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "late", CAROL))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NOT_PENDING));
    }

    @Test
    void aCommentIsRequired() {
        ApprovalRequest r = heldHardDelete();
        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "   ", ALICE))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /* ----------------------------------------------------------------- separation of duties */

    @Test
    void theRequesterCannotApproveTheirOwnRequest() {
        ApprovalService.Created created = service.create(new ApprovalService.CreateCommand("SOFT_DELETE", "delete",
                ASSET, "gs://b", "GCP", "GCS_BUCKET", Map.of(), "clean-up ticket 42", null, null, null), ALICE);
        UUID id = created.detail().request().id();
        assertThat(created.detail().request().requestedBy()).isEqualTo("sub-alice");
        assertThat(created.detail().request().requesterKind()).isEqualTo(ApprovalRequest.REQUESTER_USER);

        assertThatThrownBy(() -> service.decide(id, DecisionKind.APPROVE, "mine", ALICE))
                .isInstanceOfSatisfying(ApprovalException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.reason()).isEqualTo(ApprovalException.SELF_APPROVAL);
                });
        assertThat(service.decide(id, DecisionKind.APPROVE, "agreed", BOB).request().status())
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void neitherTheServiceAccountNorThePersonItActsForMayApprove() {
        ApprovalService.Created created = service.create(new ApprovalService.CreateCommand("HARD_DELETE", "delete",
                ASSET, null, null, null, Map.of("softDeleteRecordId", "r-1"), "soft-delete window ended", null,
                "purge-r-1", "sub-alice"), ROBOT);
        ApprovalRequest r = created.detail().request();
        assertThat(r.requestedBy()).isEqualTo("sub-alice");
        assertThat(r.submittedBy()).isEqualTo("sub-robot");
        assertThat(r.excludedSubjects()).containsExactlyInAnyOrder("sub-alice", "sub-robot");
        assertThat(r.requiredApprovals()).isEqualTo(2);

        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "mine", ALICE))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.SELF_APPROVAL));
        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "robot", ROBOT))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NOT_AN_APPROVER));
    }

    @Test
    void aServiceAccountNeverDecidesEvenWithAHumanRole() {
        ApprovalRequest r = heldHardDelete();
        Caller machine = new Caller("sub-machine", null, Set.of("ROLE_SERVICE_ACCOUNT", "ROLE_ADMIN"));
        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "automated", machine))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NOT_AN_APPROVER));
        assertThatThrownBy(() -> service.breakGlass(r.id(), "ransomware spreading through this bucket", machine))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NOT_AN_APPROVER));
        assertThat(service.inbox(machine, 0, 50).rows()).isEmpty();
    }

    @Test
    void onlyAServiceAccountMayNameThePersonItActsFor() {
        assertThatThrownBy(() -> service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET, null,
                null, null, null, "why", null, null, "sub-bob"), DAVE))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.ON_BEHALF_REFUSED));
    }

    @Test
    void anIdempotentRetryReturnsTheFirstRequest() {
        ApprovalService.CreateCommand cmd = new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET, null, null,
                null, null, "cold storage", 2, "key-1", null);
        ApprovalService.Created first = service.create(cmd, DAVE);
        ApprovalService.Created again = service.create(cmd, DAVE);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.detail().request().id()).isEqualTo(first.detail().request().id());
        assertThat(first.detail().request().requiredApprovals()).as("a caller may raise the count").isEqualTo(2);
    }

    @Test
    void thePolicyAuthorsAreExcludedFromDecidingItsFires() {
        UUID policyDbId = UUID.randomUUID();
        Policy p = new Policy();
        p.setId(policyDbId);
        p.setCreatedByUserId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));
        p.setUpdatedByUserId(UUID.fromString("00000000-0000-4000-8000-00000000000b"));
        when(policies.findById(policyDbId)).thenReturn(Optional.of(p));
        service.hold(fire("archive", Map.of(), policyDbId, "fire-auth"), ActionClass.ARCHIVE);
        ApprovalRequest r = store.findByKey("fire:fire-auth").orElseThrow();
        Caller author = new Caller("00000000-0000-4000-8000-00000000000b", "author", Set.of("ROLE_ADMIN"));

        assertThat(r.requestedBy()).isEqualTo("policy:" + policyDbId);
        assertThat(r.excludedSubjects()).contains("00000000-0000-4000-8000-00000000000a",
                "00000000-0000-4000-8000-00000000000b");
        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "my policy", author))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.SELF_APPROVAL));
    }

    @Test
    void aRoleOutsideTheApproverRolesIsRefused() {
        ApprovalRequest r = heldHardDelete();
        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "me too", DAVE))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NOT_AN_APPROVER));
        assertThat(meters.counter(ApprovalService.REFUSED_COUNTER, "reason", ApprovalException.NOT_AN_APPROVER).count())
                .isEqualTo(1.0);
    }

    @Test
    void withTheMfaRuleOnADecisionNeedsTheMarkerRole() {
        service = service(new ApprovalProperties(Duration.ofDays(7), null, List.of("ADMIN", "CDO", "POLICY_MANAGER"),
                true, "dlap-mfa-required", null, 2000, 50, Duration.ofSeconds(5)));
        ApprovalRequest r = heldHardDelete();
        Caller withMfa = new Caller("sub-erin", "erin", Set.of("ROLE_CDO", "ROLE_dlap-mfa-required"));

        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "no otp", BOB))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.MFA_REQUIRED));
        assertThat(service.decide(r.id(), DecisionKind.APPROVE, "with otp", withMfa).request().approvals())
                .isEqualTo(1);
    }

    /* ----------------------------------------------------------------- expiry */

    @Test
    void anExpiredRequestCannotBeApprovedAndIsMarkedExpired() {
        ApprovalRequest r = heldHardDelete();
        clock.advance(Duration.ofDays(7));

        assertThatThrownBy(() -> service.decide(r.id(), DecisionKind.APPROVE, "too late", ALICE))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.EXPIRED));
        ApprovalRequest after = store.find(r.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(store.decisions(r.id())).as("the late decision is not recorded").isEmpty();
    }

    @Test
    void theMaintenanceExpiresOverdueRequestsOnly() {
        ApprovalRequest overdue = heldHardDelete();
        clock.advance(Duration.ofDays(3));
        service.hold(fire("archive", Map.of(), UUID.randomUUID(), "fire-later"), ActionClass.ARCHIVE);
        clock.advance(Duration.ofDays(4));

        assertThat(service.expireOverdue()).isEqualTo(1);

        assertThat(store.find(overdue.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(store.findByKey("fire:fire-later").orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind)
                .containsExactly("REQUESTED", "REQUESTED", "DECIDED");
        assertThat(service.expireOverdue()).isZero();
    }

    /* ----------------------------------------------------------------- break-glass */

    @Test
    void breakGlassNeedsAnAdministratorAndAJustification() {
        ApprovalRequest r = heldHardDelete();
        assertThatThrownBy(() -> service.breakGlass(r.id(), "ransomware spreading through this bucket", BOB))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.NOT_AN_APPROVER));
        assertThatThrownBy(() -> service.breakGlass(r.id(), "urgent", CAROL))
                .isInstanceOfSatisfying(ApprovalException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("at least 20 characters");
                });
        assertThat(store.find(r.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void breakGlassReleasesWithoutTheQuorumAndRaisesTheAlert() throws Exception {
        ApprovalRequest r = heldHardDelete();
        service.decide(r.id(), DecisionKind.APPROVE, "first of two", ALICE);

        ApprovalService.Detail d = service.breakGlass(r.id(), "ransomware spreading through this bucket", CAROL);

        assertThat(d.request().status()).isEqualTo(ApprovalStatus.APPROVED_BREAK_GLASS);
        assertThat(d.request().status().executable()).isTrue();
        assertThat(d.request().approvals()).as("break-glass is not counted as an approval").isEqualTo(1);
        assertThat(d.decisions()).extracting(ApprovalDecision::decision)
                .containsExactly(DecisionKind.APPROVE, DecisionKind.BREAK_GLASS);
        assertThat(meters.counter(ApprovalService.BREAK_GLASS_COUNTER, "actionClass", "HARD_DELETE").count())
                .isEqualTo(1.0);
        List<ApprovalStore.OutboxMessage> out = outbox();
        assertThat(out).extracting(ApprovalStore.OutboxMessage::kind)
                .containsExactly("REQUESTED", "DECIDED", "ACTION", "BREAK_GLASS");
        ApprovalStore.OutboxMessage alert = out.get(3);
        assertThat(alert.topic()).isEqualTo(DlapConstants.KafkaTopic.APPROVAL_BREAK_GLASS);
        assertThat(json(alert.payload())).containsEntry("justification", "ransomware spreading through this bucket")
                .containsEntry("status", "APPROVED_BREAK_GLASS");
        @SuppressWarnings("unchecked")
        Map<String, Object> approval = (Map<String, Object>) json(out.get(2).payload()).get("approval");
        assertThat(approval).containsEntry("breakGlass", true)
                .containsEntry("approvers", List.of("sub-alice", "sub-carol"));
    }

    @Test
    void theRequesterCannotBreakGlassOnTheirOwnRequest() {
        ApprovalService.Created created = service.create(new ApprovalService.CreateCommand("HARD_DELETE", "delete",
                ASSET, null, null, null, null, "purge now", null, null, null), CAROL);
        assertThatThrownBy(() -> service.breakGlass(created.detail().request().id(),
                "ransomware spreading through this bucket", CAROL))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.SELF_APPROVAL));
    }

    @Test
    void breakGlassCanBeSwitchedOff() {
        service = service(new ApprovalProperties(Duration.ofDays(7), null, List.of("ADMIN"), false,
                "dlap-mfa-required", new ApprovalProperties.BreakGlass(false, 20), 2000, 50, Duration.ofSeconds(5)));
        ApprovalRequest r = heldHardDelete();
        assertThatThrownBy(() -> service.breakGlass(r.id(), "ransomware spreading through this bucket", CAROL))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalException.BREAK_GLASS_DISABLED));
    }

    /* ----------------------------------------------------------------- policy fires */

    @Test
    void aFireIsHeldOnceAndAnOpenRequestAbsorbsLaterFiresForTheSameAsset() {
        UUID policyDbId = UUID.randomUUID();
        assertThat(service.hold(fire("archive", Map.of(), policyDbId, "slot-1"), ActionClass.ARCHIVE)).isTrue();
        assertThat(service.hold(fire("archive", Map.of(), policyDbId, "slot-1"), ActionClass.ARCHIVE))
                .as("a redelivered fire").isFalse();
        assertThat(service.hold(fire("archive", Map.of(), policyDbId, "slot-2"), ActionClass.ARCHIVE))
                .as("the next schedule slot while the first request waits").isFalse();
        assertThat(store.list(null, null, ASSET, 10, 0).total()).isEqualTo(1);

        ApprovalRequest first = store.findByKey("fire:slot-1").orElseThrow();
        service.decide(first.id(), DecisionKind.REJECT, "not yet", ALICE);
        assertThat(service.hold(fire("archive", Map.of(), policyDbId, "slot-3"), ActionClass.ARCHIVE))
                .as("after a decision the next fire asks again").isTrue();
    }

    @Test
    void anApprovalInShadowModeReleasesNothing() {
        ApprovalRequest r = heldHardDelete();
        when(producer.actionsEnabled()).thenReturn(false);
        service.decide(r.id(), DecisionKind.APPROVE, "one", ALICE);
        ApprovalService.Detail d = service.decide(r.id(), DecisionKind.APPROVE, "two", BOB);

        assertThat(d.request().dispatchStatus()).isEqualTo(ApprovalRequest.DISPATCH_SHADOWED);
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::kind).containsExactly("REQUESTED", "DECIDED");
    }

    @Test
    void anApiRequestIsDecidedButReleasesNoPolicyAction() {
        ApprovalService.Created created = service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive",
                ASSET, null, null, null, null, "cold storage", null, null, null), DAVE);
        ApprovalService.Detail d = service.decide(created.detail().request().id(), DecisionKind.APPROVE, "ok", ALICE);

        assertThat(d.request().dispatchStatus()).isEqualTo(ApprovalRequest.DISPATCH_NONE);
        assertThat(outbox()).extracting(ApprovalStore.OutboxMessage::topic).containsExactly(
                DlapConstants.KafkaTopic.APPROVAL_REQUESTED, DlapConstants.KafkaTopic.APPROVAL_DECIDED);
    }

    /* ----------------------------------------------------------------- reads */

    @Test
    void theInboxShowsOnlyWhatTheCallerMayStillDecide() {
        ApprovalRequest mine = service.create(new ApprovalService.CreateCommand("ARCHIVE", "archive", ASSET, null,
                null, null, null, "mine", null, null, null), ALICE).detail().request();
        ApprovalRequest decided = heldHardDelete();
        service.decide(decided.id(), DecisionKind.APPROVE, "done my part", ALICE);
        service.hold(fire("archive", Map.of(), UUID.randomUUID(), "fire-open"), ActionClass.ARCHIVE);
        ApprovalRequest open = store.findByKey("fire:fire-open").orElseThrow();

        ApprovalService.Page alice = service.inbox(ALICE, 0, 50);
        ApprovalService.Page bob = service.inbox(BOB, 0, 50);

        assertThat(alice.rows()).extracting(ApprovalRequest::id).containsExactly(open.id());
        assertThat(bob.rows()).extracting(ApprovalRequest::id)
                .containsExactlyInAnyOrder(mine.id(), decided.id(), open.id());
        assertThat(service.inbox(DAVE, 0, 50).rows()).as("a practitioner approves nothing").isEmpty();
        assertThat(service.inbox(ROBOT, 0, 50).rows()).isEmpty();
    }

    @Test
    void theListFiltersAndPages() {
        heldHardDelete();
        service.hold(fire("archive", Map.of(), UUID.randomUUID(), "fire-b"), ActionClass.ARCHIVE);

        assertThat(service.list(ApprovalStatus.PENDING, ActionClass.ARCHIVE, null, 0, 50).total()).isEqualTo(1);
        assertThat(service.list(null, null, null, 0, 1).rows()).hasSize(1);
        assertThat(service.list(null, null, null, 1, 1).rows()).hasSize(1);
        assertThat(service.list(ApprovalStatus.APPROVED, null, null, 0, 50).total()).isZero();
        assertThatThrownBy(() -> service.list(null, null, null, 0, 201)).isInstanceOf(ApprovalException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /* ----------------------------------------------------------------- lock */

    @Test
    void theMaintenanceLockIsExclusiveOnTheDatabaseClock() {
        LockProvider provider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName(SCHEMA + ".shedlock").usingDbTime().build());
        LockConfiguration config = new LockConfiguration(Instant.now(), ApprovalMaintenance.LOCK_NAME,
                Duration.ofMinutes(10), Duration.ZERO);

        Optional<SimpleLock> first = provider.lock(config);
        assertThat(first).isPresent();
        assertThat(provider.lock(config)).as("a second replica is refused").isEmpty();
        first.get().unlock();
    }

    /** A clock a test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
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
            return now;
        }
    }
}
