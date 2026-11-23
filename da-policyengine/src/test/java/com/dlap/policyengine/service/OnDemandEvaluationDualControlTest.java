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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.approval.ApprovalException;
import com.dlap.policyengine.approval.ApprovalGate;
import com.dlap.policyengine.approval.ApprovalProperties;
import com.dlap.policyengine.approval.ApprovalRequest;
import com.dlap.policyengine.approval.ApprovalService;
import com.dlap.policyengine.approval.ApprovalStatus;
import com.dlap.policyengine.approval.ApprovalStore;
import com.dlap.policyengine.approval.Caller;
import com.dlap.policyengine.approval.DecisionKind;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;
import com.dlap.policyengine.client.dto.CatalogAssetDTO;
import com.dlap.policyengine.dsl.PolicyDslEvaluator;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.dto.PolicyEvaluationOutputDTO;
import com.dlap.policyengine.dto.PolicyEvaluationRequestDTO;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.dlap.policyengine.mapper.PolicyMapper;
import com.dlap.policyengine.model.Policy;
import com.dlap.policyengine.model.PolicyEvaluation;
import com.dlap.policyengine.model.PolicyStatus;
import com.dlap.policyengine.repository.PolicyEvaluationRepository;
import com.dlap.policyengine.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Q-569 (FU-4B): the administrator who triggers an on-demand evaluation is an excluded approver of
 * the request it creates. The real {@link PolicyEvaluationService}, {@link ApprovalGate},
 * {@link ApprovalService} and {@link ApprovalStore} run on a PostgreSQL with Flyway V7, so the
 * 403 and the second approver's decision are the service's own answers, not a mock's.
 */
@Testcontainers
class OnDemandEvaluationDualControlTest {

