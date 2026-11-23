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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dlap.common.audit.AuditActor;
import com.dlap.common.audit.AuditEvent;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogWriter;
import com.dlap.common.security.SecurityUtils;
import com.dlap.policyengine.approval.AuditUnavailableException;
import com.dlap.policyengine.approval.GovernanceAudit;
import com.dlap.policyengine.approval.TestAudits;
import com.dlap.policyengine.common.ConflictException;
import com.dlap.policyengine.common.ResourceNotFoundException;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dsl.PolicyDslTranslator;
import com.dlap.policyengine.dto.PolicyInputDTO;
import com.dlap.policyengine.mapper.PolicyDraftMapper;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyChangeType;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.model.PolicyVersion;
import com.dlap.policyengine.model.PolicyVersionView;
import com.dlap.policyengine.repository.PolicyDraftRepository;
import com.dlap.policyengine.repository.PolicyRepository;
import com.dlap.policyengine.repository.PolicyVersionRepository;

import jakarta.persistence.EntityManager;

/**
 * AUD-02a, the policy side without a database: every change of a policy is a new version whose
 * snapshot hash is in its {@code govern.policy} entry, a delete is soft and closes the history, a
 * pre-history policy first gets a BASELINE, an audit failure leaves no version, and a retried change
 * replays the entry an uncommitted attempt left (Q-692). The real tables and chain are in
 * PolicyHistoryContainerTest.
 */
class PolicyHistoryServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final String DOC = ""
            + "apiVersion: dlap/v1\n"
            + "kind: Policy\n"
            + "metadata: {id: p, name: p}\n"
            + "spec:\n"
            + "  triggers: [{type: event, lifecycle: created}]\n"
            + "  action: {type: addLabel, parameters: {label: Prod}}\n";

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant start) {
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

    private final PolicyRepository repository = mock(PolicyRepository.class);
    private final PolicyVersionRepository versionRepository = mock(PolicyVersionRepository.class);
    private final List<PolicyVersion> rows = new ArrayList<>();
    private final MovingClock clock = new MovingClock(T0);
    private TestAudits.FakeChain chain;
    private PolicyHistoryService history;
    private PolicyService service;

    @BeforeEach
    void wire() {
        chain = new TestAudits.FakeChain(T0);
        when(versionRepository.saveAndFlush(any(PolicyVersion.class))).thenAnswer(i -> {
            rows.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(versionRepository.findByPolicyIdAndVersion(any(), anyInt())).thenAnswer(i -> rows.stream()
                .filter(v -> v.getPolicyId().equals(i.getArgument(0)) && v.getVersion() == (int) i.getArgument(1))
                .findFirst());
        when(versionRepository.findByPolicyIdOrderByVersionAsc(any())).thenAnswer(i -> rows.stream()
                .filter(v -> v.getPolicyId().equals(i.getArgument(0))).toList());
        history = historyWith(chain.governance());
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(repository.save(any(Policy.class))).thenAnswer(inv -> {
            Policy p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private PolicyHistoryService historyWith(GovernanceAudit audit) {
        PolicyHistoryService h = new PolicyHistoryService(versionRepository, repository, audit,
                mock(EntityManager.class), clock);
        service = new PolicyService(repository, new PolicyMapper(), mock(PolicyDraftRepository.class),
                mock(PolicyDraftMapper.class), new PolicyDslParser(), mock(PolicyDslTranslator.class),
                mock(ApplicationEventPublisher.class), h);
        return h;
    }

    private static PolicyInputDTO input(String name, PolicyStatus status) {
        PolicyInputDTO dto = new PolicyInputDTO();
        dto.setName(name);
        dto.setDescription("retire stale exports");
        dto.setPolicyDocument(DOC);
        dto.setStatus(status);
        dto.setActions(Map.of("addLabel", "Stale"));
        return dto;
    }

    /** A stored live policy as a fresh load would return it. */
    private Policy stored(UUID id, int currentVersion) {
        Policy p = new Policy();
        p.setId(id);
        p.setName("retire-stale");
        p.setDescription("retire stale exports");
        p.setPolicyDocument(DOC);
        p.setStatus(PolicyStatus.DISABLED);
        p.setActions(Map.of("addLabel", "Stale"));
        p.setCreatedByUserId(ALICE);
        p.setCreatedAt(T0.minus(Duration.ofDays(30)));
        p.setUpdatedAt(T0.minus(Duration.ofDays(2)));
        p.setCurrentVersion(currentVersion);
        when(repository.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void aNewPolicyIsVersionOneAndItsSnapshotHashIsChained() {
        UUID id = service.createPolicy(input("retire-stale", PolicyStatus.DISABLED), ALICE).getId();

        assertThat(rows).hasSize(1);
        PolicyVersion v = rows.get(0);
        assertThat(v.getPolicyId()).isEqualTo(id);
        assertThat(v.getVersion()).isEqualTo(1);
        assertThat(v.getChangeType()).isEqualTo(PolicyChangeType.CREATED);
        assertThat(v.getChangedBy()).isEqualTo(ALICE.toString());
        assertThat(v.getChangedByKind()).isEqualTo("USER");
        assertThat(v.getChangedAt()).isEqualTo(T0);
        assertThat(v.getContentSha256()).isEqualTo(GovernanceAudit.sha256(v.getSnapshot()));
        assertThat(v.getSnapshot()).startsWith("{\"actions\":{\"addLabel\":\"Stale\"},\"conditionLogic\":null,")
                .contains("\"deleted\":false").contains("\"schema\":\"dlap-policy-version/1\"");
        AuditEvent e = chain.appends.get(0);
        assertThat(e.eventType()).isEqualTo("govern.policy.created");
        assertThat(e.target().kind()).isEqualTo("POLICY");
        assertThat(e.target().id()).isEqualTo(id.toString());
        assertThat(e.idempotencyKey()).startsWith(id + ":v1:").hasSize(36 + 4 + 32);
        assertThat(e.details()).containsEntry("contentSha256", v.getContentSha256()).containsEntry("version", 1)
                .doesNotContainKey("previousContentSha256");
        assertThat(v.getAuditSeq()).isEqualTo(chain.byKey.get(e.idempotencyKey()).seq());
        assertThat(v.getAuditEntryHash()).isEqualTo(chain.byKey.get(e.idempotencyKey()).entryHash());
    }

    @Test
    void anUpdateAndAStatusChangeAreNewVersionsLinkedToThePreviousOne() {
        UUID id = UUID.randomUUID();
        Policy p = stored(id, 1);
        rows.add(new PolicyVersion(UUID.randomUUID(), id, 1, PolicyChangeType.CREATED, "retire-stale",
                PolicyStatus.DISABLED, false, PolicyHistoryService.snapshot(p, false),
                GovernanceAudit.sha256(PolicyHistoryService.snapshot(p, false)), ALICE.toString(), "USER",
                T0.minus(Duration.ofDays(30)), 1, "a".repeat(64)));

        clock.advance(Duration.ofMinutes(1));
        PolicyInputDTO changed = input("retire-stale", PolicyStatus.DISABLED);
        changed.setDescription("retire stale exports after 400 days");
        service.updatePolicy(id, changed, BOB);
        clock.advance(Duration.ofMinutes(1));
        service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);

        assertThat(rows).extracting(PolicyVersion::getVersion).containsExactly(1, 2, 3);
        assertThat(rows).extracting(PolicyVersion::getChangeType).containsExactly(PolicyChangeType.CREATED,
                PolicyChangeType.UPDATED, PolicyChangeType.STATUS_CHANGED);
        assertThat(rows.get(1).getChangedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(rows.get(2).getStatus()).isEqualTo(PolicyStatus.ENABLED);
        assertThat(chain.appends.get(0).details()).containsEntry("previousContentSha256", rows.get(0).getContentSha256());
        assertThat(chain.appends.get(1).details()).containsEntry("previousContentSha256", rows.get(1).getContentSha256());
        assertThat(chain.appends).extracting(AuditEvent::eventType)
                .containsExactly("govern.policy.updated", "govern.policy.status_changed");
        assertThat(p.getCurrentVersion()).isEqualTo(3);
        assertThat(p.getUpdatedAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
        assertThat(p.getCreatedByUserId()).isEqualTo(ALICE);
    }

    @Test
    void aPolicyFromBeforeHistoryGetsABaselineBySystemBeforeItsFirstChange() {
        UUID id = UUID.randomUUID();
        Policy p = stored(id, 0);
        String before = PolicyHistoryService.snapshot(p, false);

        service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);

        assertThat(rows).extracting(PolicyVersion::getChangeType)
                .containsExactly(PolicyChangeType.BASELINE, PolicyChangeType.STATUS_CHANGED);
        PolicyVersion baseline = rows.get(0);
        assertThat(baseline.getSnapshot()).isEqualTo(before);
        assertThat(baseline.getChangedBy()).isEqualTo(PolicyHistoryService.SYSTEM_ACTOR);
        assertThat(baseline.getChangedByKind()).isEqualTo("SYSTEM");
        assertThat(baseline.getChangedAt()).as("the policy's last update").isEqualTo(T0.minus(Duration.ofDays(2)));
        assertThat(chain.appends.get(0).eventType()).isEqualTo("govern.policy.baseline");
        assertThat(rows.get(1).getVersion()).isEqualTo(2);
        assertThat(p.getCurrentVersion()).isEqualTo(2);
    }

    @Test
    void aDeleteIsSoftDisablesTheRuleAndIsTheLastVersion() {
        UUID id = UUID.randomUUID();
        Policy p = stored(id, 0);
        p.setStatus(PolicyStatus.ENABLED);
        SecurityContextHolder.getContext().setAuthentication(jwt(BOB.toString(), "ROLE_POLICY_MANAGER"));

        service.deletePolicy(id);

        verify(repository, never()).deleteById(any());
        verify(repository, never()).delete(any(Policy.class));
        assertThat(p.getDeletedAt()).isEqualTo(T0);
        assertThat(p.getDeletedByUserId()).isEqualTo(BOB);
        assertThat(p.getStatus()).isEqualTo(PolicyStatus.DISABLED);
        PolicyVersion last = rows.get(rows.size() - 1);
        assertThat(last.getChangeType()).isEqualTo(PolicyChangeType.DELETED);
        assertThat(last.isDeleted()).isTrue();
        assertThat(last.getSnapshot()).contains("\"deleted\":true");
        assertThat(last.getChangedBy()).isEqualTo(BOB.toString());
        assertThat(chain.appends.get(chain.appends.size() - 1).eventType()).isEqualTo("govern.policy.deleted");
        assertThatThrownBy(() -> service.getPolicyById(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deletePolicy(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anAuditFailureOnAPolicyChangeLeavesNoVersion() {
        AuditLogWriter broken = mock(AuditLogWriter.class);
        when(broken.append(any(), any())).thenThrow(new AuditException("connection refused", null));
        historyWith(TestAudits.governance(broken));
        UUID id = UUID.randomUUID();
        stored(id, 1);

        Throwable t = catchThrowable(() -> service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB));

        assertThat(t).isInstanceOf(AuditUnavailableException.class);
        verify(versionRepository, never()).saveAndFlush(any());
        assertThat(rows).isEmpty();
    }

    @Test
    void aRetriedPolicyChangeReplaysTheEntryOfTheUncommittedAttempt() {
        UUID id = UUID.randomUUID();
        stored(id, 1);
        rows.add(new PolicyVersion(UUID.randomUUID(), id, 1, PolicyChangeType.CREATED, "retire-stale",
                PolicyStatus.DISABLED, false, "{}", GovernanceAudit.sha256("{}"), ALICE.toString(), "USER",
                T0.minus(Duration.ofDays(30)), 1, "a".repeat(64)));
        chain.loseNextCommit();
        assertThat(catchThrowable(() -> service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows).as("the first attempt saved no version").hasSize(1);

        clock.advance(Duration.ofMinutes(5));
        stored(id, 1);
        service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);

        assertThat(chain.appends).as("the retry appended nothing").hasSize(1);
        PolicyVersion v2 = rows.get(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getChangedAt()).as("the time the chain states").isEqualTo(T0);
        assertThat(v2.getAuditSeq()).isEqualTo(chain.byKey.values().iterator().next().seq());
    }

    @Test
    void aConcurrentChangeThatTookTheVersionNumberIsAConflict() {
        UUID id = UUID.randomUUID();
        stored(id, 1);
        when(versionRepository.saveAndFlush(any(PolicyVersion.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key uq_policy_versions_policy_version"));

        assertThatThrownBy(() -> service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed concurrently");
    }

    @Test
    void theActorIsTheUserTheServiceAccountOrTheSystem() {
        assertThat(PolicyHistoryService.actorOf(null)).isEqualTo(AuditActor.system("da-policyengine"));
        assertThat(PolicyHistoryService.actorOf(SecurityUtils.getSystemUserId()).kind()).isEqualTo(AuditActor.Kind.SYSTEM);
        assertThat(PolicyHistoryService.actorOf(ALICE)).isEqualTo(AuditActor.user(ALICE.toString()));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("svc", "n/a", "ROLE_SERVICE_ACCOUNT", "ROLE_ADMIN"));
        assertThat(PolicyHistoryService.actorOf(BOB)).isEqualTo(AuditActor.service(BOB.toString()));
    }

    @Test
    void theVersionsApiListsWithoutContentAndReadsOneWithIt() {
        UUID id = UUID.randomUUID();
        Policy p = stored(id, 0);
        service.setPolicyStatus(id, PolicyStatus.ENABLED, BOB);

        List<PolicyVersionView> list = history.versions(id);
        PolicyVersionView one = history.version(id, 1);

        assertThat(list).extracting(PolicyVersionView::version).containsExactly(1, 2);
        assertThat(list.get(0).policyDocument()).isNull();
        assertThat(list.get(0).auditStream()).isEqualTo("govern.policy");
        assertThat(one.policyDocument()).isEqualTo(DOC);
        assertThat(one.description()).isEqualTo("retire stale exports");
        assertThat(one.actions()).containsEntry("addLabel", "Stale");
        assertThat(one.status()).isEqualTo(PolicyStatus.DISABLED);
        assertThat(p.getStatus()).isEqualTo(PolicyStatus.ENABLED);
        assertThatThrownBy(() -> history.version(id, 9)).isInstanceOf(ResourceNotFoundException.class);
        UUID unknown = UUID.randomUUID();
        when(repository.existsById(unknown)).thenReturn(false);
        assertThatThrownBy(() -> history.versions(unknown)).isInstanceOf(ResourceNotFoundException.class);
        UUID untouched = UUID.randomUUID();
        when(repository.existsById(untouched)).thenReturn(true);
        assertThat(history.versions(untouched)).isEmpty();
    }

    @Test
    void aDeletedPolicyCannotBeLockedForAChange() {
        Policy p = new Policy();
        p.setId(UUID.randomUUID());
        p.setDeletedAt(T0);

        assertThatThrownBy(() -> history.lockForChange(p)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt(
            String sub, String... roles) {
        org.springframework.security.oauth2.jwt.Jwt token = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("t").header("alg", "none").subject(sub).claim("sub", sub).build();
        List<org.springframework.security.core.GrantedAuthority> authorities = new ArrayList<>();
        for (String r : roles) {
            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(r));
        }
        return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token,
                authorities);
    }
}