    private static final String SCHEMA = "dlap_policyengine";
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Caller ALICE_ADMIN = new Caller("sub-alice-admin", "alice", Set.of("ROLE_ADMIN"));
    private static final Caller BOB_CDO = new Caller("sub-bob-cdo", "bob", Set.of("ROLE_CDO"));
    private static final Caller DORA_DIRECTOR = new Caller("sub-dora-director", "dora", Set.of("ROLE_DIRECTOR"));
    private static final Caller CAROL_ADMIN = new Caller("sub-carol-admin", "carol", Set.of("ROLE_ADMIN"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final PolicyEvaluationRepository evaluationRepository = mock(PolicyEvaluationRepository.class);
    private final PolicyMapper policyMapper = mock(PolicyMapper.class);
    private final PolicyActionProducer producer = mock(PolicyActionProducer.class);
    private final ResilientCatalogAssetClient catalog = mock(ResilientCatalogAssetClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID policyId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    private ApprovalStore store;
    private ApprovalService approvals;
    private PolicyEvaluationService evaluations;

    @BeforeEach
    void wire() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        store = new ApprovalStore(dataSource, JSON, SCHEMA);
        when(producer.actionsEnabled()).thenReturn(true);
        approvals = new ApprovalService(store, ApprovalProperties.defaults(), policyRepository, producer, JSON, clock,
                new SimpleMeterRegistry(), new DataSourceTransactionManager(dataSource),
                DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
                com.dlap.policyengine.approval.TestAudits.accepting());
        evaluations = new PolicyEvaluationService(policyRepository, evaluationRepository, policyMapper, JSON,
                new ApprovalGate(producer, approvals), catalog, new PolicyDslParser(), new PolicyDslEvaluator(clock),
                clock);
        when(evaluationRepository.save(any(PolicyEvaluation.class))).thenAnswer(inv -> {
            PolicyEvaluation e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        when(policyMapper.toPolicyEvaluationOutputDTO(any(PolicyEvaluation.class), eq("gcp-lifecycle")))
                .thenAnswer(inv -> {
                    PolicyEvaluation e = inv.getArgument(0);
                    PolicyEvaluationOutputDTO dto = new PolicyEvaluationOutputDTO();
                    dto.setStatus(e.getStatus());
                    dto.setEvaluationDetails(e.getEvaluationDetails());
                    dto.setTriggeredActions(e.getTriggeredActions());
                    return dto;
                });
        CatalogAssetDTO asset = new CatalogAssetDTO();
        asset.setAssetId(assetId);
        asset.setAssetUri("gs://dlap-gcp-e2e-bucket");
        asset.setCloudProvider("GCP");
        asset.setServiceType("GCS_BUCKET");
        asset.setAssetType("GCS_BUCKET");
        when(catalog.getAssetById(assetId)).thenReturn(Optional.of(asset));
    }

    /** A dlap/v1 policy matching GCP assets whose legacy actions map carries one action. */
    private void policyWith(String documentAction, String actionKey, Map<String, Object> parameters) {
        Policy p = new Policy();
        p.setId(policyId);
        p.setName("gcp-lifecycle");
        p.setStatus(PolicyStatus.ENABLED);
        p.setPolicyDocument("apiVersion: dlap/v1\nkind: Policy\nmetadata: {id: gcp-lifecycle, name: gcp-lifecycle}\n"
                + "spec:\n  triggers: [{type: schedule, cron: '0 0 0 1 1 ?'}]\n  match: {cloudProvider: GCP}\n"
                + "  action: " + documentAction + "\n");
        p.setActions(Map.of(actionKey, parameters));
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(p));
    }

    /** Triggers the on-demand evaluation as the given administrator and returns the one pending request. */
    private ApprovalRequest triggerAs(Caller admin) {
        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());
        evaluations.triggerPolicyEvaluation(policyId, req, UUID.randomUUID(), admin.subject());
        List<ApprovalRequest> pending = store.list(ApprovalStatus.PENDING, null, assetId, 10, 0).rows();
        assertThat(pending).hasSize(1);
        return pending.get(0);
    }

    private static void assertRefusedAs(Runnable call, String reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApprovalException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.reason()).isEqualTo(reason);
        });
    }

    @Test
    void theTriggeringAdminGets403OnTheirOwnRequestAndADifferentApproverCanDecideIt() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));

        ApprovalRequest request = triggerAs(ALICE_ADMIN);

        assertThat(request.requestedBy()).isEqualTo("policy:" + policyId);
        assertThat(request.excludedSubjects()).contains(ALICE_ADMIN.subject(), "policy:" + policyId);
        assertRefusedAs(() -> approvals.decide(request.id(), DecisionKind.APPROVE, "my own evaluation", ALICE_ADMIN),
                ApprovalException.SELF_APPROVAL);
        assertThat(store.decisions(request.id())).as("the refused decision is not recorded").isEmpty();
        assertThat(store.find(request.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        ApprovalService.Detail done = approvals.decide(request.id(), DecisionKind.APPROVE, "reviewed with the owner",
                BOB_CDO);

        assertThat(done.request().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(done.request().approvals()).isEqualTo(1);
    }

    @Test
    void theTriggeringAdminCannotBreakGlassTheirOwnRequestEither() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        ApprovalRequest request = triggerAs(ALICE_ADMIN);

        assertRefusedAs(() -> approvals.breakGlass(request.id(), "ransomware spreading through this bucket",
                ALICE_ADMIN), ApprovalException.SELF_APPROVAL);

        assertThat(store.find(request.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        ApprovalService.Detail released = approvals.breakGlass(request.id(),
                "ransomware spreading through this bucket", CAROL_ADMIN);
        assertThat(released.request().status()).isEqualTo(ApprovalStatus.APPROVED_BREAK_GLASS);
    }

    @Test
    void theTriggeringAdminCannotBeOneOfTheTwoApproversOfAHardDelete() {
        policyWith("{type: delete}", "DELETE", Map.of("softDeleteWindowDays", 0));
        ApprovalRequest request = triggerAs(ALICE_ADMIN);
        assertThat(request.requiredApprovals()).isEqualTo(2);

        approvals.decide(request.id(), DecisionKind.APPROVE, "first of two", BOB_CDO);
        assertRefusedAs(() -> approvals.decide(request.id(), DecisionKind.APPROVE, "second of two, from me",
                ALICE_ADMIN), ApprovalException.SELF_APPROVAL);
        assertThat(store.find(request.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        ApprovalService.Detail done = approvals.decide(request.id(), DecisionKind.APPROVE, "second of two",
                DORA_DIRECTOR);
        assertThat(done.request().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(done.request().approvals()).isEqualTo(2);
    }

    @Test
    void anotherAdminWhoDidNotTriggerTheEvaluationMayApproveIt() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        ApprovalRequest request = triggerAs(ALICE_ADMIN);

        ApprovalService.Detail done = approvals.decide(request.id(), DecisionKind.APPROVE, "second pair of eyes",
                CAROL_ADMIN);

        assertThat(done.request().status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void aFireWithNoTriggeringPersonExcludesOnlyThePolicyAndTheEngine() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        PolicyEvaluationRequestDTO req = new PolicyEvaluationRequestDTO();
        req.setTargetAssetId(assetId.toString());

        evaluations.triggerPolicyEvaluation(policyId, req, UUID.randomUUID(), null);

        ApprovalRequest request = store.list(ApprovalStatus.PENDING, null, assetId, 10, 0).rows().get(0);
        assertThat(request.excludedSubjects()).containsExactlyInAnyOrder("policy:" + policyId,
                ApprovalRequest.POLICY_ENGINE);
    }

    @Test
    void theAuthorOfThePolicyCannotApproveItsOnDemandFiresEither() {
        policyWith("{type: archive}", "ARCHIVE", Map.of("tier", "COLD"));
        Policy stored = policyRepository.findById(policyId).orElseThrow();
        UUID authorId = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
        stored.setCreatedByUserId(authorId);
        Caller author = new Caller(authorId.toString(), "author", Set.of("ROLE_CDO"));

        ApprovalRequest request = triggerAs(ALICE_ADMIN);

        assertThat(request.excludedSubjects()).contains(authorId.toString(), ALICE_ADMIN.subject());
        assertRefusedAs(() -> approvals.decide(request.id(), DecisionKind.APPROVE, "my own policy", author),
                ApprovalException.SELF_APPROVAL);
    }
}
